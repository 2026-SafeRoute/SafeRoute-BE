package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saferoute.domain.report.entity.Grade;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrainingReportScoreCalculatorTest {

    // === evacuationScore ===

    @Test
    @DisplayName("목표 시간 이내면 100점이다")
    void evacuationScore_withinTarget_returns100() {
        assertThat(TrainingReportScoreCalculator.evacuationScore(200, 300)).isEqualTo(100);
        assertThat(TrainingReportScoreCalculator.evacuationScore(300, 300)).isEqualTo(100);
    }

    @Test
    @DisplayName("목표 시간의 2배 이상이면 0점이다")
    void evacuationScore_doubleTargetOrMore_returns0() {
        assertThat(TrainingReportScoreCalculator.evacuationScore(600, 300)).isEqualTo(0);
        assertThat(TrainingReportScoreCalculator.evacuationScore(900, 300)).isEqualTo(0);
    }

    @Test
    @DisplayName("목표 시간과 2배 사이는 선형으로 감점된다")
    void evacuationScore_betweenTargetAndDouble_linearlyDecreases() {
        // 300초 목표에 450초(50% 초과) 걸렸으면 50점
        assertThat(TrainingReportScoreCalculator.evacuationScore(450, 300)).isEqualTo(50);
    }

    // === survivalRate ===

    @Test
    @DisplayName("대피 성공 인원을 전체 참여 인원으로 나눈 백분율이다")
    void survivalRate_computesPercentage() {
        assertThat(TrainingReportScoreCalculator.survivalRate(48, 50))
                .isEqualByComparingTo(BigDecimal.valueOf(96.0));
    }

    @Test
    @DisplayName("전체 참여 인원이 0이면 0을 반환한다")
    void survivalRate_zeroParticipants_returnsZero() {
        assertThat(TrainingReportScoreCalculator.survivalRate(0, 0))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === bottleneckScore ===

    @Test
    @DisplayName("병목이 없으면 100점이다")
    void bottleneckScore_noBottleneck_returns100() {
        assertThat(TrainingReportScoreCalculator.bottleneckScore(0)).isEqualTo(100);
    }

    @Test
    @DisplayName("병목 발생 횟수 1회당 10점씩 감점된다")
    void bottleneckScore_penalizesPerEvent() {
        assertThat(TrainingReportScoreCalculator.bottleneckScore(1)).isEqualTo(90);
    }

    @Test
    @DisplayName("점수는 0점 아래로 내려가지 않는다")
    void bottleneckScore_flooredAtZero() {
        assertThat(TrainingReportScoreCalculator.bottleneckScore(10)).isEqualTo(0);
    }

    // === deviationScore ===

    @Test
    @DisplayName("이탈률의 반대(준수율)를 점수로 쓴다")
    void deviationScore_isInverseOfDeviationRate() {
        assertThat(TrainingReportScoreCalculator.deviationScore(0.0)).isEqualTo(100);
        assertThat(TrainingReportScoreCalculator.deviationScore(0.5)).isEqualTo(50);
        assertThat(TrainingReportScoreCalculator.deviationScore(1.0)).isEqualTo(0);
    }

    // === overallScore & gradeOf ===

    @Test
    @DisplayName("4개 항목을 법적 근거표 가중치(35/30/20/15)로 가중합한다")
    void overallScore_appliesLegalWeights() {
        // 100*0.35 + 80*0.30 + 100*0.20 + 100*0.15 = 35+24+20+15 = 94.0
        double score = TrainingReportScoreCalculator.overallScore(100, BigDecimal.valueOf(80), 100, 100);
        assertThat(score).isEqualTo(94.0);
    }

    @Test
    @DisplayName("종합 점수를 등급 구간(A~F)으로 변환한다")
    void gradeOf_mapsScoreToLetterGrade() {
        assertThat(TrainingReportScoreCalculator.gradeOf(95)).isEqualTo(Grade.A);
        assertThat(TrainingReportScoreCalculator.gradeOf(85)).isEqualTo(Grade.B);
        assertThat(TrainingReportScoreCalculator.gradeOf(75)).isEqualTo(Grade.C);
        assertThat(TrainingReportScoreCalculator.gradeOf(65)).isEqualTo(Grade.D);
        assertThat(TrainingReportScoreCalculator.gradeOf(59)).isEqualTo(Grade.F);
    }
}
