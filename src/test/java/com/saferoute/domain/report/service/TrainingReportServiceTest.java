package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.domain.evacuation.deviation.service.SessionDeviationResult;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.report.dto.GenerateReportRequest;
import com.saferoute.domain.report.dto.ReportResponse;
import com.saferoute.domain.report.entity.Grade;
import com.saferoute.domain.report.entity.TrainingReport;
import com.saferoute.domain.report.repository.TrainingReportRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.ReportErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrainingReportServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private TrainingReportService trainingReportService;

    @Mock
    private TrainingReportRepository trainingReportRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private CongestionEventRepository congestionEventRepository;

    @Mock
    private RouteDeviationService routeDeviationService;

    @Mock
    private TrainingReportChartService trainingReportChartService;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private RouteRecalculationRepository routeRecalculationRepository;

    @Mock
    private BottleneckLocationResolver bottleneckLocationResolver;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();

    @BeforeEach
    void setUpSchoolContext() {
        org.mockito.Mockito.lenient()
                .when(schoolContextService.getSchoolName(EMAIL))
                .thenReturn(SCHOOL_NAME);
    }

    // 가드(종료 여부/중복/생존인원 검증)를 통과한 뒤에야 scenario.getTargetEvacuationSec()/
    // startedAt에 접근하므로, 가드에서 예외가 나는 테스트는 그 스텁을 쓰지 않는다 - lenient로 풀어준다.
    private TrainingSession endedSession(int targetEvacuationSec, Instant startedAt, Instant endedAt) {
        TrainingScenario scenario = mock(TrainingScenario.class);
        org.mockito.Mockito.lenient().when(scenario.getTargetEvacuationSec()).thenReturn(targetEvacuationSec);
        org.mockito.Mockito.lenient().when(scenario.getBuildingId()).thenReturn(buildingId);
        org.mockito.Mockito.lenient().when(scenario.getName()).thenReturn("정기 훈련");
        TrainingSession session = mock(TrainingSession.class);
        org.mockito.Mockito.lenient().when(session.getScenario()).thenReturn(scenario);
        org.mockito.Mockito.lenient().when(session.getStartedAt()).thenReturn(startedAt);
        // 실제 엔티티는 경로가 막힌 적이 없으면 null을 돌려준다 - 목의 기본값(0)이면 "0초에 막힘"으로 읽힌다.
        org.mockito.Mockito.lenient().when(session.routeBlockedAfterSec()).thenReturn(null);
        given(session.getEndedAt()).willReturn(endedAt);
        return session;
    }

    @Test
    @DisplayName("shortId와 기관이 일치하는 리포트를 조회할 수 있다")
    void getReport_matchingShortIdAndSchool_returnsReport() {
        String reportId = "abc123XYZ0";
        TrainingReport report = mock(TrainingReport.class);
        given(report.getShortId()).willReturn(reportId);
        given(report.getCumulativeEvacuationPoints()).willReturn(Collections.emptyList());
        given(report.getZoneDensityPoints()).willReturn(Collections.emptyList());
        given(report.getRecentEvacuationPoints()).willReturn(Collections.emptyList());
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(trainingReportRepository
                .findByShortIdAndTrainingSession_Scenario_Building_SchoolName(reportId, SCHOOL_NAME))
                .willReturn(Optional.of(report));

        var response = trainingReportService.getReport(reportId, EMAIL);

        assertThat(response.getReportId()).isEqualTo(reportId);
    }

    @Test
    @DisplayName("존재하지 않거나 다른 기관의 shortId로 조회하면 예외가 발생한다")
    void getReport_notFound_throwsException() {
        String reportId = "abc123XYZ0";
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(trainingReportRepository
                .findByShortIdAndTrainingSession_Scenario_Building_SchoolName(reportId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingReportService.getReport(reportId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ReportErrorCode.REPORT_NOT_FOUND);
    }

    // === generate ===

    @Test
    @DisplayName("목표 대피 시간이 없으면 명확한 오류로 리포트 생성을 막는다")
    void generate_targetEvacuationSecNotConfigured_throwsException() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        TrainingScenario scenario = mock(TrainingScenario.class);
        TrainingSession session = mock(TrainingSession.class);
        given(session.getScenario()).willReturn(scenario);
        given(scenario.getTargetEvacuationSec()).willReturn(null);
        given(session.getStartedAt()).willReturn(startedAt);
        given(session.getEndedAt()).willReturn(startedAt.plusSeconds(300));
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingReportRepository.existsByTrainingSession_Id(sessionId)).willReturn(false);

        assertThatThrownBy(() -> trainingReportService.generate(
                sessionId, new GenerateReportRequest(50, 50), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ReportErrorCode.TARGET_EVACUATION_SEC_NOT_CONFIGURED);

        verify(trainingReportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("종료된 세션이면 참여/생존 인원 입력만으로 나머지 항목을 계산해 리포트를 생성한다")
    void generate_endedSession_computesRemainingScoresAndSaves() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant endedAt = startedAt.plusSeconds(300);
        TrainingSession session = endedSession(300, startedAt, endedAt);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingReportRepository.existsByTrainingSession_Id(sessionId)).willReturn(false);
        given(congestionEventRepository.countBottlenecksBySessionId(sessionId.toString()))
                .willReturn(0);
        given(routeDeviationService.calculateForSession(sessionId, EMAIL))
                .willReturn(new SessionDeviationResult(10, 0, 0.0));
        given(trainingReportChartService.buildCumulativeEvacuation(session, 50))
                .willReturn(Collections.emptyList());
        given(trainingReportChartService.buildZoneDensities(session))
                .willReturn(Collections.emptyList());
        given(trainingReportChartService.buildRecentEvacuationTimes(buildingId, 300))
                .willReturn(Collections.emptyList());
        given(trainingReportRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        GenerateReportRequest request = new GenerateReportRequest(50, 50);
        ReportResponse response = trainingReportService.generate(sessionId, request, EMAIL);

        assertThat(response.getGrade()).isEqualTo(Grade.A);
        assertThat(response.getOverallScore()).isEqualTo(100.0);
        assertThat(response.getEvacuationScore()).isEqualTo(100);
        assertThat(response.getAvgEvacuationSec()).isEqualTo(300);
        assertThat(response.getSurvivalRate()).isEqualByComparingTo(java.math.BigDecimal.valueOf(100.0));
        assertThat(response.getBottleneckCount()).isZero();
        assertThat(response.getBottleneckScore()).isEqualTo(100);
        assertThat(response.getDeviationScore()).isEqualTo(100);
        // 4개 항목이 전부 만점이라 강점 문장만 있고 개선 권고사항은 비어있어야 한다.
        assertThat(response.getSummaryText()).contains("강점:").doesNotContain("개선:");
        assertThat(response.getRecommendations()).isEmpty();

        ArgumentCaptor<TrainingReport> captor = ArgumentCaptor.forClass(TrainingReport.class);
        verify(trainingReportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getTrainingSession()).isEqualTo(session);
    }

    @Test
    @DisplayName("훈련 중 대피 경로가 막혔으면 그 경과 초를 리포트에 복사해 응답한다")
    void generate_routeBlockedDuringTraining_copiesRouteBlockedAfterSec() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        TrainingSession session = endedSession(300, startedAt, startedAt.plusSeconds(300));
        given(session.routeBlockedAfterSec()).willReturn(95);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(routeDeviationService.calculateForSession(sessionId, EMAIL))
                .willReturn(new SessionDeviationResult(10, 0, 0.0));
        given(trainingReportRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        ReportResponse response = trainingReportService.generate(sessionId, new GenerateReportRequest(50, 50), EMAIL);

        assertThat(response.getRouteBlockedAfterSec()).isEqualTo(95);
        ArgumentCaptor<TrainingReport> captor = ArgumentCaptor.forClass(TrainingReport.class);
        verify(trainingReportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRouteBlockedAfterSec()).isEqualTo(95);
    }

    @Test
    @DisplayName("A등급이어도 재탐색 없이 경로가 막혔으면 경로 권고가 맨 앞에 나오고 요약에 경로 문단이 붙는다")
    void generate_routeBlockedWithoutRecalculation_addsRouteRecommendationEvenForGradeA() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        TrainingSession session = endedSession(300, startedAt, startedAt.plusSeconds(300));
        given(session.routeBlockedAfterSec()).willReturn(95);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(routeDeviationService.calculateForSession(sessionId, EMAIL))
                .willReturn(new SessionDeviationResult(10, 0, 0.0));
        given(routeRecalculationRepository.findAllByTrainingSession_IdOrderByRequestedAtDesc(sessionId))
                .willReturn(Collections.emptyList());
        given(trainingReportRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        ReportResponse response = trainingReportService.generate(sessionId, new GenerateReportRequest(50, 50), EMAIL);

        assertThat(response.getGrade()).isEqualTo(Grade.A);
        assertThat(response.getRecommendations()).hasSize(1);
        assertThat(response.getRecommendations().get(0).description())
                .isEqualTo(RouteRecalculationReportNarrative.NO_ALTERNATIVE_ROUTE_DESCRIPTION);
        assertThat(response.getSummaryText()).contains("경로: 화재 확산으로 훈련 시작 1분 35초 후 모든 대피 경로가 막혔습니다.");
    }

    @Test
    @DisplayName("bottleneckCount는 저장소가 집계한 병목 횟수를 그대로 사용한다")
    void generate_usesRepositoryBottleneckCountAsIs() {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant endedAt = startedAt.plusSeconds(300);
        TrainingSession session = endedSession(300, startedAt, endedAt);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingReportRepository.existsByTrainingSession_Id(sessionId)).willReturn(false);
        given(congestionEventRepository.countBottlenecksBySessionId(sessionId.toString()))
                .willReturn(7);
        given(routeDeviationService.calculateForSession(sessionId, EMAIL))
                .willReturn(new SessionDeviationResult(10, 0, 0.0));
        given(trainingReportChartService.buildCumulativeEvacuation(session, 50))
                .willReturn(Collections.emptyList());
        given(trainingReportChartService.buildZoneDensities(session))
                .willReturn(Collections.emptyList());
        given(trainingReportChartService.buildRecentEvacuationTimes(buildingId, 300))
                .willReturn(Collections.emptyList());
        given(trainingReportRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

        ReportResponse response = trainingReportService.generate(
                sessionId, new GenerateReportRequest(50, 50), EMAIL);

        assertThat(response.getBottleneckCount()).isEqualTo(7);
        verify(congestionEventRepository).countBottlenecksBySessionId(sessionId.toString());
        verify(congestionEventRepository, never())
                .findAllBySessionId(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("병목 위치를 찾으면 병목 권고 설명에 CCTV와 구간을 적는다")
    void generate_bottleneckLocationResolved_includesLocationInRecommendation() {
        TrainingSession session = sessionWithBottlenecks(7);
        given(session.getId()).willReturn(sessionId);
        given(bottleneckLocationResolver.resolve(sessionId, buildingId))
                .willReturn(Optional.of("CCTV_122(복도 4 – 복도 5 구간)"));

        ReportResponse response = trainingReportService.generate(sessionId, new GenerateReportRequest(50, 50), EMAIL);

        assertThat(response.getRecommendations())
                .anySatisfy(recommendation -> assertThat(recommendation.description())
                        .isEqualTo("CCTV_122(복도 4 – 복도 5 구간)에서 병목(혼잡) 7회 감지"));
        assertThat(response.getSummaryText()).contains("훈련 중 CCTV_122(복도 4 – 복도 5 구간)에서 병목이 7회 감지");
    }

    @Test
    @DisplayName("병목 위치 조회가 실패해도 리포트는 기존 문구로 정상 생성된다")
    void generate_bottleneckLocationLookupFails_stillSavesReportWithDefaultText() {
        TrainingSession session = sessionWithBottlenecks(7);
        given(session.getId()).willReturn(sessionId);
        given(bottleneckLocationResolver.resolve(sessionId, buildingId))
                .willThrow(new IllegalStateException("DynamoDB 장애"));

        ReportResponse response = trainingReportService.generate(sessionId, new GenerateReportRequest(50, 50), EMAIL);

        assertThat(response.getRecommendations())
                .anySatisfy(recommendation -> assertThat(recommendation.description())
                        .isEqualTo("훈련 중 병목(혼잡) 7회 감지"));
        verify(trainingReportRepository).saveAndFlush(any());
    }

    @Test
    @DisplayName("병목이 없으면 병목 위치를 조회하지 않는다")
    void generate_noBottleneck_doesNotResolveLocation() {
        sessionWithBottlenecks(0);

        trainingReportService.generate(sessionId, new GenerateReportRequest(50, 50), EMAIL);

        verify(bottleneckLocationResolver, never()).resolve(any(), any());
    }

    private TrainingSession sessionWithBottlenecks(int bottleneckCount) {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        TrainingSession session = endedSession(300, startedAt, startedAt.plusSeconds(300));
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(congestionEventRepository.countBottlenecksBySessionId(sessionId.toString()))
                .willReturn(bottleneckCount);
        given(routeDeviationService.calculateForSession(sessionId, EMAIL))
                .willReturn(new SessionDeviationResult(10, 0, 0.0));
        given(trainingReportRepository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));
        return session;
    }

    @Test
    @DisplayName("아직 종료되지 않은 세션은 리포트를 생성할 수 없다")
    void generate_sessionNotEnded_throwsException() {
        TrainingSession session = endedSession(300, Instant.now(), null);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingReportService.generate(
                sessionId, new GenerateReportRequest(50, 50), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ReportErrorCode.SESSION_NOT_ENDED);
        verify(trainingReportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("존재하지 않는 세션이면 예외가 발생한다")
    void generate_sessionNotFound_throwsException() {
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingReportService.generate(
                sessionId, new GenerateReportRequest(50, 50), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 리포트가 존재하는 세션이면 예외가 발생한다")
    void generate_reportAlreadyExists_throwsException() {
        TrainingSession session = endedSession(300, Instant.now(), Instant.now().plusSeconds(300));
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingReportRepository.existsByTrainingSession_Id(sessionId)).willReturn(true);

        assertThatThrownBy(() -> trainingReportService.generate(
                sessionId, new GenerateReportRequest(50, 50), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ReportErrorCode.REPORT_ALREADY_EXISTS);
        verify(trainingReportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("생존 판정 인원이 전체 참여 인원보다 많으면 예외가 발생한다")
    void generate_survivorExceedsParticipants_throwsException() {
        TrainingSession session = endedSession(300, Instant.now(), Instant.now().plusSeconds(300));
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingReportRepository.existsByTrainingSession_Id(sessionId)).willReturn(false);

        assertThatThrownBy(() -> trainingReportService.generate(
                sessionId, new GenerateReportRequest(10, 20), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ReportErrorCode.SURVIVOR_COUNT_EXCEEDS_PARTICIPANTS);
        verify(trainingReportRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("최근 훈련 리포트 목록에는 상세 조회용 reportId가 포함된다")
    void getRecentTrainingReport_includesReportId() {
        String reportId = "abc123XYZ0";
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getName()).willReturn("정기 훈련");
        TrainingSession session = mock(TrainingSession.class);
        given(session.getScenario()).willReturn(scenario);
        given(session.getStartedAt()).willReturn(Instant.parse("2026-01-01T00:00:00Z"));
        TrainingReport report = mock(TrainingReport.class);
        given(report.getShortId()).willReturn(reportId);
        given(report.getTrainingSession()).willReturn(session);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(trainingReportRepository.findRecentReportsBySchoolName(
                org.mockito.ArgumentMatchers.eq(SCHOOL_NAME), any()))
                .willReturn(Collections.singletonList(report));

        var response = trainingReportService.getRecentTrainingReport(EMAIL);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).reportId()).isEqualTo(reportId);
    }

    @Test
    void calculatesDashboardStatsOnlyForAuthenticatedUsersSchool() {
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(trainingSessionRepository.countByScenario_Building_SchoolName(SCHOOL_NAME))
                .willReturn(3L);
        given(trainingReportRepository.getStatisticsBySchoolName(SCHOOL_NAME))
                .willReturn(Collections.singletonList(new Object[]{80.5, 120.0, 42L}));

        var response = trainingReportService.getStats(EMAIL);

        assertThat(response.getTotalSessions()).isEqualTo(3L);
        verify(trainingReportRepository).getStatisticsBySchoolName(SCHOOL_NAME);
    }
}
