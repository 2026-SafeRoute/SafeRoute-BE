package com.saferoute.domain.congestion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// Pi가 사용하는 혼잡 임계값·탐지 설정. CCTV마다 다르게 둘 이유가 없어 시스템 전체가 공유하는
// 단일 행(싱글턴)으로 관리한다 (id는 고정값). 감시 면적은 CCTV별로 이미 계산되므로 여기 포함하지 않는다.
@Entity
@Getter
@Table(name = "congestion_configs")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CongestionConfig {

    // 싱글턴 행을 findById로 바로 찾기 위한 고정 id. 새로 발급하지 않는다.
    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    // Pi/BE가 같은 설정을 보고 있는지 판단하는 버전. 아래 필드 중 하나라도 바뀌거나
    // CCTV 감시 영역(GridCell)이 바뀌면 증가한다 (감시 영역 변경 트리거는 CctvService/CctvRegistrationService에서 호출).
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "snapshot_interval_sec", nullable = false)
    private Integer snapshotIntervalSec;

    @Column(name = "target_inference_fps", nullable = false)
    private Integer targetInferenceFps;

    @Column(name = "caution_from", nullable = false)
    private Double cautionFrom;

    @Column(name = "crowded_from", nullable = false)
    private Double crowdedFrom;

    @Column(name = "very_crowded_from", nullable = false)
    private Double veryCrowdedFrom;

    @Column(name = "required_consecutive_frames", nullable = false)
    private Integer requiredConsecutiveFrames;

    @Column(name = "recovery_consecutive_frames", nullable = false)
    private Integer recoveryConsecutiveFrames;

    @Column(name = "cooldown_sec", nullable = false)
    private Integer cooldownSec;

    @Column(name = "state_stale_after_sec", nullable = false)
    private Integer stateStaleAfterSec;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private CongestionConfig(Integer snapshotIntervalSec, Integer targetInferenceFps,
            Double cautionFrom, Double crowdedFrom, Double veryCrowdedFrom,
            Integer requiredConsecutiveFrames, Integer recoveryConsecutiveFrames,
            Integer cooldownSec, Integer stateStaleAfterSec) {
        this.id = SINGLETON_ID;
        this.version = 1L;
        this.snapshotIntervalSec = snapshotIntervalSec;
        this.targetInferenceFps = targetInferenceFps;
        this.cautionFrom = cautionFrom;
        this.crowdedFrom = crowdedFrom;
        this.veryCrowdedFrom = veryCrowdedFrom;
        this.requiredConsecutiveFrames = requiredConsecutiveFrames;
        this.recoveryConsecutiveFrames = recoveryConsecutiveFrames;
        this.cooldownSec = cooldownSec;
        this.stateStaleAfterSec = stateStaleAfterSec;
    }

    // 문서(2.2, 2.3절)에 명시된 기본값으로 최초 1회 생성되는 설정.
    public static CongestionConfig createDefault() {
        return new CongestionConfig(1, 5, 2.0, 3.0, 5.0, 3, 5, 30, 15);
    }

    // CCTV 감시 영역(GridCell) 변경처럼 이 엔티티의 필드는 그대로지만
    // Pi가 다시 조회해야 하는 변경이 생겼을 때 외부에서 호출한다.
    public void incrementVersion() {
        this.version++;
    }

    // Pi가 보고한 값을 신뢰하지 않고 BE가 직접 밀도로 혼잡 단계를 판정한다.
    // Pi 쪽 CongestionThresholds.classify()와 동일한 경계값 판정(>=)을 사용해야 한다.
    public CongestionLevel classify(double density) {
        if (density >= veryCrowdedFrom) {
            return CongestionLevel.VERY_CROWDED;
        }
        if (density >= crowdedFrom) {
            return CongestionLevel.CROWDED;
        }
        if (density >= cautionFrom) {
            return CongestionLevel.CAUTION;
        }
        return CongestionLevel.NORMAL;
    }

    // 값이 실제로 바뀐 필드가 하나라도 있을 때만 version을 올린다 (no-op 갱신 요청으로 버전이 튀는 것 방지).
    public void updateSettings(Integer snapshotIntervalSec, Integer targetInferenceFps,
            Double cautionFrom, Double crowdedFrom, Double veryCrowdedFrom,
            Integer requiredConsecutiveFrames, Integer recoveryConsecutiveFrames,
            Integer cooldownSec, Integer stateStaleAfterSec) {
        boolean changed = false;
        changed |= applyIfChanged(snapshotIntervalSec, this.snapshotIntervalSec, v -> this.snapshotIntervalSec = v);
        changed |= applyIfChanged(targetInferenceFps, this.targetInferenceFps, v -> this.targetInferenceFps = v);
        changed |= applyIfChanged(cautionFrom, this.cautionFrom, v -> this.cautionFrom = v);
        changed |= applyIfChanged(crowdedFrom, this.crowdedFrom, v -> this.crowdedFrom = v);
        changed |= applyIfChanged(veryCrowdedFrom, this.veryCrowdedFrom, v -> this.veryCrowdedFrom = v);
        changed |= applyIfChanged(requiredConsecutiveFrames, this.requiredConsecutiveFrames,
                v -> this.requiredConsecutiveFrames = v);
        changed |= applyIfChanged(recoveryConsecutiveFrames, this.recoveryConsecutiveFrames,
                v -> this.recoveryConsecutiveFrames = v);
        changed |= applyIfChanged(cooldownSec, this.cooldownSec, v -> this.cooldownSec = v);
        changed |= applyIfChanged(stateStaleAfterSec, this.stateStaleAfterSec, v -> this.stateStaleAfterSec = v);

        if (changed) {
            this.version++;
        }
    }

    private <T> boolean applyIfChanged(T newValue, T currentValue, java.util.function.Consumer<T> setter) {
        if (newValue == null || newValue.equals(currentValue)) {
            return false;
        }
        setter.accept(newValue);
        return true;
    }
}
