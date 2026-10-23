package com.saferoute.domain.report.service;

import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.RecommendationPriority;
import com.saferoute.domain.report.entity.ZoneDensityPoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// 자동 평가 보고서와 개선 권고사항을 고정 템플릿으로 생성
public final class TrainingReportNarrativeGenerator {

    // 이 점수 이상이면 강점 문장, 미만이면 개선 권고 대상
    private static final double GOOD_SCORE_THRESHOLD = 85.0;
    // 이 밀집도를 넘는 구역이 있으면 그 구역 이름을 집어 권고한다.
    private static final double CROWDED_ZONE_THRESHOLD_PERCENT = 70.0;
    private static final int MAX_RECOMMENDATIONS = 3;

    private TrainingReportNarrativeGenerator() {
    }

    public static String buildSummary(ReportNarrativeInput input) {
        List<Finding> findings = buildFindings(input);

        StringBuilder summary = new StringBuilder();
        summary.append(String.format(
                "본 훈련(%s)은 참가자 %d명 중 %d명이 %s 이내 대피를 완료하여 평가 등급 %s(%.1f점)을 획득하였습니다.",
                input.scenarioName(), input.participantCount(), input.survivorCount(),
                formatDuration(input.evacuationSec()), input.grade(), input.overallScore()
        ));

        String strengths = buildStrengthSentences(input);
        if (!strengths.isBlank()) {
            summary.append("\n\n강점: ").append(strengths);
        }

        if (!findings.isEmpty()) {
            String improvements = findings.stream()
                    .map(Finding::narrativeSentence)
                    .reduce((a, b) -> a + " " + b)
                    .orElse("");
            summary.append("\n\n개선: ").append(improvements);
        }

        return summary.toString();
    }

    public static List<RecommendationPoint> buildRecommendations(ReportNarrativeInput input) {
        return buildFindings(input).stream()
                .sorted(Comparator.comparing(Finding::priority))
                .limit(MAX_RECOMMENDATIONS)
                .map(f -> new RecommendationPoint(f.priority(), f.title(), f.description()))
                .toList();
    }

    // 4개 지표(가중치 순: 대피시간 35%/생존률 30%/병목 20%/경로준수율 15%)를 훑어
    // 기준 미달인 것만 모음
    private static List<Finding> buildFindings(ReportNarrativeInput input) {
        List<Finding> findings = new ArrayList<>();

        if (input.evacuationScore() < GOOD_SCORE_THRESHOLD) {
            int overSec = Math.max(0, input.evacuationSec() - input.targetEvacuationSec());
            findings.add(new Finding(
                    priorityFor(input.evacuationScore()),
                    "대피 동선 재점검",
                    String.format("목표 대피시간 %s 대비 %s 초과 소요",
                            formatDuration(input.targetEvacuationSec()), formatDuration(overSec)),
                    String.format("목표 대피시간을 %s 초과하여 대피 동선 점검이 필요합니다.", formatDuration(overSec)),
                    input.evacuationScore()
            ));
        }

        if (input.survivalRate().doubleValue() < GOOD_SCORE_THRESHOLD) {
            findings.add(new Finding(
                    priorityFor(input.survivalRate().doubleValue()),
                    "안전 교육 보강 필요",
                    String.format("생존 판정 %d/%d명 (%.1f%%)",
                            input.survivorCount(), input.participantCount(), input.survivalRate()),
                    String.format("생존률이 %.1f%%로 안전 교육 보강이 필요합니다.", input.survivalRate()),
                    input.survivalRate().doubleValue()
            ));
        }

        if (input.bottleneckScore() < GOOD_SCORE_THRESHOLD) {
            Optional<ZoneDensityPoint> worstZone = input.zoneDensities().stream()
                    .filter(z -> z.getAvgDensityPercent() >= CROWDED_ZONE_THRESHOLD_PERCENT)
                    .max(Comparator.comparingDouble(ZoneDensityPoint::getAvgDensityPercent));

            String title = worstZone.map(z -> z.getZoneName() + " 분산 유도").orElse("혼잡 구간 분산 유도");
            // 밀집 구역이 있어도 CCTV 병목 위치를 알면 함께 적는다 - 구역 밀집도와 실제 병목 감지 위치는 별개 정보다.
            String location = input.bottleneckLocation();
            String locationDetail = location != null
                    ? String.format("%s에서 병목(혼잡) %d회 감지", location, input.bottleneckCount())
                    : null;
            String description = worstZone
                    .map(z -> {
                        String zoneDetail = String.format("평균 밀집도 %.1f%%로 병목 위험 구간", z.getAvgDensityPercent());
                        return locationDetail != null ? zoneDetail + ", " + locationDetail : zoneDetail;
                    })
                    .orElse(locationDetail != null
                            ? locationDetail
                            : String.format("훈련 중 병목(혼잡) %d회 감지", input.bottleneckCount()));
            String narrative = worstZone
                    .map(z -> location != null
                            ? String.format("%s 평균 밀집도가 %.1f%%로 병목이 발생했으며, %s에서 병목이 %d회 감지되었습니다.",
                                    z.getZoneName(), z.getAvgDensityPercent(), location, input.bottleneckCount())
                            : String.format("%s 평균 밀집도가 %.1f%%로 병목이 발생했습니다.",
                                    z.getZoneName(), z.getAvgDensityPercent()))
                    .orElse(location != null
                            ? String.format("훈련 중 %s에서 병목이 %d회 감지되어 우회 경로 안내가 필요합니다.",
                                    location, input.bottleneckCount())
                            : String.format("훈련 중 병목이 %d회 감지되어 우회 경로 안내가 필요합니다.",
                                    input.bottleneckCount()));

            findings.add(new Finding(priorityFor(input.bottleneckScore()), title, description, narrative,
                    input.bottleneckScore()));
        }

        if (input.deviationScore() < GOOD_SCORE_THRESHOLD) {
            double deviationPercent = input.deviationRate() * 100.0;
            findings.add(new Finding(
                    priorityFor(input.deviationScore()),
                    "유도등 안내 점검",
                    String.format("경로 이탈률 %.1f%% 감지", deviationPercent),
                    String.format("참가자 중 %.1f%%가 권장 경로를 이탈해 유도등 안내 점검이 필요합니다.", deviationPercent),
                    input.deviationScore()
            ));
        }

        return findings;
    }

    private static String buildStrengthSentences(ReportNarrativeInput input) {
        List<String> sentences = new ArrayList<>();

        if (input.evacuationScore() >= GOOD_SCORE_THRESHOLD) {
            sentences.add(String.format("목표 대피시간 이내(%s)에 신속하게 대피를 완료했습니다.",
                    formatDuration(input.evacuationSec())));
        }
        if (input.survivalRate().doubleValue() >= GOOD_SCORE_THRESHOLD) {
            sentences.add(String.format("생존률 %.1f%%로 높은 안전성을 확인했습니다.", input.survivalRate()));
        }
        if (input.bottleneckCount() == 0) {
            sentences.add("구역별 병목 없이 원활한 대피가 진행되었습니다.");
        }
        if (input.deviationScore() >= GOOD_SCORE_THRESHOLD) {
            sentences.add(String.format("참가자 %.1f%%가 권장 경로를 준수했습니다.",
                    100.0 - input.deviationRate() * 100.0));
        }

        return String.join(" ", sentences);
    }

    private static RecommendationPriority priorityFor(double score) {
        if (score < 50.0) {
            return RecommendationPriority.HIGH;
        }
        if (score < 75.0) {
            return RecommendationPriority.MEDIUM;
        }
        return RecommendationPriority.LOW;
    }

    private static String formatDuration(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes == 0) {
            return seconds + "초";
        }
        return minutes + "분 " + seconds + "초";
    }

    private record Finding(RecommendationPriority priority, String title, String description,
                            String narrativeSentence, double score) {
    }
}
