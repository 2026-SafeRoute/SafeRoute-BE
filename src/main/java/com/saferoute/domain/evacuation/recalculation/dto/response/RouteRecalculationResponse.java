package com.saferoute.domain.evacuation.recalculation.dto.response;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RouteRecalculationResponse(
        UUID id,
        UUID trainingSessionId,
        UUID triggerEdgeId,
        CongestionLevel congestionLevel,
        List<UUID> recalculatedNodeIds,
        double totalWeight,
        RecalculationStatus status,
        Instant requestedAt,
        Instant resolvedAt,
        String resolvedBy,
        String rejectReason
) {

    public static RouteRecalculationResponse from(RouteRecalculation recalculation) {
        return new RouteRecalculationResponse(
                recalculation.getId(),
                recalculation.getTrainingSession().getId(),
                recalculation.triggerEdgeIdOrNull(),
                recalculation.getCongestionLevel(),
                // recalculatedNodeIds는 @ElementCollection(LAZY)이라, open-in-view: false 환경에서
                // 트랜잭션이 끝난 뒤(JSON 직렬화 시점)에 접근하면 LazyInitializationException이 난다.
                // 트랜잭션이 살아있는 지금 여기서 List.copyOf로 미리 순회해 즉시 초기화한다.
                List.copyOf(recalculation.getRecalculatedNodeIds()),
                recalculation.getTotalWeight(),
                recalculation.getStatus(),
                recalculation.getRequestedAt(),
                recalculation.getResolvedAt(),
                recalculation.getResolvedBy() != null ? recalculation.getResolvedBy().getUsername() : null,
                recalculation.getRejectReason()
        );
    }
}
