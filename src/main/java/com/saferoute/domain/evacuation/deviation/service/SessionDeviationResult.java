package com.saferoute.domain.evacuation.deviation.service;

// totalObservedWindows: 세션에 속한 모든 유도등을 통틀어, 유도등이 좌/우 중 하나를 가리키던 중
//   CCTV가 인원을 탐지한 관측 구간 수의 합
// deviatedWindows: 그중 유도등이 가리킨 방향과 다른 쪽 CCTV에서 인원이 탐지된 구간 수의 합
public record SessionDeviationResult(long totalObservedWindows, long deviatedWindows, double deviationRate) {
}
