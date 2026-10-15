package com.saferoute.domain.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.dto.CreateScenarioEvacuationSetupRequest;
import com.saferoute.domain.training.dto.ScenarioEvacuationSetupResponse;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.ScenarioStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.GridErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
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

@ExtendWith(MockitoExtension.class)
class ScenarioEvacuationSetupServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private ScenarioEvacuationSetupService scenarioEvacuationSetupService;

    @Mock
    private TrainingScenarioRepository scenarioRepository;
    @Mock
    private FireZoneRepository fireZoneRepository;
    @Mock
    private FloorGridCellRepository gridCellRepository;
    @Mock
    private MapNodeJpaRepository mapNodeRepository;
    @Mock
    private SchoolContextService schoolContextService;

    private final UUID scenarioId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();
    private final UUID floorId = UUID.randomUUID();
    private final UUID gridCellId = UUID.randomUUID();
    private final UUID startNodeId = UUID.randomUUID();

    private TrainingScenario scenario;
    private FloorGridCell cell;
    private MapNode startNode;
    private Floor floor;
    private CreateScenarioEvacuationSetupRequest request;

    @BeforeEach
    void setUp() {
        scenario = mock(TrainingScenario.class);
        cell = mock(FloorGridCell.class);
        startNode = mock(MapNode.class);
        floor = mock(Floor.class);
        Building building = mock(Building.class);
        request = new CreateScenarioEvacuationSetupRequest(gridCellId, startNodeId);

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        lenient().when(scenarioRepository.findForUpdateByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .thenReturn(Optional.of(scenario));
        lenient().when(scenario.getStatus()).thenReturn(ScenarioStatus.READY);
        lenient().when(scenario.getId()).thenReturn(scenarioId);
        lenient().when(scenario.getBuildingId()).thenReturn(buildingId);
        lenient().when(gridCellRepository.findById(gridCellId)).thenReturn(Optional.of(cell));
        lenient().when(mapNodeRepository.findById(startNodeId)).thenReturn(Optional.of(startNode));
        lenient().when(cell.getFloor()).thenReturn(floor);
        lenient().when(startNode.getFloor()).thenReturn(floor);
        lenient().when(startNode.getType()).thenReturn(NodeType.START);
        lenient().when(floor.getId()).thenReturn(floorId);
        lenient().when(floor.getBuilding()).thenReturn(building);
        lenient().when(building.getId()).thenReturn(buildingId);
        lenient().when(fireZoneRepository.save(any(FireZone.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubNodeFixtureDetails() {
        given(cell.getId()).willReturn(gridCellId);
        given(cell.getRowIndex()).willReturn(3);
        given(cell.getColumnIndex()).willReturn(7);
        given(cell.getCenterX()).willReturn(0.72);
        given(cell.getCenterY()).willReturn(0.35);
        given(startNode.getId()).willReturn(startNodeId);
        given(startNode.getCode()).willReturn("START_3F_01");
        given(startNode.getName()).willReturn("3층 동측 대피 시작점");
        given(startNode.getX()).willReturn(0.15);
        given(startNode.getY()).willReturn(0.42);
    }

    @Test
    @DisplayName("발화점과 시작점을 함께 설정하면 시나리오에 START 노드가 연결된다")
    void setup_success() {
        stubNodeFixtureDetails();

        ScenarioEvacuationSetupResponse response =
                scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL);

        verify(scenario).assignStartNode(startNode);
        verify(fireZoneRepository).save(any(FireZone.class));
        verify(cell, never()).markFired();
        assertThat(response.scenarioId()).isEqualTo(scenarioId);
        assertThat(response.buildingId()).isEqualTo(buildingId);
        assertThat(response.floorId()).isEqualTo(floorId);
        assertThat(response.fireOrigin().gridCellId()).isEqualTo(gridCellId);
        assertThat(response.fireOrigin().rowIndex()).isEqualTo(3);
        assertThat(response.fireOrigin().columnIndex()).isEqualTo(7);
        assertThat(response.startNode().nodeId()).isEqualTo(startNodeId);
        assertThat(response.startNode().type()).isEqualTo(NodeType.START);
    }

    @Test
    @DisplayName("다른 학교 소속 시나리오는 설정할 수 없다")
    void setup_otherSchool_throws() {
        given(scenarioRepository.findForUpdateByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND);

        verify(gridCellRepository, never()).findById(any());
        verify(mapNodeRepository, never()).findById(any());
        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("발화점 셀이 존재하지 않으면 설정할 수 없다")
    void setup_gridCellNotFound_throws() {
        given(gridCellRepository.findById(gridCellId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(GridErrorCode.GRID_CELL_NOT_FOUND);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("START 노드가 존재하지 않으면 설정할 수 없다")
    void setup_startNodeNotFound_throws() {
        given(mapNodeRepository.findById(startNodeId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(EvacuationErrorCode.MAP_NODE_NOT_FOUND);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("발화점이 시나리오 건물과 다르면 설정할 수 없다")
    void setup_fireOriginBuildingMismatch_throws() {
        Building otherBuilding = mock(Building.class);
        given(otherBuilding.getId()).willReturn(UUID.randomUUID());
        Floor otherFloor = mock(Floor.class);
        given(otherFloor.getBuilding()).willReturn(otherBuilding);
        given(cell.getFloor()).willReturn(otherFloor);

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(GridErrorCode.GRID_CELL_FLOOR_MISMATCH);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("START 노드가 시나리오 건물과 다르면 설정할 수 없다")
    void setup_startNodeBuildingMismatch_throws() {
        Building otherBuilding = mock(Building.class);
        given(otherBuilding.getId()).willReturn(UUID.randomUUID());
        Floor otherFloor = mock(Floor.class);
        given(otherFloor.getBuilding()).willReturn(otherBuilding);
        given(startNode.getFloor()).willReturn(otherFloor);

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.START_NODE_BUILDING_MISMATCH);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("선택한 노드 타입이 START가 아니면 설정할 수 없다")
    void setup_startNodeTypeInvalid_throws() {
        given(startNode.getType()).willReturn(NodeType.HALLWAY);

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.START_NODE_TYPE_INVALID);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("발화점과 START 노드가 서로 다른 층이면 설정할 수 없다")
    void setup_floorMismatch_throws() {
        Floor otherFloor = mock(Floor.class);
        Building building = mock(Building.class);
        given(building.getId()).willReturn(buildingId);
        given(otherFloor.getId()).willReturn(UUID.randomUUID());
        given(otherFloor.getBuilding()).willReturn(building);
        given(startNode.getFloor()).willReturn(otherFloor);

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.FIRE_ORIGIN_START_FLOOR_MISMATCH);

        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("발화점과 startNode가 모두 설정된 시나리오는 다시 설정할 수 없다")
    void setup_fullyConfigured_throws() {
        given(fireZoneRepository.existsByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(true);
        given(scenario.getStartNode()).willReturn(mock(MapNode.class));

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SCENARIO_EVACUATION_SETUP_ALREADY_EXISTS);

        verify(gridCellRepository, never()).findById(any());
        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
        verify(fireZoneRepository, never()).deleteAllByScenario_IdAndIsManualAddTrue(any());
    }

    @Test
    @DisplayName("도면 변경으로 startNode만 지워지고 발화점이 남았으면, 남은 발화점을 지우고 다시 등록한다")
    void setup_onlyFireOriginRemains_replacesOriginAndRegisters() {
        stubNodeFixtureDetails();
        given(fireZoneRepository.existsByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(true);

        scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL);

        verify(fireZoneRepository).deleteAllByScenario_IdAndIsManualAddTrue(scenarioId);
        verify(fireZoneRepository).save(any(FireZone.class));
        verify(scenario).assignStartNode(startNode);
    }

    @Test
    @DisplayName("그리드 재생성으로 발화점만 지워지고 startNode가 남았으면, 다시 등록해 startNode를 교체한다")
    void setup_onlyStartNodeRemains_registersAgain() {
        stubNodeFixtureDetails();
        // 발화점이 없으면 startNode는 확인할 필요 없이 재등록을 허용하므로(서비스가 읽지 않음) lenient로 둔다.
        lenient().when(scenario.getStartNode()).thenReturn(mock(MapNode.class));

        scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL);

        verify(fireZoneRepository, never()).deleteAllByScenario_IdAndIsManualAddTrue(any());
        verify(fireZoneRepository).save(any(FireZone.class));
        verify(scenario).assignStartNode(startNode);
    }

    @Test
    @DisplayName("READY가 아닌 시나리오는 설정할 수 없다")
    void setup_scenarioNotReady_throws() {
        given(scenario.getStatus()).willReturn(ScenarioStatus.IN_PROGRESS);

        assertThatThrownBy(() -> scenarioEvacuationSetupService.setup(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);

        verify(gridCellRepository, never()).findById(any());
        verify(scenario, never()).assignStartNode(any());
        verify(fireZoneRepository, never()).save(any());
    }

    @Test
    @DisplayName("설정 전 조회는 fireOrigin/startNode가 모두 null인 200 응답이다")
    void get_notConfigured_returnsNullFields() {
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(scenario.getStartNode()).willReturn(null);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId)).willReturn(List.of());

        ScenarioEvacuationSetupResponse response = scenarioEvacuationSetupService.get(scenarioId, EMAIL);

        assertThat(response.fireOrigin()).isNull();
        assertThat(response.startNode()).isNull();
        assertThat(response.configuredAt()).isNull();
        assertThat(response.scenarioId()).isEqualTo(scenarioId);
    }

    @Test
    @DisplayName("설정 후 조회는 발화점·시작점·좌표를 함께 반환한다")
    void get_configured_returnsFields() {
        stubNodeFixtureDetails();
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(scenario.getStartNode()).willReturn(startNode);
        FireZone fireOrigin = mock(FireZone.class);
        given(fireOrigin.getGridCell()).willReturn(cell);
        given(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId))
                .willReturn(List.of(fireOrigin));

        ScenarioEvacuationSetupResponse response = scenarioEvacuationSetupService.get(scenarioId, EMAIL);

        assertThat(response.floorId()).isEqualTo(floorId);
        assertThat(response.fireOrigin().gridCellId()).isEqualTo(gridCellId);
        assertThat(response.startNode().nodeId()).isEqualTo(startNodeId);
        assertThat(response.startNode().name()).isEqualTo("3층 동측 대피 시작점");
    }

    @Test
    @DisplayName("다른 학교 소속 시나리오의 설정은 조회할 수 없다")
    void get_otherSchool_throws() {
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> scenarioEvacuationSetupService.get(scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND);
    }
}
