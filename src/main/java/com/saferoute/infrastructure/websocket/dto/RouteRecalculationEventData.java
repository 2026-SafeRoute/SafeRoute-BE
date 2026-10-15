package com.saferoute.infrastructure.websocket.dto;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import java.util.List;
import java.util.UUID;

// triggerType으로 화재(FIRE_SPREAD)와 혼잡(STARTED/LEVEL_UP/ENDED) 재탐색을 구분한다.
// FIRE_SPREAD면 혼잡 개념이 없어 congestionLevel은 null이다.
public record RouteRecalculationEventData(
        UUID recalculationId,
        UUID triggerEdgeId,
        RecalculationTriggerType triggerType,
        CongestionLevel congestionLevel,
        List<UUID> recalculatedNodeIds,
        double totalWeight,
        RecalculationStatus status,
        String reason
) {

    public static RouteRecalculationEventData from(RouteRecalculation recalculation) {
        String reason = recalculation.getStatus() == RecalculationStatus.REJECTED
                ? recalculation.getRejectReason()
                : recalculation.getCancelReason();
        return new RouteRecalculationEventData(
                recalculation.getId(),
                recalculation.getTriggerEdge().getId(),
                recalculation.getTriggerType(),
                recalculation.getCongestionLevel(),
                recalculation.getRecalculatedNodeIds(),
                recalculation.getTotalWeight(),
                recalculation.getStatus(),
                reason
        );
    }
}
