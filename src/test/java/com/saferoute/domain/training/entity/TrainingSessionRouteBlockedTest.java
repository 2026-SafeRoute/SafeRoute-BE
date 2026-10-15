package com.saferoute.domain.training.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.saferoute.domain.user.entity.User;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrainingSessionRouteBlockedTest {

    private static final Instant STARTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private TrainingSession runningSession() {
        return TrainingSession.create(
                TrainingStatus.RUNNING, STARTED_AT, mock(User.class), mock(TrainingScenario.class));
    }

    @Test
    @DisplayName("경로가 처음 막히면 시각을 남기고 true를 돌려준다")
    void markRouteBlocked_firstTime_recordsAndReturnsTrue() {
        TrainingSession session = runningSession();
        Instant blockedAt = STARTED_AT.plusSeconds(95);

        assertThat(session.markRouteBlocked(blockedAt)).isTrue();
        assertThat(session.getRouteBlockedAt()).isEqualTo(blockedAt);
        assertThat(session.routeBlockedAfterSec()).isEqualTo(95);
    }

    @Test
    @DisplayName("이미 막힌 적이 있으면 최초 시각을 유지하고 false를 돌려준다")
    void markRouteBlocked_alreadyBlocked_keepsFirstTimeAndReturnsFalse() {
        TrainingSession session = runningSession();
        Instant first = STARTED_AT.plusSeconds(95);
        session.markRouteBlocked(first);

        assertThat(session.markRouteBlocked(STARTED_AT.plusSeconds(200))).isFalse();
        assertThat(session.getRouteBlockedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("막힌 적이 없으면 경과 초는 null이다")
    void routeBlockedAfterSec_neverBlocked_isNull() {
        assertThat(runningSession().routeBlockedAfterSec()).isNull();
    }

    @Test
    @DisplayName("시작 시각이 없으면 경과 초는 null이다")
    void routeBlockedAfterSec_notStarted_isNull() {
        TrainingSession session = TrainingSession.schedule(mock(User.class), mock(TrainingScenario.class));
        session.markRouteBlocked(STARTED_AT);

        assertThat(session.routeBlockedAfterSec()).isNull();
    }
}
