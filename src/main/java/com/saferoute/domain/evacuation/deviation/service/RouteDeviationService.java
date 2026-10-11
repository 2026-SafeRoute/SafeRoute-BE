package com.saferoute.domain.evacuation.deviation.service;

import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.evacuation.deviation.dto.RouteDeviationResponse;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventType;
import com.saferoute.domain.telemetry.dynamo.entity.LightDirectionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.entity.RouteDeviationState;
import com.saferoute.domain.telemetry.dynamo.entity.RouteDeviationStateItem;
import com.saferoute.domain.telemetry.dynamo.repository.GeneralMonitoringEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.telemetry.dynamo.repository.LightDirectionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationRepository;
import com.saferoute.domain.telemetry.dynamo.repository.RouteDeviationStateRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.IoTLightErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 유도등이 안내한 방향(LightDirectionEventItem 이력)과 CCTV가 실제로 인원을 탐지한 위치(ObservationItem)를
// 시간 축으로 조인해 경로 이탈률을 계산한다. CCTV 한 대는 headcount/밀집도만 보고하므로 개별 이동 경로까지는
// 알 수 없지만, "좌/우 통로를 각각 감시하는 CCTV가 분리되어 있다"는 전제(데모 구성) 하에 어느 쪽 CCTV에서
// 인원이 탐지됐는지를 실제 이동 방향의 대리 지표로 사용한다.
//
// evaluateObservation()은 위 리포트 집계와 별개로, Observation이 들어올 때마다 실시간으로 경로 이탈
// 상태(RouteDeviationStateItem)를 갱신하고 NORMAL->DEVIATING 전환 시 ROUTE_DEVIATION_DETECTED
// 일반 모니터링 이벤트를 생성한다. resolveCctvCodes/activeDirectionAt/모호 CCTV 제외 로직은
// computeWindowStats와 동일한 기준을 공유하기 위해 그대로 재사용한다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteDeviationService {

    private static final int OBSERVATION_QUERY_LIMIT = 2000;
    private static final int NORMAL_RECOVERY_ZERO_STREAK = 2;

    private final IoTLightJpaRepository iotLightJpaRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final LightDirectionEventRepository lightDirectionEventRepository;
    private final ObservationRepository observationRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final SchoolContextService schoolContextService;
    private final RouteDeviationStateRepository routeDeviationStateRepository;
    private final GeneralMonitoringEventRepository generalMonitoringEventRepository;
    private final TrainingEventPublisher trainingEventPublisher;

    public RouteDeviationResponse calculate(UUID lightId, UUID trainingSessionId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        IoTLight light = iotLightJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(lightId, schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND));
        TrainingSession session = trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(trainingSessionId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        if (!light.isGuidanceConfigured()) {
            throw new ApiException(IoTLightErrorCode.GUIDANCE_NOT_CONFIGURED);
        }

        WindowStats stats = computeWindowStats(light, session)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.DEVIATION_CCTV_MAPPING_NOT_FOUND));

        return new RouteDeviationResponse(
                light.getId(), session.getId(), stats.total, stats.deviated, stats.deviationRate());
    }

    // 훈련 리포트의 "경로 이탈률(→준수율)" 산정용 - 세션이 속한 건물의 모든 유도등을 통틀어 집계
    public SessionDeviationResult calculateForSession(UUID sessionId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingSession session = trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        UUID buildingId = session.getScenario().getBuildingId();
        List<IoTLight> lights = iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId);

        long total = 0;
        long deviated = 0;
        for (IoTLight light : lights) {
            if (!light.isGuidanceConfigured()) {
                continue;
            }
            WindowStats stats = computeWindowStats(light, session).orElse(null);
            if (stats == null) {
                continue;
            }
            total += stats.total;
            deviated += stats.deviated;
        }

        double deviationRate = total == 0 ? 0.0 : (double) deviated / total;
        return new SessionDeviationResult(total, deviated, deviationRate);
    }

    // Observation 하나가 들어올 때마다 이 CCTV를 좌/우 CCTV로 쓰는 모든 유도등에 대해 실시간으로
    // 경로 이탈 여부를 판정한다. CongestionObservationService.reportObservation()에서 Observation이
    // 유효하게 저장된 직후(처리 lease 선점 여부와 무관) 호출된다. 경로 재계산/유도등 제어는 호출하지 않는다.
    // 예외는 유도등 하나당 개별로 흡수한다 - 바깥에서 한 번에 흡수하면 유도등 하나(예: edge
    // 매핑이 깨진 경우)가 던진 예외가 같은 Observation으로 아직 평가 안 한 나머지 유도등 전체를
    // 건너뛰게 만든다. Observation 저장 자체(reportObservation() 전체)는 어느 쪽이든 실패시키지 않는다.
    @Transactional
    public void evaluateObservation(Cctv cctv, ObservationItem observation) {
        UUID buildingId;
        List<IoTLight> lights;
        try {
            buildingId = cctv.getCustomNode().getFloor().getBuilding().getId();
            lights = iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId);
        } catch (RuntimeException exception) {
            log.error(
                    "경로 이탈 판정 중 오류: sessionId={}, cctvCode={}",
                    observation.getTrainingSessionId(), observation.getCctvCode(), exception
            );
            return;
        }
        for (IoTLight light : lights) {
            if (!light.isGuidanceConfigured()) {
                continue;
            }
            try {
                evaluateLight(light, observation);
            } catch (RuntimeException exception) {
                log.error(
                        "경로 이탈 판정 중 오류: sessionId={}, cctvCode={}, lightId={}",
                        observation.getTrainingSessionId(), observation.getCctvCode(), light.getId(), exception
                );
            }
        }
    }

    // 유도등 하나에 대해, 이번 Observation이 "안내 반대편 CCTV의 신호"인지 판단하고 상태 전환을 시도한다.
    private void evaluateLight(IoTLight light, ObservationItem observation) {
        String cctvCode = observation.getCctvCode();
        Set<String> leftCctvCodes = resolveCctvCodes(light.getLeftEdge().getId());
        Set<String> rightCctvCodes = resolveCctvCodes(light.getRightEdge().getId());
        Set<String> ambiguous = intersect(leftCctvCodes, rightCctvCodes);
        if (ambiguous.contains(cctvCode)) {
            return;
        }
        leftCctvCodes.removeAll(ambiguous);
        rightCctvCodes.removeAll(ambiguous);

        boolean isLeftCctv = leftCctvCodes.contains(cctvCode);
        boolean isRightCctv = rightCctvCodes.contains(cctvCode);
        if (!isLeftCctv && !isRightCctv) {
            return;
        }

        List<LightDirectionEventItem> directionEvents = lightDirectionEventRepository
                .findAllBySessionIdAndLightCode(observation.getTrainingSessionId(), light.getCode());
        IoTLightDirection activeDirection = activeDirectionAt(directionEvents, observation.getCapturedAt());
        if (activeDirection == null || activeDirection == IoTLightDirection.BOTH
                || activeDirection == IoTLightDirection.OFF) {
            return;
        }

        boolean isOppositeSideSignal = (activeDirection == IoTLightDirection.LEFT && isRightCctv)
                || (activeDirection == IoTLightDirection.RIGHT && isLeftCctv);
        if (!isOppositeSideSignal) {
            return;
        }

        boolean deviationSignal = observation.getAvgHeadcount() != null && observation.getAvgHeadcount() > 0;
        applyTransition(observation.getTrainingSessionId(), light.getId(), cctvCode, deviationSignal,
                observation.getCapturedAt());
    }

    // 유도등+세션별 상태를 읽어 다음 상태를 계산한 뒤, capturedAt 기준 조건부 쓰기로 원자적으로 반영한다.
    // 조건부 쓰기가 성공하고 NORMAL -> DEVIATING 전환이 일어난 경우에만 이벤트를 생성한다.
    private void applyTransition(
            String trainingSessionId, UUID lightId, String cctvCode, boolean deviationSignal, long capturedAt
    ) {
        Optional<RouteDeviationStateItem> existing = routeDeviationStateRepository.find(trainingSessionId, lightId);
        RouteDeviationState currentState = existing.map(RouteDeviationStateItem::getState)
                .orElse(RouteDeviationState.NORMAL);
        int currentZeroStreak = existing.map(RouteDeviationStateItem::getZeroStreak).orElse(0);
        Long lastProcessedCapturedAt = existing.map(RouteDeviationStateItem::getLastProcessedCapturedAt)
                .orElse(null);
        if (lastProcessedCapturedAt != null && capturedAt <= lastProcessedCapturedAt) {
            return;
        }

        RouteDeviationState nextState = currentState;
        int nextZeroStreak = currentZeroStreak;
        boolean transitionedToDeviating = false;
        if (deviationSignal) {
            nextZeroStreak = 0;
            if (currentState == RouteDeviationState.NORMAL) {
                nextState = RouteDeviationState.DEVIATING;
                transitionedToDeviating = true;
            }
        } else {
            nextZeroStreak = currentZeroStreak + 1;
            if (currentState == RouteDeviationState.DEVIATING && nextZeroStreak >= NORMAL_RECOVERY_ZERO_STREAK) {
                nextState = RouteDeviationState.NORMAL;
                nextZeroStreak = 0;
            }
        }

        RouteDeviationStateItem nextItem =
                RouteDeviationStateItem.create(trainingSessionId, lightId, nextState, nextZeroStreak, capturedAt);
        boolean written = routeDeviationStateRepository.saveIfNewer(nextItem);
        if (written && transitionedToDeviating) {
            createRouteDeviationDetectedEvent(trainingSessionId, cctvCode, capturedAt);
        }
    }

    // 전환마다 새 eventId를 발급한다 - 상태 전환 자체가 조건부 쓰기로 최대 1회만 성공하므로 중복 생성 위험이 없다.
    private void createRouteDeviationDetectedEvent(String trainingSessionId, String cctvCode, long occurredAt) {
        GeneralMonitoringEventItem item = GeneralMonitoringEventItem.create(
                UUID.randomUUID().toString(),
                trainingSessionId,
                cctvCode,
                GeneralMonitoringEventType.ROUTE_DEVIATION_DETECTED,
                occurredAt,
                null
        );
        IdempotentSaveResult<GeneralMonitoringEventItem> result = generalMonitoringEventRepository.saveIfAbsent(item);
        if (result.created()) {
            trainingEventPublisher.publishGeneralMonitoringEventReceived(
                    UUID.fromString(trainingSessionId), result.item());
        }
    }

    // 유도등 하나에 대해 (관측 구간 수, 이탈 구간 수)를 계산, 경로가 지나는 CCTV를 특정할 수 없으면 empty.
    private Optional<WindowStats> computeWindowStats(IoTLight light, TrainingSession session) {
        Set<String> leftCctvCodes = resolveCctvCodes(light.getLeftEdge().getId());
        Set<String> rightCctvCodes = resolveCctvCodes(light.getRightEdge().getId());
        Set<String> ambiguous = intersect(leftCctvCodes, rightCctvCodes);
        leftCctvCodes.removeAll(ambiguous);
        rightCctvCodes.removeAll(ambiguous);
        if (leftCctvCodes.isEmpty() || rightCctvCodes.isEmpty()) {
            return Optional.empty();
        }

        List<LightDirectionEventItem> directionEvents = lightDirectionEventRepository
                .findAllBySessionIdAndLightCode(session.getId().toString(), light.getCode());

        // windowStart(관측 구간의 시작 시각) 기준으로 좌/우 CCTV 결과를 먼저 합산한다.
        // 같은 구간을 좌/우 CCTV 레코드 각각으로 두 번 집계하지 않기 위함이다.
        Map<Long, WindowHeadcount> windows = new TreeMap<>();
        for (String cctvCode : union(leftCctvCodes, rightCctvCodes)) {
            boolean isLeft = leftCctvCodes.contains(cctvCode);
            List<ObservationItem> observations = observationRepository.findAllBySessionIdAndCctvCode(
                    session.getId().toString(), cctvCode, OBSERVATION_QUERY_LIMIT);
            for (ObservationItem observation : observations) {
                if (observation.getAvgHeadcount() == null || observation.getAvgHeadcount() <= 0) {
                    continue;
                }
                WindowHeadcount window = windows.computeIfAbsent(observation.getWindowStart(),
                        key -> new WindowHeadcount());
                window.capturedAt = observation.getCapturedAt();
                if (isLeft) {
                    window.left += observation.getAvgHeadcount();
                } else {
                    window.right += observation.getAvgHeadcount();
                }
            }
        }

        long total = 0;
        long deviated = 0;
        for (WindowHeadcount headcount : windows.values()) {
            // 방향 조회는 관측 구간의 실제 보고 시각(capturedAt)을 기준으로 한다. windowStart는 병합 키일 뿐이다.
            IoTLightDirection activeDirection = activeDirectionAt(directionEvents, headcount.capturedAt);
            // BOTH(평상시)는 "안내 반대쪽"이라는 개념이 없어 이탈 여부를 판단할 기준이 없으므로 OFF와 함께 제외한다.
            if (activeDirection == null || activeDirection == IoTLightDirection.OFF
                    || activeDirection == IoTLightDirection.BOTH) {
                continue;
            }
            total++;
            // 안내 방향의 반대쪽에서도 인원이 탐지되면(양쪽 모두 탐지된 경우 포함) 그 구간은 이탈로 집계한다.
            boolean nonGuidedSideDetected = activeDirection == IoTLightDirection.LEFT
                    ? headcount.right > 0
                    : headcount.left > 0;
            if (nonGuidedSideDetected) {
                deviated++;
            }
        }

        return Optional.of(new WindowStats(total, deviated));
    }

    private record WindowStats(long total, long deviated) {
        double deviationRate() {
            return total == 0 ? 0.0 : (double) deviated / total;
        }
    }

    private static final class WindowHeadcount {
        private double left;
        private double right;
        private long capturedAt;
    }

    // 주어진 시각(관측 구간의 windowStart)에 유도등이 가리키고 있던 방향. 이력이 시간순으로 정렬되어
    // 있으므로 해당 시각 이전(이하)의 마지막 전환 이벤트를 찾는다. 전환 이력이 아직 없으면 null.
    private IoTLightDirection activeDirectionAt(List<LightDirectionEventItem> events, long timestamp) {
        IoTLightDirection active = null;
        for (LightDirectionEventItem event : events) {
            if (event.getChangedAt() > timestamp) {
                break;
            }
            active = event.getDirection();
        }
        return active;
    }

    // Edge -> GridCell -> CCTV 역방향 조회로, 이 통로를 감시하는 CCTV 코드 목록을 찾는다.
    private Set<String> resolveCctvCodes(UUID edgeId) {
        List<UUID> gridCellIds = mapEdgeGridCellRepository.findAllByMapEdge_Id(edgeId).stream()
                .map(mapping -> mapping.getGridCell().getId())
                .toList();
        if (gridCellIds.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> codes = new HashSet<>();
        cctvGridCellRepository.findAllByGridCell_IdIn(gridCellIds)
                .forEach(mapping -> codes.add(mapping.getCctv().getCode()));
        return codes;
    }

    private Set<String> intersect(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return result;
    }
}
