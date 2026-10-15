package com.saferoute.domain.evacuation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
class EvacuationRouteServiceTest {

    @InjectMocks
    private EvacuationRouteService evacuationRouteService;

    @Mock
    private MapGraphRepository mapGraphRepository;

    private final UUID floorId = UUID.randomUUID();
    private Floor floor;

    // 테스트용 그래프:
    // ROOM1 -- DOOR1 -- HALLWAY1 -- STAIR1(EXIT, 우회 경로)
    //                       |
    //                   HALLWAY2 -- STAIR2(EXIT, 최단 경로)
    private MapNode room1;
    private MapNode door1;
    private MapNode hallway1;
    private MapNode hallway2;
    private MapNode stair1;
    private MapNode stair2;

    @BeforeEach
    void setUp() {
        floor = mock(Floor.class);

        room1 = createNode("ROOM1", NodeType.ROOM, false);
        door1 = createNode("DOOR1", NodeType.DOOR, false);
        hallway1 = createNode("HALLWAY1", NodeType.HALLWAY, false);
        hallway2 = createNode("HALLWAY2", NodeType.HALLWAY, false);
        stair1 = createNode("STAIR1", NodeType.STAIR, true);
        stair2 = createNode("STAIR2", NodeType.STAIR, true);
    }

    private MapNode createNode(String code, NodeType type, boolean isExitTarget) {
        MapNode node = MapNode.create(floor, code, type, code, 0, 0, isExitTarget);
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    private MapEdge createEdge(MapNode from, MapNode to, double distance) {
        MapEdge edge = MapEdge.create(floor, from, to, distance, true);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        return edge;
    }

    @Test
    @DisplayName("여러 EXIT 후보 중 가장 가까운 경로를 반환한다")
    void findShortestRoute_picksNearestExit() {
        // given
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10); // 멀리 있는 EXIT
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 가까운 EXIT

        given(mapGraphRepository.findNodesByFloor(floorId))
                .willReturn(List.of(room1, door1, hallway1, hallway2, stair1, stair2));
        given(mapGraphRepository.findEdgesByFloor(floorId))
                .willReturn(List.of(e1, e2, e3, e4, e5));

        // when
        EvacuationRoute route = evacuationRouteService.findShortestRoute(floorId, room1.getId());

        // then
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 3 + 2);
        assertThat(route.path()).containsExactly(room1, door1, hallway1, hallway2, stair2);
    }

    @Test
    @DisplayName("제외된 엣지를 우회해서 다른 EXIT으로 경로를 계산한다")
    void findShortestRoute_withExcludedEdges_detours() {
        // given
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10); // 우회로 (먼 EXIT)
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 원래 최단 경로에 쓰이는 엣지 - 이번엔 제외

        given(mapGraphRepository.findNodesByFloor(floorId))
                .willReturn(List.of(room1, door1, hallway1, hallway2, stair1, stair2));
        given(mapGraphRepository.findEdgesByFloor(floorId))
                .willReturn(List.of(e1, e2, e3, e4, e5));

        // when
        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), java.util.Set.of(e5.getId()));

        // then
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 10);
        assertThat(route.path()).containsExactly(room1, door1, hallway1, stair1);
    }

    @Test
    @DisplayName("배율이 낮으면 제외가 아니라서 여전히 그 엣지를 통과하되 가중치만 반영한다")
    void findShortestRoute_withLowWeightMultiplier_stillUsesEdgeWithPenalty() {
        // given
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // CAUTION 배율(1.5) 적용 대상

        given(mapGraphRepository.findNodesByFloor(floorId))
                .willReturn(List.of(room1, door1, hallway1, hallway2, stair1, stair2));
        given(mapGraphRepository.findEdgesByFloor(floorId))
                .willReturn(List.of(e1, e2, e3, e4, e5));

        // when
        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), java.util.Set.of(), java.util.Map.of(e5.getId(), 1.5));

        // then: 2+5+3+(2*1.5) = 13, 대안(2+5+10=17)보다 여전히 저렴해서 e5를 그대로 씀
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 3 + (2 * 1.5));
        assertThat(route.path()).containsExactly(room1, door1, hallway1, hallway2, stair2);
    }

    @Test
    @DisplayName("배율이 충분히 크면 제외 없이도 다른 대안 경로를 고른다")
    void findShortestRoute_withHighWeightMultiplier_choosesAlternative() {
        // given
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // CROWDED 배율(3.0)로도 부족해서 더 크게 줌

        given(mapGraphRepository.findNodesByFloor(floorId))
                .willReturn(List.of(room1, door1, hallway1, hallway2, stair1, stair2));
        given(mapGraphRepository.findEdgesByFloor(floorId))
                .willReturn(List.of(e1, e2, e3, e4, e5));

        // when
        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), java.util.Set.of(), java.util.Map.of(e5.getId(), 10.0));

        // then: 2+5+3+(2*10)=30 > 대안(2+5+10=17)이라 우회 경로(stair1)를 선택
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 10);
        assertThat(route.path()).containsExactly(room1, door1, hallway1, stair1);
    }

    @Test
    @DisplayName("도달 가능한 EXIT이 없으면 예외를 던진다")
    void findShortestRoute_noReachableExit_throws() {
        // given
        MapNode isolatedRoom = createNode("ISOLATED", NodeType.ROOM, false);
        MapNode exitOnly = createNode("EXIT_ONLY", NodeType.STAIR, true);
        // 두 노드 사이 엣지 없음 -> 도달 불가

        given(mapGraphRepository.findNodesByFloor(floorId))
                .willReturn(List.of(isolatedRoom, exitOnly));
        given(mapGraphRepository.findEdgesByFloor(floorId))
                .willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> evacuationRouteService.findShortestRoute(floorId, isolatedRoom.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("층에 지정된 EXIT 노드가 없으면 예외를 던진다")
    void findShortestRoute_noExitDesignated_throws() {
        // given
        MapNode isolatedRoom = createNode("ISOLATED", NodeType.ROOM, false);

        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(List.of(isolatedRoom));
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> evacuationRouteService.findShortestRoute(floorId, isolatedRoom.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.EXIT_NODE_NOT_DESIGNATED.getMessage());
    }

    @Test
    @DisplayName("시작 노드가 해당 층에 없으면 예외를 던진다")
    void findShortestRoute_startNodeNotInFloor_throws() {
        // given
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(List.of(room1));
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(List.of());

        UUID unknownNodeId = UUID.randomUUID();

        // when & then
        assertThatThrownBy(() -> evacuationRouteService.findShortestRoute(floorId, unknownNodeId))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.MAP_NODE_NOT_FOUND.getMessage());
    }

    // === computeNextHops ===

    private MapEdge createOneWayEdge(MapNode from, MapNode to, double distance) {
        MapEdge edge = MapEdge.create(floor, from, to, distance, false);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        return edge;
    }

    private void givenGraph(List<MapNode> nodes, List<MapEdge> edges) {
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(nodes);
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(edges);
    }

    @Test
    @DisplayName("일자 그래프에서 각 노드의 다음 홉은 출구 방향이고 EXIT 노드는 키에 없다")
    void computeNextHops_straightGraph() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, stair1, 3);
        givenGraph(List.of(room1, door1, stair1), List.of(e1, e2));

        Map<UUID, UUID> nextHops = evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of());

        assertThat(nextHops).containsOnly(
                Map.entry(room1.getId(), door1.getId()),
                Map.entry(door1.getId(), stair1.getId()));
    }

    @Test
    @DisplayName("출구가 둘이면 각 노드는 더 가까운 출구 쪽 다음 홉을 갖는다")
    void computeNextHops_multipleExits_pickNearest() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10); // 먼 EXIT
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 가까운 EXIT
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));

        Map<UUID, UUID> nextHops = evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of());

        assertThat(nextHops).containsOnly(
                Map.entry(room1.getId(), door1.getId()),
                Map.entry(door1.getId(), hallway1.getId()),
                Map.entry(hallway1.getId(), hallway2.getId()),
                Map.entry(hallway2.getId(), stair2.getId()));
    }

    @Test
    @DisplayName("단방향 엣지는 이동 가능한 방향으로만 다음 홉에 반영된다")
    void computeNextHops_respectsEdgeDirection() {
        // hallway1 -> hallway2 단방향이므로 hallway2의 다음 홉이 hallway1이 될 수 없다.
        MapEdge e1 = createOneWayEdge(hallway1, hallway2, 1);
        MapEdge e2 = createEdge(hallway2, stair1, 4);
        // stair1 -> room1 단방향이라 room1은 EXIT으로 나갈 수 없다(역방향 이동 불가).
        MapEdge e3 = createOneWayEdge(stair1, room1, 1);
        givenGraph(List.of(room1, hallway1, hallway2, stair1), List.of(e1, e2, e3));

        Map<UUID, UUID> nextHops = evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of());

        assertThat(nextHops).containsOnly(
                Map.entry(hallway1.getId(), hallway2.getId()),
                Map.entry(hallway2.getId(), stair1.getId()));
    }

    @Test
    @DisplayName("제외된 엣지로 끊긴 노드는 키에 없고 나머지는 우회한 다음 홉을 갖는다")
    void computeNextHops_excludedEdges() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));

        // 가까운 출구로 가는 e5를 막으면 hallway2는 hallway1을 거쳐 먼 출구로 간다.
        Map<UUID, UUID> detour = evacuationRouteService.computeNextHops(floorId, Set.of(e5.getId()), Map.of());
        assertThat(detour.get(hallway1.getId())).isEqualTo(stair1.getId());
        assertThat(detour.get(hallway2.getId())).isEqualTo(hallway1.getId());

        // e2까지 막으면 room1/door1은 EXIT에 닿을 수 없다.
        Map<UUID, UUID> cutOff = evacuationRouteService.computeNextHops(
                floorId, Set.of(e2.getId()), Map.of());
        assertThat(cutOff).doesNotContainKeys(room1.getId(), door1.getId());
        assertThat(cutOff).containsKey(hallway1.getId());
    }

    @Test
    @DisplayName("짧은 쪽 엣지에 큰 배율을 주면 다른 출구 쪽으로 돌아간다")
    void computeNextHops_weightMultipliers() {
        MapEdge e1 = createEdge(hallway1, stair1, 10);
        MapEdge e2 = createEdge(hallway1, hallway2, 3);
        MapEdge e3 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3));

        Map<UUID, UUID> plain = evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of());
        assertThat(plain.get(hallway1.getId())).isEqualTo(hallway2.getId());

        Map<UUID, UUID> penalized = evacuationRouteService.computeNextHops(
                floorId, Set.of(), Map.of(e3.getId(), 10.0));
        assertThat(penalized.get(hallway1.getId())).isEqualTo(stair1.getId());
    }

    @Test
    @DisplayName("EXIT 대상이 없으면 예외 없이 빈 맵을 반환한다")
    void computeNextHops_noExit_returnsEmpty() {
        MapEdge e1 = createEdge(room1, door1, 2);
        givenGraph(List.of(room1, door1), List.of(e1));

        assertThat(evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("동점이어도 입력 순서와 무관하게 항상 같은 다음 홉을 반환한다")
    void computeNextHops_tieIsDeterministic() {
        MapEdge toStair1 = createEdge(room1, stair1, 5);
        MapEdge toStair2 = createEdge(room1, stair2, 5);
        UUID expected = stair1.getId().compareTo(stair2.getId()) < 0 ? stair1.getId() : stair2.getId();

        for (int i = 0; i < 20; i++) {
            List<MapNode> nodes = new ArrayList<>(List.of(room1, stair1, stair2));
            List<MapEdge> edges = new ArrayList<>(List.of(toStair1, toStair2));
            Collections.shuffle(nodes);
            Collections.shuffle(edges);
            givenGraph(nodes, edges);

            assertThat(evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of()))
                    .containsEntry(room1.getId(), expected);
        }
    }

    // === 동점 타이브레이크 ===
    // findEdgesByFloor()는 정렬이 없어 DB가 돌려주는 순서가 호출마다 달라질 수 있다. 같은 엣지를
    // 정순/역순으로 돌려주는 두 번의 호출이 같은 경로를 내는지로 그 비결정성을 재현한다.

    private EvacuationRoute findRouteWithEdgeOrder(
            List<MapNode> nodes, List<MapEdge> edges, MapNode start, boolean reversed) {
        List<MapEdge> ordered = new ArrayList<>(edges);
        if (reversed) {
            Collections.reverse(ordered);
        }
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(nodes);
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(ordered);
        return evacuationRouteService.findShortestRoute(floorId, start.getId());
    }

    @Test
    @DisplayName("길이가 같은 경로가 둘이어도 엣지 조회 순서와 무관하게 같은 경로를 반환한다")
    void findShortestRoute_tiedPaths_returnsSameRouteRegardlessOfEdgeOrder() {
        // room1 -> hallway1 -> stair1, room1 -> hallway2 -> stair1: 두 경로 모두 길이 2
        MapEdge e1 = createEdge(room1, hallway1, 1);
        MapEdge e2 = createEdge(room1, hallway2, 1);
        MapEdge e3 = createEdge(hallway1, stair1, 1);
        MapEdge e4 = createEdge(hallway2, stair1, 1);
        List<MapNode> nodes = List.of(room1, hallway1, hallway2, stair1);
        List<MapEdge> edges = List.of(e1, e2, e3, e4);

        EvacuationRoute forward = findRouteWithEdgeOrder(nodes, edges, room1, false);
        EvacuationRoute backward = findRouteWithEdgeOrder(nodes, edges, room1, true);

        assertThat(backward.path()).isEqualTo(forward.path());
        // 거리가 같으면 nodeId가 작은 노드를 먼저 꺼내므로 그 노드가 경로를 차지한다.
        MapNode expectedMiddle = hallway1.getId().compareTo(hallway2.getId()) < 0 ? hallway1 : hallway2;
        assertThat(forward.path()).containsExactly(room1, expectedMiddle, stair1);
    }

    @Test
    @DisplayName("거리가 같은 EXIT이 둘이어도 엣지 조회 순서와 무관하게 같은 EXIT을 고른다")
    void findShortestRoute_tiedExits_picksSameExitRegardlessOfEdgeOrder() {
        MapEdge toStair1 = createEdge(room1, stair1, 3);
        MapEdge toStair2 = createEdge(room1, stair2, 3);
        List<MapNode> nodes = List.of(room1, stair1, stair2);
        List<MapEdge> edges = List.of(toStair1, toStair2);

        EvacuationRoute forward = findRouteWithEdgeOrder(nodes, edges, room1, false);
        EvacuationRoute backward = findRouteWithEdgeOrder(nodes, edges, room1, true);

        assertThat(backward.path()).isEqualTo(forward.path());
        MapNode expectedExit = stair1.getId().compareTo(stair2.getId()) < 0 ? stair1 : stair2;
        assertThat(forward.path()).containsExactly(room1, expectedExit);
    }

    // === 화재 근접 위험 가중치 ===
    // 기본 그래프(setUp 주석)에서 화재가 없으면 STAIR2 경로(2+5+3+2=12)가 STAIR1 경로(2+5+10=17)보다 짧다.

    private static final double MEDIUM_INTERVAL_SEC = 15.0;
    private static final double FAST_INTERVAL_SEC = 5.0;

    @Test
    @DisplayName("다음 세대에 불이 번질 복도는 페널티를 받아, 조금 더 먼 경로를 고른다")
    void findShortestRoute_fireProximity_avoidsCorridorNextToFire() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 불 바로 옆(1세대) - 도착 전에 불붙는다 ×5
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 1), MEDIUM_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(), fireProximity);

        // STAIR2 쪽은 2+5+3+2×5=20이 되어 STAIR1 쪽(17)이 더 싸다.
        assertThat(route.path()).containsExactly(room1, door1, hallway1, stair1);
        assertThat(route.totalWeight()).isEqualTo(17.0);
    }

    @Test
    @DisplayName("불 옆 구간만 남은 경로도 제외가 아니라 페널티라서 여전히 선택된다")
    void findShortestRoute_fireProximity_onlyPathNearFire_stillSelected() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10); // 불붙은 셀 - 제외
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 불 바로 옆 - 페널티만
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 1), MEDIUM_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(e3.getId()), Map.of(), fireProximity);

        assertThat(route.path()).containsExactly(room1, door1, hallway1, hallway2, stair2);
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 3 + 2 * 5.0);
    }

    @Test
    @DisplayName("불붙은 셀의 엣지는 근접 가중치와 무관하게 여전히 제외된다 - 근접 페널티가 큰 우회로를 대신 고른다")
    void findShortestRoute_fireProximity_firedEdgeStillExcluded() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10); // 불 바로 옆 - ×5
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2); // 불붙은 셀 - 근접 정보에 같이 들어 있어도 제외
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e3.getId(), 1, e5.getId(), 1), MEDIUM_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(e5.getId()), Map.of(), fireProximity);

        assertThat(route.path()).containsExactly(room1, door1, hallway1, stair1);
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 10 * 5.0);
    }

    @Test
    @DisplayName("불붙은 셀로 모든 출구가 막히면 근접 가중치가 있어도 경로 없음 예외를 던진다")
    void findShortestRoute_fireProximity_allExitsFired_throws() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e4.getId(), 1), MEDIUM_INTERVAL_SEC);

        assertThatThrownBy(() -> evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(e3.getId(), e5.getId()), Map.of(), fireProximity))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("2세대 구간은 도착 전에 불이 붙지 않으면 작은 페널티(×2)만 받아 그대로 지나간다")
    void findShortestRoute_fireProximity_secondGeneration_arrivesBeforeIgnition() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        // e5를 다 지나는 시점: 12m / 1.2m/s = 10초 < 예상 발화 15초(보통 속도 1세대 뒤)
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 2), MEDIUM_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(), fireProximity);

        assertThat(route.path()).containsExactly(room1, door1, hallway1, hallway2, stair2);
        assertThat(route.totalWeight()).isEqualTo(2 + 5 + 3 + 2 * 2.0);
    }

    @Test
    @DisplayName("2세대 구간이라도 확산이 빨라 도착 전에 불이 붙으면 큰 페널티(×5)를 받아 피한다")
    void findShortestRoute_fireProximity_secondGeneration_ignitesBeforeArrival() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        // e5를 다 지나는 시점 10초 >= 예상 발화 5초(빠른 속도 1세대 뒤)
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 2), FAST_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(), fireProximity);

        assertThat(route.path()).containsExactly(room1, door1, hallway1, stair1);
        assertThat(route.totalWeight()).isEqualTo(17.0);
    }

    @Test
    @DisplayName("혼잡 배율과 화재 근접 배율이 겹치면 곱해서 반영한다")
    void findShortestRoute_fireProximity_multipliesWithCongestion() {
        MapEdge e1 = createEdge(room1, door1, 2);
        MapEdge e2 = createEdge(door1, hallway1, 5);
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(room1, door1, hallway1, hallway2, stair1, stair2), List.of(e1, e2, e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 2), MEDIUM_INTERVAL_SEC);

        EvacuationRoute route = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(e5.getId(), 1.5), fireProximity);

        // e5 = 2 × 1.5(CAUTION) × 2(불 옆) = 6 -> 2+5+3+6 = 16 (max였다면 2×2 = 4 -> 14)
        assertThat(route.path()).containsExactly(room1, door1, hallway1, hallway2, stair2);
        assertThat(route.totalWeight()).isEqualTo(16.0);
    }

    @Test
    @DisplayName("화재 근접 가중치가 있어도 엣지 조회 순서와 무관하게 같은 경로를 반환한다")
    void findShortestRoute_fireProximity_deterministicRegardlessOfEdgeOrder() {
        // room1 -> hallway1 -> door1 와 room1 -> hallway2 -> door1 은 비용이 같지만(4+1, 2×2+1)
        // 실제로 걸은 거리가 달라(5m, 3m) 그 뒤 2세대 구간(door1 -> stair1)의 도착 시점 판정이 갈린다.
        MapEdge toHallway1 = createEdge(room1, hallway1, 4);
        MapEdge toHallway2 = createEdge(room1, hallway2, 2);
        MapEdge hallway1ToDoor = createEdge(hallway1, door1, 1);
        MapEdge hallway2ToDoor = createEdge(hallway2, door1, 1);
        MapEdge doorToStair = createEdge(door1, stair1, 3);
        List<MapNode> nodes = List.of(room1, hallway1, hallway2, door1, stair1);
        List<MapEdge> edges = List.of(toHallway1, toHallway2, hallway1ToDoor, hallway2ToDoor, doorToStair);
        FireProximity fireProximity = new FireProximity(
                Map.of(toHallway2.getId(), 2, doorToStair.getId(), 2), 6.0);

        List<MapEdge> reversed = new ArrayList<>(edges);
        Collections.reverse(reversed);
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(nodes);
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(edges, reversed);

        EvacuationRoute forward = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(), fireProximity);
        EvacuationRoute backward = evacuationRouteService.findShortestRoute(
                floorId, room1.getId(), Set.of(), Map.of(), fireProximity);

        assertThat(backward.path()).isEqualTo(forward.path());
        assertThat(backward.totalWeight()).isEqualTo(forward.totalWeight());
    }

    @Test
    @DisplayName("층 전체 다음 홉도 불 바로 옆 구간을 피하는 방향을 가리킨다")
    void computeNextHops_fireProximity_avoidsCorridorNextToFire() {
        MapEdge e3 = createEdge(hallway1, stair1, 10);
        MapEdge e4 = createEdge(hallway1, hallway2, 3);
        MapEdge e5 = createEdge(hallway2, stair2, 2);
        givenGraph(List.of(hallway1, hallway2, stair1, stair2), List.of(e3, e4, e5));
        FireProximity fireProximity = new FireProximity(Map.of(e5.getId(), 1), MEDIUM_INTERVAL_SEC);

        Map<UUID, UUID> withoutFire = evacuationRouteService.computeNextHops(floorId, Set.of(), Map.of());
        Map<UUID, UUID> withFire = evacuationRouteService.computeNextHops(
                floorId, Set.of(), Map.of(), fireProximity);

        assertThat(withoutFire).containsEntry(hallway1.getId(), hallway2.getId());
        // hallway2 경유는 3 + 2×5 = 13 > 직행 10
        assertThat(withFire).containsEntry(hallway1.getId(), stair1.getId());
        // 다른 길이 더 비싸면(3+10=13 > 2×5=10) 불 옆이어도 그 길을 가리킨다 - 제외가 아니라 페널티
        assertThat(withFire).containsEntry(hallway2.getId(), stair2.getId());
    }
}
