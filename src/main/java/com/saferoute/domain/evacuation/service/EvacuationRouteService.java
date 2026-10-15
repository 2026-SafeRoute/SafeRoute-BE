package com.saferoute.domain.evacuation.service;

import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.FloorErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 경로 탐색은 읽기 전용이라 되돌릴 변경이 없다. 그런데 기본 설정이면 "경로 없음"(EVAC005) 같은
// ApiException이 이 프록시를 통과하면서 호출부 트랜잭션을 rollback-only로 만들어, 호출부가 예외를
// 잡고 정상 진행해도 커밋 시점에 UnexpectedRollbackException이 난다 - 화재 우회 재탐색은 우회 경로가
// 없을 때 이 때문에 PENDING 무효화와 경로 소멸 기록까지 통째로 롤백되고 있었다. 그래서 ApiException은
// 롤백 대상에서 제외한다 (computeNextHops와 같은 이유).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class EvacuationRouteService {

    // TODO: 혼잡도 가중치 계수 - DynamoDB 혼잡도 연동 인터페이스 확정 후 담당 팀원과 조율
    private static final double CONGESTION_WEIGHT = 1.0; // α

    // 우선순위 큐에서 꺼내는 순서. 거리가 같으면 nodeId 순으로 고정한다.
    // 거리만으로 비교하면 거리가 같은 노드는 힙에 들어간 순서, 즉 인접 리스트 순서(=정렬 없는
    // findEdgesByFloor() 결과 순서)대로 꺼내져서, 길이가 같은 경로나 출구가 둘 이상일 때 같은
    // 입력에서도 호출마다 다른 경로가 나온다. 후보 경로가 기존 PENDING과 같은지 비교해 취소/재생성을
    // 결정하는 RouteRecalculationService(#265, #270)는 같은 입력이면 같은 결과가 나온다고 전제하므로,
    // 흔들리면 실제 상황은 그대로인데 PENDING이 취소와 재생성을 반복한다. findShortestRoute와
    // computeNextHops가 서로 다른 규칙을 쓰지 않도록 상수 하나를 공유한다.
    private static final Comparator<NodeDistance> QUEUE_ORDER =
            Comparator.comparingDouble(NodeDistance::distance).thenComparing(NodeDistance::nodeId);

    private final MapGraphRepository mapGraphRepository;
    private final FloorRepository floorRepository;
    private final SchoolContextService schoolContextService;

    // 시작 노드에서 가장 가까운 EXIT 대상 노드까지 최단 경로 계산 (mock data 기준, congestion은 0 고정)
    public EvacuationRoute findShortestRoute(UUID floorId, UUID startNodeId) {
        return findShortestRoute(floorId, startNodeId, Set.of());
    }

    public EvacuationRoute findShortestRoute(UUID floorId, UUID startNodeId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        if (floorRepository.findByIdAndBuilding_SchoolName(floorId, schoolName).isEmpty()) {
            throw new ApiException(FloorErrorCode.FLOOR_NOT_FOUND);
        }
        return findShortestRoute(floorId, startNodeId);
    }

    // 혼잡 재탐색용 - 지정된 엣지를 그래프에서 제외하고 우회 경로를 계산한다 (VERY_CROWDED처럼 완전히 막힌 경우).
    // 레벨별로 페널티만 주고 여전히 후보에 남기고 싶다면 weightMultipliers를 쓰는 4-인자 오버로드를 사용한다.
    public EvacuationRoute findShortestRoute(UUID floorId, UUID startNodeId, Set<UUID> excludedEdgeIds) {
        return findShortestRoute(floorId, startNodeId, excludedEdgeIds, Map.of());
    }

    // 혼잡 단계별로 특정 엣지의 가중치에 배율을 적용한다 (CAUTION ×1.5, CROWDED ×3.0). 
    // 배율이 없는 엣지는 1.0을 적용한 것과 같다. 
    // VERY_CROWDED처럼 아예 후보에서 빼야 하는 경우는 배율이 아니라 excludedEdgeIds로 처리한다 
    // -> 배율만으로는 그래프가 다른 대안이 없을 때 여전히 그 엣지를 통과하는 경로를 고를 수 있기 때문이다.
    public EvacuationRoute findShortestRoute(
            UUID floorId, UUID startNodeId, Set<UUID> excludedEdgeIds, Map<UUID, Double> weightMultipliers
    ) {
        return findShortestRoute(floorId, startNodeId, excludedEdgeIds, weightMultipliers, FireProximity.NONE);
    }

    // 화재 근접 위험 가중치까지 반영한다(FireProximity 참고). 불붙은 셀은 여전히 excludedEdgeIds로 제외하고,
    // 곧 불붙을 셀을 지나는 엣지는 "그 엣지를 지나는 예상 시점"과 "예상 발화 시점"을 비교해 배율을 준다.
    // 예상 시점은 시작 노드부터 그 경로로 걸은 실제 거리(m)를 보행 속도로 나눠 구한다 - 비용(가중치 합)이
    // 아니라 실제 거리를 따로 누적해야 혼잡 배율 때문에 도착 시점이 부풀려지지 않는다.
    // 비용이 도착 시점에 따라 달라지므로 엄밀한 최단 경로 보장은 없지만(시간 의존 비용), 꺼내는 순서는
    // QUEUE_ORDER로 고정돼 있어 같은 입력이면 항상 같은 경로가 나온다.
    public EvacuationRoute findShortestRoute(
            UUID floorId, UUID startNodeId, Set<UUID> excludedEdgeIds, Map<UUID, Double> weightMultipliers,
            FireProximity fireProximity
    ) {
        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        List<MapEdge> edges = mapGraphRepository.findEdgesByFloor(floorId).stream()
                .filter(edge -> !excludedEdgeIds.contains(edge.getId()))
                .toList();

        Map<UUID, MapNode> nodeById = new HashMap<>();
        for (MapNode node : nodes) {
            nodeById.put(node.getId(), node);
        }
        if (!nodeById.containsKey(startNodeId)) {
            throw new ApiException(EvacuationErrorCode.MAP_NODE_NOT_FOUND);
        }

        // 층에 EXIT 대상 노드가 하나도 없는 경우 - "도달 불가"(EVAC005)와 구분되는 별도 원인이므로 먼저 검증
        if (nodes.stream().noneMatch(MapNode::isExitTarget)) {
            throw new ApiException(EvacuationErrorCode.EXIT_NODE_NOT_DESIGNATED);
        }

        Map<UUID, List<MapEdge>> adjacency = buildAdjacencyList(edges);

        Map<UUID, Double> distance = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        Map<UUID, Double> walkedMeters = new HashMap<>();
        distance.put(startNodeId, 0.0);
        walkedMeters.put(startNodeId, 0.0);

        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(QUEUE_ORDER);
        queue.add(new NodeDistance(startNodeId, 0.0));
        Set<UUID> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();
            if (!visited.add(current.nodeId())) {
                continue;
            }

            MapNode currentNode = nodeById.get(current.nodeId());
            if (currentNode.isExitTarget()) {
                return buildRoute(nodeById, previous, distance, current.nodeId());
            }

            for (MapEdge edge : adjacency.getOrDefault(current.nodeId(), Collections.emptyList())) {
                // TODO: blocked 상태는 더 이상 MapEdge 컬럼이 아니라 훈련별 서버 메모리에서 관리됨 -
                // 런타임 blocked 데이터 소스 연동 확정 후 여기서 제외 처리 추가 필요
                UUID neighbor = edge.getToNode().getId().equals(current.nodeId())
                        ? edge.getFromNode().getId()
                        : edge.getToNode().getId();

                double walked = walkedMeters.get(current.nodeId()) + edge.getDistance();
                // 엣지를 다 지나는 시점 기준 - 들어가는 시점보다 늦으므로 위험을 더 보수적으로 본다.
                double arrivalSec = walked / FireProximity.WALKING_SPEED_MPS;
                double newDistance = distance.get(current.nodeId())
                        + calculateWeight(edge, weightMultipliers, fireProximity, arrivalSec);
                if (newDistance < distance.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    distance.put(neighbor, newDistance);
                    walkedMeters.put(neighbor, walked);
                    previous.put(neighbor, current.nodeId());
                    queue.add(new NodeDistance(neighbor, newDistance));
                }
            }
        }

        throw new ApiException(EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND);
    }

    // 층의 모든 노드에 대해 "가장 가까운 EXIT 대상으로 가려면 다음에 갈 노드(next hop)"를 계산한다.
    // 대표 시작점 하나의 경로만으로는 그 경로 밖에 있는 사람(과 유도등)이 어느 쪽으로 가야 하는지
    // 알 수 없으므로, EXIT 대상들을 출발점으로 한 역방향 다중 출발 Dijkstra를 한 번 돌려 층 전체를
    // 한꺼번에 구한다. 비용은 findShortestRoute 한 번과 같다.
    // - EXIT 대상 노드 자신과 EXIT에 도달할 수 없는 노드는 결과 맵의 키에 없다.
    // - 이 결과는 유도등 안내라는 부가 기능에서만 쓰이므로(승인/훈련 시작이 막히면 안 된다),
    //   EXIT 대상이 하나도 없어도 findShortestRoute처럼 예외를 던지지 않고 빈 맵을 돌려준다.
    // - 가중치는 정방향 경로와 비용 정의가 같아야 하므로 calculateWeight를 그대로 쓴다.
    // - 호출부(approve()/start())가 이 메서드의 실패를 잡아 다음 홉 없이 계속 진행한다. 예외가 이 프록시를
    //   통과하면서 호출부 트랜잭션을 rollback-only로 만들어버리면, 호출부가 예외를 삼켜도 커밋 시점에
    //   UnexpectedRollbackException이 나서 승인/훈련 시작이 통째로 롤백된다 - 읽기 전용이라 되돌릴
    //   변경도 없으므로 롤백 대상에서 제외한다 (CurrentCongestionWeightProvider와 같은 이유).
    @Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
    public Map<UUID, UUID> computeNextHops(
            UUID floorId, Set<UUID> excludedEdgeIds, Map<UUID, Double> weightMultipliers
    ) {
        return computeNextHops(floorId, excludedEdgeIds, weightMultipliers, FireProximity.NONE);
    }

    // 화재 근접 위험 가중치까지 반영한 다음 홉. 출발점이 층 전체라 엣지 도착 시점을 알 수 없으므로
    // 도착 시점 비교 없이 확산 세대만으로 배율을 준다(FireProximity.multiplier의 arrivalSec = null).
    @Transactional(readOnly = true, noRollbackFor = RuntimeException.class)
    public Map<UUID, UUID> computeNextHops(
            UUID floorId, Set<UUID> excludedEdgeIds, Map<UUID, Double> weightMultipliers,
            FireProximity fireProximity
    ) {
        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        List<MapEdge> edges = mapGraphRepository.findEdgesByFloor(floorId).stream()
                .filter(edge -> !excludedEdgeIds.contains(edge.getId()))
                .toList();

        Map<UUID, Double> distance = new HashMap<>();
        Map<UUID, UUID> nextHop = new HashMap<>();
        // 동점일 때 유도등 방향이 호출마다 바뀌면 안 되므로 findShortestRoute와 같은 QUEUE_ORDER를 쓴다.
        PriorityQueue<NodeDistance> queue = new PriorityQueue<>(QUEUE_ORDER);
        for (MapNode node : nodes) {
            if (node.isExitTarget()) {
                distance.put(node.getId(), 0.0);
                queue.add(new NodeDistance(node.getId(), 0.0));
            }
        }
        if (queue.isEmpty()) {
            return nextHop;
        }

        Map<UUID, List<MapEdge>> incoming = buildIncomingAdjacencyList(edges);
        Set<UUID> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            NodeDistance current = queue.poll();
            if (!visited.add(current.nodeId())) {
                continue;
            }
            // current로 들어오는 방향으로 이동할 수 있는 엣지의 반대편 노드가 이동 출발점(predecessor)이다.
            for (MapEdge edge : incoming.getOrDefault(current.nodeId(), Collections.emptyList())) {
                UUID predecessor = edge.getToNode().getId().equals(current.nodeId())
                        ? edge.getFromNode().getId()
                        : edge.getToNode().getId();
                double newDistance = current.distance()
                        + calculateWeight(edge, weightMultipliers, fireProximity, null);
                if (newDistance < distance.getOrDefault(predecessor, Double.MAX_VALUE)) {
                    distance.put(predecessor, newDistance);
                    nextHop.put(predecessor, current.nodeId());
                    queue.add(new NodeDistance(predecessor, newDistance));
                }
            }
        }
        return nextHop;
    }

    // buildAdjacencyList의 역방향 버전: "이 노드로 들어올 수 있는 엣지"를 노드별로 등록한다.
    // 이동 가능 조건은 정방향과 같다 - 모든 엣지는 from -> to로 이동할 수 있고, bidirectional이면
    // to -> from도 가능하다. 그래서 toNode 쪽에는 항상, fromNode 쪽에는 양방향일 때만 등록한다.
    private Map<UUID, List<MapEdge>> buildIncomingAdjacencyList(List<MapEdge> edges) {
        Map<UUID, List<MapEdge>> incoming = new HashMap<>();
        for (MapEdge edge : edges) {
            incoming.computeIfAbsent(edge.getToNode().getId(), k -> new ArrayList<>()).add(edge);
            if (edge.isBidirectional()) {
                incoming.computeIfAbsent(edge.getFromNode().getId(), k -> new ArrayList<>()).add(edge);
            }
        }
        return incoming;
    }

    // bidirectional 엣지만 양방향 인접 리스트에 등록, 단방향 엣지는 fromNode -> toNode 방향으로만 등록
    private Map<UUID, List<MapEdge>> buildAdjacencyList(List<MapEdge> edges) {
        Map<UUID, List<MapEdge>> adjacency = new HashMap<>();
        for (MapEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFromNode().getId(), k -> new ArrayList<>()).add(edge);
            if (edge.isBidirectional()) {
                adjacency.computeIfAbsent(edge.getToNode().getId(), k -> new ArrayList<>()).add(edge);
            }
        }
        return adjacency;
    }

    // weight = (distance + α×congestion) × 혼잡 배율 × 화재 근접 배율
    // TODO: congestion 현재 0 고정 - 혼잡도(DynamoDB) 데이터 연동 확정 후 반영.
    // weightMultipliers는 RouteRecalculationService가 혼잡 구간에 "경로 혼잡 비용"(CAUTION ×1.5,
    // CROWDED ×3.0, VERY_CROWDED ×10)을 적용하기 위해 넘기는 값이다.
    // 혼잡 배율과 화재 근접 배율이 겹치면 max가 아니라 곱한다. 두 위험은 서로 독립적이고 겹치면 더 나빠진다 -
    // 혼잡하면 걸음이 느려져 불 옆에 머무는 시간이 길어지고, 불 옆이면 혼잡한 구간에서 빠져나갈 여유가 줄어든다.
    // max로 합치면 "혼잡한 불 옆 복도"와 "혼잡하기만 한 복도"의 비용이 같아져 둘 중 하나를 고를 때 화재를
    // 전혀 고려하지 못한다.
    private double calculateWeight(MapEdge edge, Map<UUID, Double> weightMultipliers,
            FireProximity fireProximity, Double arrivalSec) {
        double congestion = 0.0;
        double baseWeight = edge.getDistance() + CONGESTION_WEIGHT * congestion;
        return baseWeight
                * weightMultipliers.getOrDefault(edge.getId(), 1.0)
                * fireProximity.multiplier(edge.getId(), arrivalSec);
    }

    private EvacuationRoute buildRoute(Map<UUID, MapNode> nodeById, Map<UUID, UUID> previous,
            Map<UUID, Double> distance, UUID exitNodeId) {
        List<MapNode> path = new ArrayList<>();
        UUID current = exitNodeId;
        while (current != null) {
            path.add(nodeById.get(current));
            current = previous.get(current);
        }
        Collections.reverse(path);
        return new EvacuationRoute(path, distance.get(exitNodeId));
    }

    private record NodeDistance(UUID nodeId, double distance) {
    }
}
