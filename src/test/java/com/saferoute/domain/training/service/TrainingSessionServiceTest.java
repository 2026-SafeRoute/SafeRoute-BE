package com.saferoute.domain.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.device.service.IoTLightService;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.recalculation.dto.response.CurrentRouteResponse;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.evacuation.service.EvacuationRoute;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.dto.CreateSessionRequest;
import com.saferoute.domain.training.dto.TrainingSessionListResponse;
import com.saferoute.domain.training.dto.TrainingSessionResponse;
import com.saferoute.domain.training.dto.TrainingSessionSummaryResponse;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrainingSessionServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private TrainingSessionService trainingSessionService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private TrainingScenarioRepository trainingScenarioRepository;

    @Mock
    private FireZoneRepository fireZoneRepository;

    @Mock
    private RouteRecalculationService routeRecalculationService;

    @Mock
    private EvacuationRouteService evacuationRouteService;

    @Mock
    private IoTLightService ioTLightService;

    @Mock
    private TrainingEventPublisher trainingEventPublisher;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private BuildingRepository buildingRepository;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();

    @BeforeEach
    void setUpSchoolContext() {
        org.mockito.Mockito.lenient()
                .when(schoolContextService.getSchoolName(EMAIL))
                .thenReturn(SCHOOL_NAME);
        // start()가 시나리오 삭제와 같은 시나리오 행 잠금을 먼저 잡는다. 기본값은 잠금 성공.
        org.mockito.Mockito.lenient()
                .when(trainingScenarioRepository.findForUpdateByIdAndAdmin_SchoolName(any(), eq(SCHOOL_NAME)))
                .thenReturn(Optional.of(mock(TrainingScenario.class)));
    }

    private TrainingSession sessionWithStatus(TrainingStatus status) {
        TrainingScenario scenario = mock(TrainingScenario.class);
        org.mockito.Mockito.lenient().when(scenario.getBuildingId()).thenReturn(buildingId);
        TrainingSession session = TrainingSession.create(status, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        return session;
    }

    @Test
    @DisplayName("다른 기관의 훈련 세션 상태는 조회할 수 없다")
    void getTrainingStatus_otherSchool_throwsNotFound() {
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingSessionService.getTrainingStatus(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("종료된 세션의 상태를 조회하면 오류 없이 EndedSessionResponse를 반환한다")
    void getTrainingStatus_endedSession_returnsEndedSessionResponse() {
        Building building = mock(Building.class);
        given(building.getName()).willReturn("A동");
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getBuilding()).willReturn(building);

        Instant startedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        TrainingSession session = TrainingSession.create(TrainingStatus.RUNNING, startedAt, mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        Instant endedAt = Instant.now();
        session.complete(endedAt);

        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));

        var response = (com.saferoute.domain.training.dto.EndedSessionResponse)
                trainingSessionService.getTrainingStatus(sessionId, EMAIL);

        assertThat(response.getBuildingName()).isEqualTo("A동");
        assertThat(response.getStatus()).isEqualTo(TrainingStatus.COMPLETED);
        assertThat(response.getEndedAt()).isEqualTo(endedAt);
        assertThat(response.getElapsedSeconds()).isEqualTo(endedAt.getEpochSecond() - startedAt.getEpochSecond());
        assertThat(response.getRouteBlockedAt()).isNull();
        assertThat(response.getRouteBlockedAfterSec()).isNull();
    }

    @Test
    @DisplayName("진행 중 세션의 경로가 막혔으면 상태 조회에 소멸 시각을 담아, 알림을 놓친 화면도 복원할 수 있다")
    void getTrainingStatus_runningAndRouteBlocked_includesRouteBlockedAt() {
        Building building = mock(Building.class);
        given(building.getName()).willReturn("A동");
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getBuilding()).willReturn(building);

        Instant startedAt = Instant.now().minus(3, ChronoUnit.MINUTES);
        TrainingSession session = TrainingSession.create(TrainingStatus.RUNNING, startedAt, mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        Instant blockedAt = startedAt.plusSeconds(95);
        session.markRouteBlocked(blockedAt);

        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));

        var response = (com.saferoute.domain.training.dto.RunningSessionResponse)
                trainingSessionService.getTrainingStatus(sessionId, EMAIL);

        assertThat(response.getRouteBlockedAt()).isEqualTo(blockedAt);
        assertThat(response.getRouteBlockedAfterSec()).isEqualTo(95);
    }

    @Test
    @DisplayName("경로가 막힌 뒤 종료된 세션의 상태 조회에도 소멸 시각과 경과 초가 그대로 담긴다")
    void getTrainingStatus_endedAfterRouteBlocked_includesRouteBlockedAt() {
        Building building = mock(Building.class);
        given(building.getName()).willReturn("A동");
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getBuilding()).willReturn(building);

        Instant startedAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        TrainingSession session = TrainingSession.create(TrainingStatus.RUNNING, startedAt, mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        Instant blockedAt = startedAt.plusSeconds(95);
        session.markRouteBlocked(blockedAt);
        session.complete(Instant.now());

        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));

        var response = (com.saferoute.domain.training.dto.EndedSessionResponse)
                trainingSessionService.getTrainingStatus(sessionId, EMAIL);

        assertThat(response.getStatus()).isEqualTo(TrainingStatus.COMPLETED);
        assertThat(response.getRouteBlockedAt()).isEqualTo(blockedAt);
        assertThat(response.getRouteBlockedAfterSec()).isEqualTo(95);
    }

    // === getSessions ===

    @Test
    @DisplayName("SCHEDULED 세션 목록을 생성 시각 기준 최신순으로 반환한다")
    void getSessions_scheduledOrdersByCreatedAtDescending() {
        UUID buildingId = UUID.randomUUID();
        UUID newestScenarioId = UUID.randomUUID();
        UUID olderScenarioId = UUID.randomUUID();
        Building building = mock(Building.class);
        given(building.getId()).willReturn(buildingId);
        given(building.getName()).willReturn("A동");

        TrainingScenario newestScenario = mock(TrainingScenario.class);
        given(newestScenario.getId()).willReturn(newestScenarioId);
        given(newestScenario.getName()).willReturn("최신 훈련");
        given(newestScenario.getBuilding()).willReturn(building);
        TrainingSession newestSession = TrainingSession.schedule(mock(User.class), newestScenario);
        ReflectionTestUtils.setField(newestSession, "id", sessionId);
        ReflectionTestUtils.setField(newestSession, "createdAt", Instant.parse("2026-09-03T10:00:00Z"));

        TrainingScenario olderScenario = mock(TrainingScenario.class);
        given(olderScenario.getId()).willReturn(olderScenarioId);
        given(olderScenario.getName()).willReturn("이전 훈련");
        given(olderScenario.getBuilding()).willReturn(building);
        TrainingSession olderSession = TrainingSession.schedule(mock(User.class), olderScenario);
        ReflectionTestUtils.setField(olderSession, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(olderSession, "createdAt", Instant.parse("2026-09-02T10:00:00Z"));

        given(trainingSessionRepository
                .findAllByStatusAndScenario_Building_SchoolNameOrderByCreatedAtDesc(
                        TrainingStatus.SCHEDULED, SCHOOL_NAME))
                .willReturn(List.of(newestSession, olderSession));

        TrainingSessionListResponse response = trainingSessionService.getSessions(TrainingStatus.SCHEDULED, EMAIL);

        assertThat(response.sessions())
                .extracting(TrainingSessionSummaryResponse::scenarioId)
                .containsExactly(newestScenarioId, olderScenarioId);
        assertThat(response.sessions())
                .extracting(TrainingSessionSummaryResponse::startedAt)
                .containsOnlyNulls();
        verify(trainingSessionRepository)
                .findAllByStatusAndScenario_Building_SchoolNameOrderByCreatedAtDesc(
                        TrainingStatus.SCHEDULED, SCHOOL_NAME);
    }

    @Test
    @DisplayName("해당 상태의 세션이 없으면 빈 목록을 반환한다")
    void getSessions_noMatchingSessions_returnsEmptyList() {
        given(trainingSessionRepository
                .findAllByStatusAndScenario_Building_SchoolNameOrderByStartedAtDesc(
                        TrainingStatus.RUNNING, SCHOOL_NAME))
                .willReturn(List.of());

        TrainingSessionListResponse response = trainingSessionService.getSessions(TrainingStatus.RUNNING, EMAIL);

        assertThat(response.sessions()).isEmpty();
    }

    // === create ===

    @Test
    @DisplayName("세션을 생성하면 항상 SCHEDULED 상태이고 시작 시각은 비어 있다")
    void create_alwaysCreatesScheduledSessionWithoutStartedAt() {
        UUID adminId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();

        User manager = mock(User.class);
        given(manager.getRole()).willReturn(UserRole.MANAGER);
        given(userRepository.findByIdAndSchoolName(adminId, SCHOOL_NAME)).willReturn(Optional.of(manager));
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStatus()).willReturn(com.saferoute.domain.training.entity.ScenarioStatus.READY);
        given(trainingScenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(trainingSessionRepository.saveAndFlush(any(TrainingSession.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        CreateSessionRequest request = new CreateSessionRequest(adminId);

        trainingSessionService.create(request, scenarioId, EMAIL);

        var sessionCaptor = org.mockito.ArgumentCaptor.forClass(TrainingSession.class);
        verify(trainingSessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        assertThat(sessionCaptor.getValue().getStartedAt()).isNull();
    }

    @Test
    @DisplayName("작성이 완료되지 않은(DRAFT) 시나리오로는 세션을 생성할 수 없다")
    void create_draftScenario_throwsScenarioDraftNotAllowed() {
        UUID adminId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        User manager = mock(User.class);
        given(manager.getRole()).willReturn(UserRole.MANAGER);
        given(userRepository.findByIdAndSchoolName(adminId, SCHOOL_NAME)).willReturn(Optional.of(manager));
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStatus()).willReturn(com.saferoute.domain.training.entity.ScenarioStatus.DRAFT);
        given(trainingScenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        CreateSessionRequest request = new CreateSessionRequest(adminId);

        assertThatThrownBy(() -> trainingSessionService.create(request, scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SCENARIO_DRAFT_NOT_ALLOWED);
        verify(trainingSessionRepository, never()).existsByScenario_Id(any());
        verify(trainingSessionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 세션이 존재하는 시나리오로는 세션을 또 생성할 수 없다")
    void create_scenarioAlreadyHasSession_throwsException() {
        UUID adminId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        User manager = mock(User.class);
        given(manager.getRole()).willReturn(UserRole.MANAGER);
        given(userRepository.findByIdAndSchoolName(adminId, SCHOOL_NAME)).willReturn(Optional.of(manager));
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStatus()).willReturn(com.saferoute.domain.training.entity.ScenarioStatus.READY);
        given(trainingScenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(trainingSessionRepository.existsByScenario_Id(scenarioId)).willReturn(true);

        CreateSessionRequest request = new CreateSessionRequest(adminId);

        assertThatThrownBy(() -> trainingSessionService.create(request, scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SESSION_ALREADY_EXISTS);
        verify(trainingSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("existsByScenario_Id 통과 이후 동시 요청으로 인한 UNIQUE 제약 위반은 SESSION_ALREADY_EXISTS로 변환된다")
    void create_concurrentInsertViolatesUniqueConstraint_throwsSessionAlreadyExists() {
        UUID adminId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        User manager = mock(User.class);
        given(manager.getRole()).willReturn(UserRole.MANAGER);
        given(userRepository.findByIdAndSchoolName(adminId, SCHOOL_NAME)).willReturn(Optional.of(manager));
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStatus()).willReturn(com.saferoute.domain.training.entity.ScenarioStatus.READY);
        given(trainingScenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(trainingSessionRepository.existsByScenario_Id(scenarioId)).willReturn(false);
        given(trainingSessionRepository.saveAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("unique constraint"));

        CreateSessionRequest request = new CreateSessionRequest(adminId);

        assertThatThrownBy(() -> trainingSessionService.create(request, scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SESSION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("다른 기관의 시나리오로 훈련 세션을 생성할 수 없다")
    void create_otherSchoolScenario_throwsNotFound() {
        UUID adminId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        User manager = mock(User.class);
        given(manager.getRole()).willReturn(UserRole.MANAGER);
        given(userRepository.findByIdAndSchoolName(adminId, SCHOOL_NAME))
                .willReturn(Optional.of(manager));
        given(trainingScenarioRepository
                .findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.empty());
        CreateSessionRequest request = new CreateSessionRequest(adminId);

        assertThatThrownBy(() -> trainingSessionService.create(request, scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND);
        verify(trainingSessionRepository, never()).save(any());
    }

    // === start ===

    @Test
    @DisplayName("SCHEDULED 상태의 세션을 시작하면 RUNNING으로 전이하고 최초 경로를 유도등에 반영한다")
    void start_fromScheduled_transitionsToRunningAndAppliesRouteGuidance() {
        UUID scenarioId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID startNodeId = UUID.randomUUID();
        UUID exitNodeId = UUID.randomUUID();
        MapNode startNode = mock(MapNode.class);
        Floor floor = mock(Floor.class);
        given(floor.getId()).willReturn(floorId);
        given(startNode.getFloor()).willReturn(floor);
        given(startNode.getId()).willReturn(startNodeId);
        given(startNode.getType()).willReturn(NodeType.START);
        MapNode exitNode = mock(MapNode.class);
        given(exitNode.getId()).willReturn(exitNodeId);

        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getStartNode()).willReturn(startNode);
        FireZone fireOrigin = mock(FireZone.class);
        FloorGridCell fireCell = mock(FloorGridCell.class);
        given(fireOrigin.getFloorId()).willReturn(floorId);
        given(fireOrigin.getGridCell()).willReturn(fireCell);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(fireOrigin));
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(startNode, exitNode), 12.0));
        java.util.Map<UUID, UUID> nextHops = java.util.Map.of(startNodeId, exitNodeId);
        given(evacuationRouteService.computeNextHops(eq(floorId), anySet(), eq(java.util.Map.of())))
                .willReturn(nextHops);

        trainingSessionService.start(sessionId, EMAIL);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.RUNNING);
        verify(trainingEventPublisher, times(1)).publishTrainingStatusUpdatedAfterCommit(session);
        verify(scenario, times(1)).markInProgress();
        verify(ioTLightService).applyFloorGuidance(floorId, List.of(startNodeId, exitNodeId), nextHops);
        // 설정 단계에서는 isFired를 바꾸지 않으므로, 실제 화재 셀 활성화는 훈련 시작 시점에 일어나야 한다.
        verify(fireCell).markFired();
    }

    @Test
    @DisplayName("훈련 시작 시 최초 경로는 방금 활성화한 발화점 구간을 제외하고 계산한다")
    void start_excludesJustActivatedFireOriginEdgesFromInitialRoute() {
        UUID scenarioId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID startNodeId = UUID.randomUUID();
        UUID exitNodeId = UUID.randomUUID();
        UUID firedEdgeId = UUID.randomUUID();
        MapNode startNode = mock(MapNode.class);
        Floor floor = mock(Floor.class);
        given(floor.getId()).willReturn(floorId);
        given(startNode.getFloor()).willReturn(floor);
        given(startNode.getId()).willReturn(startNodeId);
        given(startNode.getType()).willReturn(NodeType.START);
        MapNode exitNode = mock(MapNode.class);
        given(exitNode.getId()).willReturn(exitNodeId);

        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getStartNode()).willReturn(startNode);
        FireZone fireOrigin = mock(FireZone.class);
        FloorGridCell fireCell = mock(FloorGridCell.class);
        given(fireOrigin.getFloorId()).willReturn(floorId);
        given(fireOrigin.getGridCell()).willReturn(fireCell);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(fireOrigin));
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(routeRecalculationService.firedEdgeIds(scenarioId, floorId))
                .willReturn(java.util.Set.of(firedEdgeId));
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId, java.util.Set.of(firedEdgeId)))
                .willReturn(new EvacuationRoute(List.of(startNode, exitNode), 12.0));

        trainingSessionService.start(sessionId, EMAIL);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.RUNNING);
        verify(evacuationRouteService).findShortestRoute(floorId, startNodeId, java.util.Set.of(firedEdgeId));
        // 다음 홉 계산에도 최초 경로와 같은 화재 제외 구간이 적용돼야 한다.
        verify(evacuationRouteService).computeNextHops(floorId, java.util.Set.of(firedEdgeId), java.util.Map.of());
        verify(ioTLightService).applyFloorGuidance(
                eq(floorId), eq(List.of(startNodeId, exitNodeId)), anyMap());
    }

    @Test
    @DisplayName("다음 홉 계산이 실패해도 훈련 시작은 막히지 않고 최초 경로만 유도등에 반영한다")
    void start_whenNextHopComputationFails_stillStartsWithPathOnlyGuidance() {
        UUID scenarioId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID startNodeId = UUID.randomUUID();
        UUID exitNodeId = UUID.randomUUID();
        MapNode startNode = mock(MapNode.class);
        Floor floor = mock(Floor.class);
        given(floor.getId()).willReturn(floorId);
        given(startNode.getFloor()).willReturn(floor);
        given(startNode.getId()).willReturn(startNodeId);
        given(startNode.getType()).willReturn(NodeType.START);
        MapNode exitNode = mock(MapNode.class);
        given(exitNode.getId()).willReturn(exitNodeId);

        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getStartNode()).willReturn(startNode);
        FireZone fireOrigin = mock(FireZone.class);
        given(fireOrigin.getFloorId()).willReturn(floorId);
        given(fireOrigin.getGridCell()).willReturn(mock(FloorGridCell.class));
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(fireOrigin));
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(startNode, exitNode), 12.0));
        given(evacuationRouteService.computeNextHops(eq(floorId), anySet(), anyMap()))
                .willThrow(new IllegalStateException("boom"));

        trainingSessionService.start(sessionId, EMAIL);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.RUNNING);
        verify(ioTLightService).applyFloorGuidance(
                floorId, List.of(startNodeId, exitNodeId), java.util.Map.of());
    }

    @Test
    @DisplayName("같은 건물에 RUNNING 세션이 있으면 SCHEDULED 세션을 시작할 수 없다")
    void start_buildingAlreadyHasRunningSession_throwsConflict() {
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getBuildingId()).willReturn(buildingId);
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingSessionRepository.existsByStatusAndScenario_Building_Id(
                TrainingStatus.RUNNING, buildingId)).willReturn(true);

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.RUNNING_SESSION_ALREADY_EXISTS);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        verify(evacuationRouteService, never()).findShortestRoute(any(), any());
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    @Test
    @DisplayName("시작 노드가 지정되지 않은 시나리오는 훈련을 시작할 수 없다")
    void start_startNodeNotConfigured_throwsExceptionWithoutChangingState() {
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStartNode()).willReturn(null);
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
        assertThat(session.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        verify(scenario, never()).markInProgress();
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
        verify(ioTLightService, never()).applyFloorGuidance(any(), any(), any());
    }

    @Test
    @DisplayName("출발 노드에서 경로를 찾지 못하면 훈련 시작이 차단되고 상태가 바뀌지 않는다")
    void start_routeNotFound_throwsExceptionWithoutChangingState() {
        UUID scenarioId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID startNodeId = UUID.randomUUID();
        MapNode startNode = mock(MapNode.class);
        Floor floor = mock(Floor.class);
        given(floor.getId()).willReturn(floorId);
        given(startNode.getFloor()).willReturn(floor);
        given(startNode.getId()).willReturn(startNodeId);
        given(startNode.getType()).willReturn(NodeType.START);

        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getStartNode()).willReturn(startNode);
        FireZone fireOrigin = mock(FireZone.class);
        FloorGridCell fireCell = mock(FloorGridCell.class);
        given(fireOrigin.getFloorId()).willReturn(floorId);
        given(fireOrigin.getGridCell()).willReturn(fireCell);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(fireOrigin));
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willThrow(new ApiException(com.saferoute.global.api.error.EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(com.saferoute.global.api.error.EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND);
        assertThat(session.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        verify(scenario, never()).markInProgress();
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
        verify(ioTLightService, never()).applyFloorGuidance(any(), any(), any());
    }

    @Test
    @DisplayName("발화 위치가 없는 시나리오는 훈련을 시작할 수 없다")
    void start_fireOriginNotConfigured_throwsExceptionWithoutChangingState() {
        UUID scenarioId = UUID.randomUUID();
        MapNode startNode = mock(MapNode.class);
        given(startNode.getType()).willReturn(NodeType.START);
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getStartNode()).willReturn(startNode);
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of());

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.FIRE_ORIGIN_NOT_CONFIGURED);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        verify(evacuationRouteService, never()).findShortestRoute(any(), any());
    }

    @Test
    @DisplayName("대피 시작 노드가 START 유형이 아니면 훈련을 시작할 수 없다")
    void start_startNodeTypeInvalid_throwsExceptionWithoutChangingState() {
        MapNode startNode = mock(MapNode.class);
        given(startNode.getType()).willReturn(NodeType.HALLWAY);
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getStartNode()).willReturn(startNode);
        TrainingSession session =
                TrainingSession.create(TrainingStatus.SCHEDULED, Instant.now(), mock(User.class), scenario);
        ReflectionTestUtils.setField(session, "id", sessionId);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.START_NODE_TYPE_INVALID);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        verify(fireZoneRepository, never()).findByScenario_IdAndIsManualAddTrue(any());
    }

    @Test
    @DisplayName("이미 RUNNING인 세션은 다시 시작할 수 없다")
    void start_alreadyRunning_throwsException() {
        TrainingSession session = sessionWithStatus(TrainingStatus.RUNNING);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    @Test
    @DisplayName("존재하지 않는 세션을 시작하려 하면 예외가 발생한다")
    void start_sessionNotFound_throwsException() {
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
    }

    // === end ===

    @Test
    @DisplayName("RUNNING 상태의 세션을 정상 종료하면 COMPLETED로 전이하고 이벤트를 발행하며 시나리오도 COMPLETED로 바뀐다")
    void end_fromRunning_transitionsToCompletedAndPublishesEvent() {
        TrainingSession session = sessionWithStatus(TrainingStatus.RUNNING);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        TrainingSessionResponse response = trainingSessionService.end(sessionId, EMAIL);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.COMPLETED);
        assertThat(session.getEndedAt()).isNotNull();
        assertThat(response.getEndedAt()).isEqualTo(session.getEndedAt());
        verify(trainingEventPublisher, times(1)).publishTrainingStatusUpdatedAfterCommit(session);
        verify(session.getScenario(), times(1)).markCompleted();
        verify(ioTLightService).resetToNormal(buildingId);
    }

    @Test
    @DisplayName("이미 종료된 훈련은 정상 종료할 수 없다")
    void end_alreadyCompleted_throwsException() {
        TrainingSession session = sessionWithStatus(TrainingStatus.COMPLETED);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingSessionService.end(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    @Test
    @DisplayName("존재하지 않는 세션을 정상 종료하려 하면 예외가 발생한다")
    void end_sessionNotFound_throwsException() {
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingSessionService.end(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    // === forceEnd ===

    @Test
    @DisplayName("RUNNING 상태의 세션을 강제 종료하면 STOPPED로 전이하고 이벤트를 발행하며 시나리오는 ERROR로 바뀐다")
    void forceEnd_fromRunning_transitionsToStoppedAndPublishesEvent() {
        TrainingSession session = sessionWithStatus(TrainingStatus.RUNNING);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        TrainingSessionResponse response = trainingSessionService.forceEnd(sessionId, EMAIL);

        assertThat(session.getStatus()).isEqualTo(TrainingStatus.STOPPED);
        assertThat(session.getEndedAt()).isNotNull();
        assertThat(response.getEndedAt()).isEqualTo(session.getEndedAt());
        verify(trainingEventPublisher, times(1)).publishTrainingStatusUpdatedAfterCommit(session);
        verify(session.getScenario(), times(1)).markError();
        verify(ioTLightService).resetToNormal(buildingId);
    }

    @Test
    @DisplayName("이미 종료된 훈련은 강제종료할 수 없다")
    void forceEnd_alreadyStopped_throwsException() {
        TrainingSession session = sessionWithStatus(TrainingStatus.STOPPED);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.of(session));

        assertThatThrownBy(() -> trainingSessionService.forceEnd(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    @Test
    @DisplayName("존재하지 않는 세션을 강제종료하려 하면 예외가 발생한다")
    void forceEnd_sessionNotFound_throwsException() {
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingSessionService.forceEnd(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    // === failTimedOutSessions ===

    @Test
    @DisplayName("10분 타임아웃을 넘긴 RUNNING 세션을 FAILED로 처리하고 이벤트를 발행하며 시나리오는 TIMEOUT_FAILED로 바뀐다")
    void failTimedOutSessions_marksExpiredSessionsAsFailed() {
        TrainingScenario scenario = mock(TrainingScenario.class);
        given(scenario.getBuildingId()).willReturn(buildingId);
        TrainingSession timedOut = TrainingSession.create(
                TrainingStatus.RUNNING,
                Instant.now().minus(11, ChronoUnit.MINUTES),
                mock(User.class),
                scenario);
        ReflectionTestUtils.setField(timedOut, "id", sessionId);

        given(trainingSessionRepository.findByStatusAndStartedAtBefore(any(), any()))
                .willReturn(List.of(timedOut));

        trainingSessionService.failTimedOutSessions();

        assertThat(timedOut.getStatus()).isEqualTo(TrainingStatus.FAILED);
        verify(trainingEventPublisher, times(1)).publishTrainingStatusUpdatedAfterCommit(timedOut);
        verify(timedOut.getScenario(), times(1)).markTimeoutFailed();
        verify(timedOut.getScenario(), never()).markError();
        verify(ioTLightService).resetToNormal(buildingId);
    }

    @Test
    @DisplayName("타임아웃된 세션이 없으면 아무 것도 하지 않는다")
    void failTimedOutSessions_noExpiredSessions_doesNothing() {
        given(trainingSessionRepository.findByStatusAndStartedAtBefore(any(), any()))
                .willReturn(List.of());

        trainingSessionService.failTimedOutSessions();

        verify(trainingEventPublisher, never()).publishTrainingStatusUpdatedAfterCommit(any());
    }

    // === getCurrentRoute ===

    @Test
    @DisplayName("현재 유효 경로 조회는 RouteRecalculationService에 위임한다")
    void getCurrentRoute_delegatesToRouteRecalculationService() {
        CurrentRouteResponse expected = new CurrentRouteResponse(
                sessionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CurrentRouteResponse.RouteSource.RECALCULATED, List.of(), 10.0, Instant.now());
        given(routeRecalculationService.getCurrentRoute(sessionId, EMAIL)).willReturn(expected);

        CurrentRouteResponse response = trainingSessionService.getCurrentRoute(sessionId, EMAIL);

        assertThat(response).isEqualTo(expected);
    }

    @Test
    @DisplayName("시나리오가 이미 삭제됐으면 훈련 시작은 시나리오 없음으로 거부된다")
    void start_scenarioAlreadyDeleted_throwsScenarioNotFound() {
        TrainingSession session = sessionWithStatus(TrainingStatus.SCHEDULED);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(trainingScenarioRepository.findForUpdateByIdAndAdmin_SchoolName(any(), eq(SCHOOL_NAME)))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> trainingSessionService.start(sessionId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND);
    }
}
