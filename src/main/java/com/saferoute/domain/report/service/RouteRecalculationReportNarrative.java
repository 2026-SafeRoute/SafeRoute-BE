package com.saferoute.domain.report.service;

import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.RecommendationPriority;
import java.util.ArrayList;
import java.util.List;

// 경로 재탐색 결과를 리포트 요약·권고사항 문장으로 덧붙인다 (#289).
// 점수 기반 문장(TrainingReportNarrativeGenerator)과 달리 점수와 무관하게 실제로 일어난 일만 적는다.
// 리포트 엔티티/응답 구조는 바꾸지 않고 summaryText와 recommendations에만 반영하므로, 프론트 변경 없이
// 기존 "자동 평가 보고서"·"개선 권고사항" 카드와 PDF에 그대로 나온다.
final class RouteRecalculationReportNarrative {

    static final String NO_ALTERNATIVE_ROUTE_TITLE = "대체 경로 확보";
    static final String NO_ALTERNATIVE_ROUTE_DESCRIPTION =
            "대체 경로가 없어 재탐색 없이 경로가 막혔습니다. 도면에서 다른 탈출구로 가는 길을 이어 주세요.";
    // TrainingReportNarrativeGenerator.MAX_RECOMMENDATIONS와 같은 값 - 카드에 보이는 권고 수를 늘리지 않는다.
    private static final int MAX_RECOMMENDATIONS = 3;

    private RouteRecalculationReportNarrative() {
    }

    // 재탐색 요청이 있었거나 경로가 막혔을 때만 "경로:" 문단을 붙인다. 둘 다 없으면 요약을 그대로 돌려준다.
    static String appendToSummary(String summary, List<RouteRecalculation> recalculations,
            Integer routeBlockedAfterSec) {
        List<String> sentences = new ArrayList<>();
        if (!recalculations.isEmpty()) {
            long approved = recalculations.stream()
                    .filter(recalculation -> recalculation.getStatus() == RecalculationStatus.APPROVED)
                    .count();
            sentences.add(String.format("훈련 중 경로 재탐색 요청 %d건 중 %d건이 승인되었습니다.",
                    recalculations.size(), approved));
        }
        if (routeBlockedAfterSec != null) {
            sentences.add(String.format("화재 확산으로 훈련 시작 %s 후 모든 대피 경로가 막혔습니다.",
                    formatDuration(routeBlockedAfterSec)));
        }
        if (sentences.isEmpty()) {
            return summary;
        }
        return summary + "\n\n경로: " + String.join(" ", sentences);
    }

    // 화재로 경로가 막혔는데 화재 재탐색 제안이 한 번도 없었다면 = 불이 경로에 닿았을 때 돌아갈 길이 없었다.
    // 점수가 높아도 반드시 보여야 하므로 점수 기준과 무관하게 맨 앞에 넣는다(최대 개수는 유지).
    // 혼잡 재탐색은 화재 우회 가능 여부와 상관없으므로 판단에서 뺀다.
    static List<RecommendationPoint> prependRecommendation(List<RecommendationPoint> recommendations,
            List<RouteRecalculation> recalculations, Integer routeBlockedAfterSec) {
        boolean hadFireRecalculation = recalculations.stream()
                .anyMatch(recalculation -> recalculation.getTriggerType() == RecalculationTriggerType.FIRE_SPREAD);
        if (routeBlockedAfterSec == null || hadFireRecalculation) {
            return recommendations;
        }
        List<RecommendationPoint> result = new ArrayList<>();
        result.add(new RecommendationPoint(
                RecommendationPriority.HIGH, NO_ALTERNATIVE_ROUTE_TITLE, NO_ALTERNATIVE_ROUTE_DESCRIPTION));
        result.addAll(recommendations);
        return result.size() > MAX_RECOMMENDATIONS ? List.copyOf(result.subList(0, MAX_RECOMMENDATIONS)) : result;
    }

    // TrainingReportNarrativeGenerator.formatDuration과 같은 표기.
    private static String formatDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "초";
        }
        return minutes + "분 " + seconds + "초";
    }
}
