package com.saferoute.domain.evacuation.recalculation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.service.IoTLightService;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.dto.response.CurrentRouteResponse;
import com.saferoute.domain.evacuation.recalculation.dto.response.RouteRecalculationResponse;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.evacuation.service.EvacuationRoute;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RouteRecalculationServiceTest {

    private static final String MANAGER_EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private RouteRecalculationService routeRecalculationService;

    @Mock
    private RouteRecalculationRepository routeRecalculationRepository;

    @Mock
    private EvacuationRouteService evacuationRouteService;

    @Mock
    private IoTLightService ioTLightService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TrainingEventPublisher trainingEventPublisher;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private MapNodeJpaRepository mapNodeJpaRepository;

    @Mock
    private MapEdgeJpaRepository mapEdgeJpaRepository;

    @Mock
    private FloorGridCellRepository floorGridCellRepository;

    @Mock
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;

    @Mock
    private FireZoneRepository fireZoneRepository;

    @Mock
    private CurrentCongestionWeightProvider currentCongestionWeightProvider;

    private TrainingSession session;
    private TrainingScenario scenario;
    private MapEdge triggerEdge;
    private MapNode triggerEdgeFromNode;
    private MapNode triggerEdgeToNode;
    private MapNode representativeStart;
    private UUID floorId;
    private UUID startNodeId;

    @BeforeEach
    void setUp() {
        session = mock(TrainingSession.class);
        UUID sessionId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(session.getId()).thenReturn(sessionId);
        // trigger()가 락 직후 RUNNING 여부를 확인하므로, 종료 후 케이스를 따로 다루는
        // 테스트가 아니면 기본값은 RUNNING으로 둔다.
        org.mockito.Mockito.lenient().when(session.getStatus()).thenReturn(TrainingStatus.RUNNING);
        org.mockito.Mockito.lenient().when(trainingSessionRepository.findByIdForUpdate(sessionId))
                .thenReturn(Optional.of(session));
        org.mockito.Mockito.lenient().when(routeRecalculationRepository
                .findTrainingSessionIdByIdAndSchoolName(any(), any()))
                .thenReturn(Optional.of(sessionId));
        org.mockito.Mockito.lenient().when(schoolContextService.getSchoolName(MANAGER_EMAIL))
                .thenReturn(SCHOOL_NAME);

        Floor floor = mock(Floor.class);
        floorId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(floor.getId()).thenReturn(floorId);

        // 시나리오의 대표 startNode. 재탐색 계산은 이제 이 노드를 출발점으로 쓴다.
        representativeStart = MapNode.create(floor, "HALLWAY1", NodeType.HALLWAY, "HALLWAY1", 0, 0, false);
        startNodeId = UUID.randomUUID();
        ReflectionTestUtils.setField(representativeStart, "id", startNodeId);

        scenario = mock(TrainingScenario.class);
        org.mockito.Mockito.lenient().when(scenario.getStartNode()).thenReturn(representativeStart);
        org.mockito.Mockito.lenient().when(session.getScenario()).thenReturn(scenario);

        triggerEdgeFromNode = MapNode.create(floor, "FROM", NodeType.HALLWAY, "FROM", 0, 0, false);
        ReflectionTestUtils.setField(triggerEdgeFromNode, "id", UUID.randomUUID());
        triggerEdgeToNode = MapNode.create(floor, "TO", NodeType.HALLWAY, "TO", 0, 0, false);
        ReflectionTestUtils.setField(triggerEdgeToNode, "id", UUID.randomUUID());
        triggerEdge = MapEdge.create(floor, triggerEdgeFromNode, triggerEdgeToNode, 5.0, true);
        ReflectionTestUtils.setField(triggerEdge, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(triggerEdge, "floor", floor);
    }

    // triggerForFireSpread()가 이제 "활성 경로가 실제로 화재 엣지를 지나는지"부터 확인하므로,
    // 화재 재탐색 관련 테스트는 활성(직행) 경로가 triggerEdge의 두 노드를 연속으로 지나도록
    // 세팅해야 그 다음 단계(우회 경로 계산)까지 도달한다.
    private void givenDirectRouteCrossesTriggerEdge() {
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId))
                .willReturn(new EvacuationRoute(List.of(triggerEdgeFromNode, triggerEdgeToNode), 8.0));
    }

    private void givenNoExistingPending() {
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                any(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("다른 기관의 훈련 세션으로 재탐색 목록을 조회하면 세션 not-found를 반환한다")
    void getRecalculations_otherSchool_throwsTrainingSessionNotFound() {
        UUID sessionId = UUID.randomUUID();
        given(trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(sessionId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> routeRecalculationService
                .getRecalculations(sessionId, null, MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
    }

    private void givenNoApprovedHistory() {
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                any(), any())).willReturn(Optional.empty());
    }

    private void givenNoDirectRoute() {
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));
    }

    @Test
    @DisplayName("세션이 이미 종료됐으면 PENDING을 조회/생성하지 않고 건너뛴다")
    void trigger_skipsWhenSessionAlreadyEnded() {
        given(session.getStatus()).willReturn(TrainingStatus.COMPLETED);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5);

        verify(routeRecalculationRepository, never())
                .findAllByTrainingSession_IdAndStatus(any(), any());
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED(복구) 트리거도 세션이 이미 종료됐으면 건너뛴다")
    void trigger_ended_skipsWhenSessionAlreadyEnded() {
        given(session.getStatus()).willReturn(TrainingStatus.COMPLETED);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        verify(routeRecalculationRepository, never())
                .findAllByTrainingSession_IdAndStatus(any(), any());
        verify(routeRecalculationRepository, never())
                .findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(any(), any());
    }

    // 다른 CCTV가 만든 PENDING이어도(cctvCode가 다름) 새로 계산한 후보가 그 PENDING의 경로와
    // 같으면(=실제로 바뀐 게 없으면) 건드리지 않는다. 여러 CCTV가 번갈아 보고할 때 매번
    // 취소+재생성이 반복되던 문제의 직접적인 재현/수정 확인 테스트다.
    @Test
    @DisplayName("다른 CCTV가 만든 PENDING이어도 새로 계산한 후보가 같으면 취소/재생성하지 않는다")
    void trigger_skipsWhenCandidateMatchesExistingPendingFromDifferentCctv() {
        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        UUID exitNodeId = UUID.randomUUID();
        ReflectionTestUtils.setField(exitNode, "id", exitNodeId);

        RouteRecalculation existingFromOtherCctv = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_OTHER", RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(exitNodeId), 12.5);
        ReflectionTestUtils.setField(existingFromOtherCctv, "id", UUID.randomUUID());
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existingFromOtherCctv));
        givenNoApprovedHistory();
        givenNoDirectRoute();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willReturn(new EvacuationRoute(List.of(exitNode), 12.5));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5);

        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(any());
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("레벨이 바뀌었으면 기존 PENDING을 CANCELLED로 무효화하고 새로 계산한다")
    void trigger_cancelsAndRecreatesWhenLevelChanges() {
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existing));
        givenNoApprovedHistory();
        givenNoDirectRoute();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.VERY_CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 5.5);

        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(trainingEventPublisher).publishRouteRecalculationCancelledAfterCommit(existing);
    }

    @Test
    @DisplayName("혼잡 트리거는 화재 확산이 만든 PENDING(FIRE_SPREAD)을 건드리지 않는다")
    void trigger_doesNotTouchFireSpreadPending() {
        RouteRecalculation firePending = firePendingRecalculation();
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(firePending));
        givenNoApprovedHistory();
        givenNoDirectRoute();

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);
        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        assertThat(firePending.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(firePending);
    }

    @Test
    @DisplayName("혼잡 종료(ENDED) 트리거도 화재 확산 PENDING은 무효화하지 않는다")
    void trigger_ended_doesNotCancelFireSpreadPending() {
        RouteRecalculation firePending = firePendingRecalculation();
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(firePending));
        givenNoApprovedHistory();
        UUID sameNode = UUID.randomUUID();
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId)).willReturn(routeOf(8.0, sameNode));
        givenEndedCandidate(routeOf(8.0, sameNode));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(firePending.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(firePending);
    }

    // 시작 노드(floorId 층)와 다른 층에 있는 혼잡 엣지.
    private MapEdge edgeOnOtherFloor() {
        Floor otherFloor = mock(Floor.class);
        org.mockito.Mockito.lenient().when(otherFloor.getId()).thenReturn(UUID.randomUUID());
        MapEdge edge = MapEdge.create(otherFloor, mock(MapNode.class), mock(MapNode.class), 5.0, true);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(edge, "floor", otherFloor);
        return edge;
    }

    @Test
    @DisplayName("혼잡 엣지가 시작 노드와 다른 층이면 예외 없이 건너뛰고 경로 탐색/저장을 하지 않는다")
    void trigger_skipsWhenTriggerEdgeOnDifferentFloorThanStartNode() {
        routeRecalculationService.trigger(session, List.of(edgeOnOtherFloor()), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        verifyNoInteractions(evacuationRouteService);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 층 혼잡 이벤트는 같은 층의 기존 PENDING을 취소하지 않는다")
    void trigger_differentFloor_doesNotCancelExistingPending() {
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);

        routeRecalculationService.trigger(session, List.of(edgeOnOtherFloor()), CongestionLevel.VERY_CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_002", 5.5);

        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(any());
        verify(routeRecalculationRepository, never())
                .findAllByTrainingSession_IdAndStatus(any(), any());
    }

    @Test
    @DisplayName("다른 층 ENDED는 승인 이력이 있어도 예외 없이 건너뛰고 복구 PENDING을 만들지 않는다")
    void trigger_ended_differentFloor_doesNotThrowAndDoesNotCreateRecovery() {
        routeRecalculationService.trigger(session, List.of(edgeOnOtherFloor()), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_002", 1.0);

        verifyNoInteractions(evacuationRouteService);
        verify(routeRecalculationRepository, never()).save(any());
        verify(routeRecalculationRepository, never())
                .findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(any(), any());
    }

    @Test
    @DisplayName("여러 층의 엣지가 섞여 있으면 시작 노드 층의 엣지만 남겨 계산한다")
    void trigger_mixedFloors_usesOnlyStartFloorEdges() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        routeRecalculationService.trigger(session, List.of(edgeOnOtherFloor(), triggerEdge),
                CongestionLevel.CROWDED, RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        verify(evacuationRouteService).findShortestRoute(eq(floorId), eq(startNodeId), anySet(), any());
    }

    @Test
    @DisplayName("혼잡 우회 후보를 계산할 때 현재 그 층에 번진 화재 구간도 함께 제외한다")
    void trigger_excludesCurrentlyFiredEdgesFromCongestionCandidate() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();

        UUID scenarioId = UUID.randomUUID();
        given(scenario.getId()).willReturn(scenarioId);

        FloorGridCell firedCell = mock(FloorGridCell.class);
        UUID firedCellId = UUID.randomUUID();
        given(firedCell.getId()).willReturn(firedCellId);
        given(floorGridCellRepository.findAllByFloor_IdAndIsFiredTrue(floorId)).willReturn(List.of(firedCell));

        FireZone fireZone = mock(FireZone.class);
        given(fireZone.getGridCellId()).willReturn(firedCellId);
        given(fireZoneRepository.findByScenario_IdAndFloor_Id(scenarioId, floorId)).willReturn(List.of(fireZone));

        MapEdge firedEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 3.0, true);
        UUID firedEdgeId = UUID.randomUUID();
        ReflectionTestUtils.setField(firedEdge, "id", firedEdgeId);
        MapEdgeGridCell mapping = MapEdgeGridCell.create(firedEdge, firedCell);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(firedCellId))).willReturn(List.of(mapping));

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);
        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(evacuationRouteService).findShortestRoute(any(), any(), excludedEdgesCaptor.capture(), any());
        assertThat(excludedEdgesCaptor.getValue()).contains(firedEdgeId);
    }

    @Test
    @DisplayName("우회 경로가 없으면 로그만 남기고 승인 대기 항목을 만들지 않는다")
    void trigger_skipsWhenNoDetourRouteFound() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        verify(routeRecalculationRepository, never()).save(any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationRequestedAfterCommit(any());
    }

    // VERY_CROWDED도 완전 제외가 아니라 큰 페널티(CurrentCongestionWeightProvider와 동일한
    // 배율)로만 반영한다 - 화재 우회(triggerForFireSpread)와 같은 정책으로 통일했다. 여러
    // 구간이 동시에 VERY_CROWDED여도 완전 제외 때문에 경로 자체가 안 나오는 일이 없어야 한다.
    @Test
    @DisplayName("VERY_CROWDED면 CCTV 영향 엣지 모두에 10배 페널티를 주고(완전 제외하지 않음) 후보에 남긴다")
    void trigger_veryCrowded_appliesHeavyPenaltyWithoutExcluding() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);

        RouteRecalculation saved = pendingRecalculation(CongestionLevel.VERY_CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);
        MapEdge secondEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 4.0, true);
        ReflectionTestUtils.setField(secondEdge, "id", UUID.randomUUID());

        routeRecalculationService.trigger(session, List.of(triggerEdge, secondEdge), CongestionLevel.VERY_CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 5.5);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(
                any(), any(), excludedEdgesCaptor.capture(), multipliersCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).isEmpty();
        assertThat(multipliersCaptor.getValue()).containsEntry(
                triggerEdge.getId(), CurrentCongestionWeightProvider.VERY_CROWDED_WEIGHT_MULTIPLIER);
        assertThat(multipliersCaptor.getValue()).containsEntry(
                secondEdge.getId(), CurrentCongestionWeightProvider.VERY_CROWDED_WEIGHT_MULTIPLIER);

        verify(routeRecalculationRepository, times(1)).save(any());
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRequestedAfterCommit(saved);
    }

    // #265: 이번 호출 CCTV의 가중치뿐 아니라, 같은 층 다른 CCTV가 지금 보고 중인 혼잡
    // (CurrentCongestionWeightProvider)도 함께 반영해야 한다.
    @Test
    @DisplayName("다른 CCTV가 현재 보고 중인 층 전체 혼잡도 가중치에 함께 반영한다")
    void trigger_mergesCurrentFloorWideCongestionWithOwnWeights() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();

        UUID otherCctvEdgeId = UUID.randomUUID();
        given(currentCongestionWeightProvider.currentMultipliers(session.getId(), floorId))
                .willReturn(Map.of(otherCctvEdgeId, 3.0));

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willReturn(new EvacuationRoute(List.of(exitNode), 12.5));
        given(routeRecalculationRepository.save(any())).willReturn(pendingRecalculation(CongestionLevel.CROWDED));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(any(), any(), anySet(), multipliersCaptor.capture());
        assertThat(multipliersCaptor.getValue())
                .containsEntry(triggerEdge.getId(), 3.0)
                .containsEntry(otherCctvEdgeId, 3.0);
    }

    // 코드래빗 리뷰(#265): 혼잡 재탐색은 화재 우회와 달리 안전 기능이 아니라 최적화이므로, 층
    // 전체 혼잡 조회가 실패하면 그 불완전한 정보로 기존 PENDING을 취소/대체하지 않고 그대로
    // 건너뛰어야 한다(triggerForFireSpread의 fail-open과 대비되는 fail-closed).
    @Test
    @DisplayName("현재 혼잡 상태 조회가 실패하면 기존 PENDING을 그대로 두고 재탐색을 건너뛴다")
    void trigger_congestionLookupFails_skipsWithoutTouchingExistingPending() {
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existing));
        givenNoApprovedHistory();
        givenNoDirectRoute();
        given(currentCongestionWeightProvider.currentMultipliers(session.getId(), floorId))
                .willThrow(new IllegalStateException("DynamoDB 장애"));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5);

        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet(), any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(any());
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("CROWDED면 CCTV 영향 엣지 모두에 3배 가중치를 주고 후보에 남긴다")
    void trigger_crowded_appliesWeightMultiplierToAllAffectedEdges() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);

        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);
        MapEdge secondEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 4.0, true);
        ReflectionTestUtils.setField(secondEdge, "id", UUID.randomUUID());

        routeRecalculationService.trigger(session, List.of(triggerEdge, secondEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(
                any(), any(), excludedEdgesCaptor.capture(), multipliersCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).isEmpty();
        assertThat(multipliersCaptor.getValue()).containsEntry(triggerEdge.getId(), 3.0);
        assertThat(multipliersCaptor.getValue()).containsEntry(secondEdge.getId(), 3.0);

        verify(routeRecalculationRepository, times(1)).save(any());
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRequestedAfterCommit(saved);
    }

    @Test
    @DisplayName("STARTED/LEVEL_UP 후보 경로가 이미 승인된 활성 경로와 동일하면 새 승인 요청을 만들지 않는다")
    void trigger_skipsWhenCandidateMatchesActiveRoute() {
        givenNoExistingPending();
        UUID sharedNodeId = UUID.randomUUID();
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(sharedNodeId), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", sharedNodeId);
        EvacuationRoute candidateRoute = new EvacuationRoute(List.of(exitNode), 20.0);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(candidateRoute);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5);

        verify(routeRecalculationRepository, never()).save(any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationRequestedAfterCommit(any());
    }

    @Test
    @DisplayName("ENDED인데 승인된 우회 경로가 없고 후보가 직행 경로와 같으면 아무것도 만들지 않는다")
    void trigger_ended_doesNothingWithoutApprovedDetour() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        UUID sameNode = UUID.randomUUID();
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId)).willReturn(routeOf(8.0, sameNode));
        givenEndedCandidate(routeOf(8.0, sameNode));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED이고 승인된 우회 경로가 있으면 복구 후보를 PENDING으로 만든다")
    void trigger_ended_createsRecoveryCandidateWhenApprovedDetourExists() {
        givenNoExistingPending();
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(UUID.randomUUID()), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));
        givenEndedCandidate(routeOf(12.5, UUID.randomUUID()));

        RouteRecalculation saved = pendingRecalculation(CongestionLevel.NORMAL);
        given(routeRecalculationRepository.save(any())).willReturn(saved);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        ArgumentCaptor<RouteRecalculation> savedCaptor = ArgumentCaptor.forClass(RouteRecalculation.class);
        verify(routeRecalculationRepository, times(1)).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTriggerType()).isEqualTo(RecalculationTriggerType.ENDED);
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRequestedAfterCommit(saved);
    }

    @Test
    @DisplayName("ENDED 복구 후보가 현재 활성 경로와 동일하면 새 승인 요청을 만들지 않는다")
    void trigger_ended_skipsWhenRecoveryMatchesActiveRoute() {
        givenNoExistingPending();
        UUID sharedNodeId = UUID.randomUUID();
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(sharedNodeId), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));
        givenEndedCandidate(routeOf(20.0, sharedNodeId));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED 복구 후보는 끝난 CCTV 몫만 뺀 층 전체 혼잡을 가중치로 반영해 계산한다")
    void trigger_ended_recoveryAvoidsEdgesOtherCctvStillCongested() {
        givenNoExistingPending();
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(UUID.randomUUID()), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));
        UUID stillCongestedEdgeId = UUID.randomUUID();
        given(currentCongestionWeightProvider.currentMultipliers(any(), eq(floorId), eq(Set.of("CCTV_001"))))
                .willReturn(Map.of(stillCongestedEdgeId, 3.0));
        givenEndedCandidate(routeOf(12.5, UUID.randomUUID()));
        given(routeRecalculationRepository.save(any())).willReturn(pendingRecalculation(CongestionLevel.NORMAL));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(
                eq(floorId), eq(startNodeId), anySet(), multipliersCaptor.capture());
        assertThat(multipliersCaptor.getValue()).containsExactly(Map.entry(stillCongestedEdgeId, 3.0));
    }

    @Test
    @DisplayName("ENDED여도 이번 후보와 같은 PENDING(다른 CCTV 혼잡으로 생긴 제안)은 남기고 다른 것만 취소한다")
    void trigger_ended_keepsPendingMatchingCandidateAndCancelsOthers() {
        UUID candidateNode = UUID.randomUUID();
        RouteRecalculation stillValid = pendingWithNodes(List.of(candidateNode));
        RouteRecalculation stale = pendingWithNodes(List.of(UUID.randomUUID()));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(stillValid, stale));
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(UUID.randomUUID()), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));
        givenEndedCandidate(routeOf(12.5, candidateNode));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(stillValid.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        assertThat(stale.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED 후보가 현재 활성 경로와 같으면 떠 있던 PENDING은 낡은 제안이므로 취소한다")
    void trigger_ended_cancelsPendingWhenCandidateMatchesActiveRoute() {
        UUID sharedNodeId = UUID.randomUUID();
        RouteRecalculation pending = pendingWithNodes(List.of(UUID.randomUUID()));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(pending));
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(sharedNodeId), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));
        givenEndedCandidate(routeOf(20.0, sharedNodeId));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(pending.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED인데 승인된 우회가 없으면 다른 CCTV 혼잡 PENDING은 남기고, 새 우회 제안은 만들지 않는다")
    void trigger_ended_withoutApprovedDetour_keepsMatchingPendingAndCreatesNothing() {
        UUID candidateNode = UUID.randomUUID();
        RouteRecalculation stillValid = pendingWithNodes(List.of(candidateNode));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(stillValid));
        givenNoApprovedHistory();
        given(evacuationRouteService.findShortestRoute(floorId, startNodeId))
                .willReturn(routeOf(8.0, UUID.randomUUID()));
        givenEndedCandidate(routeOf(12.5, candidateNode));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(stillValid.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("ENDED 중 현재 혼잡 조회가 실패하면 기존 PENDING을 건드리지 않고 건너뛴다")
    void trigger_ended_congestionLookupFails_keepsPendingAndSkips() {
        RouteRecalculation pending = pendingWithNodes(List.of(UUID.randomUUID()));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(pending));
        given(currentCongestionWeightProvider.currentMultipliers(any(), eq(floorId), eq(Set.of("CCTV_001"))))
                .willThrow(new IllegalStateException("DynamoDB 장애"));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(pending.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(routeRecalculationRepository, never()).save(any());
        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet(), any());
    }

    @Test
    @DisplayName("ENDED 후보 경로를 찾을 수 없으면 낡은 PENDING을 취소하고 새로 만들지 않는다")
    void trigger_ended_routeNotFound_cancelsStalePending() {
        RouteRecalculation pending = pendingWithNodes(List.of(UUID.randomUUID()));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(pending));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_001", 1.0);

        assertThat(pending.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("화재 확산이면 화재 구간 엣지는 모두 제외하고, 현재 혼잡 엣지는 가중치로 반영해 우회 경로를 계산한다")
    void triggerForFireSpread_excludesAllAffectedEdges() {
        // [설계 결정 가드 - 이슈 #247] 승인된 경로가 없어 resolveActiveRoute가 화재를 모른 채
        // (givenDirectRouteCrossesTriggerEdge) previous를 계산해도, candidate와 달라 PENDING이
        // 정상적으로 생성되는지 고정한다. resolveActiveRoute를 getCurrentRoute처럼 화재 인지하게
        // "고치면" candidate와 previous가 항상 같아져 아래 save 검증이 깨진다 - 그게 의도다
        // (resolveActiveRoute의 주석 참고: 승인 경로 없는 세션에서 PENDING이 영원히 안 생기는
        // 회귀를 막기 위해 이 부정확함을 의도적으로 유지 중).
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);
        UUID congestedEdgeId = UUID.randomUUID();
        given(currentCongestionWeightProvider.currentMultipliers(any(), eq(floorId)))
                .willReturn(Map.of(congestedEdgeId, 3.0));

        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);
        MapEdge secondEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 4.0, true);
        ReflectionTestUtils.setField(secondEdge, "id", UUID.randomUUID());

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge, secondEdge));

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(
                any(), any(), excludedEdgesCaptor.capture(), multipliersCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).containsExactlyInAnyOrder(triggerEdge.getId(), secondEdge.getId());
        assertThat(multipliersCaptor.getValue()).containsExactly(Map.entry(congestedEdgeId, 3.0));

        ArgumentCaptor<RouteRecalculation> savedCaptor = ArgumentCaptor.forClass(RouteRecalculation.class);
        verify(routeRecalculationRepository, times(1)).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getTriggerType()).isEqualTo(RecalculationTriggerType.FIRE_SPREAD);
        assertThat(savedCaptor.getValue().getCctvCode()).isNull();
        assertThat(savedCaptor.getValue().getCongestionLevel()).isNull();
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRequestedAfterCommit(saved);
    }

    @Test
    @DisplayName("현재 혼잡 상태 조회가 실패해도 화재 우회 후보는 혼잡 가중치 없이 계산해 저장한다")
    void triggerForFireSpread_congestionLookupFails_stillCreatesPendingWithoutWeights() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();
        given(currentCongestionWeightProvider.currentMultipliers(any(), eq(floorId)))
                .willThrow(new IllegalStateException("DynamoDB 장애"));

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willReturn(new EvacuationRoute(List.of(exitNode), 12.5));
        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        ArgumentCaptor<Map<UUID, Double>> multipliersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(evacuationRouteService).findShortestRoute(any(), any(), anySet(), multipliersCaptor.capture());
        assertThat(multipliersCaptor.getValue()).isEmpty();
        verify(routeRecalculationRepository, times(1)).save(any());
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRequestedAfterCommit(saved);
    }

    @Test
    @DisplayName("화재 확산으로 새 우회 후보를 실제로 저장할 때만 기존 PENDING을 무효화한다")
    void triggerForFireSpread_cancelsExistingPendingOnlyWhenSavingNewCandidate() {
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existing));
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        EvacuationRoute route = new EvacuationRoute(List.of(exitNode), 12.5);
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any())).willReturn(route);
        RouteRecalculation saved = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.save(any())).willReturn(saved);

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(trainingEventPublisher).publishRouteRecalculationCancelledAfterCommit(existing);
    }

    @Test
    @DisplayName("화재로 막힌 구간을 피할 경로가 없으면 승인 대기 항목을 만들지 않고, 화재와 무관한 기존 PENDING은 그대로 둔다")
    void triggerForFireSpread_skipsWhenNoDetourRouteFound() {
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existing));
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        // existing의 경로(triggerEdge와 무관한 임의 노드 한 개)는 triggerEdge를 지나지 않으므로 무효화되지 않는다.
        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(trainingEventPublisher, never()).publishRouteRecalculationCancelledAfterCommit(any());
        verify(routeRecalculationRepository, never()).save(any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationRequestedAfterCommit(any());
    }

    @Test
    @DisplayName("우회 경로가 처음 사라지면 세션에 소멸 시각을 남기고 경로 소멸 알림을 보낸다")
    void triggerForFireSpread_noDetourFirstTime_marksBlockedAndPublishes() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));
        given(session.markRouteBlocked(any())).willReturn(true);

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        verify(session).markRouteBlocked(any());
        verify(trainingEventPublisher).publishEvacuationRouteBlockedAfterCommit(session, floorId);
    }

    @Test
    @DisplayName("이미 경로 소멸을 알린 세션이면 다음 틱에서 같은 알림을 다시 보내지 않는다")
    void triggerForFireSpread_noDetourAlreadyBlocked_doesNotPublishAgain() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenDirectRouteCrossesTriggerEdge();
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willThrow(new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND));
        given(session.markRouteBlocked(any())).willReturn(false);

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        verify(trainingEventPublisher, never()).publishEvacuationRouteBlockedAfterCommit(any(), any());
    }

    @Test
    @DisplayName("현재 활성 경로가 화재 구간과 무관하면 우회 경로를 다시 계산하지 않는다")
    void triggerForFireSpread_skipsWhenActiveRouteDoesNotCrossFire() {
        // 승인된 혼잡 우회 경로 D가 활성 상태이고, 그 경로는 이번 화재 엣지(triggerEdge)와 무관하다.
        // (버그 재현: 예전에는 이 경우에도 "화재만 피한 최단 경로"를 새로 계산해 D와 비교했는데,
        // D는 혼잡 우회라 화재-only 최단 경로와 항상 다르게 나와서 화재와 무관한 틱마다 D를
        // 원래의(다시 혼잡한) 직행 경로로 되돌리는 PENDING이 반복 생성됐다.)
        RouteRecalculation existing = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(existing));
        UUID unrelatedNodeId1 = UUID.randomUUID();
        UUID unrelatedNodeId2 = UUID.randomUUID();
        RouteRecalculation approvedDetour = approvedRecalculation(List.of(unrelatedNodeId1, unrelatedNodeId2), 20.0);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                session.getId(), RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approvedDetour));

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.PENDING);
        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet(), any());
        verify(routeRecalculationRepository, never()).save(any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationRequestedAfterCommit(any());
    }

    @Test
    @DisplayName("기존 PENDING이 이번에 새로 화재 구간이 된 엣지를 지나가면, 새 대안을 못 찾아도 즉시 무효화한다")
    void triggerForFireSpread_cancelsPendingCrossingFireEdgeEvenWithoutNewCandidate() {
        UUID crossedFromId = UUID.randomUUID();
        UUID crossedToId = UUID.randomUUID();
        MapNode crossedFrom = mock(MapNode.class);
        MapNode crossedTo = mock(MapNode.class);
        org.mockito.Mockito.lenient().when(crossedFrom.getId()).thenReturn(crossedFromId);
        org.mockito.Mockito.lenient().when(crossedTo.getId()).thenReturn(crossedToId);
        // triggerEdge 자체가 화재 구간 엣지이고, 기존 PENDING의 저장된 경로가 그 두 노드를 연속으로 지난다.
        ReflectionTestUtils.setField(triggerEdge, "fromNode", crossedFrom);
        ReflectionTestUtils.setField(triggerEdge, "toNode", crossedTo);

        RouteRecalculation crossingPending = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_001", RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(crossedFromId, crossedToId), 12.5);
        ReflectionTestUtils.setField(crossingPending, "id", UUID.randomUUID());
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(crossingPending));
        givenNoApprovedHistory();
        givenNoDirectRoute();

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        assertThat(crossingPending.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        verify(trainingEventPublisher).publishRouteRecalculationCancelledAfterCommit(crossingPending);
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("화재 확산 대상 엣지가 없으면 아무 것도 하지 않는다")
    void triggerForFireSpread_skipsWhenNoAffectedEdges() {
        routeRecalculationService.triggerForFireSpread(session, List.of());

        verify(trainingSessionRepository, never()).findByIdForUpdate(any());
        verify(routeRecalculationRepository, never()).save(any());
        verify(trainingEventPublisher, never()).publishRouteRecalculationRequestedAfterCommit(any());
    }

    @Test
    @DisplayName("시나리오에 대표 startNode가 없으면 화재 확산 재탐색도 승인 대기 항목을 만들지 않는다")
    void triggerForFireSpread_noRepresentativeStartNode_doesNothing() {
        given(scenario.getStartNode()).willReturn(null);

        routeRecalculationService.triggerForFireSpread(session, List.of(triggerEdge));

        verify(routeRecalculationRepository, never()).save(any());
        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet(), any());
    }

    @Test
    @DisplayName("훈련 세션의 남은 PENDING을 일괄 CANCELLED 처리한다")
    void cancelAllPendingForSession_cancelsEachPending() {
        RouteRecalculation pendingA = pendingRecalculation(CongestionLevel.CROWDED);
        RouteRecalculation pendingB = pendingRecalculation(CongestionLevel.VERY_CROWDED);
        UUID sessionId = UUID.randomUUID();
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(sessionId, RecalculationStatus.PENDING))
                .willReturn(List.of(pendingA, pendingB));

        routeRecalculationService.cancelAllPendingForSession(sessionId, "훈련 종료로 무효화됨");

        assertThat(pendingA.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        assertThat(pendingB.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        assertThat(pendingA.getCancelReason()).isEqualTo("훈련 종료로 무효화됨");
        verify(trainingEventPublisher, times(2)).publishRouteRecalculationCancelledAfterCommit(any());
    }

    private MapNode nodeWithId(UUID id) {
        MapNode node = MapNode.create(mock(Floor.class), "N", NodeType.STAIR, "N", 0, 0, true);
        ReflectionTestUtils.setField(node, "id", id);
        return node;
    }

    private EvacuationRoute routeOf(double weight, UUID... nodeIds) {
        return new EvacuationRoute(java.util.Arrays.stream(nodeIds).map(this::nodeWithId).toList(), weight);
    }

    // ENDED 후보(층 전체 혼잡 + 화재 반영)로 계산되는 경로.
    private void givenEndedCandidate(EvacuationRoute route) {
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet(), any()))
                .willReturn(route);
    }

    private RouteRecalculation pendingWithNodes(List<UUID> candidateNodeIds) {
        RouteRecalculation recalculation = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_OTHER", RecalculationTriggerType.LEVEL_UP, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, candidateNodeIds, 12.5);
        ReflectionTestUtils.setField(recalculation, "id", UUID.randomUUID());
        return recalculation;
    }

    private RouteRecalculation pendingRecalculation(CongestionLevel level) {
        RouteRecalculation recalculation = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_001", RecalculationTriggerType.STARTED, level, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(UUID.randomUUID()), 12.5);
        ReflectionTestUtils.setField(recalculation, "id", UUID.randomUUID());
        return recalculation;
    }

    private RouteRecalculation approvedRecalculation(List<UUID> nodeIds, double totalWeight) {
        RouteRecalculation recalculation = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_001", RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, nodeIds, totalWeight);
        ReflectionTestUtils.setField(recalculation, "id", UUID.randomUUID());
        recalculation.approve(Instant.now(), mock(User.class));
        return recalculation;
    }

    private RouteRecalculation firePendingRecalculation() {
        RouteRecalculation recalculation = RouteRecalculation.createPendingForFireSpread(
                session, triggerEdge, List.of(UUID.randomUUID()), 10.0, List.of(UUID.randomUUID()), 12.5);
        ReflectionTestUtils.setField(recalculation, "id", UUID.randomUUID());
        return recalculation;
    }

    @Test
    @DisplayName("triggerAsync는 capturedAt이 너무 오래됐으면 엣지를 다시 읽지도 않고 건너뛴다")
    void triggerAsync_skipsWhenCapturedAtTooStale() {
        long staleCapturedAtMs = System.currentTimeMillis() - 30_000L;

        routeRecalculationService.triggerAsync(session, List.of(triggerEdge.getId()), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5, staleCapturedAtMs);

        verify(mapEdgeJpaRepository, never()).findAllById(any());
        verify(trainingSessionRepository, never()).findByIdForUpdate(any());
        verify(routeRecalculationRepository, never()).save(any());
    }

    @Test
    @DisplayName("triggerAsync는 capturedAt이 신선하면 id로 엣지를 다시 읽어 원래 순서로 trigger()에 넘긴다")
    void triggerAsync_reloadsEdgesByIdInOriginalOrderThenDelegates() {
        givenNoExistingPending();
        givenNoApprovedHistory();
        givenNoDirectRoute();

        UUID secondEdgeId = UUID.randomUUID();
        MapEdge secondEdge = MapEdge.create(
                triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 4.0, true);
        ReflectionTestUtils.setField(secondEdge, "id", secondEdgeId);
        // findAllById는 순서를 보장하지 않는다 - 저장소가 요청과 다른 순서로 돌려줘도
        // reloadEdges가 원래 순서로 재정렬해야 대표 엣지(affectedEdges.get(0))가 triggerEdge로
        // 유지된다.
        given(mapEdgeJpaRepository.findAllById(List.of(triggerEdge.getId(), secondEdgeId)))
                .willReturn(List.of(secondEdge, triggerEdge));

        MapNode exitNode = MapNode.create(mock(Floor.class), "STAIR1", NodeType.STAIR, "STAIR1", 0, 0, true);
        ReflectionTestUtils.setField(exitNode, "id", UUID.randomUUID());
        given(evacuationRouteService.findShortestRoute(any(), any(), anySet(), any()))
                .willReturn(new EvacuationRoute(List.of(exitNode), 12.5));
        given(routeRecalculationRepository.save(any())).willReturn(pendingRecalculation(CongestionLevel.CROWDED));

        routeRecalculationService.triggerAsync(session, List.of(triggerEdge.getId(), secondEdgeId),
                CongestionLevel.CROWDED, RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5,
                System.currentTimeMillis());

        verify(trainingSessionRepository).findByIdForUpdate(session.getId());
        ArgumentCaptor<RouteRecalculation> captor = ArgumentCaptor.forClass(RouteRecalculation.class);
        verify(routeRecalculationRepository).save(captor.capture());
        assertThat(captor.getValue().getTriggerEdge()).isEqualTo(triggerEdge);
    }

    @Test
    @DisplayName("존재하지 않는 재탐색 ID로 승인하면 ROUTE_RECALCULATION_NOT_FOUND를 던진다")
    void approve_whenNotFound_throws() {
        UUID recalculationId = UUID.randomUUID();
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculationId, SCHOOL_NAME)).willReturn(Optional.empty());

        assertThatThrownBy(() -> routeRecalculationService.approve(recalculationId, MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", EvacuationErrorCode.ROUTE_RECALCULATION_NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING이 아니면 승인 시 INVALID_RECALCULATION_STATUS_TRANSITION을 던진다")
    void approve_whenNotPending_throws() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        recalculation.approve(Instant.now(), mock(User.class));
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));

        assertThatThrownBy(() -> routeRecalculationService.approve(recalculation.getId(), MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", EvacuationErrorCode.INVALID_RECALCULATION_STATUS_TRANSITION);

        verify(trainingEventPublisher, never()).publishEvacuationRouteUpdatedAfterCommit(any());
    }

    @Test
    @DisplayName("승인하려는 경로가 현재 화재 구간을 지나면 ROUTE_RECALCULATION_CROSSES_FIRE를 던지고, 그 PENDING을 즉시 무효화하며, 유도등은 반영하지 않는다")
    void approve_whenCandidateCrossesCurrentFire_throwsCancelsAndSkipsGuidance() {
        UUID crossedFromId = UUID.randomUUID();
        UUID crossedToId = UUID.randomUUID();
        RouteRecalculation recalculation = RouteRecalculation.createPending(
                session, triggerEdge, "CCTV_001", RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(crossedFromId, crossedToId), 12.5);
        ReflectionTestUtils.setField(recalculation, "id", UUID.randomUUID());
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));

        UUID scenarioId = UUID.randomUUID();
        given(scenario.getId()).willReturn(scenarioId);

        FloorGridCell firedCell = mock(FloorGridCell.class);
        UUID firedCellId = UUID.randomUUID();
        given(firedCell.getId()).willReturn(firedCellId);
        given(floorGridCellRepository.findAllByFloor_IdAndIsFiredTrue(triggerEdge.getFloor().getId()))
                .willReturn(List.of(firedCell));

        FireZone fireZone = mock(FireZone.class);
        given(fireZone.getGridCellId()).willReturn(firedCellId);
        given(fireZoneRepository.findByScenario_IdAndFloor_Id(scenarioId, triggerEdge.getFloor().getId()))
                .willReturn(List.of(fireZone));

        MapEdge firedEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 3.0, true);
        ReflectionTestUtils.setField(firedEdge, "fromNode", withId(crossedFromId));
        ReflectionTestUtils.setField(firedEdge, "toNode", withId(crossedToId));
        MapEdgeGridCell mapping = MapEdgeGridCell.create(firedEdge, firedCell);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(firedCellId))).willReturn(List.of(mapping));

        assertThatThrownBy(() -> routeRecalculationService.approve(recalculation.getId(), MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", EvacuationErrorCode.ROUTE_RECALCULATION_CROSSES_FIRE);

        assertThat(recalculation.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        assertThat(recalculation.getCancelReason()).isNotBlank();
        verify(trainingEventPublisher).publishRouteRecalculationCancelledAfterCommit(recalculation);
        verify(ioTLightService, never()).applyFloorGuidance(any(), any(), any());
        verify(trainingEventPublisher, never()).publishEvacuationRouteUpdatedAfterCommit(any());
    }

    private MapNode withId(UUID id) {
        MapNode node = mock(MapNode.class);
        org.mockito.Mockito.lenient().when(node.getId()).thenReturn(id);
        return node;
    }

    @Test
    @DisplayName("승인자를 찾을 수 없으면 ADMIN_NOT_FOUND를 던진다")
    void approve_whenApproverMissing_throws() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));
        given(userRepository.findByEmail(MANAGER_EMAIL)).willReturn(Optional.empty());

        assertThatThrownBy(() -> routeRecalculationService.approve(recalculation.getId(), MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", TrainingErrorCode.ADMIN_NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING이면 승인 시 상태를 APPROVED로 바꾸고 WS 발행 및 유도등 반영을 한다")
    void approve_whenPending_succeedsAndPublishes() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));
        User manager = mock(User.class);
        org.mockito.Mockito.lenient().when(manager.getUsername()).thenReturn("manager");
        given(userRepository.findByEmail(MANAGER_EMAIL)).willReturn(Optional.of(manager));
        Map<UUID, UUID> nextHops = Map.of(UUID.randomUUID(), UUID.randomUUID());
        given(evacuationRouteService.computeNextHops(eq(floorId), anySet(), anyMap())).willReturn(nextHops);

        RouteRecalculationResponse response = routeRecalculationService.approve(recalculation.getId(), MANAGER_EMAIL);

        assertThat(response.status()).isEqualTo(RecalculationStatus.APPROVED);
        assertThat(recalculation.getStatus()).isEqualTo(RecalculationStatus.APPROVED);
        assertThat(recalculation.getResolvedAt()).isNotNull();
        assertThat(recalculation.getResolvedBy()).isEqualTo(manager);
        verify(trainingEventPublisher, times(1)).publishEvacuationRouteUpdatedAfterCommit(recalculation);
        verify(ioTLightService, times(1)).applyFloorGuidance(
                floorId, recalculation.getRecalculatedNodeIds(), nextHops);
        InOrder approvalOrder = inOrder(routeRecalculationRepository, trainingSessionRepository);
        approvalOrder.verify(routeRecalculationRepository)
                .findTrainingSessionIdByIdAndSchoolName(recalculation.getId(), SCHOOL_NAME);
        approvalOrder.verify(trainingSessionRepository).findByIdForUpdate(session.getId());
        approvalOrder.verify(routeRecalculationRepository)
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(recalculation.getId(), SCHOOL_NAME);
    }

    @Test
    @DisplayName("경로를 승인하면 같은 세션의 다른 PENDING 제안을 취소한다")
    void approve_cancelsSiblingPendingRecalculations() {
        RouteRecalculation approved = pendingRecalculation(CongestionLevel.CROWDED);
        RouteRecalculation sibling = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(approved.getId(), SCHOOL_NAME))
                .willReturn(Optional.of(approved));
        given(routeRecalculationRepository.findAllByTrainingSession_IdAndStatus(
                session.getId(), RecalculationStatus.PENDING)).willReturn(List.of(approved, sibling));
        User manager = mock(User.class);
        given(userRepository.findByEmail(MANAGER_EMAIL)).willReturn(Optional.of(manager));

        routeRecalculationService.approve(approved.getId(), MANAGER_EMAIL);

        assertThat(approved.getStatus()).isEqualTo(RecalculationStatus.APPROVED);
        assertThat(sibling.getStatus()).isEqualTo(RecalculationStatus.CANCELLED);
        assertThat(sibling.getCancelReason()).isEqualTo("다른 경로 승인으로 무효화됨");
        verify(trainingEventPublisher).publishRouteRecalculationCancelledAfterCommit(sibling);
    }

    @Test
    @DisplayName("층 전체 다음 홉 계산이 실패해도 승인은 유지되고 승인 경로만 유도등에 반영한다")
    void approve_whenNextHopComputationFails_stillApprovesWithPathOnlyGuidance() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));
        given(userRepository.findByEmail(MANAGER_EMAIL)).willReturn(Optional.of(mock(User.class)));
        given(evacuationRouteService.computeNextHops(eq(floorId), anySet(), anyMap()))
                .willThrow(new IllegalStateException("boom"));

        routeRecalculationService.approve(recalculation.getId(), MANAGER_EMAIL);

        assertThat(recalculation.getStatus()).isEqualTo(RecalculationStatus.APPROVED);
        verify(ioTLightService).applyFloorGuidance(
                floorId, recalculation.getRecalculatedNodeIds(), Map.of());
    }

    @Test
    @DisplayName("PENDING이 아니면 거절 시 INVALID_RECALCULATION_STATUS_TRANSITION을 던진다")
    void reject_whenNotPending_throws() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        recalculation.reject(Instant.now(), mock(User.class), null);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));

        assertThatThrownBy(() -> routeRecalculationService.reject(recalculation.getId(), MANAGER_EMAIL, "사유"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", EvacuationErrorCode.INVALID_RECALCULATION_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("PENDING이면 거절 시 상태를 REJECTED로 바꾸고 사유를 저장한다")
    void reject_whenPending_succeedsWithoutEvacuationUpdate() {
        RouteRecalculation recalculation = pendingRecalculation(CongestionLevel.CROWDED);
        given(routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(
                        recalculation.getId(), SCHOOL_NAME)).willReturn(Optional.of(recalculation));
        User manager = mock(User.class);
        given(userRepository.findByEmail(MANAGER_EMAIL)).willReturn(Optional.of(manager));

        RouteRecalculationResponse response =
                routeRecalculationService.reject(recalculation.getId(), MANAGER_EMAIL, "현장 확인 결과 통행 가능");

        assertThat(response.status()).isEqualTo(RecalculationStatus.REJECTED);
        assertThat(recalculation.getStatus()).isEqualTo(RecalculationStatus.REJECTED);
        assertThat(recalculation.getRejectReason()).isEqualTo("현장 확인 결과 통행 가능");
        verify(trainingEventPublisher, never()).publishEvacuationRouteUpdatedAfterCommit(any());
        verify(trainingEventPublisher, times(1)).publishRouteRecalculationRejectedAfterCommit(recalculation);
        verify(ioTLightService, never()).applyFloorGuidance(any(), any(), any());
    }

    // === getCurrentRoute ===

    @Test
    @DisplayName("다른 기관의 훈련 세션으로 현재 경로를 조회하면 세션 not-found를 반환한다")
    void getCurrentRoute_otherSchool_throwsTrainingSessionNotFound() {
        UUID otherSessionId = UUID.randomUUID();
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(otherSessionId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> routeRecalculationService.getCurrentRoute(otherSessionId, MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND);
    }

    @Test
    @DisplayName("승인된 재탐색이 있으면 그 경로를 노드 상세와 함께 현재 경로로 반환한다")
    void getCurrentRoute_withApprovedRecalculation_returnsApprovedRoute() {
        UUID recSessionId = session.getId();
        UUID scenarioId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getBuildingId()).willReturn(buildingId);

        MapNode stairNode = mock(MapNode.class);
        UUID stairNodeId = UUID.randomUUID();
        given(stairNode.getId()).willReturn(stairNodeId);
        given(stairNode.getName()).willReturn("서측 계단");
        given(stairNode.getType()).willReturn(NodeType.STAIR);
        given(stairNode.getX()).willReturn(0.5);
        given(stairNode.getY()).willReturn(0.4);

        MapNode exitNode = mock(MapNode.class);
        UUID exitNodeId = UUID.randomUUID();
        given(exitNode.getId()).willReturn(exitNodeId);
        given(exitNode.getName()).willReturn("1층 출입구");
        given(exitNode.getType()).willReturn(NodeType.EXIT);
        given(exitNode.getX()).willReturn(0.8);
        given(exitNode.getY()).willReturn(0.3);

        List<UUID> recalculatedIds = List.of(startNodeId, stairNodeId, exitNodeId);
        RouteRecalculation approved = approvedRecalculation(recalculatedIds, 18.3);
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.of(approved));
        // findAllById가 저장 순서를 보장하지 않는다는 걸 검증하기 위해 일부러 뒤섞어 반환한다.
        given(mapNodeJpaRepository.findAllById(recalculatedIds))
                .willReturn(List.of(exitNode, representativeStart, stairNode));

        CurrentRouteResponse response = routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL);

        assertThat(response.sessionId()).isEqualTo(recSessionId);
        assertThat(response.scenarioId()).isEqualTo(scenarioId);
        assertThat(response.buildingId()).isEqualTo(buildingId);
        assertThat(response.floorId()).isEqualTo(floorId);
        assertThat(response.startNodeId()).isEqualTo(startNodeId);
        assertThat(response.source()).isEqualTo(CurrentRouteResponse.RouteSource.RECALCULATED);
        assertThat(response.path()).extracting(CurrentRouteResponse.NodePoint::nodeId)
                .containsExactly(startNodeId, stairNodeId, exitNodeId);
        assertThat(response.path().get(1).name()).isEqualTo("서측 계단");
        assertThat(response.path().get(2).type()).isEqualTo(NodeType.EXIT);
        assertThat(response.totalWeight()).isEqualTo(18.3);
        assertThat(response.updatedAt()).isEqualTo(approved.getResolvedAt());
    }

    @Test
    @DisplayName("SCHEDULED 세션에 승인된 재탐색이 없으면 대표 START 기준 INITIAL 경로를 반환한다")
    void getCurrentRoute_scheduledWithoutApprovedRecalculation_returnsInitialRoute() {
        UUID recSessionId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        TrainingSession scheduledSession = TrainingSession.schedule(mock(User.class), scenario);
        ReflectionTestUtils.setField(scheduledSession, "id", recSessionId);
        ReflectionTestUtils.setField(scheduledSession, "createdAt", createdAt);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(scheduledSession));
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(scenario.getId()).willReturn(scenarioId);
        given(scenario.getBuildingId()).willReturn(buildingId);

        MapNode exitNode = mock(MapNode.class);
        UUID exitNodeId = UUID.randomUUID();
        given(exitNode.getId()).willReturn(exitNodeId);
        given(exitNode.getName()).willReturn("1층 출입구");
        given(exitNode.getType()).willReturn(NodeType.EXIT);
        given(exitNode.getX()).willReturn(0.8);
        given(exitNode.getY()).willReturn(0.3);

        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(representativeStart, exitNode), 9.5));

        CurrentRouteResponse response = routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL);

        assertThat(response.sessionId()).isEqualTo(recSessionId);
        assertThat(response.scenarioId()).isEqualTo(scenarioId);
        assertThat(response.buildingId()).isEqualTo(buildingId);
        assertThat(response.floorId()).isEqualTo(floorId);
        assertThat(response.startNodeId()).isEqualTo(startNodeId);
        assertThat(response.source()).isEqualTo(CurrentRouteResponse.RouteSource.INITIAL);
        assertThat(response.path()).extracting(CurrentRouteResponse.NodePoint::nodeId)
                .containsExactly(startNodeId, exitNodeId);
        assertThat(response.totalWeight()).isEqualTo(9.5);
        assertThat(response.updatedAt()).isEqualTo(createdAt);
        assertThat(scheduledSession.getStatus()).isEqualTo(TrainingStatus.SCHEDULED);
        assertThat(scheduledSession.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("진행 중인 세션에 승인된 재탐색이 없으면 INITIAL 경로 조회도 현재 화재 구간을 제외하고 계산한다")
    void getCurrentRoute_runningWithoutApprovedRecalculation_excludesCurrentlyFiredEdges() {
        UUID recSessionId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        TrainingSession scheduledSession = TrainingSession.schedule(mock(User.class), scenario);
        scheduledSession.start(createdAt);
        ReflectionTestUtils.setField(scheduledSession, "id", recSessionId);
        ReflectionTestUtils.setField(scheduledSession, "createdAt", createdAt);
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(scheduledSession));
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(scenario.getId()).willReturn(scenarioId);

        FloorGridCell firedCell = mock(FloorGridCell.class);
        UUID firedCellId = UUID.randomUUID();
        given(firedCell.getId()).willReturn(firedCellId);
        given(floorGridCellRepository.findAllByFloor_IdAndIsFiredTrue(floorId)).willReturn(List.of(firedCell));

        FireZone fireZone = mock(FireZone.class);
        given(fireZone.getGridCellId()).willReturn(firedCellId);
        given(fireZoneRepository.findByScenario_IdAndFloor_Id(scenarioId, floorId)).willReturn(List.of(fireZone));

        MapEdge firedEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 3.0, true);
        UUID firedEdgeId = UUID.randomUUID();
        ReflectionTestUtils.setField(firedEdge, "id", firedEdgeId);
        MapEdgeGridCell mapping = MapEdgeGridCell.create(firedEdge, firedCell);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(firedCellId))).willReturn(List.of(mapping));

        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(representativeStart), 5.0));

        routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(evacuationRouteService).findShortestRoute(eq(floorId), eq(startNodeId), excludedEdgesCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).contains(firedEdgeId);
    }

    @Test
    @DisplayName("시작 전(SCHEDULED) 세션은 isFired가 아직 꺼져 있어도 시나리오의 최초 발화점 구간을 제외한다")
    void getCurrentRoute_scheduled_excludesScenarioOriginEdgesEvenIfNotFiredYet() {
        UUID recSessionId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        TrainingSession scheduledSession = TrainingSession.schedule(mock(User.class), scenario);
        ReflectionTestUtils.setField(scheduledSession, "id", recSessionId);
        ReflectionTestUtils.setField(scheduledSession, "createdAt", Instant.now());
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(scheduledSession));
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(scenario.getId()).willReturn(scenarioId);

        FloorGridCell originCell = mock(FloorGridCell.class);
        UUID originCellId = UUID.randomUUID();
        FireZone origin = mock(FireZone.class);
        given(origin.getFloorId()).willReturn(floorId);
        given(origin.getGridCellId()).willReturn(originCellId);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of(origin));

        MapEdge originEdge = MapEdge.create(triggerEdge.getFloor(), mock(MapNode.class), mock(MapNode.class), 3.0, true);
        UUID originEdgeId = UUID.randomUUID();
        ReflectionTestUtils.setField(originEdge, "id", originEdgeId);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(originCellId)))
                .willReturn(List.of(MapEdgeGridCell.create(originEdge, originCell)));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(representativeStart), 5.0));

        routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(evacuationRouteService).findShortestRoute(eq(floorId), eq(startNodeId), excludedEdgesCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).containsExactly(originEdgeId);
        // 시작 전에는 동적 화재 상태(isFired)를 보지 않는다.
        verify(floorGridCellRepository, never()).findAllByFloor_IdAndIsFiredTrue(any());
    }

    @Test
    @DisplayName("시작 전(SCHEDULED) 세션에서 다른 층의 발화점은 제외 대상에 섞이지 않는다")
    void getCurrentRoute_scheduled_ignoresOriginOnOtherFloor() {
        UUID recSessionId = UUID.randomUUID();
        UUID scenarioId = UUID.randomUUID();
        TrainingSession scheduledSession = TrainingSession.schedule(mock(User.class), scenario);
        ReflectionTestUtils.setField(scheduledSession, "id", recSessionId);
        ReflectionTestUtils.setField(scheduledSession, "createdAt", Instant.now());
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(scheduledSession));
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(scenario.getId()).willReturn(scenarioId);

        FireZone otherFloorOrigin = mock(FireZone.class);
        given(otherFloorOrigin.getFloorId()).willReturn(UUID.randomUUID());
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(otherFloorOrigin));
        given(evacuationRouteService.findShortestRoute(eq(floorId), eq(startNodeId), anySet()))
                .willReturn(new EvacuationRoute(List.of(representativeStart), 5.0));

        routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL);

        ArgumentCaptor<Set<UUID>> excludedEdgesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(evacuationRouteService).findShortestRoute(eq(floorId), eq(startNodeId), excludedEdgesCaptor.capture());
        assertThat(excludedEdgesCaptor.getValue()).isEmpty();
        verify(mapEdgeGridCellRepository, never()).findAllByGridCell_IdIn(any());
    }

    @Test
    @DisplayName("승인된 재탐색도 없고 대표 startNode도 없으면 START_NODE_NOT_CONFIGURED를 던진다")
    void getCurrentRoute_noApprovedAndNoStartNode_throws() {
        UUID recSessionId = session.getId();
        given(trainingSessionRepository.findByIdAndScenario_Building_SchoolName(recSessionId, SCHOOL_NAME))
                .willReturn(Optional.of(session));
        given(routeRecalculationRepository.findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                recSessionId, RecalculationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(scenario.getStartNode()).willReturn(null);

        assertThatThrownBy(() -> routeRecalculationService.getCurrentRoute(recSessionId, MANAGER_EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("시나리오에 대표 startNode가 없으면 재탐색 승인 대기 항목을 만들지 않는다")
    void trigger_noRepresentativeStartNode_doesNothing() {
        // startNode 검사가 기존 PENDING 조회/취소보다 앞이라 PENDING 조회 자체가 일어나지 않는다.
        given(scenario.getStartNode()).willReturn(null);

        routeRecalculationService.trigger(session, List.of(triggerEdge), CongestionLevel.CROWDED,
                RecalculationTriggerType.STARTED, "CCTV_001", 3.5);

        verify(routeRecalculationRepository, never())
                .findAllByTrainingSession_IdAndStatus(any(), any());
        verify(routeRecalculationRepository, never()).save(any());
        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet(), any());
    }
}
