package com.saferoute.domain.evacuation.graph.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.evacuation.graph.dto.request.CreateMapEdgeRequest;
import com.saferoute.domain.evacuation.graph.dto.request.CreateMapNodeRequest;
import com.saferoute.domain.evacuation.graph.dto.request.UpdateMapNodePositionRequest;
import com.saferoute.domain.evacuation.graph.dto.response.FloorGraphResponse;
import com.saferoute.domain.evacuation.graph.dto.response.MapEdgeResponse;
import com.saferoute.domain.evacuation.graph.dto.response.MapNodeResponse;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.CustomDeviceType;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapGraphRepository;
import com.saferoute.domain.evacuation.grid.service.FloorGridService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
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
class MapGraphServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private MapGraphService mapGraphService;

    @Mock
    private MapGraphRepository mapGraphRepository;

    @Mock
    private FloorRepository floorRepository;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private FloorGridService floorGridService;

    @Mock
    private FloorMapChangeGuard floorMapChangeGuard;

    private final UUID floorId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();
    private Floor floor;

    @BeforeEach
    void setUp() {
        floor = mock(Floor.class);
        lenient().when(floor.getId()).thenReturn(floorId);
        Building building = mock(Building.class);
        lenient().when(building.getId()).thenReturn(buildingId);
        lenient().when(floor.getBuilding()).thenReturn(building);
    }

    // === 훈련 중 편집 차단 ===

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 엣지를 삭제하지 않고 FLOOR004를 던진다")
    void deleteEdge_whileTrainingRunning_throwsAndKeepsEdge() {
        MapEdge edge = MapEdge.create(floor, createNode("A", NodeType.HALLWAY), createNode("B", NodeType.HALLWAY), 3.0, true);
        UUID edgeId = UUID.randomUUID();
        given(mapGraphRepository.findEdgeById(edgeId)).willReturn(Optional.of(edge));
        org.mockito.BDDMockito.willThrow(new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard).validateNoRunningTraining(buildingId);

        assertThatThrownBy(() -> mapGraphService.deleteEdge(edgeId))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        org.mockito.Mockito.verify(mapGraphRepository, org.mockito.Mockito.never()).deleteEdge(any());
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 노드를 삭제하지 않고 FLOOR004를 던진다")
    void deleteNode_whileTrainingRunning_throwsAndKeepsNode() {
        MapNode node = createNode("HALL", NodeType.HALLWAY);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        org.mockito.BDDMockito.willThrow(new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard).validateNoRunningTraining(buildingId);

        assertThatThrownBy(() -> mapGraphService.deleteNode(node.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        org.mockito.Mockito.verify(mapGraphRepository, org.mockito.Mockito.never()).deleteNode(any());
    }

    private MapNode createNode(String code, NodeType type) {
        return createNode(code, type, false);
    }

    private MapNode createNode(String code, NodeType type, boolean isExitTarget) {
        MapNode node = MapNode.create(floor, code, type, code, 0, 0, isExitTarget);
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    // === getFloorGraph ===

    @Test
    @DisplayName("다른 기관 층의 그래프는 조회할 수 없다")
    void getFloorGraph_otherSchool_throwsNotFound() {
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> mapGraphService.getFloorGraph(floorId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_NOT_FOUND);
    }

    @Test
    @DisplayName("층의 노드/엣지 전체를 조회한다")
    void getFloorGraph_success() {
        // given
        MapNode node = createNode("ROOM1", NodeType.ROOM);
        MapNode doorNode = createNode("DOOR1", NodeType.DOOR);
        MapEdge edge = MapEdge.create(floor, node, doorNode, 3.0, true);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        given(floorRepository.existsById(floorId)).willReturn(true);
        given(mapGraphRepository.findNodesByFloor(floorId)).willReturn(List.of(node));
        given(mapGraphRepository.findEdgesByFloor(floorId)).willReturn(List.of(edge));

        // when
        FloorGraphResponse response = mapGraphService.getFloorGraph(floorId);

        // then
        assertThat(response.nodes()).hasSize(1);
        assertThat(response.edges()).hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 층을 조회하면 예외가 발생한다")
    void getFloorGraph_floorNotFound_throws() {
        // given
        given(floorRepository.existsById(floorId)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> mapGraphService.getFloorGraph(floorId))
                .isInstanceOf(ApiException.class)
                .hasMessage(FloorErrorCode.FLOOR_NOT_FOUND.getMessage());
    }

    // === createNode ===

    @Test
    @DisplayName("노드를 생성한다")
    void createNode_success() {
        // given
        CreateMapNodeRequest request = new CreateMapNodeRequest("ROOM1", NodeType.ROOM, "방1", 0.0, 0.0, false);
        MapNode savedNode = createNode("ROOM1", NodeType.ROOM);
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.addNode(floor, "ROOM1", NodeType.ROOM, "방1", 0.0, 0.0, false))
                .willReturn(savedNode);

        // when
        MapNodeResponse response = mapGraphService.createNode(floorId, request);

        // then
        assertThat(response.code()).isEqualTo("ROOM1");
        assertThat(response.type()).isEqualTo(NodeType.ROOM);
    }

    @Test
    @DisplayName("존재하지 않는 층에 노드를 생성하려 하면 예외가 발생한다")
    void createNode_floorNotFound_throws() {
        // given
        CreateMapNodeRequest request = new CreateMapNodeRequest("ROOM1", NodeType.ROOM, "방1", 0.0, 0.0, false);
        given(floorRepository.findById(floorId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> mapGraphService.createNode(floorId, request))
                .isInstanceOf(ApiException.class)
                .hasMessage(FloorErrorCode.FLOOR_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("START 노드는 EXIT 대상이 아닌 상태로 생성한다")
    void createNode_startNode_forcesExitTargetFalse() {
        CreateMapNodeRequest request =
                new CreateMapNodeRequest("START1", NodeType.START, "대피 시작점 후보 1", 0.1, 0.2, true);
        MapNode savedNode = createNode("START1", NodeType.START);
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.addNode(floor, "START1", NodeType.START, "대피 시작점 후보 1", 0.1, 0.2, false))
                .willReturn(savedNode);

        MapNodeResponse response = mapGraphService.createNode(floorId, request);

        assertThat(response.type()).isEqualTo(NodeType.START);
        assertThat(response.isExitTarget()).isFalse();
    }

    @Test
    @DisplayName("한 층에 START 후보 노드를 여러 개 생성할 수 있다")
    void createNode_multipleStartNodes_allowed() {
        CreateMapNodeRequest firstRequest =
                new CreateMapNodeRequest("START1", NodeType.START, "대피 시작점 후보 1", 0.1, 0.2, false);
        CreateMapNodeRequest secondRequest =
                new CreateMapNodeRequest("START2", NodeType.START, "대피 시작점 후보 2", 0.3, 0.4, false);
        MapNode firstSaved = createNode("START1", NodeType.START);
        MapNode secondSaved = createNode("START2", NodeType.START);
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.addNode(floor, "START1", NodeType.START, "대피 시작점 후보 1", 0.1, 0.2, false))
                .willReturn(firstSaved);
        given(mapGraphRepository.addNode(floor, "START2", NodeType.START, "대피 시작점 후보 2", 0.3, 0.4, false))
                .willReturn(secondSaved);

        // 같은 층에 첫 번째 START 후보가 이미 존재하는 상태에서
        mapGraphService.createNode(floorId, firstRequest);
        // 두 번째 START 후보를 생성해도 거부되지 않는다
        MapNodeResponse response = mapGraphService.createNode(floorId, secondRequest);

        assertThat(response.code()).isEqualTo("START2");
        assertThat(response.type()).isEqualTo(NodeType.START);
        verify(mapGraphRepository).addNode(floor, "START1", NodeType.START, "대피 시작점 후보 1", 0.1, 0.2, false);
        verify(mapGraphRepository).addNode(floor, "START2", NodeType.START, "대피 시작점 후보 2", 0.3, 0.4, false);
    }

    // === updateNodePosition ===

    @Test
    @DisplayName("노드 위치를 수정한다")
    void updateNodePosition_success() {
        // given
        MapNode node = createNode("ROOM1", NodeType.ROOM);
        UpdateMapNodePositionRequest request = new UpdateMapNodePositionRequest(10.0, 20.0, true);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(mapGraphRepository.updateNodePosition(node, 10.0, 20.0, true)).willReturn(node);

        // when
        MapNodeResponse response = mapGraphService.updateNodePosition(node.getId(), request);

        // then
        assertThat(response.id()).isEqualTo(node.getId());
        verify(mapGraphRepository).updateNodePosition(node, 10.0, 20.0, true);
    }

    @Test
    @DisplayName("존재하지 않는 노드의 위치를 수정하려 하면 예외가 발생한다")
    void updateNodePosition_nodeNotFound_throws() {
        // given
        UUID unknownNodeId = UUID.randomUUID();
        UpdateMapNodePositionRequest request = new UpdateMapNodePositionRequest(10.0, 20.0, true);
        given(mapGraphRepository.findNodeById(unknownNodeId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> mapGraphService.updateNodePosition(unknownNodeId, request))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.MAP_NODE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("층에 남은 EXIT 대상 노드가 하나뿐이면 isExitTarget을 해제할 수 없다")
    void updateNodePosition_lastExitNodeUnset_throws() {
        // given
        MapNode node = createNode("EXIT1", NodeType.EXIT, true);
        UpdateMapNodePositionRequest request =
                new UpdateMapNodePositionRequest(10.0, 20.0, false, NodeType.HALLWAY);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.countExitTargetNodesByFloor(floorId)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> mapGraphService.updateNodePosition(node.getId(), request))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.EXIT_NODE_UNSET_NOT_ALLOWED.getMessage());
        verify(mapGraphRepository, never()).updateNodePosition(any(), anyDouble(), anyDouble(), anyBoolean());
    }

    @Test
    @DisplayName("층에 EXIT 대상 노드가 여러 개면 그중 하나는 isExitTarget을 해제할 수 있다")
    void updateNodePosition_notLastExitNodeUnset_success() {
        // given
        MapNode node = createNode("EXIT1", NodeType.EXIT, true);
        UpdateMapNodePositionRequest request =
                new UpdateMapNodePositionRequest(10.0, 20.0, false, NodeType.HALLWAY);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.countExitTargetNodesByFloor(floorId)).willReturn(2L);
        given(mapGraphRepository.updateNodePosition(node, 10.0, 20.0, false)).willReturn(node);

        // when
        mapGraphService.updateNodePosition(node.getId(), request);

        // then
        verify(mapGraphRepository).updateNodePosition(node, 10.0, 20.0, false);
        assertThat(node.getType()).isEqualTo(NodeType.HALLWAY);
    }

    @Test
    @DisplayName("노드 유형을 START로 변경하면 EXIT 대상을 해제한다")
    void updateNodePosition_changeTypeToStart_success() {
        MapNode node = createNode("HALLWAY1", NodeType.HALLWAY);
        UpdateMapNodePositionRequest request =
                new UpdateMapNodePositionRequest(10.0, 20.0, true, NodeType.START);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.updateNodePosition(node, 10.0, 20.0, false)).willReturn(node);

        MapNodeResponse response = mapGraphService.updateNodePosition(node.getId(), request);

        assertThat(response.type()).isEqualTo(NodeType.START);
        assertThat(response.isExitTarget()).isFalse();
    }

    @Test
    @DisplayName("같은 층에 이미 START가 있어도 다른 노드를 START로 변경할 수 있다")
    void updateNodePosition_changeTypeToStart_allowedWithExistingStart() {
        // 같은 층에 이미 START로 지정된 노드가 존재하는 상태
        MapNode existingStartNode = createNode("START1", NodeType.START);
        MapNode node = createNode("HALLWAY1", NodeType.HALLWAY);
        UpdateMapNodePositionRequest request =
                new UpdateMapNodePositionRequest(10.0, 20.0, false, NodeType.START);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.updateNodePosition(node, 10.0, 20.0, false)).willReturn(node);

        MapNodeResponse response = mapGraphService.updateNodePosition(node.getId(), request);

        assertThat(existingStartNode.getType()).isEqualTo(NodeType.START);
        assertThat(response.type()).isEqualTo(NodeType.START);
        verify(mapGraphRepository).updateNodePosition(node, 10.0, 20.0, false);
    }

    @Test
    @DisplayName("CUSTOM 노드를 다른 유형으로 변경하면 기기 유형을 제거한다")
    void changeType_fromCustom_clearsCustomDeviceType() {
        MapNode node = MapNode.createCustom(
                floor, "CCTV1", "CCTV", 0.1, 0.2, CustomDeviceType.CCTV);

        node.changeType(NodeType.CUSTOM);
        assertThat(node.getCustomDeviceType()).isEqualTo(CustomDeviceType.CCTV);

        node.changeType(NodeType.START);
        assertThat(node.getCustomDeviceType()).isNull();
    }

    // === updateStartCandidate ===

    @Test
    @DisplayName("DOOR 노드는 시작 후보 여부를 토글할 수 있다")
    void updateStartCandidate_doorNode_success() {
        // given
        MapNode node = createNode("DOOR1", NodeType.DOOR);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(mapGraphRepository.updateStartCandidate(node, true)).willReturn(node);

        // when
        MapNodeResponse response = mapGraphService.updateStartCandidate(node.getId(), true);

        // then
        assertThat(response.id()).isEqualTo(node.getId());
        verify(mapGraphRepository).updateStartCandidate(node, true);
    }

    @Test
    @DisplayName("DOOR가 아닌 노드는 시작 후보로 지정할 수 없다")
    void updateStartCandidate_notDoorNode_throws() {
        // given
        MapNode node = createNode("ROOM1", NodeType.ROOM);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));

        // when & then
        assertThatThrownBy(() -> mapGraphService.updateStartCandidate(node.getId(), true))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.START_CANDIDATE_ONLY_FOR_DOOR.getMessage());
        verify(mapGraphRepository, never()).updateStartCandidate(any(), anyBoolean());
    }

    // === deleteNode ===

    @Test
    @DisplayName("노드를 삭제한다")
    void deleteNode_success() {
        // given
        MapNode node = createNode("ROOM1", NodeType.ROOM);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));

        // when
        mapGraphService.deleteNode(node.getId());

        // then
        verify(mapGraphRepository).deleteNode(node);
    }

    @Test
    @DisplayName("존재하지 않는 노드를 삭제하려 하면 예외가 발생한다")
    void deleteNode_notFound_throws() {
        // given
        UUID unknownNodeId = UUID.randomUUID();
        given(mapGraphRepository.findNodeById(unknownNodeId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> mapGraphService.deleteNode(unknownNodeId))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.MAP_NODE_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("층에 남은 EXIT 대상 노드가 하나뿐이면 삭제할 수 없다")
    void deleteNode_lastExitNode_throws() {
        // given
        MapNode node = createNode("EXIT1", NodeType.EXIT, true);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.countExitTargetNodesByFloor(floorId)).willReturn(1L);

        // when & then
        assertThatThrownBy(() -> mapGraphService.deleteNode(node.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.EXIT_NODE_DELETE_NOT_ALLOWED.getMessage());
        verify(mapGraphRepository).countExitTargetNodesByFloor(floorId);
        verify(mapGraphRepository, never()).deleteNode(node);
    }

    @Test
    @DisplayName("층에 EXIT 대상 노드가 여러 개면 그중 하나는 삭제할 수 있다")
    void deleteNode_notLastExitNode_success() {
        // given
        MapNode node = createNode("EXIT1", NodeType.EXIT, true);
        given(mapGraphRepository.findNodeById(node.getId())).willReturn(Optional.of(node));
        given(floorRepository.findByIdForUpdate(floorId)).willReturn(Optional.of(floor));
        given(mapGraphRepository.countExitTargetNodesByFloor(floorId)).willReturn(2L);

        // when
        mapGraphService.deleteNode(node.getId());

        // then
        verify(mapGraphRepository).countExitTargetNodesByFloor(floorId);
        verify(mapGraphRepository).deleteNode(node);
    }

    // === createEdge ===

    @Test
    @DisplayName("엣지를 연결한다 - floor는 fromNode의 floor를 그대로 사용한다")
    void createEdge_success() {
        // given
        MapNode fromNode = createNode("DOOR1", NodeType.DOOR);
        MapNode toNode = createNode("HALLWAY1", NodeType.HALLWAY);
        CreateMapEdgeRequest request = new CreateMapEdgeRequest(fromNode.getId(), toNode.getId(), 5.0, true);
        MapEdge savedEdge = MapEdge.create(floor, fromNode, toNode, 5.0, true);

        given(mapGraphRepository.findNodeById(fromNode.getId())).willReturn(Optional.of(fromNode));
        given(mapGraphRepository.findNodeById(toNode.getId())).willReturn(Optional.of(toNode));
        given(mapGraphRepository.addEdge(fromNode.getFloor(), fromNode, toNode, 5.0, true)).willReturn(savedEdge);

        // when
        MapEdgeResponse response = mapGraphService.createEdge(request);

        // then
        assertThat(response.distance()).isEqualTo(5.0);
        assertThat(response.fromNodeId()).isEqualTo(fromNode.getId());
        assertThat(response.toNodeId()).isEqualTo(toNode.getId());
    }

    @Test
    @DisplayName("존재하지 않는 노드로 엣지를 연결하려 하면 예외가 발생한다")
    void createEdge_fromNodeNotFound_throws() {
        // given
        UUID unknownNodeId = UUID.randomUUID();
        UUID toNodeId = UUID.randomUUID();
        CreateMapEdgeRequest request = new CreateMapEdgeRequest(unknownNodeId, toNodeId, 5.0, true);
        given(mapGraphRepository.findNodeById(unknownNodeId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> mapGraphService.createEdge(request))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.MAP_NODE_NOT_FOUND.getMessage());
    }

    // === deleteEdge ===

    @Test
    @DisplayName("엣지를 삭제한다")
    void deleteEdge_success() {
        // given
        MapNode fromNode = createNode("DOOR1", NodeType.DOOR);
        MapNode toNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapEdge edge = MapEdge.create(floor, fromNode, toNode, 5.0, true);
        UUID edgeId = UUID.randomUUID();
        ReflectionTestUtils.setField(edge, "id", edgeId);
        given(mapGraphRepository.findEdgeById(edgeId)).willReturn(Optional.of(edge));

        // when
        mapGraphService.deleteEdge(edgeId);

        // then
        verify(mapGraphRepository).deleteEdge(edge);
    }

    @Test
    @DisplayName("존재하지 않는 엣지를 삭제하려 하면 예외가 발생한다")
    void deleteEdge_notFound_throws() {
        // given
        UUID unknownEdgeId = UUID.randomUUID();
        given(mapGraphRepository.findEdgeById(unknownEdgeId)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> mapGraphService.deleteEdge(unknownEdgeId))
                .isInstanceOf(ApiException.class)
                .hasMessage(EvacuationErrorCode.MAP_EDGE_NOT_FOUND.getMessage());
    }
}
