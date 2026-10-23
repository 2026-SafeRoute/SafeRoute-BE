package com.saferoute.domain.report.service;

import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.domain.evacuation.deviation.service.SessionDeviationResult;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.report.dto.GenerateReportRequest;
import com.saferoute.domain.report.entity.Grade;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import com.saferoute.domain.training.dto.DashboardStatsResponse;
import com.saferoute.domain.report.dto.ReportResponse;
import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.TrainingReport;
import com.saferoute.domain.report.entity.TrainingReportCharts;
import com.saferoute.domain.report.entity.ZoneDensityPoint;
import com.saferoute.domain.report.dto.RecentTrainingReportResponse;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.report.repository.TrainingReportRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.ReportErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingReportService {

  private final TrainingReportRepository trainingReportRepository;
  private final TrainingSessionRepository trainingSessionRepository;
  private final CongestionEventRepository congestionEventRepository;
  private final RouteDeviationService routeDeviationService;
  private final TrainingReportChartService trainingReportChartService;
  private final TrainingReportPdfGenerator trainingReportPdfGenerator;
  private final SchoolContextService schoolContextService;
  private final RouteRecalculationRepository routeRecalculationRepository;
  private final BottleneckLocationResolver bottleneckLocationResolver;

  // 훈련 종료 후 관리자가 참여/생존 인원만 입력하면, 나머지 3개 항목(대피시간/병목/경로준수율)은
  // 이미 갖고 있는 데이터로 직접 계산해 리포트를 생성한다.
  @Transactional
  public ReportResponse generate(UUID sessionId, GenerateReportRequest request, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    TrainingSession session = trainingSessionRepository
        .findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
        .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

    if (session.getEndedAt() == null) {
      throw new ApiException(ReportErrorCode.SESSION_NOT_ENDED);
    }
    if (trainingReportRepository.existsByTrainingSession_Id(sessionId)) {
      throw new ApiException(ReportErrorCode.REPORT_ALREADY_EXISTS);
    }
    if (request.survivorCount() > request.participantCount()) {
      throw new ApiException(ReportErrorCode.SURVIVOR_COUNT_EXCEEDS_PARTICIPANTS);
    }

    int evacuationSec = (int) Duration.between(session.getStartedAt(), session.getEndedAt()).getSeconds();
    Integer configuredTargetEvacuationSec = session.getScenario().getTargetEvacuationSec();
    if (configuredTargetEvacuationSec == null) {
      throw new ApiException(ReportErrorCode.TARGET_EVACUATION_SEC_NOT_CONFIGURED);
    }
    int targetEvacuationSec = configuredTargetEvacuationSec;
    int evacuationScore = TrainingReportScoreCalculator.evacuationScore(evacuationSec, targetEvacuationSec);

    BigDecimal survivalRate = TrainingReportScoreCalculator.survivalRate(
        request.survivorCount(), request.participantCount());

    // 병목은 CONGESTION_STARTED이면서 congestionLevel이 CROWDED/VERY_CROWDED로 판정된 경우만
    // "병목 구간이 시작된 횟수"로 센다 - LEVEL_UP/ENDED는 같은 구간의 상태 변화라 제외한다.
    int bottleneckCount = congestionEventRepository.countBottlenecksBySessionId(sessionId.toString());
    int bottleneckScore = TrainingReportScoreCalculator.bottleneckScore(bottleneckCount);

    SessionDeviationResult deviation = routeDeviationService.calculateForSession(sessionId, email);
    int deviationScore = TrainingReportScoreCalculator.deviationScore(deviation.deviationRate());

    double overallScore = TrainingReportScoreCalculator.overallScore(
        evacuationScore, survivalRate, bottleneckScore, deviationScore);
    Grade grade = TrainingReportScoreCalculator.gradeOf(overallScore);

    List<ZoneDensityPoint> zoneDensities = trainingReportChartService.buildZoneDensities(session);
    TrainingReportCharts charts = new TrainingReportCharts(
        trainingReportChartService.buildCumulativeEvacuation(session, request.participantCount()),
        zoneDensities,
        trainingReportChartService.buildRecentEvacuationTimes(session.getScenario().getBuildingId(), evacuationSec));

    ReportNarrativeInput narrativeInput = new ReportNarrativeInput(
        session.getScenario().getName(),
        request.participantCount(), request.survivorCount(), survivalRate,
        evacuationSec, targetEvacuationSec, evacuationScore,
        bottleneckCount, bottleneckScore,
        deviation.deviationRate(), deviationScore,
        overallScore, grade,
        zoneDensities,
        resolveBottleneckLocation(session, bottleneckCount));
    // 점수 기반 문장 뒤에 실제 경로 재탐색 결과를 덧붙인다 (#289). 경로 권고는 점수와 무관하게 맨 앞에 온다.
    List<RouteRecalculation> recalculations =
        routeRecalculationRepository.findAllByTrainingSession_IdOrderByRequestedAtDesc(sessionId);
    Integer routeBlockedAfterSec = session.routeBlockedAfterSec();
    String summaryText = RouteRecalculationReportNarrative.appendToSummary(
        TrainingReportNarrativeGenerator.buildSummary(narrativeInput), recalculations, routeBlockedAfterSec);
    List<RecommendationPoint> recommendations = RouteRecalculationReportNarrative.prependRecommendation(
        TrainingReportNarrativeGenerator.buildRecommendations(narrativeInput), recalculations, routeBlockedAfterSec);

    TrainingReport report = TrainingReport.create(
        grade, overallScore,
        evacuationSec, evacuationScore,
        request.participantCount(), request.survivorCount(), survivalRate,
        bottleneckCount, bottleneckScore,
        deviation.deviationRate(), deviationScore,
        charts,
        summaryText, recommendations,
        session);

    try {
      return ReportResponse.from(trainingReportRepository.saveAndFlush(report));
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(ReportErrorCode.REPORT_ALREADY_EXISTS);
    }
  }

  // 병목 위치는 권고 문구를 구체적으로 만드는 부가 정보일 뿐이다. 조회가 어떤 이유로든 실패하면
  // (DynamoDB 장애, 삭제된 CCTV, 바뀐 도면 등) 위치 없이 기존 문구로 리포트를 만든다 - 이 때문에
  // 리포트 생성 자체가 실패하면 안 된다. resolver는 REQUIRES_NEW라 실패해도 이 트랜잭션은 멀쩡하다.
  private String resolveBottleneckLocation(TrainingSession session, int bottleneckCount) {
    if (bottleneckCount == 0) {
      return null;
    }
    try {
      return bottleneckLocationResolver.resolve(session.getId(), session.getScenario().getBuildingId())
          .orElse(null);
    } catch (RuntimeException exception) {
      log.warn("병목 위치를 찾지 못해 위치 없이 리포트 권고를 생성: sessionId={}", session.getId(), exception);
      return null;
    }
  }

  @Transactional(readOnly = true)
  public ReportResponse getReport(String reportId, String email) {
    return ReportResponse.from(findReportEntity(reportId, email));
  }

  // 리포트는 생성된 이후 값이 바뀌지 않으므로 PDF를 S3에 저장해두지 않고, 요청마다 저장된 값으로
  // 그 자리에서 새로 만든다
  @Transactional(readOnly = true)
  public byte[] generatePdf(String reportId, String email) {
    TrainingReport report = findReportEntity(reportId, email);
    return trainingReportPdfGenerator.generate(report);
  }

  private TrainingReport findReportEntity(String reportId, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    return trainingReportRepository
        .findByShortIdAndTrainingSession_Scenario_Building_SchoolName(reportId, schoolName)
        .orElseThrow(() -> new ApiException(ReportErrorCode.REPORT_NOT_FOUND));
  }

  public List<RecentTrainingReportResponse> getRecentTrainingReport(String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    List<TrainingReport> reports =
        trainingReportRepository.findRecentReportsBySchoolName(
            schoolName,
            PageRequest.of(0, 5)
        );
    return reports.stream()
        .map(report -> new RecentTrainingReportResponse(
            report.getShortId(),
            report.getTrainingSession()
                .getScenario()
                .getName(),
            report.getTrainingSession()
                .getStartedAt(),
            report.getParticipantCount(),
            report.getAvgEvacuationSec(),
            report.getSurvivalRate(),
            report.getGrade()
        ))
        .toList();
  }

  public DashboardStatsResponse getStats(String email) {
    String schoolName = schoolContextService.getSchoolName(email);

    long totalSessions = trainingSessionRepository.countByScenario_Building_SchoolName(schoolName);

    Object[] result = trainingReportRepository.getStatisticsBySchoolName(schoolName).get(0);
    Double avgSurvivalRate = result[0] != null ? ((Number) result[0]).doubleValue() : 0.0;
    Double avgEvacuationSec = result[1] != null ? ((Number) result[1]).doubleValue() : 0.0;
    Long totalParticipants = result[2] != null ? ((Number) result[2]).longValue() : 0L;

    return new DashboardStatsResponse(totalSessions, avgEvacuationSec, avgSurvivalRate,
        totalParticipants);
  }

}
