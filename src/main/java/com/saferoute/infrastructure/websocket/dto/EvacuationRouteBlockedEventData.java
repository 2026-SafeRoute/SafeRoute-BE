package com.saferoute.infrastructure.websocket.dto;

import com.saferoute.domain.training.entity.TrainingSession;
import java.time.Instant;
import java.util.UUID;

// 화재로 시작 지점에서 출구로 가는 우회 경로가 처음 사라졌을 때 보내는 알림.
// 훈련을 자동 종료하지 않으므로, 프론트는 경고만 띄우고 종료 여부는 관리자가 판단한다.
public record EvacuationRouteBlockedEventData(
        UUID floorId,
        Instant blockedAt,
        Integer blockedAfterSec
) {

    public static EvacuationRouteBlockedEventData from(TrainingSession session, UUID floorId) {
        return new EvacuationRouteBlockedEventData(
                floorId,
                session.getRouteBlockedAt(),
                session.routeBlockedAfterSec()
        );
    }
}
