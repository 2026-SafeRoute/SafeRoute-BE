package com.saferoute.domain.training.entity;

import java.time.Duration;

// 화재 확산 속도.
// 시뮬레이션 사용 방식: 확산 tick 간격을 결정
//   FAST > MEDIUM > SLOW 순으로 인접 셀로 번지는 주기가 짧게
public enum FireSpreadSpeed {
    SLOW,
    MEDIUM,
    FAST;

    // 한 세대(상하좌우 인접 셀로 한 칸) 번지는 주기. 확산 시뮬레이션(FireSpreadStepService)과
    // 경로 탐색의 화재 근접 가중치(FireProximity)가 같은 값을 써야 하므로 여기 한 곳에 둔다.
    public Duration tickInterval() {
        return switch (this) {
            case FAST -> Duration.ofSeconds(5);
            case MEDIUM -> Duration.ofSeconds(15);
            case SLOW -> Duration.ofSeconds(30);
        };
    }
}
