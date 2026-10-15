package com.saferoute.domain.analysis.service;

import com.saferoute.domain.analysis.dto.AnalyseFloorResponse;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.service.FloorGridService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.entity.SegmentationStatus;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.global.api.error.AnalysisErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FloorAnalysisResultService {

    private final FloorRepository floorRepository;
    private final MapNodeJpaRepository mapNodeRepository;
    private final MapEdgeJpaRepository mapEdgeRepository;
    private final FloorGridService floorGridService;
    private final FloorMapChangeGuard floorMapChangeGuard;

    @Transactional
    public void persistAnalysisResult(UUID floorId, AnalyseFloorResponse response) {
        Floor floor = floorRepository.findByIdForUpdate(floorId)
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
        // 분석 요청 시점의 사전 검사(FloorService.requestAnalysis)만으로는 부족하다 - AI 분석이 도는 동안 훈련이
        // 시작될 수 있으므로, 실제로 그래프를 교체하는 이 시점에 다시 검사한다. 막히면 예외로 롤백되고
        // FloorAnalysisService가 분석을 FAILED로 표시한다.
        floorMapChangeGuard.validateNoRunningTraining(floor.getBuilding().getId());

        validateGraphIntegrity(response);
        replaceExistingGraph(floor);

        Map<String, MapNode> tempIdToNode = new HashMap<>();
        for (var nodeResponse : response.nodes()) {
            NodeType type = NodeType.valueOf(nodeResponse.type());
            MapNode node = MapNode.create(
                    floor, nodeResponse.code(), type, defaultNameFor(type),
                    nodeResponse.x(), nodeResponse.y(), false);
            mapNodeRepository.save(node);
            tempIdToNode.put(nodeResponse.tempId(), node);
        }

        for (var edgeResponse : response.edges()) {
            MapEdge edge = MapEdge.create(
                    floor,
                    tempIdToNode.get(edgeResponse.fromTempId()),
                    tempIdToNode.get(edgeResponse.toTempId()),
                    edgeResponse.distance(),
                    edgeResponse.bidirectional());
            mapEdgeRepository.save(edge);
        }

        // 아래 bulk delete가 영속성 컨텍스트를 비우기 전에 변경 상태를 flush하도록 먼저 반영한다.
        floor.applyPlanSize(response.planWidthPx(), response.planHeightPx());
        floor.updateSegmentationStatus(SegmentationStatus.DONE);
        floorGridService.remapGraphToExistingGrid(floorId);
    }

    private void validateGraphIntegrity(AnalyseFloorResponse response) {
        Set<String> tempIds = new HashSet<>();
        for (var nodeResponse : response.nodes()) {
            if (!tempIds.add(nodeResponse.tempId())) {
                throw new ApiException(AnalysisErrorCode.AI_ANALYSIS_INVALID_GRAPH);
            }
        }
        for (var edgeResponse : response.edges()) {
            if (!tempIds.contains(edgeResponse.fromTempId())
                    || !tempIds.contains(edgeResponse.toTempId())) {
                throw new ApiException(AnalysisErrorCode.AI_ANALYSIS_INVALID_GRAPH);
            }
        }
    }

    private void replaceExistingGraph(Floor floor) {
        mapEdgeRepository.deleteAllByFloor(floor);
        mapNodeRepository.deleteAllByFloor(floor);
    }

    private String defaultNameFor(NodeType type) {
        return switch (type) {
            case ROOM -> "방";
            case HALLWAY -> "복도";
            case STAIR -> "계단";
            case DOOR -> "문";
            default -> "노드";
        };
    }
}
