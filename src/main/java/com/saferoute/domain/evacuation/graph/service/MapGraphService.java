package com.saferoute.domain.evacuation.graph.service;

import com.saferoute.domain.evacuation.graph.dto.request.CreateMapEdgeRequest;
import com.saferoute.domain.evacuation.graph.dto.request.CreateMapNodeRequest;
import com.saferoute.domain.evacuation.graph.dto.request.UpdateMapNodePositionRequest;
import com.saferoute.domain.evacuation.graph.dto.response.FloorGraphResponse;
import com.saferoute.domain.evacuation.graph.dto.response.MapEdgeResponse;
import com.saferoute.domain.evacuation.graph.dto.response.MapNodeResponse;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.domain.evacuation.grid.service.FloorGridService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.domain.user.service.SchoolContextService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MapGraphService {

    private final MapGraphRepository mapGraphRepository;
    private final FloorRepository floorRepository;
    private final SchoolContextService schoolContextService;
    private final FloorGridService floorGridService;
    private final FloorMapChangeGuard floorMapChangeGuard;

    // 커스텀 편집 UI 초기 로딩용 - 층의 노드/엣지 전체 조회
    public FloorGraphResponse getFloorGraph(UUID floorId) {
        validateFloorExists(floorId);
        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        List<MapEdge> edges = mapGraphRepository.findEdgesByFloor(floorId);
        return FloorGraphResponse.of(nodes, edges);
    }

    public FloorGraphResponse getFloorGraph(UUID floorId, String email) {
        validateFloorForSchool(floorId, email);
        List<MapNode> nodes = mapGraphRepository.findNodesByFloor(floorId);
        List<MapEdge> edges = mapGraphRepository.findEdgesByFloor(floorId);
        return FloorGraphResponse.of(nodes, edges);
    }

    private void validateFloorForSchool(UUID floorId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        if (floorRepository.findByIdAndBuilding_SchoolName(floorId, schoolName).isEmpty()) {
            throw new ApiException(FloorErrorCode.FLOOR_NOT_FOUND);
        }
    }

    // 노드 추가
    @Transactional
    public MapNodeResponse createNode(UUID floorId, CreateMapNodeRequest request) {
        Floor floor = floorRepository.findById(floorId)
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
        validateNoRunningTraining(floor);
        boolean isExitTarget = resolveExitTarget(request.type(), request.isExitTarget());
        MapNode node = mapGraphRepository.addNode(floor, request.code(), request.type(), request.name(),
                request.x(), request.y(), isExitTarget);
        return MapNodeResponse.from(node);
    }

    // 노드 위치 및 EXIT 대상 여부 수정 (드래그 편집)
    @Transactional
    public MapNodeResponse updateNodePosition(UUID nodeId, UpdateMapNodePositionRequest request) {
        MapNode node = findNodeOrThrow(nodeId);
        NodeType nextType = request.type() != null ? request.type() : node.getType();
        boolean nextExitTarget = resolveExitTarget(nextType, request.isExitTarget());
        if (!nextExitTarget) {
            // 동일 층에 대한 동시 수정 요청을 직렬화해 마지막 EXIT 카운트 검증과 해제 사이의 경쟁을 방지
            floorRepository.findByIdForUpdate(node.getFloor().getId())
                    .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
            validateNotLastExitNode(node, EvacuationErrorCode.EXIT_NODE_UNSET_NOT_ALLOWED);
        }
        validateNoRunningTraining(node.getFloor());
        node.changeType(nextType);
        MapNode updated = mapGraphRepository.updateNodePosition(
                node, request.x(), request.y(), nextExitTarget);
        // 노드가 이동하면 여기 연결된 엣지들이 지나가는 셀도 달라지므로 grid cell 매핑을 다시 계산한다
        // (FloorGridService.recomputeEdgeGridCells 참고 - 그리드 생성 시점에만 계산되던 매핑이
        // 노드/엣지 편집 이후로 안 맞게 되는 문제를 막기 위함).
        mapGraphRepository.findEdgesConnectedToNode(updated.getId())
                .forEach(floorGridService::recomputeEdgeGridCells);
        return MapNodeResponse.from(updated);
    }

    // DOOR 노드의 시작 후보 여부만 위치/타입과 별개로 토글 (시나리오 대피 설정에서 startNode 후보로 인정됨)
    @Transactional
    public MapNodeResponse updateStartCandidate(UUID nodeId, boolean isStartCandidate) {
        MapNode node = findNodeOrThrow(nodeId);
        if (node.getType() != NodeType.DOOR) {
            throw new ApiException(EvacuationErrorCode.START_CANDIDATE_ONLY_FOR_DOOR);
        }
        validateNoRunningTraining(node.getFloor());
        MapNode updated = mapGraphRepository.updateStartCandidate(node, isStartCandidate);
        return MapNodeResponse.from(updated);
    }

    private boolean resolveExitTarget(NodeType type, boolean requestedExitTarget) {
        if (type == NodeType.START) {
            return false;
        }
        if (type == NodeType.EXIT) {
            return true;
        }
        return requestedExitTarget;
    }

    // 노드 삭제 (연결된 엣지도 함께 삭제)
    @Transactional
    public void deleteNode(UUID nodeId) {
        MapNode node = findNodeOrThrow(nodeId);
        // 동일 층에 대한 동시 삭제 요청을 직렬화해 마지막 EXIT 카운트 검증과 삭제 사이의 경쟁을 방지
        floorRepository.findByIdForUpdate(node.getFloor().getId())
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
        validateNoRunningTraining(node.getFloor());
        validateNotLastExitNode(node, EvacuationErrorCode.EXIT_NODE_DELETE_NOT_ALLOWED);
        mapGraphRepository.deleteNode(node);
    }

    // 대상 노드가 EXIT 대상(isExitTarget)이고, 해당 층에 남은 EXIT 대상 노드가 1개뿐이면 거부
    // (출구가 여러 개인 층에서 하나를 지우거나 해제하는 정상 편집은 허용, 마지막 하나만 보호)
    private void validateNotLastExitNode(MapNode node, EvacuationErrorCode errorCode) {
        if (!node.isExitTarget()) {
            return;
        }
        long exitCount = mapGraphRepository.countExitTargetNodesByFloor(node.getFloor().getId());
        if (exitCount <= 1) {
            throw new ApiException(errorCode);
        }
    }

    // 엣지 연결 (fromNode가 속한 floor를 그대로 사용, room-hallway 직접 연결은 Repository에서 검증)
    @Transactional
    public MapEdgeResponse createEdge(CreateMapEdgeRequest request) {
        MapNode fromNode = findNodeOrThrow(request.fromNodeId());
        MapNode toNode = findNodeOrThrow(request.toNodeId());
        validateNoRunningTraining(fromNode.getFloor());
        MapEdge edge = mapGraphRepository.addEdge(
                fromNode.getFloor(), fromNode, toNode, request.distance(), request.bidirectional());
        // 그리드가 이미 생성된 층이면 새 엣지도 바로 grid cell에 매핑해둔다 - 안 그러면 그리드를
        // 재생성하기 전까지 이 엣지는 CCTV 감시 구역과 연결이 안 돼 재탐색 트리거 대상에서 빠진다.
        floorGridService.recomputeEdgeGridCells(edge);
        return MapEdgeResponse.from(edge);
    }

    // 엣지 삭제
    @Transactional
    public void deleteEdge(UUID edgeId) {
        MapEdge edge = mapGraphRepository.findEdgeById(edgeId)
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.MAP_EDGE_NOT_FOUND));
        validateNoRunningTraining(edge.getFloor());
        mapGraphRepository.deleteEdge(edge);
    }

    // 훈련 중 그래프가 바뀌면 안내 중인 경로·유도등 분기점·화재 구간 매핑이 어긋난다. 도면 재업로드·초기화와 같은
    // 기준(FloorMapChangeGuard)으로 막는다. 층 행을 잠그는 메서드에서는 그 뒤에 호출해 다른 경로(분석 반영,
    // 그리드 재생성)와 같은 "층 -> 건물" 잠금 순서를 지킨다.
    private void validateNoRunningTraining(Floor floor) {
        floorMapChangeGuard.validateNoRunningTraining(floor.getBuilding().getId());
    }

    private MapNode findNodeOrThrow(UUID nodeId) {
        return mapGraphRepository.findNodeById(nodeId)
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.MAP_NODE_NOT_FOUND));
    }

    private void validateFloorExists(UUID floorId) {
        if (!floorRepository.existsById(floorId)) {
            throw new ApiException(FloorErrorCode.FLOOR_NOT_FOUND);
        }
    }
}
