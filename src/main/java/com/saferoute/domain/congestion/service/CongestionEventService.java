package com.saferoute.domain.congestion.service;

import com.saferoute.domain.congestion.dto.request.ReportCongestionEventRequest;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.util.MonitoredAreaCalculator;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.EventProcessingStatus;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.CongestionErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Pi가 혼잡 진입/상승/종료를 감지한 즉시 보내는 이벤트를 받아 BE가 직접 밀도·혼잡 단계를
// 계산하고 저장한다. 멱등 저장/처리 상태 전이/after-commit 발행 구조는 CongestionObservationService
// (주기 관측값)와 동일한 패턴을 쓰되, CongestionEventRepository는 처리 소유자(lease) 없이
// RECEIVED/PROCESSING/PROCESSED/FAILED 상태만으로 동시 처리를 막는다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CongestionEventService {

    private final CongestionEventRepository congestionEventRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final CongestionConfigService congestionConfigService;
    private final RouteRecalculationService routeRecalculationService;
    private final TrainingEventPublisher trainingEventPublisher;

    @Transactional
    public IdempotentSaveResult<CongestionEventItem> reportCongestionEvent(
            Cctv cctv, ReportCongestionEventRequest request
    ) {
        Floor floor = cctv.getCustomNode().getFloor();
        UUID buildingId = floor.getBuilding().getId();
        TrainingSession session = trainingSessionRepository
                .findByIdAndStatusAndScenario_Building_Id(
                        request.trainingSessionId(), TrainingStatus.RUNNING, buildingId)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.RUNNING_TRAINING_SESSION_NOT_FOUND));

        CongestionConfig config = congestionConfigService.getConfig();
        Double monitoredAreaM2 = MonitoredAreaCalculator.calculate(
                cctvGridCellRepository.countByCctv_Id(cctv.getId()), floor.getGridCellSizeMeter());
        if (monitoredAreaM2 == null || monitoredAreaM2 <= 0) {
            throw new ApiException(CongestionErrorCode.MONITORED_AREA_NOT_AVAILABLE);
        }

        double density = request.headcount() / monitoredAreaM2;
        CongestionLevel level = config.classify(density);

        CongestionEventItem item = CongestionEventItem.received(
                request.eventId(),
                session.getId(),
                cctv.getCode(),
                request.eventType(),
                request.detectedAt(),
                request.headcount(),
                request.localDensity(),
                request.localCongestionLevel(),
                density,
                level,
                request.configVersion(),
                null
        );

        IdempotentSaveResult<CongestionEventItem> saveResult = congestionEventRepository.saveReceivedIfAbsent(item);
        validateEventIdentity(saveResult.item(), request, session.getId());

        if (saveResult.item().getEventStatus() == EventProcessingStatus.PROCESSED) {
            // 이미 후속 처리까지 끝난 이벤트의 재전송 - 있는 그대로 반환하고 다시 처리하지 않는다.
            return saveResult;
        }
        if (!claimProcessing(saveResult.item())) {
            // 다른 요청이 이미 처리 중(PROCESSING)이거나 상태 전이 규칙상 지금은 시작할 수 없는 경우.
            // 재시도를 유도하기 위해 저장된 상태를 그대로 반환한다.
            return saveResult;
        }

        try {
            List<MapEdge> affectedEdges = resolveAffectedEdges(cctv.getId());
            // 현재 상태(CurrentCctvStateItem)는 주기 Observation만을 기준 데이터로 삼는다.
            // 즉시 이벤트는 여기서 상태를 갱신하지 않는다(다음 관측값이 곧바로 덮어쓰므로 의미가 없다).
            // 대신 경로 가중치 계산(CurrentCongestionWeightProvider)이 CCTV별 최신 즉시 이벤트를 직접
            // 조회해 함께 반영한다(#293).

            CongestionLevel savedLevel = saveResult.item().getCongestionLevel();
            RecalculationTriggerType triggerType = mapTriggerType(saveResult.item().getEventType());
            // ENDED는 레벨이 NORMAL이라 requiresRouteRecalculation()이 false지만, 정상 경로로의
            // 복구 후보를 제시해야 하므로(RouteRecalculationService.trigger 참고) 별도로 포함한다.
            if (savedLevel.requiresRouteRecalculation() || triggerType == RecalculationTriggerType.ENDED) {
                if (affectedEdges.isEmpty()) {
                    log.warn(
                            "재계산 기준을 충족했지만 감시 중인 MapEdge가 없어 재계산을 건너뜀: "
                                    + "sessionId={}, cctvCode={}, level={}, triggerType={}",
                            session.getId(), cctv.getCode(), savedLevel, triggerType
                    );
                } else if (triggerType == RecalculationTriggerType.ENDED) {
                    // ENDED(정상 경로 복구 판단)는 Pi가 보내는 1회성 신호라, 비동기 처리 중
                    // 실패하면 관측값이 재시도해줄 다음 기회가 없다(관측값은 레벨이 NORMAL이면
                    // 애초에 재탐색을 트리거하지 않음, CongestionObservationService 참고).
                    // 그래서 ENDED만큼은 동기로 남겨 디바이스 응답까지 묶어 실패를 바로
                    // 드러낸다 (#250).
                    routeRecalculationService.trigger(
                            session, affectedEdges, savedLevel, triggerType, cctv.getCode(), density);
                } else {
                    // STARTED/LEVEL_UP은 혼잡이 지속되는 동안 다음 관측값이 관측 주기마다 재시도해주므로
                    // 비동기로 떼어내도 안전하다 (#250). MapEdge 엔티티가 아니라 id만 넘기고,
                    // detectedAt을 함께 넘겨 너무 늦게 실행될 낡은 판단은 triggerAsync가 스스로
                    // 건너뛰게 한다.
                    routeRecalculationService.triggerAsync(
                            session, affectedEdges.stream().map(MapEdge::getId).toList(), savedLevel,
                            triggerType, cctv.getCode(), density, request.detectedAt());
                }
            }
            publishAndCompleteAfterCommit(session.getId(), affectedEdges, saveResult.item());
        } catch (ApiException exception) {
            failProcessing(saveResult.item().getEventId());
            throw exception;
        } catch (RuntimeException exception) {
            failProcessing(saveResult.item().getEventId());
            throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
        }
        return saveResult;
    }

    // CCTV -> CctvGridCell -> FloorGridCell -> MapEdgeGridCell -> MapEdge 연쇄 조회.
    // CCTV 한 대가 여러 Edge를 감시할 수 있어 목록으로 반환한다 (CongestionObservationService와 동일 패턴).
    private List<MapEdge> resolveAffectedEdges(UUID cctvId) {
        List<UUID> gridCellIds = cctvGridCellRepository
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctvId)
                .stream()
                .map(mapping -> mapping.getGridCell().getId())
                .toList();
        if (gridCellIds.isEmpty()) {
            return List.of();
        }
        return mapEdgeGridCellRepository.findAllByGridCell_IdIn(gridCellIds).stream()
                .map(MapEdgeGridCell::getMapEdge)
                .distinct()
                .toList();
    }

    private RecalculationTriggerType mapTriggerType(CongestionEventType eventType) {
        return switch (eventType) {
            case CONGESTION_STARTED -> RecalculationTriggerType.STARTED;
            case CONGESTION_LEVEL_UP -> RecalculationTriggerType.LEVEL_UP;
            case CONGESTION_ENDED -> RecalculationTriggerType.ENDED;
        };
    }

    private boolean claimProcessing(CongestionEventItem item) {
        EventProcessingStatus current = item.getEventStatus();
        if (current != EventProcessingStatus.RECEIVED && current != EventProcessingStatus.FAILED) {
            return false;
        }
        return congestionEventRepository.updateEventStatus(
                item.getEventId(), current, EventProcessingStatus.PROCESSING);
    }

    private void validateEventIdentity(
            CongestionEventItem item, ReportCongestionEventRequest request, UUID sessionId
    ) {
        boolean sameIdentity = Objects.equals(item.getTrainingSessionId(), sessionId.toString())
                && Objects.equals(item.getCctvCode(), request.cctvCode());
        if (!sameIdentity) {
            throw new ApiException(CongestionErrorCode.EVENT_IDENTITY_MISMATCH);
        }
    }

    private void publishAndCompleteAfterCommit(
            UUID sessionId, List<MapEdge> affectedEdges, CongestionEventItem item
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishCongestionEventReceived(sessionId, affectedEdges, item);
            completeProcessing(item.getEventId());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publishCongestionEventReceived(sessionId, affectedEdges, item);
                    completeProcessing(item.getEventId());
                } catch (RuntimeException exception) {
                    failProcessing(item.getEventId());
                    throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    failProcessing(item.getEventId());
                }
            }
        });
    }

    // 즉시 혼잡 상태 전환 1건 = CONGESTION_EVENT_RECEIVED 1건 발행 원칙을 지키기 위해 영향받는 Edge
    // 전체를 배열로 담아 한 번만 발행한다 (이슈 #192). Edge가 없으면(감시 영역에 아직 GridCell/Edge
    // 매핑이 안 된 CCTV) 빈 목록으로 발행해 대시보드가 최소한 CCTV 단위 이벤트는 볼 수 있게 한다.
    private void publishCongestionEventReceived(UUID sessionId, List<MapEdge> affectedEdges, CongestionEventItem item) {
        List<UUID> affectedEdgeIds = affectedEdges.stream().map(MapEdge::getId).distinct().toList();
        trainingEventPublisher.publishCongestionEventReceived(sessionId, affectedEdgeIds, item);
    }

    private void completeProcessing(String eventId) {
        try {
            if (!congestionEventRepository.updateEventStatus(
                    eventId, EventProcessingStatus.PROCESSING, EventProcessingStatus.PROCESSED)) {
                log.warn("혼잡 이벤트 처리 완료 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException exception) {
            log.error("혼잡 이벤트 처리 완료 상태 저장 중 오류: eventId={}", eventId, exception);
        }
    }

    private void failProcessing(String eventId) {
        try {
            if (!congestionEventRepository.updateEventStatus(
                    eventId, EventProcessingStatus.PROCESSING, EventProcessingStatus.FAILED)) {
                log.warn("혼잡 이벤트 처리 실패 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException statusException) {
            log.error("혼잡 이벤트 처리 실패 상태 저장 중 오류: eventId={}", eventId, statusException);
        }
    }
}
