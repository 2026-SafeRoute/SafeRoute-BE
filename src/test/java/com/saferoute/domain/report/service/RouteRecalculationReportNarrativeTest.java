package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.RecommendationPriority;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteRecalculationReportNarrativeTest {

    private static final String SUMMARY = "본 훈련은 평가 등급 A를 획득하였습니다.";

    private RouteRecalculation recalculation(RecalculationTriggerType triggerType, RecalculationStatus status) {
        RouteRecalculation recalculation = mock(RouteRecalculation.class);
        lenient().when(recalculation.getTriggerType()).thenReturn(triggerType);
        lenient().when(recalculation.getStatus()).thenReturn(status);
        return recalculation;
    }

    private RecommendationPoint point(String title) {
        return new RecommendationPoint(RecommendationPriority.LOW, title, title + " 설명");
    }

    @Test
    @DisplayName("재탐색도 없고 경로도 막히지 않았으면 요약과 권고를 그대로 둔다")
    void nothingHappened_keepsSummaryAndRecommendations() {
        List<RecommendationPoint> recommendations = List.of(point("기존"));

        assertThat(RouteRecalculationReportNarrative.appendToSummary(SUMMARY, List.of(), null)).isEqualTo(SUMMARY);
        assertThat(RouteRecalculationReportNarrative.prependRecommendation(recommendations, List.of(), null))
                .isSameAs(recommendations);
    }

    @Test
    @DisplayName("재탐색 요청 수와 승인 수, 경로 막힘 시점을 요약 끝에 경로 문단으로 붙인다")
    void appendsRecalculationAndBlockedSentences() {
        List<RouteRecalculation> recalculations = List.of(
                recalculation(RecalculationTriggerType.FIRE_SPREAD, RecalculationStatus.APPROVED),
                recalculation(RecalculationTriggerType.STARTED, RecalculationStatus.CANCELLED));

        String summary = RouteRecalculationReportNarrative.appendToSummary(SUMMARY, recalculations, 125);

        assertThat(summary).isEqualTo(SUMMARY + "\n\n경로: 훈련 중 경로 재탐색 요청 2건 중 1건이 승인되었습니다. "
                + "화재 확산으로 훈련 시작 2분 5초 후 모든 대피 경로가 막혔습니다.");
    }

    @Test
    @DisplayName("재탐색 없이 경로가 막혔으면 권고가 없어도 경로 권고를 맨 앞에 넣는다")
    void blockedWithoutRecalculation_prependsRouteRecommendation() {
        List<RecommendationPoint> result =
                RouteRecalculationReportNarrative.prependRecommendation(List.of(), List.of(), 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPriority()).isEqualTo(RecommendationPriority.HIGH);
        assertThat(result.get(0).getTitle()).isEqualTo(RouteRecalculationReportNarrative.NO_ALTERNATIVE_ROUTE_TITLE);
        assertThat(result.get(0).getDescription())
                .isEqualTo(RouteRecalculationReportNarrative.NO_ALTERNATIVE_ROUTE_DESCRIPTION);
    }

    @Test
    @DisplayName("기존 권고가 3개면 경로 권고를 맨 앞에 넣고 3개로 자른다")
    void blockedWithoutRecalculation_keepsMaxThree() {
        List<RecommendationPoint> existing = List.of(point("1"), point("2"), point("3"));

        List<RecommendationPoint> result =
                RouteRecalculationReportNarrative.prependRecommendation(existing, List.of(), 20);

        assertThat(result).extracting(RecommendationPoint::getTitle)
                .containsExactly(RouteRecalculationReportNarrative.NO_ALTERNATIVE_ROUTE_TITLE, "1", "2");
    }

    @Test
    @DisplayName("화재 재탐색 제안이 한 번이라도 있었으면 경로가 막혀도 경로 권고를 넣지 않는다")
    void blockedAfterFireRecalculation_noRouteRecommendation() {
        List<RouteRecalculation> recalculations =
                List.of(recalculation(RecalculationTriggerType.FIRE_SPREAD, RecalculationStatus.REJECTED));

        assertThat(RouteRecalculationReportNarrative.prependRecommendation(List.of(), recalculations, 20)).isEmpty();
    }

    @Test
    @DisplayName("혼잡 재탐색만 있었고 화재로 막혔으면 경로 권고를 넣는다")
    void blockedWithOnlyCongestionRecalculation_prependsRouteRecommendation() {
        List<RouteRecalculation> recalculations =
                List.of(recalculation(RecalculationTriggerType.LEVEL_UP, RecalculationStatus.APPROVED));

        assertThat(RouteRecalculationReportNarrative.prependRecommendation(List.of(), recalculations, 20))
                .extracting(RecommendationPoint::getTitle)
                .containsExactly(RouteRecalculationReportNarrative.NO_ALTERNATIVE_ROUTE_TITLE);
    }
}
