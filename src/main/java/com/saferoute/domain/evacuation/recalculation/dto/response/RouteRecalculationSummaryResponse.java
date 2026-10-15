package com.saferoute.domain.evacuation.recalculation.dto.response;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.congestion.entity.DensityUnit;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import java.time.Instant;
import java.util.UUID;

// 승인 대기 목록 화면용 - 경로 상세는 포함하지 않는다 (상세 조회 API 참고).
public record RouteRecalculationSummaryResponse(
        UUID recalculationId,
        UUID trainingSessionId,
        UUID floorId,
        Integer floorNum,
        String locationName,
        String cctvCode,
        UUID triggerEdgeId,
        RecalculationTriggerType triggerType,
        CongestionLevel congestionLevel,
        double density,
        DensityUnit densityUnit,
        RecalculationStatus status,
        Instant requestedAt
) {

    public static RouteRecalculationSummaryResponse from(RouteRecalculation recalculation) {
        // 도면이 교체돼 트리거 엣지가 지워졌어도 이력은 조회돼야 하므로 층은 기록에 저장된 값을 쓴다.
        Integer floorNum = recalculation.resolvedFloorNum();
        return new RouteRecalculationSummaryResponse(
                recalculation.getId(),
                recalculation.getTrainingSession().getId(),
                recalculation.resolvedFloorId(),
                floorNum,
                floorNum != null ? floorNum + "층" : null,
                recalculation.getCctvCode(),
                recalculation.triggerEdgeIdOrNull(),
                recalculation.getTriggerType(),
                recalculation.getCongestionLevel(),
                recalculation.getDensity(),
                DensityUnit.PERSON_PER_SQUARE_METER,
                recalculation.getStatus(),
                recalculation.getRequestedAt()
        );
    }
}
