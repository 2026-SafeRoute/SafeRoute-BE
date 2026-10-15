package com.saferoute.domain.evacuation.recalculation.entity;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.user.entity.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 혼잡 감지로 트리거된 우회 경로 재탐색 결과. 관리자가 승인해야 실제 대피 경로에 반영된다.
//
// 혼잡 레벨이 오르내릴 때마다 PENDING -> CANCELLED -> 새 PENDING 이력이 쌓일 수 있다.
// 세션 잠금과 RouteRecalculationService의 트리거 로직으로 세션당 유효한 PENDING 하나만 유지한다.
@Entity
@Getter
@Table(name = "route_recalculations")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecalculation {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "training_session_id", nullable = false)
    private TrainingSession trainingSession;

    // 혼잡이 감지되어(또는 화재가 번져) 이번 재탐색을 트리거한 엣지. 도면 재업로드·초기화로 그래프가
    // 교체되면 엣지는 지워지고 이 참조만 null이 된다 - 훈련 이력은 남겨야 하므로 기록 자체는 지우지 않는다.
    // 층 정보는 엣지가 사라져도 남도록 floorId/floorNum에 따로 저장한다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_edge_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private MapEdge triggerEdge;

    // 트리거 엣지가 속한 층. 엣지가 지워져도(위 참고) 이력 조회·승인 검증에 층 정보가 필요해 생성 시점에
    // 복사해 둔다. 층 자체가 삭제돼도 이력이 남도록 FK 없이 값만 저장한다.
    // 이 컬럼이 생기기 전에 만들어진 행은 null일 수 있으므로 읽을 때는 resolvedFloorId()/resolvedFloorNum()을 쓴다.
    @Column(name = "floor_id")
    private UUID floorId;

    @Column(name = "floor_num")
    private Integer floorNum;

    // 트리거를 발생시킨 CCTV. FIRE_SPREAD 트리거는 CCTV와 무관하므로 null.
    @Column(name = "cctv_code", length = 50)
    private String cctvCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private RecalculationTriggerType triggerType;

    // FIRE_SPREAD 트리거는 혼잡 레벨이 아니므로 null.
    @Enumerated(EnumType.STRING)
    @Column(name = "congestion_level", length = 20)
    private CongestionLevel congestionLevel;

    // 트리거 시점에 BE가 계산한 density. FIRE_SPREAD 트리거는 혼잡 지표가 없으므로 0.
    @Column(name = "density", nullable = false)
    private double density;

    // 트리거 시점의 활성 경로(가장 최근 APPROVED 경로, 없으면 트리거 엣지를 그대로 포함한 정상 경로)
    @ElementCollection
    @CollectionTable(
            name = "route_recalculation_previous_nodes",
            joinColumns = @JoinColumn(name = "route_recalculation_id")
    )
    @OrderColumn(name = "node_order")
    @Column(name = "node_id", nullable = false)
    private List<UUID> previousNodeIds;

    @Column(name = "previous_total_weight", nullable = false)
    private double previousTotalWeight;

    // 새로 계산된 후보 경로를 이루는 노드 id 순서 (출발 -> 도착)
    @ElementCollection
    @CollectionTable(
            name = "route_recalculation_nodes",
            joinColumns = @JoinColumn(name = "route_recalculation_id")
    )
    @OrderColumn(name = "node_order")
    @Column(name = "node_id", nullable = false)
    private List<UUID> recalculatedNodeIds;

    @Column(name = "total_weight", nullable = false)
    private double totalWeight;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecalculationStatus status;

    @CreatedDate
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // 승인/거절한 관리자. 시스템이 자동으로 CANCELLED 처리한 경우엔 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_id")
    private User resolvedBy;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    private RouteRecalculation(TrainingSession trainingSession, MapEdge triggerEdge, String cctvCode,
            RecalculationTriggerType triggerType, CongestionLevel congestionLevel, double density,
            List<UUID> previousNodeIds, double previousTotalWeight,
            List<UUID> recalculatedNodeIds, double totalWeight) {
        this.trainingSession = trainingSession;
        this.triggerEdge = triggerEdge;
        Floor floor = triggerEdge.getFloor();
        this.floorId = floor != null ? floor.getId() : null;
        this.floorNum = floor != null ? floor.getFloorNum() : null;
        this.cctvCode = cctvCode;
        this.triggerType = triggerType;
        this.congestionLevel = congestionLevel;
        this.density = density;
        this.previousNodeIds = previousNodeIds;
        this.previousTotalWeight = previousTotalWeight;
        this.recalculatedNodeIds = recalculatedNodeIds;
        this.totalWeight = totalWeight;
        this.status = RecalculationStatus.PENDING;
    }

    // 재탐색 결과를 승인 대기 상태로 저장하는 정적 팩토리 메서드
    public static RouteRecalculation createPending(TrainingSession trainingSession, MapEdge triggerEdge,
            String cctvCode, RecalculationTriggerType triggerType, CongestionLevel congestionLevel, double density,
            List<UUID> previousNodeIds, double previousTotalWeight,
            List<UUID> recalculatedNodeIds, double totalWeight) {
        return new RouteRecalculation(trainingSession, triggerEdge, cctvCode, triggerType, congestionLevel, density,
                previousNodeIds, previousTotalWeight, recalculatedNodeIds, totalWeight);
    }

    // 화재 확산으로 트리거된 승인 대기 항목. CCTV/혼잡 레벨과 무관하므로 각각 null, 0으로 저장한다.
    public static RouteRecalculation createPendingForFireSpread(TrainingSession trainingSession, MapEdge triggerEdge,
            List<UUID> previousNodeIds, double previousTotalWeight,
            List<UUID> recalculatedNodeIds, double totalWeight) {
        return new RouteRecalculation(trainingSession, triggerEdge, null, RecalculationTriggerType.FIRE_SPREAD, null,
                0.0, previousNodeIds, previousTotalWeight, recalculatedNodeIds, totalWeight);
    }

    // floor_id가 추가되기 전 행은 아직 남아 있는 트리거 엣지에서 층을 구한다. 둘 다 없으면(옛 행인데 엣지까지
    // 지워진 경우) null이다.
    public UUID resolvedFloorId() {
        if (floorId != null) {
            return floorId;
        }
        return triggerEdge != null ? triggerEdge.getFloor().getId() : null;
    }

    public Integer resolvedFloorNum() {
        if (floorNum != null) {
            return floorNum;
        }
        return triggerEdge != null ? triggerEdge.getFloor().getFloorNum() : null;
    }

    public UUID triggerEdgeIdOrNull() {
        return triggerEdge != null ? triggerEdge.getId() : null;
    }

    // 상태 전이 검증은 이 엔티티가 아니라 RouteRecalculationService가 담당한다
    // (TrainingSessionService.start()/end()/forceEnd(), IoTLightService와 동일한 컨벤션).
    public void approve(Instant resolvedAt, User resolvedBy) {
        this.status = RecalculationStatus.APPROVED;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
    }

    public void reject(Instant resolvedAt, User resolvedBy, String reason) {
        this.status = RecalculationStatus.REJECTED;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = resolvedBy;
        this.rejectReason = reason;
    }

    public void cancel(Instant resolvedAt, String reason) {
        this.status = RecalculationStatus.CANCELLED;
        this.resolvedAt = resolvedAt;
        this.cancelReason = reason;
    }
}
