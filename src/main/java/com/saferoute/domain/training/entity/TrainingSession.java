package com.saferoute.domain.training.entity;

import com.saferoute.domain.report.entity.TrainingReport;
import com.saferoute.domain.user.entity.User;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(name = "training_sessions")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Version
    private Long version;

    //훈련 상태 (RUNNING, STOPPED 등)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "training_status", nullable = false, length = 20)
    private TrainingStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "act_participants")
    private Integer actualParticipants;

    @Column(name = "survival_rate")
    private BigDecimal currentSurvivalRate;

    @Column(name = "avg_evacuation_sec")
    private Integer currentAvgEvacuationSec;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_spread_at")
    private Instant lastSpreadAt;

    @Column(name = "current_generation", nullable = false)
    private int currentGeneration = 0;

    // 화재가 번져 시작 지점에서 출구로 가는 우회 경로가 처음으로 사라진 시각. 한 번도 막히지 않았으면 null.
    // 화재는 꺼지지 않아 한 번 막히면 다시 열리지 않으므로 최초 시각만 남긴다. 이 층 그래프 밖(아래층 계단 등)
    // 대피 수단과 실제 사람 위치는 시스템이 모르므로 이것만으로 훈련을 실패 처리하지 않는다 - 관리자에게
    // 알리고(EVACUATION_ROUTE_BLOCKED) 리포트에 기록하는 데만 쓴다.
    @Column(name = "route_blocked_at")
    private Instant routeBlockedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User admin;

    // 시나리오당 세션은 1개만 존재한다. 재훈련이 필요하면 시나리오를 새로 만든다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_scenario_id", nullable = false, unique = true)
    private TrainingScenario scenario;

    @OneToOne(mappedBy = "trainingSession", cascade = CascadeType.ALL)
    private TrainingReport trainingReport;

    private TrainingSession(TrainingStatus status, Instant startedAt, User admin, TrainingScenario scenario) {
        this.status = status;
        this.startedAt = startedAt;
        this.lastSpreadAt = startedAt;
        this.admin = admin;
        this.scenario = scenario;
    }

    // 훈련 세션 생성용 정적 팩토리 메서드
    public static TrainingSession create(TrainingStatus status, Instant startedAt, User admin, TrainingScenario scenario) {
        return new TrainingSession(status, startedAt, admin, scenario);
    }

    public static TrainingSession schedule(User admin, TrainingScenario scenario) {
        return new TrainingSession(TrainingStatus.SCHEDULED, null, admin, scenario);
    }

    // 관리자가 훈련 시작 버튼을 누른 시각으로 실제 시작 시각을 갱신하며 RUNNING으로 전이한다.
    public void start(Instant startedAt) {
        this.status = TrainingStatus.RUNNING;
        this.startedAt = startedAt;
        this.lastSpreadAt = startedAt;
        this.currentGeneration = 0;
    }

    public void advanceSpread(int nextGeneration, Instant spreadAt) {
        this.currentGeneration = nextGeneration;
        this.lastSpreadAt = spreadAt;
    }

    // 처음 막힌 경우에만 시각을 남기고 true를 돌려준다. 이미 막힌 적이 있으면 아무것도 바꾸지 않는다
    // (화재 확산 틱마다 같은 알림이 반복되지 않게 하기 위함).
    public boolean markRouteBlocked(Instant blockedAt) {
        if (this.routeBlockedAt != null) {
            return false;
        }
        this.routeBlockedAt = blockedAt;
        return true;
    }

    // 훈련 시작 후 경로가 막히기까지 걸린 초. 막힌 적이 없거나 시작 시각이 없으면 null.
    public Long routeBlockedAfterSec() {
        if (routeBlockedAt == null || startedAt == null) {
            return null;
        }
        return Duration.between(startedAt, routeBlockedAt).getSeconds();
    }

    // 훈련 정상 종료
    public void complete(Instant endedAt) {
        this.status = TrainingStatus.COMPLETED;
        this.endedAt = endedAt;
    }

    // 관리자에 의한 강제 종료
    public void stop(Instant endedAt) {
        this.status = TrainingStatus.STOPPED;
        this.endedAt = endedAt;
    }

    public void fail(Instant endedAt) {
        this.status = TrainingStatus.FAILED;
        this.endedAt = endedAt;
    }
}
