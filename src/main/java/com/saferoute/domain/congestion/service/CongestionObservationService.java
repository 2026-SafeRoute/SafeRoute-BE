package com.saferoute.domain.congestion.service;

import com.saferoute.domain.congestion.dto.request.ReportObservationRequest;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.util.MonitoredAreaCalculator;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventType;
import com.saferoute.domain.telemetry.dynamo.entity.LatestMonitoringCaptureItem;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import com.saferoute.domain.telemetry.dynamo.repository.GeneralMonitoringEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.telemetry.dynamo.repository.LatestMonitoringCaptureRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationCountRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.CongestionErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Pi가 snapshotIntervalSec(기본 1초)마다 보내는 관측값을 받아 BE가 직접 밀도·혼잡 단계를 계산하고 저장한다.
// 멱등 저장/처리 lease/after-commit 발행 구조는 CongestionEventService와 유사한 패턴을 쓴다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CongestionObservationService {

    static final Duration PROCESSING_LEASE = Duration.ofMinutes(1);

    private static final String TRAINING_PREFIX = "training";
    private static final String MONITORING_DIRECTORY = "monitoring";
    private static final String JPEG_SUFFIX = ".jpg";

    private final ObservationRepository observationRepository;
    private final ObservationCountRepository observationCountRepository;
    private final GeneralMonitoringEventRepository generalMonitoringEventRepository;
    private final LatestMonitoringCaptureRepository latestMonitoringCaptureRepository;
    private final CurrentCctvStateRepository currentCctvStateRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final CongestionConfigService congestionConfigService;
    private final RouteRecalculationService routeRecalculationService;
    private final RouteDeviationService routeDeviationService;
    private final TrainingEventPublisher trainingEventPublisher;

    @Transactional
    public IdempotentSaveResult<ObservationItem> reportObservation(Cctv cctv, ReportObservationRequest request) {
        Floor floor = cctv.getCustomNode().getFloor();
        UUID buildingId = floor.getBuilding().getId();
        TrainingSession session = trainingSessionRepository
                .findByIdAndStatusAndScenario_Building_Id(
                        request.trainingSessionId(), TrainingStatus.RUNNING, buildingId)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.RUNNING_TRAINING_SESSION_NOT_FOUND));

        CongestionConfig config = congestionConfigService.getConfig();
        Double monitoredAreaM2 = MonitoredAreaCalculator.calculate(
                cctvGridCellRepository.countByCctv_Id(cctv.getId()), floor.getGridCellSizeMeter());
        // GridCell이 하나도 없으면 0.0㎡로 계산되는데, 그대로 나누면 density가 무한대가 되므로 여기서도 막는다.
        if (monitoredAreaM2 == null || monitoredAreaM2 <= 0) {
            throw new ApiException(CongestionErrorCode.MONITORED_AREA_NOT_AVAILABLE);
        }

        String monitoringImageKey = validateMonitoringImageKey(
                session.getId(), cctv.getCode(), request.capturedAt(), request.monitoringImageKey());

        double density = request.avgHeadcount() / monitoredAreaM2;
        CongestionLevel level = config.classify(density);
        Double frameDensity = request.frameHeadcount() == null
                ? null
                : request.frameHeadcount() / monitoredAreaM2;
        CongestionLevel frameLevel = frameDensity == null ? null : config.classify(frameDensity);

        ObservationItem item = ObservationItem.create(
                request.eventId(),
                session.getId(),
                null,
                cctv.getCode(),
                request.avgHeadcount(),
                request.peakHeadcount(),
                request.frameHeadcount(),
                request.sampleCount(),
                density,
                level,
                frameDensity,
                frameLevel,
                request.windowStart(),
                request.windowEnd(),
                request.capturedAt(),
                monitoringImageKey,
                request.configVersion()
        );

        IdempotentSaveResult<ObservationItem> saveResult = observationRepository.saveIfAbsent(item);
        validateEventIdentity(saveResult.item(), request, session.getId());
        updateLatestMonitoringCapture(session.getId(), cctv.getCode(), request.capturedAt(), monitoringImageKey);
        tryCreateAiAnalysisStartedEvent(session.getId(), cctv.getCode(), request.capturedAt());
        routeDeviationService.evaluateObservation(cctv, saveResult.item());
        tryIncrementObservationCount(saveResult, session.getId(), cctv.getCode());

        String processingOwner = UUID.randomUUID().toString();
        long processingStartedAt = Instant.now().toEpochMilli();
        boolean claimed = observationRepository.claimProcessing(
                saveResult.item().getEventId(),
                processingOwner,
                processingStartedAt,
                processingStartedAt + PROCESSING_LEASE.toMillis()
        );
        if (!claimed) {
            return saveResult;
        }

        try {
            List<MapEdge> affectedEdges = resolveAffectedEdges(cctv.getId());
            updateCurrentState(session.getId(), cctv.getCode(), request, saveResult.item());

            CongestionLevel savedLevel = saveResult.item().getCongestionLevel();
            // 관측값은 주기적 스냅샷이라 STARTED/ENDED 같은 이벤트 구분이 없다 - 항상 LEVEL_UP으로 취급한다
            // (즉시 이벤트의 CONGESTION_ENDED 기반 복구 트리거는 CongestionEventService가 전담한다).
            if (savedLevel.requiresRouteRecalculation()) {
                if (affectedEdges.isEmpty()) {
                    log.warn(
                            "혼잡 레벨이 재계산 기준을 충족했지만 감시 중인 MapEdge가 없어 재계산을 건너뜀: "
                                    + "sessionId={}, cctvCode={}, level={}",
                            session.getId(), cctv.getCode(), savedLevel
                    );
                } else {
                    // 관측값은 항상 LEVEL_UP이라 혼잡이 지속되면 관측 주기마다 재시도되므로 비동기로
                    // 떼어내도 안전하다 (#250) - ENDED는 이 서비스가 다루지 않는다
                    // (CongestionEventService 참고). MapEdge 엔티티가 아니라 id만 넘기고,
                    // capturedAt을 함께 넘겨 너무 늦게 실행될 낡은 판단은 triggerAsync가 스스로
                    // 건너뛰게 한다.
                    routeRecalculationService.triggerAsync(
                            session, affectedEdges.stream().map(MapEdge::getId).toList(), savedLevel,
                            RecalculationTriggerType.LEVEL_UP, cctv.getCode(), density, request.capturedAt());
                }
            }
            publishAndCompleteAfterCommit(session.getId(), affectedEdges, saveResult.item(), processingOwner);
        } catch (ApiException exception) {
            failProcessing(saveResult.item().getEventId(), processingOwner, exception);
            throw exception;
        } catch (RuntimeException exception) {
            failProcessing(saveResult.item().getEventId(), processingOwner, exception);
            throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
        }
        return saveResult;
    }

    // CCTV -> CctvGridCell -> FloorGridCell -> MapEdgeGridCell -> MapEdge 연쇄 조회.
    // CCTV 한 대가 여러 Edge를 감시할 수 있어 목록으로 반환한다 (Cctv.java 클래스 주석 참고).
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

    private void updateCurrentState(
            UUID sessionId, String cctvCode, ReportObservationRequest request, ObservationItem item
    ) {
        CurrentCctvStateItem stateItem = CurrentCctvStateItem.create(
                sessionId,
                cctvCode,
                item.getAvgHeadcount(),
                item.getPeakHeadcount(),
                item.getDensity(),
                item.getCongestionLevel(),
                request.capturedAt(),
                request.configVersion()
        );
        // 오래된 관측값이 최신 상태를 덮어쓰지 않도록 조건부 갱신 (lastDetectedAt 기준). 실패해도 관측값 저장 자체는 유지한다.
        if (!currentCctvStateRepository.updateIfLatest(stateItem)) {
            log.debug("더 최신 상태가 있어 CCTV 현재 상태 갱신을 건너뜀: cctvCode={}", cctvCode);
        }
    }

    private void updateLatestMonitoringCapture(
            UUID sessionId,
            String cctvCode,
            long capturedAt,
            String monitoringImageKey
    ) {
        LatestMonitoringCaptureItem capture = LatestMonitoringCaptureItem.create(
                sessionId,
                cctvCode,
                capturedAt,
                monitoringImageKey
        );
        if (!latestMonitoringCaptureRepository.updateIfLatest(capture)) {
            log.debug("더 최신 캡처가 있어 모니터링 포인터 갱신을 건너뜀: cctvCode={}", cctvCode);
        }
    }

    // 세션+CCTV 조합의 첫 유효 Observation 저장 시점에 AI_ANALYSIS_STARTED 일반 모니터링 이벤트를 생성한다.
    // eventId를 세션+CCTV+이벤트타입으로부터 결정적으로 만들어 attribute_not_exists 조건부 put에 태우면,
    // 이 저장 시도 하나만으로 "세션+CCTV당 정확히 한 번" 생성을 보장할 수 있다 (별도 마커/플래그 불필요).
    // 실패해도 Observation 저장 자체(reportObservation 전체)는 실패시키지 않는다.
    private void tryCreateAiAnalysisStartedEvent(UUID sessionId, String cctvCode, long capturedAt) {
        try {
            String eventId = UUID.nameUUIDFromBytes(
                    ("AI_ANALYSIS_STARTED:" + sessionId + ":" + cctvCode).getBytes(StandardCharsets.UTF_8)
            ).toString();
            GeneralMonitoringEventItem item = GeneralMonitoringEventItem.create(
                    eventId,
                    sessionId.toString(),
                    cctvCode,
                    GeneralMonitoringEventType.AI_ANALYSIS_STARTED,
                    capturedAt,
                    null
            );
            IdempotentSaveResult<GeneralMonitoringEventItem> result =
                    generalMonitoringEventRepository.saveIfAbsent(item);
            if (result.created()) {
                trainingEventPublisher.publishGeneralMonitoringEventReceived(sessionId, result.item());
            }
        } catch (RuntimeException exception) {
            log.error(
                    "AI_ANALYSIS_STARTED 이벤트 생성 중 오류: sessionId={}, cctvCode={}",
                    sessionId, cctvCode, exception
            );
        }
    }

    // Observation이 이번에 새로 저장된 경우(중복 재시도가 아닌 경우)에만 세션+CCTV별 저장 개수 카운터를
    // 1 증가시킨다. 실패해도 Observation 저장 자체(reportObservation() 전체)는 실패시키지 않는다.
    private void tryIncrementObservationCount(
            IdempotentSaveResult<ObservationItem> saveResult, UUID sessionId, String cctvCode
    ) {
        if (!saveResult.created()) {
            return;
        }
        try {
            observationCountRepository.increment(sessionId.toString(), cctvCode);
        } catch (RuntimeException exception) {
            log.error(
                    "Observation 카운터 증가 중 오류: sessionId={}, cctvCode={}",
                    sessionId, cctvCode, exception
            );
        }
    }

    // monitoringImageKey는 Pi가 임의로 채워 보내는 문자열이라, canonical 경로 형식과 요청 신원(세션/CCTV/캡처시각)이
    // 일치하는지 검증한 뒤에만 저장한다. 다른 세션/CCTV의 S3 객체를 가리키는 변조된 key가 저장되는 것을 막기 위함.
    // 빈 문자열은 이미지 없음을 뜻하는 null로 정규화해서 반환한다 - CongestionImageUrlService는 null만 이미지 없음으로 취급한다.
    private String validateMonitoringImageKey(
            UUID sessionId, String cctvCode, long capturedAt, String monitoringImageKey
    ) {
        if (monitoringImageKey == null || monitoringImageKey.isBlank()) {
            return null;
        }

        String[] segments = monitoringImageKey.split("/", -1);
        if (segments.length != 5
                || !TRAINING_PREFIX.equals(segments[0])
                || !MONITORING_DIRECTORY.equals(segments[2])
                || !segments[4].endsWith(JPEG_SUFFIX)) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID);
        }

        UUID keySessionId = parseCanonicalUuid(segments[1]);
        String capturedAtSegment = segments[4].substring(0, segments[4].length() - JPEG_SUFFIX.length());
        long keyCapturedAt = parseCapturedAt(capturedAtSegment);

        boolean sameIdentity = sessionId.equals(keySessionId)
                && Objects.equals(cctvCode, segments[3])
                && capturedAt == keyCapturedAt;
        if (!sameIdentity) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_IDENTITY_MISMATCH);
        }
        return monitoringImageKey;
    }

    private UUID parseCanonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID, exception);
        }
    }

    private long parseCapturedAt(String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID, exception);
        }
        // Long.parseLong은 "+2000", "02000" 같은 비canonical 표기도 허용하므로,
        // 다시 문자열로 되돌렸을 때 원본과 같은지 확인해 canonical decimal만 통과시킨다.
        if (!Long.toString(parsed).equals(value)) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID);
        }
        return parsed;
    }

    private void validateEventIdentity(ObservationItem item, ReportObservationRequest request, UUID sessionId) {
        boolean sameIdentity = Objects.equals(item.getTrainingSessionId(), sessionId.toString())
                && Objects.equals(item.getCctvCode(), request.cctvCode());
        if (!sameIdentity) {
            throw new ApiException(CongestionErrorCode.EVENT_IDENTITY_MISMATCH);
        }
    }

    private void publishAndCompleteAfterCommit(
            UUID sessionId, List<MapEdge> affectedEdges, ObservationItem item, String processingOwner
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishCongestionUpdated(sessionId, affectedEdges, item);
            completeProcessing(item.getEventId(), processingOwner);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publishCongestionUpdated(sessionId, affectedEdges, item);
                    completeProcessing(item.getEventId(), processingOwner);
                } catch (RuntimeException exception) {
                    failProcessing(item.getEventId(), processingOwner, exception);
                    throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    failProcessing(item.getEventId(), processingOwner, null);
                }
            }
        });
    }

    // CCTV당 1 Observation = 1 WebSocket 이벤트 원칙을 지키기 위해 영향받는 Edge 전체를 배열로 담아
    // 한 번만 발행한다 (이슈 #192). Edge가 없으면(감시 영역에 아직 GridCell/Edge 매핑이 안 된 CCTV)
    // 빈 목록으로 발행해 대시보드가 최소한 CCTV 단위 관측값은 볼 수 있게 한다.
    private void publishCongestionUpdated(UUID sessionId, List<MapEdge> affectedEdges, ObservationItem item) {
        List<UUID> affectedEdgeIds = affectedEdges.stream().map(MapEdge::getId).distinct().toList();
        trainingEventPublisher.publishCongestionUpdated(sessionId, affectedEdgeIds, item);
    }

    private void completeProcessing(String eventId, String processingOwner) {
        try {
            if (!observationRepository.completeProcessing(eventId, processingOwner)) {
                log.warn("혼잡 관측 처리 완료 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException exception) {
            log.error("혼잡 관측 처리 완료 상태 저장 중 오류: eventId={}", eventId, exception);
        }
    }

    private void failProcessing(String eventId, String processingOwner, RuntimeException cause) {
        try {
            if (!observationRepository.failProcessing(eventId, processingOwner)) {
                log.warn("혼잡 관측 처리 실패 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException statusException) {
            if (cause != null) {
                cause.addSuppressed(statusException);
            }
            log.error("혼잡 관측 처리 실패 상태 저장 중 오류: eventId={}", eventId, statusException);
        }
    }
}
