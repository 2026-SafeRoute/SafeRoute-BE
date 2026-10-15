package com.saferoute.domain.device.service;

import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.saferoute.domain.device.dto.request.AssignCctvRequest;
import com.saferoute.domain.device.dto.request.ChangeLightDirectionRequest;
import com.saferoute.domain.device.dto.request.ConfigureGuidanceRequest;
import com.saferoute.domain.device.dto.request.CreateIoTLightRequest;
import com.saferoute.domain.device.dto.request.UpdateIoTLightRequest;
import com.saferoute.domain.device.dto.request.UpdatePiEndpointRequest;
import com.saferoute.domain.device.dto.response.IoTLightResponse;
import com.saferoute.domain.device.dto.response.LightDirectionResponse;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.device.entity.LightCommand;
import com.saferoute.domain.device.entity.LightCommandStatus;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.device.repository.LightCommandJpaRepository;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.telemetry.dynamo.entity.LightDirectionEventItem;
import com.saferoute.domain.telemetry.dynamo.repository.LightDirectionEventRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.error.IoTLightErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.domain.building.entity.Building;
import java.util.List;
import java.util.Map;
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

@ExtendWith(MockitoExtension.class)
class IoTLightServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @Mock
    private FloorMapChangeGuard floorMapChangeGuard;

    @InjectMocks
    private IoTLightService iotLightService;

    @Mock
    private IoTLightJpaRepository iotLightJpaRepository;

    @Mock
    private IoTLightCodeAllocator iotLightCodeAllocator;

    @Mock
    private CctvJpaRepository cctvJpaRepository;

    @Mock
    private MapNodeJpaRepository mapNodeJpaRepository;

    @Mock
    private MapEdgeJpaRepository mapEdgeJpaRepository;

    @Mock
    private FloorRepository floorRepository;

    @Mock
    private LightCommandJpaRepository lightCommandJpaRepository;

    @Mock
    private IoTLightDirectionStore iotLightDirectionStore;

    @Mock
    private TrainingEventPublisher trainingEventPublisher;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private LightDirectionEventRepository lightDirectionEventRepository;

    private final UUID floorId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();
    private Floor floor;

    @BeforeEach
    void setUp() {
        floor = mock(Floor.class);
        Building building = mock(Building.class);
        org.mockito.Mockito.lenient().when(floor.getBuilding()).thenReturn(building);
        org.mockito.Mockito.lenient().when(building.getId()).thenReturn(buildingId);
        org.mockito.Mockito.lenient()
                .when(schoolContextService.getSchoolName(EMAIL))
                .thenReturn(SCHOOL_NAME);
    }

    private MapNode createNode(String code, NodeType type) {
        MapNode node = type == NodeType.CUSTOM
                ? MapNode.createCustom(floor, code, code, 0, 0)
                : MapNode.create(floor, code, type, code, 0, 0, false);
        ReflectionTestUtils.setField(node, "id", UUID.randomUUID());
        return node;
    }

    private MapEdge createEdge(MapNode from, MapNode to) {
        MapEdge edge = MapEdge.create(floor, from, to, 3.0, true);
        ReflectionTestUtils.setField(edge, "id", UUID.randomUUID());
        return edge;
    }

    private Cctv createCctv(String code) {
        Cctv cctv = Cctv.create(code, code, createNode(code, NodeType.CUSTOM));
        ReflectionTestUtils.setField(cctv, "id", UUID.randomUUID());
        return cctv;
    }

    private IoTLight createLight(String code, MapNode customNode) {
        IoTLight light = IoTLight.create(code, code, customNode);
        ReflectionTestUtils.setField(light, "id", UUID.randomUUID());
        return light;
    }

    // === createLight ===

    @Test
    @DisplayName("유도등을 등록하면 도면 위치 노드가 함께 생성된다")
    void createLight_success() {
        // given
        CreateIoTLightRequest request = new CreateIoTLightRequest(floorId, "복도1 유도등", 0.3, 0.4);
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight savedLight = createLight("LIGHT_001", customNode);

        given(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME)).willReturn(Optional.of(floor));
        given(iotLightCodeAllocator.allocate()).willReturn("LIGHT_001");
        given(mapNodeJpaRepository.save(any(MapNode.class))).willReturn(customNode);
        given(iotLightJpaRepository.save(any(IoTLight.class))).willReturn(savedLight);

        // when
        IoTLightResponse response = iotLightService.createLight(request, EMAIL);

        // then
        assertThat(response.code()).isEqualTo("LIGHT_001");
        assertThat(response.enabled()).isTrue();
        assertThat(response.guidanceConfigured()).isFalse();
    }

    @Test
    @DisplayName("등록할 때마다 code가 순번대로 자동 생성된다")
    void createLight_generatesSequentialCode() {
        // given
        CreateIoTLightRequest request = new CreateIoTLightRequest(floorId, "복도1 유도등", 0.3, 0.4);
        MapNode customNode = createNode("LIGHT_006", NodeType.CUSTOM);
        IoTLight savedLight = createLight("LIGHT_006", customNode);

        given(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME)).willReturn(Optional.of(floor));
        given(iotLightCodeAllocator.allocate()).willReturn("LIGHT_006");
        given(mapNodeJpaRepository.save(any(MapNode.class))).willReturn(customNode);
        given(iotLightJpaRepository.save(any(IoTLight.class))).willReturn(savedLight);

        // when
        IoTLightResponse response = iotLightService.createLight(request, EMAIL);

        // then
        assertThat(response.code()).isEqualTo("LIGHT_006");
    }

    @Test
    @DisplayName("존재하지 않는 층에 등록하려 하면 예외가 발생한다")
    void createLight_floorNotFound_throws() {
        // given
        CreateIoTLightRequest request = new CreateIoTLightRequest(floorId, "복도1 유도등", 0.3, 0.4);
        given(floorRepository.findByIdAndBuilding_SchoolName(floorId, SCHOOL_NAME)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> iotLightService.createLight(request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(FloorErrorCode.FLOOR_NOT_FOUND.getMessage());
    }

    // === getLights / getLight ===

    @Test
    @DisplayName("층 ID로 유도등 목록을 조회한다")
    void getLights_byFloor_success() {
        // given
        MapNode node = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", node);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(iotLightJpaRepository
                .findAllByCustomNode_Floor_IdAndCustomNode_Floor_Building_SchoolName(
                        floorId, SCHOOL_NAME)).willReturn(List.of(light));

        // when
        List<IoTLightResponse> responses = iotLightService.getLights(floorId, EMAIL);

        // then
        assertThat(responses).hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 유도등을 조회하면 예외가 발생한다")
    void getLight_notFound_throws() {
        // given
        UUID unknownId = UUID.randomUUID();
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(iotLightJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(unknownId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> iotLightService.getLight(unknownId, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND.getMessage());
    }

    // === configureGuidance ===

    @Test
    @DisplayName("decisionNode에 연결된 엣지로 분기 경로를 설정한다")
    void configureGuidance_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        MapNode decisionNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftTarget = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode rightTarget = createNode("HALLWAY3", NodeType.HALLWAY);
        MapEdge leftEdge = createEdge(decisionNode, leftTarget);
        MapEdge rightEdge = createEdge(decisionNode, rightTarget);

        ConfigureGuidanceRequest request =
                new ConfigureGuidanceRequest(decisionNode.getId(), leftEdge.getId(), rightEdge.getId());

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(mapNodeJpaRepository.findByIdAndFloor_Building_SchoolName(decisionNode.getId(), SCHOOL_NAME)).willReturn(Optional.of(decisionNode));
        given(mapEdgeJpaRepository.findByIdAndFloor_Building_SchoolName(leftEdge.getId(), SCHOOL_NAME)).willReturn(Optional.of(leftEdge));
        given(mapEdgeJpaRepository.findByIdAndFloor_Building_SchoolName(rightEdge.getId(), SCHOOL_NAME)).willReturn(Optional.of(rightEdge));

        // when
        IoTLightResponse response = iotLightService.configureGuidance(light.getId(), request, EMAIL);

        // then
        assertThat(response.guidanceConfigured()).isTrue();
        assertThat(response.decisionNodeId()).isEqualTo(decisionNode.getId());
    }

    @Test
    @DisplayName("decisionNode에 연결되지 않은 엣지로 설정하려 하면 예외가 발생한다")
    void configureGuidance_edgeNotConnectedToDecisionNode_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        MapNode decisionNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode unrelatedA = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode unrelatedB = createNode("HALLWAY3", NodeType.HALLWAY);
        MapEdge unrelatedEdge = createEdge(unrelatedA, unrelatedB); // decisionNode와 무관한 엣지
        MapNode rightTarget = createNode("HALLWAY4", NodeType.HALLWAY);
        MapEdge rightEdge = createEdge(decisionNode, rightTarget);

        ConfigureGuidanceRequest request =
                new ConfigureGuidanceRequest(decisionNode.getId(), unrelatedEdge.getId(), rightEdge.getId());

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(mapNodeJpaRepository.findByIdAndFloor_Building_SchoolName(decisionNode.getId(), SCHOOL_NAME)).willReturn(Optional.of(decisionNode));
        given(mapEdgeJpaRepository.findByIdAndFloor_Building_SchoolName(unrelatedEdge.getId(), SCHOOL_NAME)).willReturn(Optional.of(unrelatedEdge));
        given(mapEdgeJpaRepository.findByIdAndFloor_Building_SchoolName(rightEdge.getId(), SCHOOL_NAME)).willReturn(Optional.of(rightEdge));

        // when & then
        assertThatThrownBy(() -> iotLightService.configureGuidance(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.INVALID_GUIDANCE_EDGE.getMessage());
    }

    @Test
    @DisplayName("leftEdge와 rightEdge가 같으면 예외가 발생한다 (500이 아닌 400)")
    void configureGuidance_sameLeftAndRightEdge_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        UUID decisionNodeId = UUID.randomUUID();
        UUID sameEdgeId = UUID.randomUUID();
        ConfigureGuidanceRequest request = new ConfigureGuidanceRequest(decisionNodeId, sameEdgeId, sameEdgeId);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when & then: decisionNode/엣지 조회 전에 걸러지므로 관련 mock 없이도 검증 가능
        assertThatThrownBy(() -> iotLightService.configureGuidance(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.INVALID_GUIDANCE_EDGE.getMessage());
    }

    @Test
    @DisplayName("존재하지 않는 decisionNode로 설정하려 하면 예외가 발생한다")
    void configureGuidance_decisionNodeNotFound_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        UUID unknownNodeId = UUID.randomUUID();
        ConfigureGuidanceRequest request =
                new ConfigureGuidanceRequest(unknownNodeId, UUID.randomUUID(), UUID.randomUUID());

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(mapNodeJpaRepository.findByIdAndFloor_Building_SchoolName(unknownNodeId, SCHOOL_NAME)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> iotLightService.configureGuidance(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.DECISION_NODE_NOT_FOUND.getMessage());
    }

    // === updateLight ===

    @Test
    @DisplayName("이름과 위치를 수정한다")
    void updateLight_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        UpdateIoTLightRequest request = new UpdateIoTLightRequest("변경된 이름", 0.7, 0.8);
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when
        IoTLightResponse response = iotLightService.updateLight(light.getId(), request, EMAIL);

        // then
        assertThat(response.name()).isEqualTo("변경된 이름");
        assertThat(response.x()).isEqualTo(0.7);
        assertThat(response.y()).isEqualTo(0.8);
    }

    // === assignCctv ===

    @Test
    @DisplayName("유도등에 CCTV(릴레이 담당 Pi)를 연결한다")
    void assignCctv_success() {
        // given
        MapNode lightNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", lightNode);
        MapNode cctvNode = createNode("CCTV_001", NodeType.CUSTOM);
        Cctv cctv = Cctv.create("CCTV_001", "CCTV_001", cctvNode);
        ReflectionTestUtils.setField(cctv, "id", UUID.randomUUID());
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME))
                .willReturn(Optional.of(light));
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctv.getId(), SCHOOL_NAME))
                .willReturn(Optional.of(cctv));

        // when
        IoTLightResponse response = iotLightService.assignCctv(
                light.getId(), new AssignCctvRequest(cctv.getId()), EMAIL);

        // then
        assertThat(response.cctvId()).isEqualTo(cctv.getId());
    }

    @Test
    @DisplayName("존재하지 않는 CCTV를 연결하려 하면 예외가 발생한다")
    void assignCctv_cctvNotFound_throws() {
        // given
        MapNode lightNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", lightNode);
        UUID unknownCctvId = UUID.randomUUID();
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME))
                .willReturn(Optional.of(light));
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(unknownCctvId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> iotLightService.assignCctv(
                light.getId(), new AssignCctvRequest(unknownCctvId), EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(CctvErrorCode.CCTV_NOT_FOUND.getMessage());
    }

    // === enable / disable ===

    @Test
    @DisplayName("유도등을 비활성화한다")
    void disableLight_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when
        IoTLightResponse response = iotLightService.disableLight(light.getId(), EMAIL);

        // then
        assertThat(response.enabled()).isFalse();
    }

    // === deleteLight ===

    @Test
    @DisplayName("유도등을 삭제하면 연결된 엣지와 도면 위치 노드가 함께 삭제된다")
    void deleteLight_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when
        iotLightService.deleteLight(light.getId(), EMAIL);

        // then
        verify(mapEdgeJpaRepository).deleteByFromNode_IdOrToNode_Id(customNode.getId(), customNode.getId());
        verify(mapNodeJpaRepository).delete(customNode);
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 유도등을 삭제하지 않고 FLOOR004를 던진다")
    void deleteLight_whileTrainingRunning_throwsAndKeepsLight() {
        MapNode customNode = createNode("LIGHT_002", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_002", customNode);
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME))
                .willReturn(Optional.of(light));
        org.mockito.BDDMockito.willThrow(new ApiException(
                        com.saferoute.global.api.error.FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard)
                .validateNoRunningTraining(org.mockito.ArgumentMatchers.<com.saferoute.domain.floor.entity.Floor>any());

        assertThatThrownBy(() -> iotLightService.deleteLight(light.getId(), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(com.saferoute.global.api.error.FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        org.mockito.Mockito.verify(mapNodeJpaRepository, org.mockito.Mockito.never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 유도등을 삭제하려 하면 예외가 발생한다")
    void deleteLight_notFound_throws() {
        // given
        UUID unknownId = UUID.randomUUID();
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(unknownId, SCHOOL_NAME)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> iotLightService.deleteLight(unknownId, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND.getMessage());
        verifyNoInteractions(mapEdgeJpaRepository, mapNodeJpaRepository);
    }

    // === changeDirection ===

    @Test
    @DisplayName("OFF 방향은 경로 안내 설정 없이도 명령을 보낼 수 있다")
    void changeDirection_off_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when
        LightDirectionResponse response = iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        assertThat(response.direction()).isEqualTo(IoTLightDirection.OFF);
        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.OFF);
        assertThat(captor.getValue().getStatus()).isEqualTo(LightCommandStatus.PENDING);
        verify(iotLightDirectionStore).update(light.getId(), IoTLightDirection.OFF);
        verify(trainingEventPublisher).publishIoTLightStatusUpdatedAfterCommit(light, IoTLightDirection.OFF);
    }

    @Test
    @DisplayName("경로 안내가 설정된 유도등에 LEFT 명령을 보낼 수 있다")
    void changeDirection_left_withGuidance_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        MapNode decisionNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftTarget = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode rightTarget = createNode("HALLWAY3", NodeType.HALLWAY);
        light.configureGuidance(decisionNode, createEdge(decisionNode, leftTarget), createEdge(decisionNode, rightTarget));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.LEFT);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when
        LightDirectionResponse response = iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        assertThat(response.direction()).isEqualTo(IoTLightDirection.LEFT);
        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.LEFT);
    }

    @Test
    @DisplayName("기존 PENDING 명령이 있으면 SUPERSEDED로 남기고 새 명령을 적재한다")
    void changeDirection_supersedesExistingPendingCommand() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        LightCommand stalePending = LightCommand.createPending(light, IoTLightDirection.LEFT);
        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(lightCommandJpaRepository.findAllByLight_IdAndStatus(light.getId(), LightCommandStatus.PENDING))
                .willReturn(List.of(stalePending));

        // when
        iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        assertThat(stalePending.getStatus()).isEqualTo(LightCommandStatus.SUPERSEDED);
        verify(lightCommandJpaRepository).save(any(LightCommand.class));
    }

    @Test
    @DisplayName("경로 안내가 설정되지 않은 유도등에 LEFT/RIGHT 명령을 보내면 예외가 발생한다")
    void changeDirection_left_withoutGuidance_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.LEFT);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when & then
        assertThatThrownBy(() -> iotLightService.changeDirection(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.GUIDANCE_NOT_CONFIGURED.getMessage());
        verifyNoInteractions(lightCommandJpaRepository, iotLightDirectionStore, trainingEventPublisher);
    }

    @Test
    @DisplayName("비활성화된 유도등에 명령을 보내면 예외가 발생한다")
    void changeDirection_disabledLight_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        light.disable();
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when & then
        assertThatThrownBy(() -> iotLightService.changeDirection(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.LIGHT_DISABLED.getMessage());
        verifyNoInteractions(lightCommandJpaRepository, iotLightDirectionStore, trainingEventPublisher);
    }

    @Test
    @DisplayName("담당 CCTV(Pi)가 연결되지 않은 유도등에 명령을 보내면 예외가 발생한다")
    void changeDirection_cctvNotAssigned_throws() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));

        // when & then
        assertThatThrownBy(() -> iotLightService.changeDirection(light.getId(), request, EMAIL))
                .isInstanceOf(ApiException.class)
                .hasMessage(IoTLightErrorCode.CCTV_NOT_ASSIGNED.getMessage());
        verifyNoInteractions(lightCommandJpaRepository, iotLightDirectionStore, trainingEventPublisher);
    }

    @Test
    @DisplayName("실행 중인 훈련 세션이 있으면 방향 전환 이력을 DynamoDB에 남긴다")
    void changeDirection_runningSession_logsDirectionEvent() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        MapNode decisionNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftTarget = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode rightTarget = createNode("HALLWAY3", NodeType.HALLWAY);
        light.configureGuidance(decisionNode, createEdge(decisionNode, leftTarget), createEdge(decisionNode, rightTarget));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.LEFT);

        TrainingSession session = mock(TrainingSession.class);
        UUID sessionId = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(session.getId()).thenReturn(sessionId);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(trainingSessionRepository.findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(
                TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));

        // when
        iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        org.mockito.ArgumentCaptor<LightDirectionEventItem> captor =
                org.mockito.ArgumentCaptor.forClass(LightDirectionEventItem.class);
        verify(lightDirectionEventRepository).save(captor.capture());
        assertThat(captor.getValue().getLightCode()).isEqualTo("LIGHT_001");
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.LEFT);
        assertThat(captor.getValue().getTrainingSessionId()).isEqualTo(sessionId.toString());
    }

    @Test
    @DisplayName("실행 중인 훈련 세션이 없으면 방향 전환 이력을 남기지 않는다")
    void changeDirection_noRunningSession_skipsLogging() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(trainingSessionRepository.findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(
                TrainingStatus.RUNNING, buildingId)).willReturn(Optional.empty());

        // when
        LightDirectionResponse response = iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        assertThat(response.direction()).isEqualTo(IoTLightDirection.OFF);
        verifyNoInteractions(lightDirectionEventRepository);
    }

    @Test
    @DisplayName("방향 전환 이력 기록이 실패해도 유도등 명령 자체는 성공한다")
    void changeDirection_loggingFails_stillSucceeds() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        ChangeLightDirectionRequest request = new ChangeLightDirectionRequest(IoTLightDirection.OFF);

        given(iotLightJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(light.getId(), SCHOOL_NAME)).willReturn(Optional.of(light));
        given(trainingSessionRepository.findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(
                TrainingStatus.RUNNING, buildingId))
                .willThrow(new RuntimeException("dynamo unavailable"));

        // when
        LightDirectionResponse response = iotLightService.changeDirection(light.getId(), request, EMAIL);

        // then
        assertThat(response.direction()).isEqualTo(IoTLightDirection.OFF);
        verify(trainingEventPublisher).publishIoTLightStatusUpdatedAfterCommit(light, IoTLightDirection.OFF);
    }

    // === resetToNormal ===

    @Test
    @DisplayName("건물 내 안내 설정이 끝난 유도등을 평상시(BOTH) 상태로 되돌린다")
    void resetToNormal_success() {
        // given
        MapNode customNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight light = createLight("LIGHT_001", customNode);
        light.assignCctv(createCctv("CCTV_001"));
        MapNode decisionNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftTarget = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode rightTarget = createNode("HALLWAY3", NodeType.HALLWAY);
        light.configureGuidance(decisionNode, createEdge(decisionNode, leftTarget), createEdge(decisionNode, rightTarget));

        given(iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId)).willReturn(List.of(light));

        // when
        iotLightService.resetToNormal(buildingId);

        // then
        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.BOTH);
        verify(iotLightDirectionStore).update(light.getId(), IoTLightDirection.BOTH);
    }

    @Test
    @DisplayName("비활성화되었거나 안내가 설정되지 않은 유도등은 건너뛴다")
    void resetToNormal_skipsDisabledOrUnconfiguredLights() {
        // given
        MapNode disabledNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight disabledLight = createLight("LIGHT_001", disabledNode);
        disabledLight.disable();

        MapNode unconfiguredNode = createNode("LIGHT_002", NodeType.CUSTOM);
        IoTLight unconfiguredLight = createLight("LIGHT_002", unconfiguredNode);

        given(iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId))
                .willReturn(List.of(disabledLight, unconfiguredLight));

        // when
        iotLightService.resetToNormal(buildingId);

        // then
        verifyNoInteractions(lightCommandJpaRepository, iotLightDirectionStore);
    }

    @Test
    @DisplayName("일부 유도등 명령이 실패해도 나머지 유도등 전환은 계속 진행된다")
    void resetToNormal_individualFailureDoesNotStopOthers() {
        // given
        MapNode failingNode = createNode("LIGHT_001", NodeType.CUSTOM);
        IoTLight failingLight = createLight("LIGHT_001", failingNode);
        // 담당 CCTV 미연결 -> changeDirection이 CCTV_NOT_ASSIGNED를 던짐
        MapNode decisionNodeA = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftTargetA = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode rightTargetA = createNode("HALLWAY3", NodeType.HALLWAY);
        failingLight.configureGuidance(
                decisionNodeA, createEdge(decisionNodeA, leftTargetA), createEdge(decisionNodeA, rightTargetA));

        MapNode succeedingNode = createNode("LIGHT_002", NodeType.CUSTOM);
        IoTLight succeedingLight = createLight("LIGHT_002", succeedingNode);
        succeedingLight.assignCctv(createCctv("CCTV_002"));
        MapNode decisionNodeB = createNode("HALLWAY4", NodeType.HALLWAY);
        MapNode leftTargetB = createNode("HALLWAY5", NodeType.HALLWAY);
        MapNode rightTargetB = createNode("HALLWAY6", NodeType.HALLWAY);
        succeedingLight.configureGuidance(
                decisionNodeB, createEdge(decisionNodeB, leftTargetB), createEdge(decisionNodeB, rightTargetB));

        given(iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId))
                .willReturn(List.of(failingLight, succeedingLight));

        // when
        iotLightService.resetToNormal(buildingId);

        // then
        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getLight()).isEqualTo(succeedingLight);
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.BOTH);
        verify(iotLightDirectionStore).update(succeedingLight.getId(), IoTLightDirection.BOTH);
        verify(trainingEventPublisher).publishIoTLightStatusUpdatedAfterCommit(succeedingLight, IoTLightDirection.BOTH);
    }

    // === applyFloorGuidance ===

    // decisionNode에서 left/right 두 갈래로 나뉘는, 담당 CCTV까지 연결된 안내 가능 유도등
    private IoTLight createGuidedLight(String code, MapNode decisionNode, MapNode leftTarget, MapNode rightTarget) {
        IoTLight light = createLight(code, createNode(code, NodeType.CUSTOM));
        light.assignCctv(createCctv("CCTV_" + code));
        light.configureGuidance(decisionNode, createEdge(decisionNode, leftTarget), createEdge(decisionNode, rightTarget));
        return light;
    }

    @Test
    @DisplayName("경로 위 유도등은 nextHops와 달라도 승인 경로 방향을 따른다")
    void applyFloorGuidance_pathOverridesNextHops() {
        MapNode decision = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode left = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode right = createNode("HALLWAY3", NodeType.HALLWAY);
        IoTLight light = createGuidedLight("LIGHT_001", decision, left, right);
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(light));

        iotLightService.applyFloorGuidance(
                floorId, List.of(decision.getId(), left.getId()), Map.of(decision.getId(), right.getId()));

        verify(iotLightDirectionStore).update(light.getId(), IoTLightDirection.LEFT);
    }

    @Test
    @DisplayName("경로 밖 유도등은 nextHops 방향으로 바뀐다 - 옛 경로를 가리키던 유도등의 회귀 테스트")
    void applyFloorGuidance_offPathLightFollowsNextHop() {
        MapNode oldPathNode = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode towardFire = createNode("HALLWAY2", NodeType.HALLWAY);
        MapNode detour = createNode("HALLWAY3", NodeType.HALLWAY);
        IoTLight light = createGuidedLight("LIGHT_001", oldPathNode, towardFire, detour);
        // 옛 경로 때문에 화재 구간 쪽(LEFT)을 가리키고 있던 상태
        given(iotLightDirectionStore.get(light.getId())).willReturn(IoTLightDirection.LEFT);
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(light));

        // 새 경로는 이 유도등을 지나지 않지만, 층 전체 다음 홉은 우회로(RIGHT)를 가리킨다.
        iotLightService.applyFloorGuidance(
                floorId, List.of(UUID.randomUUID(), UUID.randomUUID()), Map.of(oldPathNode.getId(), detour.getId()));

        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.RIGHT);
        verify(iotLightDirectionStore).update(light.getId(), IoTLightDirection.RIGHT);
    }

    @Test
    @DisplayName("다음 홉이 없거나 좌우 엣지와 맞지 않는 유도등은 평상시(BOTH)로 설정한다")
    void applyFloorGuidance_unmatchedLightsGoBoth() {
        MapNode noHopDecision = createNode("HALLWAY1", NodeType.HALLWAY);
        IoTLight noHopLight = createGuidedLight("LIGHT_001", noHopDecision,
                createNode("HALLWAY2", NodeType.HALLWAY), createNode("HALLWAY3", NodeType.HALLWAY));

        MapNode mismatchDecision = createNode("HALLWAY4", NodeType.HALLWAY);
        IoTLight mismatchLight = createGuidedLight("LIGHT_002", mismatchDecision,
                createNode("HALLWAY5", NodeType.HALLWAY), createNode("HALLWAY6", NodeType.HALLWAY));
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(noHopLight, mismatchLight));

        // mismatchLight의 다음 홉은 이 유도등의 좌우 엣지 어느 쪽도 아닌 노드다.
        iotLightService.applyFloorGuidance(
                floorId, List.of(), Map.of(mismatchDecision.getId(), UUID.randomUUID()));

        verify(iotLightDirectionStore).update(noHopLight.getId(), IoTLightDirection.BOTH);
        verify(iotLightDirectionStore).update(mismatchLight.getId(), IoTLightDirection.BOTH);
    }

    @Test
    @DisplayName("이미 같은 방향이면 명령을 적재하지 않는다")
    void applyFloorGuidance_skipsWhenDirectionUnchanged() {
        MapNode decision = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode left = createNode("HALLWAY2", NodeType.HALLWAY);
        IoTLight light = createGuidedLight("LIGHT_001", decision, left, createNode("HALLWAY3", NodeType.HALLWAY));
        given(iotLightDirectionStore.get(light.getId())).willReturn(IoTLightDirection.LEFT);
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(light));

        iotLightService.applyFloorGuidance(floorId, List.of(), Map.of(decision.getId(), left.getId()));

        verifyNoInteractions(lightCommandJpaRepository, trainingEventPublisher);
        verify(iotLightDirectionStore, never()).update(any(), any());
    }

    @Test
    @DisplayName("비활성 유도등과 안내 미설정 유도등은 건너뛴다")
    void applyFloorGuidance_skipsDisabledOrUnconfiguredLights() {
        MapNode decision = createNode("HALLWAY1", NodeType.HALLWAY);
        IoTLight disabledLight = createGuidedLight("LIGHT_001", decision,
                createNode("HALLWAY2", NodeType.HALLWAY), createNode("HALLWAY3", NodeType.HALLWAY));
        disabledLight.disable();
        IoTLight unconfiguredLight = createLight("LIGHT_002", createNode("LIGHT_002", NodeType.CUSTOM));
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(disabledLight, unconfiguredLight));

        iotLightService.applyFloorGuidance(floorId, List.of(), Map.of());

        verifyNoInteractions(lightCommandJpaRepository, trainingEventPublisher);
        verify(iotLightDirectionStore, never()).update(any(), any());
    }

    @Test
    @DisplayName("일부 유도등 전환이 실패해도 나머지 유도등은 계속 처리한다")
    void applyFloorGuidance_individualFailureDoesNotStopOthers() {
        MapNode decisionA = createNode("HALLWAY1", NodeType.HALLWAY);
        MapNode leftA = createNode("HALLWAY2", NodeType.HALLWAY);
        // 담당 CCTV 미연결 -> changeDirection이 CCTV_NOT_ASSIGNED를 던짐
        IoTLight failingLight = createLight("LIGHT_001", createNode("LIGHT_001", NodeType.CUSTOM));
        failingLight.configureGuidance(decisionA, createEdge(decisionA, leftA),
                createEdge(decisionA, createNode("HALLWAY3", NodeType.HALLWAY)));

        MapNode decisionB = createNode("HALLWAY4", NodeType.HALLWAY);
        MapNode leftB = createNode("HALLWAY5", NodeType.HALLWAY);
        IoTLight succeedingLight = createGuidedLight("LIGHT_002", decisionB, leftB,
                createNode("HALLWAY6", NodeType.HALLWAY));
        given(iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(failingLight, succeedingLight));

        iotLightService.applyFloorGuidance(floorId, List.of(),
                Map.of(decisionA.getId(), leftA.getId(), decisionB.getId(), leftB.getId()));

        org.mockito.ArgumentCaptor<LightCommand> captor = org.mockito.ArgumentCaptor.forClass(LightCommand.class);
        verify(lightCommandJpaRepository).save(captor.capture());
        assertThat(captor.getValue().getLight()).isEqualTo(succeedingLight);
        assertThat(captor.getValue().getDirection()).isEqualTo(IoTLightDirection.LEFT);
    }

    @Test
    @DisplayName("다른 기관 유도등의 모든 변경 요청은 not-found로 거부한다")
    void mutations_otherSchool_throwNotFoundWithoutChangingState() {
        UUID lightId = UUID.randomUUID();
        List<ThrowingCallable> operations = List.of(
                () -> iotLightService.configureGuidance(
                        lightId,
                        new ConfigureGuidanceRequest(
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                        EMAIL),
                () -> iotLightService.updateLight(
                        lightId, new UpdateIoTLightRequest("변경 이름", 0.4, 0.6), EMAIL),
                () -> iotLightService.updatePiEndpoint(
                        lightId, new UpdatePiEndpointRequest("http://localhost:5000"), EMAIL),
                () -> iotLightService.enableLight(lightId, EMAIL),
                () -> iotLightService.disableLight(lightId, EMAIL),
                () -> iotLightService.deleteLight(lightId, EMAIL),
                () -> iotLightService.changeDirection(
                        lightId, new ChangeLightDirectionRequest(IoTLightDirection.OFF), EMAIL)
        );

        for (ThrowingCallable operation : operations) {
            assertThatThrownBy(operation)
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getErrorCode())
                    .isEqualTo(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND);
        }

        verifyNoInteractions(lightCommandJpaRepository, iotLightDirectionStore, trainingEventPublisher);
    }
}
