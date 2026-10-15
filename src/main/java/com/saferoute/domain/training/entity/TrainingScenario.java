package com.saferoute.domain.training.entity;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.user.entity.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
@Table(name = "training_scenarios")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingScenario {

    @Id
    @GeneratedValue
    private UUID id;

    // DRAFT는 아직 이름을 정하지 않았을 수 있어 null을 허용한다. READY 전환 시 필수값으로 검증한다.
    @Size(min = 2, max = 20)
    @Column(length = 20)
    private String name;

    // DRAFT는 참가 인원 미정 상태를 허용한다. READY 전환 시 필수값으로 검증한다.
    @Column(name = "exp_participants")
    private Integer expectedParticipants;

    // 훈련 리포트의 "총 대피 시간" 항목 점수를 매길 때 기준이 되는 목표 대피 시간(초).
    // 모든 시나리오에 동일하게 10분(600초)을 적용하며, 관리자가 값을 지정하거나 바꿀 수 없다.
    public static final int DEFAULT_TARGET_EVACUATION_SEC = 600;

    @Column(name = "target_evacuation_sec", nullable = false)
    private Integer targetEvacuationSec = DEFAULT_TARGET_EVACUATION_SEC;

    // DRAFT는 훈련 일시 미정 상태를 허용한다. READY 전환 시 필수값으로 검증한다.
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "is_template", nullable = false)
    private Boolean isTemplate = false;

    // 연결된 세션의 생명주기에 따라 갱신되는 진행 상태 (상태 전이 가드는 TrainingSessionService가 담당).
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScenarioStatus status;

    // 화재 확산 속도. 발화점(FireZone)마다가 아니라 시나리오 전체에 하나로 적용된다.
    // 확산 시뮬레이션의 tick 간격을 결정한다. (FAST < MEDIUM < SLOW 순으로 주기가 짧음)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "fire_spread_speed", nullable = false, length = 10)
    private FireSpreadSpeed fireSpreadSpeed;

    // DRAFT는 건물 미지정 상태를 허용한다. READY 전환 시 필수값으로 검증한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id")
    private Building building;

    // 발화 위치를 등록하기 전에는 비어 있을 수 있다. FireZoneService가 발화 층의 START 노드를 연결한다.
    // 도면 재업로드·초기화로 노드가 지워지면 null이 되며(SET NULL), 대피 설정을 다시 등록해야 훈련을 시작할 수 있다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_node_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private MapNode startNode;

    // ERD 기준 admin_id (팀원 원본 코드의 user_id에서 변경)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private User admin;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // READY 상태로 직접 시작하는 시나리오 생성 (테스트 픽스처 및 하위 호환용).
    // 실제 API 플로우는 createDraft()로 시작해 ready()로 전이한다.
    public static TrainingScenario create(String name,
                                          Integer expectedParticipants,
                                          Instant scheduledAt,
                                          Boolean isTemplate,
                                          FireSpreadSpeed fireSpreadSpeed,
                                          Building building,
                                          User admin,
                                          MapNode startNode) {
        TrainingScenario scenario = new TrainingScenario();
        scenario.name = name;
        scenario.expectedParticipants = expectedParticipants;
        scenario.scheduledAt = scheduledAt;
        scenario.isTemplate = isTemplate != null ? isTemplate : false;
        scenario.fireSpreadSpeed = fireSpreadSpeed != null ? fireSpreadSpeed : FireSpreadSpeed.MEDIUM;
        scenario.building = building;
        scenario.admin = admin;
        scenario.startNode = startNode;
        scenario.status = ScenarioStatus.READY;
        return scenario;
    }

    // 시나리오 작성 화면 진입 시 미완성 상태로 임시 저장한다. name/building/expectedParticipants/
    // scheduledAt은 아직 비어 있을 수 있으며, READY 전환(ready()) 시 필수값으로 검증한다.
    public static TrainingScenario createDraft(String name,
                                                Integer expectedParticipants,
                                                Instant scheduledAt,
                                                Boolean isTemplate,
                                                FireSpreadSpeed fireSpreadSpeed,
                                                Building building,
                                                User admin) {
        TrainingScenario scenario = new TrainingScenario();
        scenario.name = name;
        scenario.expectedParticipants = expectedParticipants;
        scenario.scheduledAt = scheduledAt;
        scenario.isTemplate = isTemplate != null ? isTemplate : false;
        scenario.fireSpreadSpeed = fireSpreadSpeed != null ? fireSpreadSpeed : FireSpreadSpeed.MEDIUM;
        scenario.building = building;
        scenario.admin = admin;
        scenario.status = ScenarioStatus.DRAFT;
        return scenario;
    }

    public void update(String name,
                       Integer expectedParticipants,
                       Instant scheduledAt,
                       Boolean isTemplate,
                       FireSpreadSpeed fireSpreadSpeed,
                       Building building) {
        if (name != null) this.name = name;
        if (expectedParticipants != null) this.expectedParticipants = expectedParticipants;
        if (scheduledAt != null) this.scheduledAt = scheduledAt;
        if (isTemplate != null) this.isTemplate = isTemplate;
        if (fireSpreadSpeed != null) this.fireSpreadSpeed = fireSpreadSpeed;
        if (building != null) this.building = building;
    }

    // DRAFT → READY 전이. 필수값 검증은 TrainingScenarioService가 담당한다.
    public void markReady() {
        this.status = ScenarioStatus.READY;
    }

    // 상태 전이 가드는 이 엔티티가 아니라 TrainingSessionService가 담당한다
    // (RouteRecalculation, IoTLight와 동일한 컨벤션 - 엔티티는 단순 세터).
    public void markInProgress() {
        this.status = ScenarioStatus.IN_PROGRESS;
    }

    public void markCompleted() {
        this.status = ScenarioStatus.COMPLETED;
    }

    public void markError() {
        this.status = ScenarioStatus.ERROR;
    }

    // 10분 하드 타임아웃으로 훈련이 자동 종료된 경우. 리포트는 정상 생성되므로 ERROR와 구분한다.
    public void markTimeoutFailed() {
        this.status = ScenarioStatus.TIMEOUT_FAILED;
    }

    // 응답용 getter
    public UUID getBuildingId() {
        return building != null ? building.getId() : null;
    }

    public UUID getAdminId() {
        return admin != null ? admin.getId() : null;
    }

    public UUID getStartNodeId() {
        return startNode != null ? startNode.getId() : null;
    }

    public void assignStartNode(MapNode startNode) {
        this.startNode = startNode;
    }
}
