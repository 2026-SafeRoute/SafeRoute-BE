package com.saferoute.domain.evacuation.diagnosis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.saferoute.domain.evacuation.diagnosis.dto.FloorRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.dto.FloorRouteDiagnosisResponse.StartCandidate;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse.EventType;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RouteDiagnosisServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @Mock
    private MapGraphRepository mapGraphRepository;
    @Mock
    private FloorRepository floorRepository;
    @Mock
    private TrainingScenarioRepository scenarioRepository;
    @Mock
    private FireZoneRepository fireZoneRepository;
    @Mock
    private FloorGridCellRepository floorGridCellRepository;
    @Mock
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;
    @Mock
    private SchoolContextService schoolContextService;

    private RouteDiagnosisService routeDiagnosisService;

    private final UUID floorId = UUID.randomUUID();
    private Floor floor;

    @BeforeEach
    void setUp() {
        floor = mock(Floor.class);
        org.mockito.Mockito.lenient().when(floor.getId()).thenReturn(floorId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        // 경로 탐색은 실제 EvacuationRouteService를 써야 시뮬레이션이 실제 재탐색과 같은 경로를 고르는지 확인할 수 있다.
        EvacuationRouteService evacuationRouteService =
                new EvacuationRouteService(mapGraphRepository, floorRepository, schoolContextService);
        routeDiagnosisService = new RouteDiagnosisService(mapGraphRepository, floorRepository, scenarioRepository,
                fireZoneRepository, floorGridCellRepository, mapEdgeGridCellRepository, evacuationRouteService,
                schoolContextService);
    }

    private MapNode node(String code, NodeType type, boolean isExitTarget) {
        MapNode node = MapNode.create(floor, code, type, code, 0, 0, isExitTarget);
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    private MapEdge edge(MapNode from, MapNode to, double distance, boolean bidirectional) {
        MapEdge edge = MapEdge.create(floor, from, to, distance, bidirectional);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        return edge;
    }

    private void givenGraph(List<MapNode> nodes, List<MapEdge> edges) {
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(nodes);
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(edges);
    }

    @Nested
    @DisplayName("층 진단")
    class FloorDiagnosis {

        @BeforeEach
        void givenFloorOfSchool() {
            org.mockito.Mockito.lenient().when(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME))
                    .thenReturn(Optional.of(floor));
        }

        @Test
        @DisplayName("다른 학교 층이면 FLOOR_NOT_FOUND")
        void otherSchoolFloor_throwsNotFound() {
            given(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME)).willReturn(Optional.empty());

            assertThatThrownBy(() -> routeDiagnosisService.diagnoseFloor(floorId, EMAIL))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(FloorErrorCode.FLOOR_NOT_FOUND);
        }

        @Test
        @DisplayName("출구와 끊긴 노드를 찾고, CCTV·유도등 노드는 끊겨 있어도 제외한다")
        void findsUnreachableNodesExceptCustom() {
            MapNode start = node("S", NodeType.START, false);
            MapNode hall = node("H", NodeType.HALLWAY, false);
            MapNode exit = node("E", NodeType.STAIR, true);
            MapNode isolated = node("X", NodeType.HALLWAY, false);
            MapNode cctv = node("C", NodeType.CUSTOM, false);
            givenGraph(List.of(start, hall, exit, isolated, cctv),
                    List.of(edge(start, hall, 1, true), edge(hall, exit, 1, true)));

            FloorRouteDiagnosisResponse response = routeDiagnosisService.diagnoseFloor(floorId, EMAIL);

            assertThat(response.unreachableNodeIds()).containsExactly(isolated.getId());
            assertThat(response.startCandidates()).containsExactly(new StartCandidate(start.getId(), 1));
            assertThat(response.ready()).isFalse();
        }

        @Test
        @DisplayName("모든 시작 후보가 탈출구 2곳에 닿고 끊긴 노드가 없으면 ready")
        void allCandidatesReachTwoExits_ready() {
            MapNode start = node("S", NodeType.START, false);
            MapNode door = node("D", NodeType.DOOR, false);
            door.changeStartCandidate(true);
            MapNode plainDoor = node("P", NodeType.DOOR, false);
            MapNode exitLeft = node("E1", NodeType.STAIR, true);
            MapNode exitRight = node("E2", NodeType.EXIT, true);
            givenGraph(List.of(start, door, plainDoor, exitLeft, exitRight), List.of(
                    edge(exitLeft, start, 1, true), edge(start, door, 1, true),
                    edge(door, exitRight, 1, true), edge(plainDoor, door, 1, true)));

            FloorRouteDiagnosisResponse response = routeDiagnosisService.diagnoseFloor(floorId, EMAIL);

            assertThat(response.unreachableNodeIds()).isEmpty();
            // 시작 후보가 아닌 DOOR(plainDoor)는 목록에 없다.
            assertThat(response.startCandidates()).containsExactly(
                    new StartCandidate(start.getId(), 2), new StartCandidate(door.getId(), 2));
            assertThat(response.ready()).isTrue();
        }

        @Test
        @DisplayName("단방향 엣지는 반대 방향으로 지나갈 수 없다")
        void oneWayEdgeRespectsDirection() {
            MapNode start = node("S", NodeType.START, false);
            MapNode exit = node("E", NodeType.STAIR, true);
            // 출구 -> 시작 방향으로만 갈 수 있으므로 시작에서 출구로는 못 간다.
            givenGraph(List.of(start, exit), List.of(edge(exit, start, 1, false)));

            FloorRouteDiagnosisResponse response = routeDiagnosisService.diagnoseFloor(floorId, EMAIL);

            assertThat(response.unreachableNodeIds()).containsExactly(start.getId());
            assertThat(response.startCandidates()).containsExactly(new StartCandidate(start.getId(), 0));
        }

        @Test
        @DisplayName("탈출구가 하나도 없으면 ready가 아니고 모든 경로 노드가 끊긴 노드다")
        void noExit_notReady() {
            MapNode start = node("S", NodeType.START, false);
            MapNode hall = node("H", NodeType.HALLWAY, false);
            givenGraph(List.of(start, hall), List.of(edge(start, hall, 1, true)));

            FloorRouteDiagnosisResponse response = routeDiagnosisService.diagnoseFloor(floorId, EMAIL);

            assertThat(response.ready()).isFalse();
            assertThat(response.unreachableNodeIds()).containsExactlyInAnyOrder(start.getId(), hall.getId());
        }
    }

    @Nested
    @DisplayName("시나리오 진단 (화재 확산 시뮬레이션)")
    class ScenarioDiagnosis {

        private final UUID scenarioId = UUID.randomUUID();
        private final List<FloorGridCell> cells = new ArrayList<>();
        private final List<MapEdgeGridCell> mappings = new ArrayList<>();

        // 테스트용 도면 (한 줄짜리 격자, FAST = 5초마다 한 칸):
        //   격자 열:   0    1(발화)  2         3    4        5    6        7    8
        //   엣지:          A-E1(왼쪽 출구)   S-A           S-B           B-E2(오른쪽 출구)
        //   그래프: E1 - A - S - B - E2  (S가 시작 지점, 왼쪽 출구가 가까움)
        private MapNode start;
        private MapNode hallLeft;
        private MapNode exitLeft;
        private MapNode hallRight;
        private MapNode exitRight;
        private MapEdge hallLeftToExitLeft;
        private MapEdge startToHallLeft;
        private MapEdge startToHallRight;
        private MapEdge hallRightToExitRight;

        @BeforeEach
        void setUpFloor() {
            start = node("S", NodeType.START, false);
            hallLeft = node("A", NodeType.HALLWAY, false);
            exitLeft = node("E1", NodeType.STAIR, true);
            hallRight = node("B", NodeType.HALLWAY, false);
            exitRight = node("E2", NodeType.STAIR, true);
            hallLeftToExitLeft = edge(hallLeft, exitLeft, 1, true);
            startToHallLeft = edge(start, hallLeft, 1, true);
            startToHallRight = edge(start, hallRight, 5, true);
            hallRightToExitRight = edge(hallRight, exitRight, 5, true);
            for (int column = 0; column <= 10; column++) {
                FloorGridCell cell = FloorGridCell.create(floor, 0, column, true, column, 0);
                ReflectionTestUtils.setField(cell, "id", UUID.randomUUID());
                cells.add(cell);
            }
            map(hallLeftToExitLeft, 2);
            map(startToHallLeft, 4);
            map(startToHallRight, 6);
            map(hallRightToExitRight, 8);
        }

        private void map(MapEdge edge, int column) {
            mappings.add(MapEdgeGridCell.create(edge, cells.get(column)));
        }

        private void givenScenario(int fireOriginColumn, List<MapEdge> edges) {
            TrainingScenario scenario = mock(TrainingScenario.class);
            given(scenario.getStartNode()).willReturn(start);
            given(scenario.getFireSpreadSpeed()).willReturn(FireSpreadSpeed.FAST);
            given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                    .willReturn(Optional.of(scenario));
            FireZone origin = mock(FireZone.class);
            given(origin.getFloorId()).willReturn(floorId);
            given(origin.getGridCellId()).willReturn(cells.get(fireOriginColumn).getId());
            given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of(origin));
            given(floorGridCellRepository.findAllByFloor_Id(floorId)).willReturn(cells);
            org.mockito.Mockito.lenient().when(mapEdgeGridCellRepository.findAllByGridCell_IdIn(anyList()))
                    .thenAnswer(invocation -> {
                        Set<UUID> ids = Set.copyOf(invocation.<List<UUID>>getArgument(0));
                        return mappings.stream()
                                .filter(mapping -> ids.contains(mapping.getGridCell().getId()))
                                .filter(mapping -> edges.contains(mapping.getMapEdge()))
                                .toList();
                    });
            givenGraph(List.of(start, hallLeft, exitLeft, hallRight, exitRight), edges);
        }

        private List<UUID> ids(MapNode... nodes) {
            return java.util.Arrays.stream(nodes).map(MapNode::getId).toList();
        }

        @Test
        @DisplayName("불이 안내 경로에 닿으면 반대쪽 출구로 재탐색되고, 그쪽도 막히면 그 시점을 막힘으로 기록한다")
        void recalculatesThenBlocks() {
            givenScenario(1, List.of(hallLeftToExitLeft, startToHallLeft, startToHallRight, hallRightToExitRight));

            ScenarioRouteDiagnosisResponse response = routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL);

            assertThat(response.initialRouteNodeIds()).isEqualTo(ids(start, hallLeft, exitLeft));
            assertThat(response.tickSeconds()).isEqualTo(5);
            assertThat(response.simulatedUntilSeconds()).isEqualTo(600);
            // 1세대(5초): 2열(A-E1) 발화 -> 오른쪽 출구로 재탐색. 5세대(25초): 6열(S-B) 발화 -> 우회로 없음.
            assertThat(response.events()).extracting(ScenarioRouteDiagnosisResponse.Event::type)
                    .containsExactly(EventType.RECALCULATED, EventType.BLOCKED);
            assertThat(response.events().get(0).afterSeconds()).isEqualTo(5);
            assertThat(response.events().get(0).routeNodeIds()).isEqualTo(ids(start, hallRight, exitRight));
            assertThat(response.recalculationCount()).isEqualTo(1);
            assertThat(response.blockedAfterSeconds()).isEqualTo(25L);
            assertThat(response.hasAlternativeRoute()).isTrue();
        }

        @Test
        @DisplayName("불이 경로에 닿자마자 돌아갈 길이 없으면 재탐색 없이 막히고 대체 경로 없음으로 판단한다")
        void blocksWithoutRecalculation() {
            givenScenario(1, List.of(hallLeftToExitLeft, startToHallLeft));

            ScenarioRouteDiagnosisResponse response = routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL);

            assertThat(response.events()).hasSize(1);
            assertThat(response.events().get(0).type()).isEqualTo(EventType.BLOCKED);
            assertThat(response.events().get(0).afterSeconds()).isEqualTo(5);
            assertThat(response.recalculationCount()).isZero();
            assertThat(response.blockedAfterSeconds()).isEqualTo(5L);
            assertThat(response.hasAlternativeRoute()).isFalse();
        }

        @Test
        @DisplayName("불이 걸을 수 없는 셀에 막혀 번지지 않으면 이벤트가 없고 문제 없음으로 본다")
        void fireDoesNotSpread_noEvents() {
            ReflectionTestUtils.setField(cells.get(9), "walkable", false);
            givenScenario(10, List.of(hallLeftToExitLeft, startToHallLeft));

            ScenarioRouteDiagnosisResponse response = routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL);

            assertThat(response.events()).isEmpty();
            assertThat(response.recalculationCount()).isZero();
            assertThat(response.blockedAfterSeconds()).isNull();
            assertThat(response.hasAlternativeRoute()).isTrue();
        }

        @Test
        @DisplayName("다른 학교 시나리오면 TRAINING_SCENARIO_NOT_FOUND")
        void otherSchoolScenario_throwsNotFound() {
            given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME)).willReturn(Optional.empty());

            assertThatThrownBy(() -> routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND);
        }

        @Test
        @DisplayName("시작 지점이 없으면 훈련 시작과 같은 START_NODE_NOT_CONFIGURED")
        void noStartNode_throwsSameErrorAsStart() {
            TrainingScenario scenario = mock(TrainingScenario.class);
            given(scenario.getStartNode()).willReturn(null);
            given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                    .willReturn(Optional.of(scenario));

            assertThatThrownBy(() -> routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("발화 위치가 없으면 훈련 시작과 같은 FIRE_ORIGIN_NOT_CONFIGURED")
        void noFireOrigin_throwsSameErrorAsStart() {
            TrainingScenario scenario = mock(TrainingScenario.class);
            given(scenario.getStartNode()).willReturn(start);
            given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                    .willReturn(Optional.of(scenario));
            given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of());

            assertThatThrownBy(() -> routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(TrainingErrorCode.FIRE_ORIGIN_NOT_CONFIGURED);
        }

        @Test
        @DisplayName("발화 위치가 시작 지점과 다른 층이면 훈련 시작과 같은 FIRE_ORIGIN_START_FLOOR_MISMATCH")
        void fireOriginOnOtherFloor_throwsSameErrorAsStart() {
            TrainingScenario scenario = mock(TrainingScenario.class);
            given(scenario.getStartNode()).willReturn(start);
            given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                    .willReturn(Optional.of(scenario));
            FireZone origin = mock(FireZone.class);
            given(origin.getFloorId()).willReturn(UUID.randomUUID());
            given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of(origin));

            assertThatThrownBy(() -> routeDiagnosisService.diagnoseScenario(scenarioId, EMAIL))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode())
                    .isEqualTo(TrainingErrorCode.FIRE_ORIGIN_START_FLOOR_MISMATCH);
        }
    }
}
