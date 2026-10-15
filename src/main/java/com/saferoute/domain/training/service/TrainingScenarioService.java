package com.saferoute.domain.training.service;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.training.dto.CreateScenarioDraftRequest;
import com.saferoute.domain.training.dto.ScenarioResponse;
import com.saferoute.domain.training.dto.UpdateScenarioRequest;
import com.saferoute.domain.training.entity.ScenarioStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.report.repository.TrainingReportRepository;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.BuildingErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.error.UserErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainingScenarioService {

    private final TrainingScenarioRepository scenarioRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final FireZoneRepository fireZoneRepository;
    private final TrainingReportRepository trainingReportRepository;
    private final BuildingRepository buildingRepository;
    private final UserRepository userRepository;
    private final SchoolContextService schoolContextService;

    // 목록 조회 (deletable = 연결된 훈련 세션이 하나도 없는 시나리오인지 여부, reportId = 리포트가 있으면 그 id)
    public List<ScenarioResponse> getScenarios(String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        List<TrainingScenario> scenarios =
                scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(schoolName);
        if (scenarios.isEmpty()) {
            return List.of();
        }

        List<UUID> scenarioIds = scenarios.stream().map(TrainingScenario::getId).toList();
        Set<UUID> scenarioIdsWithSession = trainingSessionRepository.findScenarioIdsWithAnySession(scenarioIds);
        Map<UUID, String> reportIdsByScenarioId = trainingReportRepository.findReportIdsByScenarioIds(scenarioIds)
                .stream()
                .collect(Collectors.toMap(
                        TrainingReportRepository.ScenarioReportId::getScenarioId,
                        TrainingReportRepository.ScenarioReportId::getReportId));

        return scenarios.stream()
                .map(scenario -> ScenarioResponse.from(
                        scenario,
                        !scenarioIdsWithSession.contains(scenario.getId()),
                        reportIdsByScenarioId.get(scenario.getId())))
                .toList();
    }

    // 단건 조회
    public ScenarioResponse getScenario(UUID id, String email) {
        return ScenarioResponse.from(findByIdAndEmail(id, email));
    }

    // DRAFT 생성. 미완성 필드를 허용하며, 작성자는 요청 바디가 아니라 JWT 인증 사용자로 고정된다.
    @Transactional
    public ScenarioResponse createDraft(CreateScenarioDraftRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        User admin = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(UserErrorCode.USER_NOT_FOUND));
        Building building = resolveBuilding(request.getBuildingId(), schoolName);

        TrainingScenario scenario = TrainingScenario.createDraft(
                request.getName(),
                request.getExpectedParticipants(),
                request.getScheduledAt(),
                request.getIsTemplate(),
                request.getFireSpreadSpeed(),
                building,
                admin
        );
        return ScenarioResponse.from(scenarioRepository.save(scenario));
    }

    // 부분 수정. DRAFT/READY 상태에서만 허용되며, 요청에 포함된 값만 반영한다.
    @Transactional
    public ScenarioResponse updateScenario(UUID id, UpdateScenarioRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingScenario scenario = scenarioRepository.findByIdAndAdmin_SchoolName(id, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));
        if (!isEditable(scenario.getStatus())) {
            throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        }

        Building building = resolveBuilding(request.getBuildingId(), schoolName);
        if (building != null && scenario.getStartNode() != null
                && !building.getId().equals(scenario.getBuildingId())) {
            throw new ApiException(TrainingErrorCode.SCENARIO_EVACUATION_SETUP_ALREADY_EXISTS);
        }
        scenario.update(
                request.getName(),
                request.getExpectedParticipants(),
                request.getScheduledAt(),
                request.getIsTemplate(),
                request.getFireSpreadSpeed(),
                building
        );
        return ScenarioResponse.from(scenario);
    }

    // 작성 완료(DRAFT → READY). 필수값이 하나라도 비어 있으면 누락 필드 목록과 함께 거부한다.
    @Transactional
    public ScenarioResponse readyScenario(UUID id, String email) {
        TrainingScenario scenario = findByIdAndEmail(id, email);
        if (scenario.getStatus() != ScenarioStatus.DRAFT) {
            throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
        }

        List<String> missingFields = collectMissingRequiredFields(scenario);
        if (!missingFields.isEmpty()) {
            throw new ApiException(
                    TrainingErrorCode.TRAINING_SCENARIO_REQUIRED_FIELD_MISSING,
                    Map.of("missingFields", missingFields));
        }

        scenario.markReady();
        return ScenarioResponse.from(scenario);
    }

    // 삭제 (훈련 세션이 하나라도 연결된 시나리오는 삭제 불가 - 과거 훈련 기록 보존을 위해)
    @Transactional
    public void deleteScenario(UUID id, String email) {
        TrainingScenario scenario = findByIdAndEmail(id, email);
        if (trainingSessionRepository.existsByScenario_Id(id)) {
            throw new ApiException(TrainingErrorCode.SCENARIO_DELETE_NOT_ALLOWED);
        }
        // 세션이 없다는 건 훈련이 한 번도 돌지 않았다는 뜻이라, 남아 있는 FireZone은 대피 설정의 발화점(설정값)뿐이다.
        // fire_zones.scenario_id가 시나리오를 참조해 그대로 지우면 FK 위반이 나므로 먼저 지운다. DB FK를 CASCADE로
        // 두지 않는 이유는, 다른 경로로 시나리오가 지워질 때 실제 훈련 기록(확산 이력)까지 조용히 지워지지 않게 하기 위함이다.
        fireZoneRepository.deleteAllByScenario_Id(id);
        scenarioRepository.delete(scenario);
    }

    private TrainingScenario findByIdAndEmail(UUID id, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        return scenarioRepository.findByIdAndAdmin_SchoolName(id, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));
    }

    private Building resolveBuilding(UUID buildingId, String schoolName) {
        if (buildingId == null) {
            return null;
        }
        return buildingRepository.findByIdAndSchoolNameForUpdate(buildingId, schoolName)
                .orElseThrow(() -> new ApiException(BuildingErrorCode.BUILDING_NOT_FOUND));
    }

    private boolean isEditable(ScenarioStatus status) {
        return status == ScenarioStatus.DRAFT || status == ScenarioStatus.READY;
    }

    private List<String> collectMissingRequiredFields(TrainingScenario scenario) {
        List<String> missingFields = new ArrayList<>();
        if (scenario.getName() == null || scenario.getName().isBlank()) {
            missingFields.add("name");
        }
        if (scenario.getBuildingId() == null) {
            missingFields.add("buildingId");
        }
        if (scenario.getExpectedParticipants() == null) {
            missingFields.add("expectedParticipants");
        }
        if (scenario.getScheduledAt() == null) {
            missingFields.add("scheduledAt");
        }
        if (scenario.getAdminId() == null) {
            missingFields.add("adminId");
        }
        if (scenario.getFireSpreadSpeed() == null) {
            missingFields.add("fireSpreadSpeed");
        }
        return missingFields;
    }
}
