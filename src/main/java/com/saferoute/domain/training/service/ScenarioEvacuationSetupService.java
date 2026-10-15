package com.saferoute.domain.training.service;

import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 시나리오 설정 화면 전용 - 최초 발화점(gridCell)과 훈련 시작점(START 노드 후보)을
// 하나의 요청, 하나의 트랜잭션으로 함께 저장/조회한다. 도면 관리에서 미리 등록한 START
// 후보 노드 중 하나를 선택할 뿐, 이 서비스는 새 노드를 만들지 않는다.
@Service
@RequiredArgsConstructor
public class ScenarioEvacuationSetupService {

    private final TrainingScenarioRepository scenarioRepository;
    private final FireZoneRepository fireZoneRepository;
    private final FloorGridCellRepository gridCellRepository;
    private final MapNodeJpaRepository mapNodeRepository;
    private final SchoolContextService schoolContextService;

    @Transactional
    public ScenarioEvacuationSetupResponse setup(
            UUID scenarioId, CreateScenarioEvacuationSetupRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingScenario scenario = scenarioRepository
                .findForUpdateByIdAndAdmin_SchoolName(scenarioId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));

        if (scenario.getStatus() != ScenarioStatus.READY) {
            throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        }
        // 발화점과 시작 노드가 둘 다 있을 때만 "이미 설정됨"으로 막는다. 도면 재업로드·초기화(시작 노드 SET NULL)나
        // 그리드 재생성(발화점 셀 CASCADE 삭제)으로 한쪽만 사라지면, 훈련 시작도 설정 재등록도 못 하는 상태에 갇히고
        // 설정을 지우는 API도 없다. 그래서 반쪽만 남은 경우엔 남은 발화점을 지우고 새로 등록하게 한다.
        boolean hasOrigin = fireZoneRepository.existsByScenario_IdAndIsManualAddTrue(scenarioId);
        if (hasOrigin && scenario.getStartNode() != null) {
            throw new ApiException(TrainingErrorCode.SCENARIO_EVACUATION_SETUP_ALREADY_EXISTS);
        }
        if (hasOrigin) {
            fireZoneRepository.deleteAllByScenario_IdAndIsManualAddTrue(scenarioId);
        }

        FloorGridCell cell = gridCellRepository.findById(request.fireOriginGridCellId())
                .orElseThrow(() -> new ApiException(GridErrorCode.GRID_CELL_NOT_FOUND));
        MapNode startNode = mapNodeRepository.findById(request.startNodeId())
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.MAP_NODE_NOT_FOUND));

        UUID buildingId = scenario.getBuildingId();
        if (!cell.getFloor().getBuilding().getId().equals(buildingId)) {
            throw new ApiException(GridErrorCode.GRID_CELL_FLOOR_MISMATCH);
        }
        if (!startNode.getFloor().getBuilding().getId().equals(buildingId)) {
            throw new ApiException(TrainingErrorCode.START_NODE_BUILDING_MISMATCH);
        }
        boolean validStartType = startNode.getType() == NodeType.START
                || (startNode.getType() == NodeType.DOOR && startNode.isStartCandidate());
        if (!validStartType) {
            throw new ApiException(TrainingErrorCode.START_NODE_TYPE_INVALID);
        }
        if (!cell.getFloor().getId().equals(startNode.getFloor().getId())) {
            throw new ApiException(TrainingErrorCode.FIRE_ORIGIN_START_FLOOR_MISMATCH);
        }

        // 여기서는 FloorGridCell.markFired()를 호출하지 않는다. 설정은 시나리오별 정적 데이터이고,
        // isFired는 실제 훈련 중에만 의미 있는 동적 상태라 훈련 시작 시점(TrainingSessionService.start)에
        // 활성화한다.
        FireZone fireZone = fireZoneRepository.save(FireZone.createOrigin(scenario, cell.getFloor(), cell));
        scenario.assignStartNode(startNode);

        return ScenarioEvacuationSetupResponse.of(scenario, fireZone, cell, startNode, fireZone.getAddedAt());
    }

    @Transactional(readOnly = true)
    public ScenarioEvacuationSetupResponse get(UUID scenarioId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingScenario scenario = scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));

        MapNode startNode = scenario.getStartNode();
        List<FireZone> origins = fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId);
        if (startNode == null || origins.isEmpty()) {
            return ScenarioEvacuationSetupResponse.notConfigured(scenario);
        }

        FireZone fireZone = origins.get(0);
        return ScenarioEvacuationSetupResponse.of(
                scenario, fireZone, fireZone.getGridCell(), startNode, fireZone.getAddedAt());
    }
}
