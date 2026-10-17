package com.saferoute.domain.evacuation.diagnosis.service;

import com.saferoute.domain.evacuation.diagnosis.dto.FloorRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse.Event;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse.EventType;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.service.EvacuationRoute;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.evacuation.service.FireProximity;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.service.TrainingSessionService;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 경로 연결 진단 (#289). 읽기 전용이고 DB에 아무것도 쓰지 않는다.
// - 층 진단: 도면 관리 화면용. 시작 지점이 없으므로 모든 시작 후보를 기준으로 그래프 연결만 본다.
// - 시나리오 진단: 시나리오 화면용. 실제 훈련과 같은 규칙으로 화재 확산과 재탐색을 메모리에서 미리 돌려본다.
//   기존 재탐색·화재 확산 코드를 바꾸지 않기 위해 그 규칙을 여기서 따라 하며, 따라 하는 지점마다
//   원본 위치를 주석으로 남겨둔다. 원본 규칙이 바뀌면 여기도 같이 바꿔야 한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class RouteDiagnosisService {

    // 엣지-셀 매핑을 셀 id IN 절로 읽을 때 한 번에 넘기는 id 수 (층 셀이 수천 개라 나눠서 읽는다).
    private static final int CELL_ID_CHUNK_SIZE = 1000;

    private final MapGraphRepository mapGraphRepository;
    private final FloorRepository floorRepository;
    private final TrainingScenarioRepository scenarioRepository;
    private final FireZoneRepository fireZoneRepository;
    private final FloorGridCellRepository floorGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final EvacuationRouteService evacuationRouteService;
    private final SchoolContextService schoolContextService;

    public FloorRouteDiagnosisResponse diagnoseFloor(UUID floorId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        if (floorRepository.findByIdAndBuilding_SchoolName(floorId, schoolName).isEmpty()) {
            throw new ApiException(FloorErrorCode.FLOOR_NOT_FOUND);
        }

        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        List<MapEdge> edges = mapGraphRepository.findEdgesByFloor(floorId);

        Map<UUID, List<UUID>> forward = adjacency(edges, Set.of(), false);
        Map<UUID, List<UUID>> backward = adjacency(edges, Set.of(), true);
        Set<UUID> exitIds = exitIds(nodes);

        // 탈출구에서 거꾸로 따라가 닿는 노드 = 탈출구까지 갈 수 있는 노드.
        // CCTV·유도등 위치 노드(CUSTOM)는 대피 경로 노드가 아니라 엣지에 안 붙어 있어도 정상이므로 빼고 본다.
        Set<UUID> canReachExit = reachableFrom(exitIds, backward);
        List<UUID> unreachableNodeIds = nodes.stream()
                .filter(node -> node.getType() != NodeType.CUSTOM)
                .map(MapNode::getId)
                .filter(id -> !canReachExit.contains(id))
                .toList();

        List<FloorRouteDiagnosisResponse.StartCandidate> startCandidates = new ArrayList<>();
        for (MapNode node : nodes) {
            if (!isStartCandidate(node)) {
                continue;
            }
            Set<UUID> reached = reachableFrom(Set.of(node.getId()), forward);
            int reachableExitCount = (int) reached.stream().filter(exitIds::contains).count();
            startCandidates.add(new FloorRouteDiagnosisResponse.StartCandidate(node.getId(), reachableExitCount));
        }

        boolean ready = !exitIds.isEmpty()
                && unreachableNodeIds.isEmpty()
                && startCandidates.stream().allMatch(candidate -> candidate.reachableExitCount() >= 2);
        return new FloorRouteDiagnosisResponse(
                floorId, ready, unreachableNodeIds, undesignatedStairNodeIds(nodes), startCandidates);
    }

    public ScenarioRouteDiagnosisResponse diagnoseScenario(UUID scenarioId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingScenario scenario = scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));

        // 아래 세 가지 검사는 TrainingSessionService.start()와 같은 조건·같은 오류 코드다 - 진단이 통과했는데
        // 시작이 막히거나 그 반대가 되지 않게 한다.
        MapNode startNode = scenario.getStartNode();
        if (startNode == null) {
            throw new ApiException(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
        }
        List<FireZone> fireOrigins = fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId);
        if (fireOrigins.isEmpty()) {
            throw new ApiException(TrainingErrorCode.FIRE_ORIGIN_NOT_CONFIGURED);
        }
        UUID floorId = startNode.getFloor().getId();
        if (fireOrigins.stream().anyMatch(origin -> !floorId.equals(origin.getFloorId()))) {
            throw new ApiException(TrainingErrorCode.FIRE_ORIGIN_START_FLOOR_MISMATCH);
        }
        UUID startNodeId = startNode.getId();

        long tickSeconds = scenario.getFireSpreadSpeed().tickInterval().toSeconds();
        long simulatedUntilSeconds = TrainingSessionService.TRAINING_TIMEOUT.toSeconds();
        int maxGeneration = (int) (simulatedUntilSeconds / tickSeconds);

        Map<UUID, Integer> generationByCellId = simulateFireGenerations(floorId, fireOrigins, maxGeneration);
        Map<UUID, Integer> generationByEdgeId = edgeGenerations(generationByCellId);
        Map<UUID, MapEdge> edgeById = new HashMap<>();
        for (MapEdge edge : mapGraphRepository.findEdgesByFloor(floorId)) {
            edgeById.put(edge.getId(), edge);
        }

        // 최초 안내 경로 - TrainingSessionService.start()처럼 발화점 구간만 빼고 계산한다.
        // 출구 미지정·도달 불가면 시작도 같은 오류로 막히므로 예외를 그대로 돌려준다.
        Set<UUID> originEdgeIds = edgesUpTo(generationByEdgeId, 0);
        EvacuationRoute initialRoute = evacuationRouteService.findShortestRoute(floorId, startNodeId, originEdgeIds);

        // 최초 안내 경로와 같은 그래프(발화 지점 엣지만 제외)에서 시작 지점이 닿지 못하는 탈출구.
        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        Set<UUID> reachableFromStart = reachableFrom(
                Set.of(startNodeId), adjacency(edgeById.values(), originEdgeIds, false));
        List<UUID> unreachableExitNodeIds = nodes.stream()
                .filter(MapNode::isExitTarget)
                .map(MapNode::getId)
                .filter(id -> !reachableFromStart.contains(id))
                .toList();

        // 재탐색 판단 기준이 되는 "현재 활성 경로". 승인 이력이 없으면 RouteRecalculationService.resolveActiveRoute()는
        // 화재를 빼지 않은 직행 경로를 쓰고, 그것도 없으면 빈 경로를 쓴다(그러면 재탐색이 일어나지 않는다).
        // 재탐색 제안은 관리자가 바로 승인한다고 가정하므로, 그 뒤로는 승인된 후보가 활성 경로가 된다.
        List<UUID> activeRoute;
        try {
            activeRoute = nodeIds(evacuationRouteService.findShortestRoute(floorId, startNodeId));
        } catch (ApiException exception) {
            activeRoute = List.of();
        }

        List<Event> events = new ArrayList<>();
        Set<Integer> spreadGenerations = new HashSet<>(generationByCellId.values());
        for (int generation = 1; generation <= maxGeneration; generation++) {
            // FireSpreadStepService.spreadOneStep(): 새로 번진 셀이 없으면 재탐색을 부르지 않고, 그 뒤로도 번지지 않는다.
            if (!spreadGenerations.contains(generation)) {
                break;
            }
            Set<UUID> firedEdgeIds = edgesUpTo(generationByEdgeId, generation);
            // RouteRecalculationService.triggerForFireSpread(): 활성 경로가 불붙은 엣지를 지날 때만 다시 계산한다.
            if (!crossesAnyEdge(activeRoute, firedEdgeIds, edgeById)) {
                continue;
            }
            long afterSeconds = generation * tickSeconds;
            FireProximity fireProximity = fireProximityAt(generationByEdgeId, generation, tickSeconds);
            EvacuationRoute candidate;
            try {
                candidate = evacuationRouteService.findShortestRoute(
                        floorId, startNodeId, firedEdgeIds, Map.of(), fireProximity);
            } catch (ApiException exception) {
                if (exception.getErrorCode() == EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND) {
                    events.add(new Event(afterSeconds, EventType.BLOCKED, List.of()));
                    break;
                }
                throw exception;
            }
            List<UUID> candidateRoute = nodeIds(candidate);
            if (candidateRoute.equals(activeRoute)) {
                continue;
            }
            events.add(new Event(afterSeconds, EventType.RECALCULATED, candidateRoute));
            activeRoute = candidateRoute;
        }

        int recalculationCount = (int) events.stream().filter(e -> e.type() == EventType.RECALCULATED).count();
        Long blockedAfterSeconds = events.stream()
                .filter(e -> e.type() == EventType.BLOCKED)
                .map(Event::afterSeconds)
                .findFirst()
                .orElse(null);
        // 불이 경로에 처음 닿자마자 막히는 경우만 "대체 경로 없음"이다. 불이 경로에 닿지 않으면 문제 없음으로 본다.
        boolean hasAlternativeRoute = events.isEmpty() || events.get(0).type() != EventType.BLOCKED;

        return new ScenarioRouteDiagnosisResponse(
                scenarioId, floorId, startNodeId, scenario.getFireSpreadSpeed(), tickSeconds, simulatedUntilSeconds,
                nodeIds(initialRoute), unreachableExitNodeIds, undesignatedStairNodeIds(nodes),
                recalculationCount, blockedAfterSeconds, events, hasAlternativeRoute);
    }

    // 이동 가능 방향은 EvacuationRouteService.buildAdjacencyList와 같다:
    // from -> to는 항상, bidirectional이면 to -> from도 가능하다. reverse면 "이 노드로 들어올 수 있는" 방향이다.
    private static Map<UUID, List<UUID>> adjacency(Iterable<MapEdge> edges, Set<UUID> excludedEdgeIds,
            boolean reverse) {
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (MapEdge edge : edges) {
            if (excludedEdgeIds.contains(edge.getId())) {
                continue;
            }
            UUID from = edge.getFromNode().getId();
            UUID to = edge.getToNode().getId();
            adjacency.computeIfAbsent(reverse ? to : from, k -> new ArrayList<>()).add(reverse ? from : to);
            if (edge.isBidirectional()) {
                adjacency.computeIfAbsent(reverse ? from : to, k -> new ArrayList<>()).add(reverse ? to : from);
            }
        }
        return adjacency;
    }

    private static Set<UUID> exitIds(List<MapNode> nodes) {
        Set<UUID> exitIds = new HashSet<>();
        for (MapNode node : nodes) {
            if (node.isExitTarget()) {
                exitIds.add(node.getId());
            }
        }
        return exitIds;
    }

    // 계단·출구인데 탈출구로 지정하지 않은 노드. 경로 탐색은 이런 노드도 중간 경유지로는 지나가지만,
    // 목적지는 isExitTarget인 노드만 고르므로(EvacuationRouteService.findShortestRoute) 대피 목적지가 되지 않는다.
    private static List<UUID> undesignatedStairNodeIds(List<MapNode> nodes) {
        return nodes.stream()
                .filter(node -> node.getType() == NodeType.STAIR || node.getType() == NodeType.EXIT)
                .filter(node -> !node.isExitTarget())
                .map(MapNode::getId)
                .toList();
    }

    // TrainingSessionService.start()와 ScenarioEvacuationSetupService가 시작 지점으로 허용하는 노드와 같은 기준.
    private static boolean isStartCandidate(MapNode node) {
        return node.getType() == NodeType.START
                || (node.getType() == NodeType.DOOR && node.isStartCandidate());
    }

    private static Set<UUID> reachableFrom(Set<UUID> sources, Map<UUID, List<UUID>> adjacency) {
        Set<UUID> visited = new HashSet<>(sources);
        ArrayDeque<UUID> queue = new ArrayDeque<>(sources);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    // 셀마다 몇 번째 확산에서 불이 붙는지. FireSpreadStepService.spreadOneStep()과 같은 규칙이다:
    // 발화점(0세대)에서 시작해 한 세대에 상하좌우(FloorGridCellRepository.findAdjacent) 한 칸씩,
    // 걸을 수 있는(walkable) 셀로만 번진다. 근접 위험 가중치용으로 maxGeneration보다 2세대 더 계산한다.
    private Map<UUID, Integer> simulateFireGenerations(UUID floorId, List<FireZone> fireOrigins, int maxGeneration) {
        Map<Long, FloorGridCell> cellByPosition = new HashMap<>();
        Map<UUID, FloorGridCell> cellById = new HashMap<>();
        for (FloorGridCell cell : floorGridCellRepository.findAllByFloor_Id(floorId)) {
            cellByPosition.put(gridPositionKey(cell.getRowIndex(), cell.getColumnIndex()), cell);
            cellById.put(cell.getId(), cell);
        }

        Map<UUID, Integer> generationByCellId = new HashMap<>();
        ArrayDeque<FloorGridCell> frontier = new ArrayDeque<>();
        for (FireZone origin : fireOrigins) {
            FloorGridCell cell = cellById.get(origin.getGridCellId());
            if (cell != null && generationByCellId.putIfAbsent(cell.getId(), 0) == null) {
                frontier.add(cell);
            }
        }
        int lastGeneration = maxGeneration + FireProximity.MAX_GENERATION;
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        while (!frontier.isEmpty()) {
            FloorGridCell cell = frontier.poll();
            int generation = generationByCellId.get(cell.getId());
            if (generation >= lastGeneration) {
                continue;
            }
            for (int[] direction : directions) {
                FloorGridCell neighbor = cellByPosition.get(gridPositionKey(
                        cell.getRowIndex() + direction[0], cell.getColumnIndex() + direction[1]));
                if (neighbor == null || !neighbor.isWalkable() || generationByCellId.containsKey(neighbor.getId())) {
                    continue;
                }
                generationByCellId.put(neighbor.getId(), generation + 1);
                frontier.add(neighbor);
            }
        }
        return generationByCellId;
    }

    // 엣지마다 지나는 셀 중 가장 먼저 불붙는 세대 = 그 엣지가 막히는 세대.
    // RouteRecalculationService가 불붙은 셀의 엣지를 MapEdgeGridCell로 찾는 것과 같은 매핑을 쓴다.
    private Map<UUID, Integer> edgeGenerations(Map<UUID, Integer> generationByCellId) {
        Map<UUID, Integer> generationByEdgeId = new HashMap<>();
        List<UUID> cellIds = List.copyOf(generationByCellId.keySet());
        for (int from = 0; from < cellIds.size(); from += CELL_ID_CHUNK_SIZE) {
            List<UUID> chunk = cellIds.subList(from, Math.min(from + CELL_ID_CHUNK_SIZE, cellIds.size()));
            for (MapEdgeGridCell mapping : mapEdgeGridCellRepository.findAllByGridCell_IdIn(chunk)) {
                generationByEdgeId.merge(mapping.getMapEdge().getId(),
                        generationByCellId.get(mapping.getGridCell().getId()), Math::min);
            }
        }
        return generationByEdgeId;
    }

    private static Set<UUID> edgesUpTo(Map<UUID, Integer> generationByEdgeId, int generation) {
        Set<UUID> edgeIds = new HashSet<>();
        generationByEdgeId.forEach((edgeId, edgeGeneration) -> {
            if (edgeGeneration <= generation) {
                edgeIds.add(edgeId);
            }
        });
        return edgeIds;
    }

    // RouteRecalculationService.fireProximityForFloor()와 같은 결과: 지금 불붙은 셀에서 MAX_GENERATION 세대 안에
    // 불붙을 엣지마다 "몇 세대 뒤에 불붙는지"를 매긴다(이미 불붙은 엣지는 제외).
    private static FireProximity fireProximityAt(Map<UUID, Integer> generationByEdgeId, int generation,
            long tickSeconds) {
        Map<UUID, Integer> upcoming = new HashMap<>();
        generationByEdgeId.forEach((edgeId, edgeGeneration) -> {
            int remaining = edgeGeneration - generation;
            if (remaining >= 1 && remaining <= FireProximity.MAX_GENERATION) {
                upcoming.put(edgeId, remaining);
            }
        });
        if (upcoming.isEmpty()) {
            return FireProximity.NONE;
        }
        return new FireProximity(upcoming, tickSeconds);
    }

    // RouteRecalculationService.findCrossedEdge()와 같은 기준: 경로의 연속된 두 노드가 엣지 양 끝과 같으면
    // 방향과 상관없이 그 엣지를 지나는 것으로 본다.
    private static boolean crossesAnyEdge(List<UUID> route, Set<UUID> edgeIds, Map<UUID, MapEdge> edgeById) {
        if (route.size() < 2 || edgeIds.isEmpty()) {
            return false;
        }
        for (UUID edgeId : edgeIds) {
            MapEdge edge = edgeById.get(edgeId);
            if (edge == null) {
                continue;
            }
            UUID from = edge.getFromNode().getId();
            UUID to = edge.getToNode().getId();
            for (int i = 0; i < route.size() - 1; i++) {
                UUID a = route.get(i);
                UUID b = route.get(i + 1);
                if ((a.equals(from) && b.equals(to)) || (a.equals(to) && b.equals(from))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<UUID> nodeIds(EvacuationRoute route) {
        return route.path().stream().map(MapNode::getId).toList();
    }

    private static long gridPositionKey(int row, int column) {
        return ((long) row << 32) | (column & 0xFFFFFFFFL);
    }
}
