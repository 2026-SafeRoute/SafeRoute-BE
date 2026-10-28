package com.saferoute.domain.evacuation.recalculation.service;

import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 한 세션의 한 층에서 "지금 이 순간" 혼잡한 엣지들의 가중치 배율 맵을 만든다.
// 혼잡 트리거(RouteRecalculationService.trigger)는 그 트리거를 낸 CCTV 한 대의 엣지만 알면 되지만,
// 화재 우회 후보는 혼잡 이벤트와 무관한 시점에 계산되므로 층 전체의 현재 혼잡 상태가 필요하다.
// 기준 데이터는 CCTV별 최신 관측값(CurrentCctvStateItem)과 CCTV별 가장 최근 즉시 혼잡 이벤트(CurrentCongestionEventItem)다.
// 즉시 이벤트는 Pi가 프레임 단위로 혼잡 진입/상승을 판단해 보내는 신호라, 구간 평균인 관측값보다 혼잡을 먼저
// (또는 관측값은 NORMAL인데도) 알려줄 수 있다. 관측값만 보면 A CCTV의 즉시 이벤트로 만든 우회 제안이,
// 그 직후 B CCTV 쪽 재계산에서 A가 NORMAL로 보여 바로 취소된다(#293). 그래서 CCTV별 최신 즉시 이벤트가
// STARTED/LEVEL_UP이면(아직 ENDED가 오지 않았으면) 그 레벨도 함께 반영하고, 둘 중 더 큰 배율을 쓴다.
// CCTV 현재 상태 -> CCTV 감시 GridCell -> MapEdgeGridCell -> MapEdge
// 조회 실패(DynamoDB 장애 등)를 호출부(RouteRecalculationService)가 잡아서 혼잡 없이 계속 진행하므로,
// 예외가 이 프록시를 통과하면서 호출부 트랜잭션을 rollback-only로 오염시키지 않게 한다 - 읽기 전용이라
// 되돌릴 변경도 없다(그러지 않으면 호출부가 예외를 삼켜도 커밋 시점에 UnexpectedRollbackException).
// 마지막 관측이 stateStaleAfterSec보다 오래된 CCTV는 혼잡이 아닌 것으로 본다(모니터링 화면의 stale 판정과
// 같은 기준). Pi나 카메라가 혼잡 상태에서 끊기면 마지막 상태가 그대로 남아, 아무도 없는 구간이 훈련이
// 끝날 때까지 페널티를 받고 경로/유도등이 그 구간을 계속 피하게 되기 때문이다. 이 판정은 Pi가 보낸
// 관측 시각(capturedAt)이 아니라 BE가 받은 서버 시각(receivedAt)을 서버 시각과 비교한다 - Pi 시계가 15초만
// 틀려도 모든 CCTV가 stale로 빠져 혼잡 가중치가 통째로 무시되는 일을 막기 위함(모니터링 화면도 같은 기준).
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
public class CurrentCongestionWeightProvider {

    // 화재 우회 후보에서는 VERY_CROWDED도 완전 제외가 아니라 큰 페널티로만 반영한다.
    // 혼잡 우회(trigger)와 달리 화재 우회는 "혼잡한 길밖에 안 남은" 상황에서도 반드시 후보를
    // 내야 하므로, 혼잡 때문에 EVACUATION_ROUTE_NOT_FOUND가 나는 일이 없어야 한다.
    static final double VERY_CROWDED_WEIGHT_MULTIPLIER = 10.0;

    private final CurrentCctvStateRepository currentCctvStateRepository;
    private final CctvJpaRepository cctvJpaRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final CongestionConfigService congestionConfigService;
    private final CurrentCongestionEventRepository currentCongestionEventRepository;

    // 혼잡하지 않은 엣지(배율 1.0)는 담지 않는다. 여러 CCTV가 같은 엣지를 감시하면 가장 큰 배율을 쓴다.
    public Map<UUID, Double> currentMultipliers(UUID sessionId, UUID floorId) {
        return currentMultipliers(sessionId, floorId, Set.of());
    }

    // excludedCctvCodes에 든 CCTV의 현재 상태는 집계에서 뺀다. 혼잡 종료(ENDED) 직후에는 주기 관측값이
    // 아직 갱신되기 전이라 방금 끝난 CCTV가 여전히 혼잡으로 남아 있을 수 있어, 그 CCTV 몫만 제외하고 나머지
    // CCTV의 혼잡은 그대로 반영해야 한다. 엣지 단위로 빼면 같은 엣지를 감시하는 다른 CCTV의 혼잡까지
    // 사라지므로 CCTV 코드 기준으로 제외한다.
    public Map<UUID, Double> currentMultipliers(UUID sessionId, UUID floorId, Set<String> excludedCctvCodes) {
        CongestionConfig config = congestionConfigService.getConfig();
        long now = Instant.now().toEpochMilli();
        Integer stateStaleAfterSec = config.getStateStaleAfterSec();
        Map<String, Double> multiplierByCctvCode = new HashMap<>();
        Set<String> liveCctvCodes = new HashSet<>();
        List<String> staleCctvCodes = new ArrayList<>();
        for (CurrentCctvStateItem state : currentCctvStateRepository.findAllBySessionId(sessionId.toString())) {
            if (excludedCctvCodes.contains(state.getCctvCode())) {
                continue;
            }
            boolean stale = isStale(state.effectiveReceivedAt(), now, stateStaleAfterSec);
            if (!stale) {
                liveCctvCodes.add(state.getCctvCode());
            }
            double multiplier = multiplierFor(state.getCongestionLevel());
            if (multiplier <= 1.0) {
                continue;
            }
            if (stale) {
                staleCctvCodes.add(state.getCctvCode());
                continue;
            }
            multiplierByCctvCode.put(state.getCctvCode(), multiplier);
        }
        // 즉시 이벤트는 혼잡이 이어지는 동안 반복해서 오지 않으므로 이벤트 시각만으로 신선도를 판단하면
        // 혼잡이 계속돼도 곧 빠져버린다. 그래서 그 CCTV가 아직 관측값을 보내고 있으면(살아 있으면) 마지막
        // 이벤트를 그대로 믿고, 관측값이 끊겼으면 이벤트 자체가 방금 온 경우에만 반영한다.
        for (CurrentCongestionEventItem event
                : currentCongestionEventRepository.findAllBySessionId(sessionId.toString())) {
            String cctvCode = event.getCctvCode();
            if (excludedCctvCodes.contains(cctvCode)
                    || event.getEventType() == CongestionEventType.CONGESTION_ENDED) {
                continue;
            }
            double multiplier = multiplierFor(event.getCongestionLevel());
            if (multiplier <= 1.0) {
                continue;
            }
            if (!liveCctvCodes.contains(cctvCode) && isStale(event.effectiveReceivedAt(), now, stateStaleAfterSec)) {
                staleCctvCodes.add(cctvCode);
                continue;
            }
            multiplierByCctvCode.merge(cctvCode, multiplier, Math::max);
        }
        // 관측 주기마다 호출될 수 있어 INFO 이상은 쓰지 않는다.
        if (!staleCctvCodes.isEmpty()) {
            log.debug("관측이 끊긴 CCTV의 혼잡을 가중치에서 제외: sessionId={}, cctvCodes={}", sessionId, staleCctvCodes);
        }
        if (multiplierByCctvCode.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Double> multiplierByCctvId = new HashMap<>();
        for (Cctv cctv : cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)) {
            Double multiplier = multiplierByCctvCode.get(cctv.getCode());
            if (multiplier != null) {
                multiplierByCctvId.put(cctv.getId(), multiplier);
            }
        }
        if (multiplierByCctvId.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Double> multiplierByCellId = new HashMap<>();
        for (CctvGridCell mapping : cctvGridCellRepository.findAllByCctvIdsWithGridCell(
                new ArrayList<>(multiplierByCctvId.keySet()))) {
            multiplierByCellId.merge(
                    mapping.getGridCell().getId(), multiplierByCctvId.get(mapping.getCctv().getId()), Math::max);
        }
        if (multiplierByCellId.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Double> multiplierByEdgeId = new HashMap<>();
        for (MapEdgeGridCell edgeCell : mapEdgeGridCellRepository.findAllByGridCell_IdIn(
                List.copyOf(multiplierByCellId.keySet()))) {
            multiplierByEdgeId.merge(
                    edgeCell.getMapEdge().getId(), multiplierByCellId.get(edgeCell.getGridCell().getId()), Math::max);
        }
        return multiplierByEdgeId;
    }

    // TrainingMonitoringService.isStale()과 같은 기준(`>`, 서버 수신 시각)을 써야 화면에 stale로 표시되는 CCTV와
    // 경로 계산에서 빠지는 CCTV가 정확히 같다. 수신 시각을 알 수 없는 상태는 오래된 것으로 본다. 설정값이
    // 없으면(실제로는 기본값 생성 때문에 없다) 필터를 적용하지 않아 기존 동작을 유지한다.
    private boolean isStale(Long receivedAt, long now, Integer stateStaleAfterSec) {
        if (stateStaleAfterSec == null) {
            return false;
        }
        return receivedAt == null || now - receivedAt > stateStaleAfterSec * 1_000L;
    }

    private double multiplierFor(CongestionLevel level) {
        if (level == null) {
            return 1.0;
        }
        return switch (level) {
            case NORMAL -> 1.0;
            case CAUTION -> RouteRecalculationService.CAUTION_WEIGHT_MULTIPLIER;
            case CROWDED -> RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER;
            case VERY_CROWDED -> VERY_CROWDED_WEIGHT_MULTIPLIER;
        };
    }
}
