package com.saferoute.domain.evacuation.deviation.dto;

import java.util.UUID;

// totalObservedWindows: 유도등이 좌/우 중 하나를 가리키던 중 CCTV가 인원을 탐지한 관측 구간 수
// deviatedWindows: 그중 유도등이 가리킨 방향과 다른 쪽 CCTV에서 인원이 탐지된 구간 수
public record RouteDeviationResponse(
        UUID lightId,
        UUID trainingSessionId,
        long totalObservedWindows,
        long deviatedWindows,
        double deviationRate
) {
}
