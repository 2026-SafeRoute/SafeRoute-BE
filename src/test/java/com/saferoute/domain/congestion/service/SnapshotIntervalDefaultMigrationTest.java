package com.saferoute.domain.congestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.repository.CongestionConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SnapshotIntervalDefaultMigrationTest {

    @Mock
    private CongestionConfigRepository congestionConfigRepository;

    @InjectMocks
    private SnapshotIntervalDefaultMigration migration;

    @Test
    @DisplayName("기존 설정의 관측 주기가 이전 기본값(5초)이면 2초로 바꾸고 version만 올린다")
    void run_withPreviousDefault_updatesIntervalAndVersionOnly() {
        CongestionConfig config = configWithInterval(5);
        long versionBefore = config.getVersion();
        given(congestionConfigRepository.findById(CongestionConfig.SINGLETON_ID)).willReturn(Optional.of(config));

        migration.run(null);

        CongestionConfig defaults = CongestionConfig.createDefault();
        assertThat(config.getSnapshotIntervalSec()).isEqualTo(2);
        assertThat(config.getVersion()).isEqualTo(versionBefore + 1);
        assertThat(config.getTargetInferenceFps()).isEqualTo(defaults.getTargetInferenceFps());
        assertThat(config.getCautionFrom()).isEqualTo(defaults.getCautionFrom());
        assertThat(config.getCrowdedFrom()).isEqualTo(defaults.getCrowdedFrom());
        assertThat(config.getVeryCrowdedFrom()).isEqualTo(defaults.getVeryCrowdedFrom());
        assertThat(config.getRequiredConsecutiveFrames()).isEqualTo(defaults.getRequiredConsecutiveFrames());
        assertThat(config.getRecoveryConsecutiveFrames()).isEqualTo(defaults.getRecoveryConsecutiveFrames());
        assertThat(config.getCooldownSec()).isEqualTo(defaults.getCooldownSec());
        assertThat(config.getStateStaleAfterSec()).isEqualTo(defaults.getStateStaleAfterSec());
    }

    @Test
    @DisplayName("관측 주기가 이전 기본값이 아니면 그대로 둔다")
    void run_withOtherInterval_keepsConfig() {
        CongestionConfig config = configWithInterval(3);
        long versionBefore = config.getVersion();
        given(congestionConfigRepository.findById(CongestionConfig.SINGLETON_ID)).willReturn(Optional.of(config));

        migration.run(null);

        assertThat(config.getSnapshotIntervalSec()).isEqualTo(3);
        assertThat(config.getVersion()).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("설정 행이 아직 없으면 아무것도 하지 않는다")
    void run_withoutConfig_doesNothing() {
        given(congestionConfigRepository.findById(CongestionConfig.SINGLETON_ID)).willReturn(Optional.empty());

        migration.run(null);
    }

    private CongestionConfig configWithInterval(int snapshotIntervalSec) {
        CongestionConfig config = CongestionConfig.createDefault();
        config.updateSettings(snapshotIntervalSec, null, null, null, null, null, null, null, null);
        return config;
    }
}
