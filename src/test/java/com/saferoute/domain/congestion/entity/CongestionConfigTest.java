package com.saferoute.domain.congestion.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CongestionConfigTest {

    @Test
    @DisplayName("기본 설정은 문서에 정의된 값과 configVersion=1로 생성된다")
    void createDefault_hasDocumentedDefaultsAndVersionOne() {
        CongestionConfig config = CongestionConfig.createDefault();

        assertThat(config.getId()).isEqualTo(CongestionConfig.SINGLETON_ID);
        assertThat(config.getVersion()).isEqualTo(1L);
        assertThat(config.getSnapshotIntervalSec()).isEqualTo(2);
        assertThat(config.getTargetInferenceFps()).isEqualTo(5);
        assertThat(config.getCautionFrom()).isEqualTo(2.0);
        assertThat(config.getCrowdedFrom()).isEqualTo(3.0);
        assertThat(config.getVeryCrowdedFrom()).isEqualTo(5.0);
        assertThat(config.getRequiredConsecutiveFrames()).isEqualTo(3);
        assertThat(config.getRecoveryConsecutiveFrames()).isEqualTo(5);
        assertThat(config.getCooldownSec()).isEqualTo(30);
        assertThat(config.getStateStaleAfterSec()).isEqualTo(15);
    }

    @Test
    @DisplayName("incrementVersion은 다른 필드를 건드리지 않고 버전만 올린다")
    void incrementVersion_onlyBumpsVersion() {
        CongestionConfig config = CongestionConfig.createDefault();

        config.incrementVersion();

        assertThat(config.getVersion()).isEqualTo(2L);
        assertThat(config.getCautionFrom()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("값이 실제로 바뀐 필드가 있으면 configVersion이 증가한다")
    void updateSettings_withChangedValue_incrementsVersion() {
        CongestionConfig config = CongestionConfig.createDefault();

        config.updateSettings(null, null, 2.5, null, null, null, null, null, null);

        assertThat(config.getVersion()).isEqualTo(2L);
        assertThat(config.getCautionFrom()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("기존 값과 동일하거나 null인 필드만 넘기면 configVersion이 증가하지 않는다")
    void updateSettings_withoutRealChange_doesNotIncrementVersion() {
        CongestionConfig config = CongestionConfig.createDefault();

        config.updateSettings(null, null, 2.0, null, null, null, null, null, null);

        assertThat(config.getVersion()).isEqualTo(1L);
        assertThat(config.getCautionFrom()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("기본 임계값(2.0/3.0/5.0) 경계에서 혼잡 단계를 판정한다")
    void classify_usesDefaultThresholdBoundaries() {
        CongestionConfig config = CongestionConfig.createDefault();

        assertThat(config.classify(1.99)).isEqualTo(CongestionLevel.NORMAL);
        assertThat(config.classify(2.0)).isEqualTo(CongestionLevel.CAUTION);
        assertThat(config.classify(2.99)).isEqualTo(CongestionLevel.CAUTION);
        assertThat(config.classify(3.0)).isEqualTo(CongestionLevel.CROWDED);
        assertThat(config.classify(4.99)).isEqualTo(CongestionLevel.CROWDED);
        assertThat(config.classify(5.0)).isEqualTo(CongestionLevel.VERY_CROWDED);
        assertThat(config.classify(100.0)).isEqualTo(CongestionLevel.VERY_CROWDED);
    }
}
