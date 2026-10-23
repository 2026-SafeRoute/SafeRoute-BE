package com.saferoute.domain.report.service;

import com.saferoute.domain.report.entity.Grade;
import com.saferoute.domain.report.entity.ZoneDensityPoint;
import java.math.BigDecimal;
import java.util.List;

// TrainingReportNarrativeGenerator가 자동 평가 보고서 및 개선 권고사항을 만드는 데 필요한 계산된 값들을 모은 입력. TrainingReportService.generate()에서 계산 직후 조립
public record ReportNarrativeInput(
        String scenarioName,
        int participantCount,
        int survivorCount,
        BigDecimal survivalRate,
        int evacuationSec,
        int targetEvacuationSec,
        int evacuationScore,
        int bottleneckCount,
        int bottleneckScore,
        double deviationRate,
        int deviationScore,
        double overallScore,
        Grade grade,
        List<ZoneDensityPoint> zoneDensities,
        // 병목 위치 문구(예: "CCTV_122(복도 4 – 복도 5 구간)"). 위치를 특정하지 못하면 null.
        String bottleneckLocation
) {

    public ReportNarrativeInput(
            String scenarioName, int participantCount, int survivorCount, BigDecimal survivalRate,
            int evacuationSec, int targetEvacuationSec, int evacuationScore,
            int bottleneckCount, int bottleneckScore,
            double deviationRate, int deviationScore,
            double overallScore, Grade grade,
            List<ZoneDensityPoint> zoneDensities) {
        this(scenarioName, participantCount, survivorCount, survivalRate,
                evacuationSec, targetEvacuationSec, evacuationScore,
                bottleneckCount, bottleneckScore,
                deviationRate, deviationScore,
                overallScore, grade,
                zoneDensities, null);
    }
}
