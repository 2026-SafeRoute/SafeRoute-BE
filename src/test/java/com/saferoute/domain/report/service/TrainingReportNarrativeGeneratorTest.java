package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saferoute.domain.report.entity.Grade;
import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.RecommendationPriority;
import com.saferoute.domain.report.entity.ZoneDensityPoint;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrainingReportNarrativeGeneratorTest {

    private ReportNarrativeInput allGoodInput() {
        return new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                200, 300, 100,
                0, 100,
                0.0, 100,
                100.0, Grade.A,
                List.of());
    }

    @Test
    @DisplayName("모든 항목이 기준 이상이면 강점 문장만 있고 개선 권고사항은 비어있다")
    void allScoresGood_onlyStrengthsAndNoRecommendations() {
        String summary = TrainingReportNarrativeGenerator.buildSummary(allGoodInput());
        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(allGoodInput());

        assertThat(summary).contains("강점:");
        assertThat(summary).doesNotContain("개선:");
        assertThat(recommendations).isEmpty();
    }

    @Test
    @DisplayName("대피시간 점수가 낮으면 대피 동선 재점검 권고가 생성된다")
    void lowEvacuationScore_generatesEvacuationRecommendation() {
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                600, 300, 0, // evacuationSec=600, target=300 -> score 0
                0, 100,
                0.0, 100,
                70.0, Grade.C,
                List.of());

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getTitle()).isEqualTo("대피 동선 재점검");
        assertThat(recommendations.get(0).getPriority()).isEqualTo(RecommendationPriority.HIGH);

        String summary = TrainingReportNarrativeGenerator.buildSummary(input);
        assertThat(summary).contains("개선:");
        assertThat(summary).contains("목표 대피시간을 5분 0초 초과");
    }

    @Test
    @DisplayName("병목 점수가 낮고 임계치를 넘는 구역이 있으면 그 구역 이름을 콕 집어 권고한다")
    void lowBottleneckScoreWithCrowdedZone_namesTheZone() {
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                200, 300, 100,
                5, 40, // bottleneckScore=40 -> 기준 미달
                0.0, 100,
                80.0, Grade.B,
                List.of(new ZoneDensityPoint("1층 로비", 92.0), new ZoneDensityPoint("A구역", 30.0)));

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getTitle()).isEqualTo("1층 로비 분산 유도");
        assertThat(recommendations.get(0).getDescription()).contains("92.0%");
    }

    @Test
    @DisplayName("밀집 구역이 있어도 병목 위치를 알면 밀집도와 함께 CCTV·구간을 적는다")
    void lowBottleneckScoreWithCrowdedZoneAndLocation_includesBoth() {
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                200, 300, 100,
                5, 40,
                0.0, 100,
                80.0, Grade.B,
                List.of(new ZoneDensityPoint("1층 로비", 92.0)),
                "CCTV_122(복도 4 – 복도 5 구간)");

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations.get(0).getTitle()).isEqualTo("1층 로비 분산 유도");
        assertThat(recommendations.get(0).getDescription())
                .isEqualTo("평균 밀집도 92.0%로 병목 위험 구간, CCTV_122(복도 4 – 복도 5 구간)에서 병목(혼잡) 5회 감지");
        assertThat(TrainingReportNarrativeGenerator.buildSummary(input))
                .contains("1층 로비 평균 밀집도가 92.0%로 병목이 발생했으며, "
                        + "CCTV_122(복도 4 – 복도 5 구간)에서 병목이 5회 감지되었습니다.");
    }

    @Test
    @DisplayName("병목이 여러 CCTV에서 났으면 CCTV별 구간과 횟수를 전부 적는다")
    void lowBottleneckScoreWithMultipleLocations_listsAll() {
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                200, 300, 100,
                4, 40,
                0.0, 100,
                80.0, Grade.B,
                List.of(),
                "CCTV_122(복도 3 – 복도 4 구간, 3회), CCTV_130(1회)");

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations.get(0).getDescription())
                .isEqualTo("CCTV_122(복도 3 – 복도 4 구간, 3회), CCTV_130(1회)에서 병목(혼잡) 4회 감지");
        assertThat(TrainingReportNarrativeGenerator.buildSummary(input))
                .contains("훈련 중 CCTV_122(복도 3 – 복도 4 구간, 3회), CCTV_130(1회)에서 병목이 4회 감지되어");
    }

    @Test
    @DisplayName("병목 점수가 낮은데 임계치를 넘는 구역이 없으면 일반적인 문구로 대체한다")
    void lowBottleneckScoreWithoutCrowdedZone_usesGenericWording() {
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                200, 300, 100,
                3, 40,
                0.0, 100,
                80.0, Grade.B,
                List.of()); // 구역 데이터 없음

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).getTitle()).isEqualTo("혼잡 구간 분산 유도");
        assertThat(recommendations.get(0).getDescription()).contains("3회");
    }

    @Test
    @DisplayName("우선순위 경계값(50/75)에 따라 HIGH/MEDIUM/LOW가 정확히 갈린다")
    void priorityThresholds_mapCorrectly() {
        assertThat(recommendationFor(evacuationScore(49)).getPriority()).isEqualTo(RecommendationPriority.HIGH);
        assertThat(recommendationFor(evacuationScore(50)).getPriority()).isEqualTo(RecommendationPriority.MEDIUM);
        assertThat(recommendationFor(evacuationScore(74)).getPriority()).isEqualTo(RecommendationPriority.MEDIUM);
        assertThat(recommendationFor(evacuationScore(75)).getPriority()).isEqualTo(RecommendationPriority.LOW);
        assertThat(recommendationFor(evacuationScore(84)).getPriority()).isEqualTo(RecommendationPriority.LOW);
    }

    private ReportNarrativeInput evacuationScore(int score) {
        return new ReportNarrativeInput(
                "정기 훈련", 50, 50, BigDecimal.valueOf(100.0),
                300, 300, score,
                0, 100,
                0.0, 100,
                score * 0.35, Grade.C,
                List.of());
    }

    private RecommendationPoint recommendationFor(ReportNarrativeInput input) {
        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);
        assertThat(recommendations).hasSize(1);
        return recommendations.get(0);
    }

    @Test
    @DisplayName("권고사항이 4개 나오는 상황이면 우선순위가 높은 3개만 남고 LOW 하나는 잘린다")
    void multipleFindings_sortedByPriorityAndCappedAtThree() {
        // 대피시간(0점=HIGH), 대피 성공률(40=HIGH), 병목(60점=MEDIUM), 경로준수율(80점=LOW) - 4개 모두 기준 미달
        ReportNarrativeInput input = new ReportNarrativeInput(
                "정기 훈련", 50, 20, BigDecimal.valueOf(40.0),
                600, 300, 0,
                5, 60,
                0.1, 80,
                40.0, Grade.F,
                List.of());

        List<RecommendationPoint> recommendations = TrainingReportNarrativeGenerator.buildRecommendations(input);

        assertThat(recommendations).hasSize(3);
        assertThat(recommendations.get(0).getPriority()).isEqualTo(RecommendationPriority.HIGH);
        assertThat(recommendations.get(0).getTitle()).isEqualTo("대피 동선 재점검");
        assertThat(recommendations.get(1).getPriority()).isEqualTo(RecommendationPriority.HIGH);
        assertThat(recommendations.get(1).getTitle()).isEqualTo("안전 교육 보강 필요");
        assertThat(recommendations.get(2).getPriority()).isEqualTo(RecommendationPriority.MEDIUM);
        // LOW였던 "유도등 안내 점검"(경로준수율)은 3개 상한에 밀려 제외된다.
        assertThat(recommendations).extracting(RecommendationPoint::getTitle).doesNotContain("유도등 안내 점검");
    }
}
