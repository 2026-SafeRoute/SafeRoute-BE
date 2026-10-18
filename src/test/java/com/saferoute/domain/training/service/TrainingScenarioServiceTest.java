package com.saferoute.domain.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.report.repository.TrainingReportRepository;
import com.saferoute.domain.training.dto.CreateScenarioDraftRequest;
import com.saferoute.domain.training.dto.ScenarioResponse;
import com.saferoute.domain.training.dto.UpdateScenarioRequest;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.ScenarioStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TrainingScenarioServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @Mock
    private com.saferoute.domain.training.repository.FireZoneRepository fireZoneRepository;

    @InjectMocks
    private TrainingScenarioService trainingScenarioService;

    @Mock
    private TrainingScenarioRepository scenarioRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private TrainingReportRepository trainingReportRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SchoolContextService schoolContextService;

    private TrainingScenario scenarioWithId(UUID id) {
        TrainingScenario scenario = TrainingScenario.create(
                "테스트 시나리오",
                10,
                Instant.now(),
                false,
                FireSpreadSpeed.MEDIUM,
                mock(Building.class),
                mock(User.class),
                null);
        ReflectionTestUtils.setField(scenario, "id", id);
        return scenario;
    }

    private TrainingScenario draftScenarioWithId(UUID id) {
        TrainingScenario scenario = TrainingScenario.createDraft(
                null, null, null, false, FireSpreadSpeed.MEDIUM, null, mock(User.class));
        ReflectionTestUtils.setField(scenario, "id", id);
        return scenario;
    }

    // === createDraft ===

    @Test
    @DisplayName("건물 없이 이름만 채운 DRAFT를 생성한다")
    void createDraft_withoutBuilding_succeeds() {
        CreateScenarioDraftRequest request = new CreateScenarioDraftRequest(
                "2026년 4월 정기 훈련", null, null, null, false, FireSpreadSpeed.MEDIUM);
        User admin = mock(User.class);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(admin));
        given(scenarioRepository.save(org.mockito.ArgumentMatchers.any(TrainingScenario.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ScenarioResponse response = trainingScenarioService.createDraft(request, EMAIL);

        assertThat(response.getStatus()).isEqualTo(ScenarioStatus.DRAFT);
        assertThat(response.getBuildingId()).isNull();
        verify(buildingRepository, never()).findByIdAndSchoolNameForUpdate(any(), any());
    }

    @Test
    @DisplayName("buildingId를 포함한 DRAFT를 생성하면 학교 소속 건물을 조회해 연결한다")
    void createDraft_withBuilding_resolvesBuilding() {
        UUID buildingId = UUID.randomUUID();
        Building building = mock(Building.class);
        User admin = mock(User.class);
        CreateScenarioDraftRequest request = new CreateScenarioDraftRequest(
                null, buildingId, null, null, false, null);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(admin));
        given(buildingRepository.findByIdAndSchoolNameForUpdate(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(building.getId()).willReturn(buildingId);
        given(scenarioRepository.save(org.mockito.ArgumentMatchers.any(TrainingScenario.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ScenarioResponse response = trainingScenarioService.createDraft(request, EMAIL);

        assertThat(response.getBuildingId()).isEqualTo(buildingId);
    }

    // === updateScenario ===

    @Test
    @DisplayName("DRAFT 시나리오는 buildingId를 포함해 부분 수정할 수 있다")
    void updateScenario_draftScenario_allowsBuildingIdChange() {
        UUID scenarioId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        TrainingScenario scenario = draftScenarioWithId(scenarioId);
        Building building = mock(Building.class);
        UpdateScenarioRequest request = new UpdateScenarioRequest(
                "이름 채우기", buildingId, 30, Instant.now(), null, null);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(buildingRepository.findByIdAndSchoolNameForUpdate(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(building.getId()).willReturn(buildingId);

        ScenarioResponse response = trainingScenarioService.updateScenario(scenarioId, request, EMAIL);

        assertThat(response.getName()).isEqualTo("이름 채우기");
        assertThat(response.getBuildingId()).isEqualTo(buildingId);
    }

    @Test
    @DisplayName("IN_PROGRESS 시나리오는 수정할 수 없다")
    void updateScenario_inProgressScenario_throws() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        scenario.markInProgress();
        UpdateScenarioRequest request = new UpdateScenarioRequest(
                "변경 시도", null, null, null, null, null);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        assertThatThrownBy(() -> trainingScenarioService.updateScenario(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName("발화점·START 설정이 완료된 시나리오는 다른 건물로 변경할 수 없다")
    void updateScenario_setupCompleted_rejectsDifferentBuilding() {
        UUID scenarioId = UUID.randomUUID();
        UUID currentBuildingId = UUID.randomUUID();
        UUID newBuildingId = UUID.randomUUID();
        Building currentBuilding = mock(Building.class);
        given(currentBuilding.getId()).willReturn(currentBuildingId);
        MapNode startNode = mock(MapNode.class);
        TrainingScenario scenario = TrainingScenario.create(
                "테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.MEDIUM,
                currentBuilding, mock(User.class), startNode);
        ReflectionTestUtils.setField(scenario, "id", scenarioId);
        Building newBuilding = mock(Building.class);
        given(newBuilding.getId()).willReturn(newBuildingId);
        UpdateScenarioRequest request = new UpdateScenarioRequest(
                null, newBuildingId, null, null, null, null);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(buildingRepository.findByIdAndSchoolNameForUpdate(newBuildingId, SCHOOL_NAME))
                .willReturn(Optional.of(newBuilding));

        assertThatThrownBy(() -> trainingScenarioService.updateScenario(scenarioId, request, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SCENARIO_EVACUATION_SETUP_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("발화점·START 설정이 완료된 시나리오도 같은 건물로 재지정하는 수정은 허용된다")
    void updateScenario_setupCompleted_allowsSameBuilding() {
        UUID scenarioId = UUID.randomUUID();
        UUID buildingId = UUID.randomUUID();
        Building building = mock(Building.class);
        given(building.getId()).willReturn(buildingId);
        MapNode startNode = mock(MapNode.class);
        TrainingScenario scenario = TrainingScenario.create(
                "테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.MEDIUM,
                building, mock(User.class), startNode);
        ReflectionTestUtils.setField(scenario, "id", scenarioId);
        UpdateScenarioRequest request = new UpdateScenarioRequest(
                "이름만 변경", buildingId, null, null, null, null);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(buildingRepository.findByIdAndSchoolNameForUpdate(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));

        ScenarioResponse response = trainingScenarioService.updateScenario(scenarioId, request, EMAIL);

        assertThat(response.getName()).isEqualTo("이름만 변경");
        assertThat(response.getBuildingId()).isEqualTo(buildingId);
    }

    // === readyScenario ===

    @Test
    @DisplayName("필수값이 모두 채워진 DRAFT는 READY로 전환된다")
    void readyScenario_allRequiredFieldsPresent_transitionsToReady() {
        UUID scenarioId = UUID.randomUUID();
        Building building = mock(Building.class);
        given(building.getId()).willReturn(UUID.randomUUID());
        User admin = mock(User.class);
        given(admin.getId()).willReturn(UUID.randomUUID());
        TrainingScenario scenario = TrainingScenario.create(
                "테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.MEDIUM, building, admin, null);
        ReflectionTestUtils.setField(scenario, "id", scenarioId);
        ReflectionTestUtils.setField(scenario, "status", ScenarioStatus.DRAFT);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        ScenarioResponse response = trainingScenarioService.readyScenario(scenarioId, EMAIL);

        assertThat(response.getStatus()).isEqualTo(ScenarioStatus.READY);
    }

    @Test
    @DisplayName("필수값이 비어 있는 DRAFT는 누락 필드 목록과 함께 READY 전환이 거부된다")
    void readyScenario_missingRequiredFields_throwsWithMissingFieldList() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = draftScenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        assertThatThrownBy(() -> trainingScenarioService.readyScenario(scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;
                    assertThat(apiException.getErrorCode())
                            .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_REQUIRED_FIELD_MISSING);
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> result = (Map<String, List<String>>) apiException.getResult();
                    assertThat(result.get("missingFields"))
                            .contains("name", "buildingId", "expectedParticipants", "scheduledAt");
                });
    }

    @Test
    @DisplayName("공백만 있는 이름은 채워진 것으로 보지 않고 READY 전환을 거부한다")
    void readyScenario_blankName_treatedAsMissing() {
        UUID scenarioId = UUID.randomUUID();
        Building building = mock(Building.class);
        given(building.getId()).willReturn(UUID.randomUUID());
        User admin = mock(User.class);
        given(admin.getId()).willReturn(UUID.randomUUID());
        TrainingScenario scenario = TrainingScenario.create(
                "  ", 10, Instant.now(), false, FireSpreadSpeed.MEDIUM, building, admin, null);
        ReflectionTestUtils.setField(scenario, "id", scenarioId);
        ReflectionTestUtils.setField(scenario, "status", ScenarioStatus.DRAFT);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        assertThatThrownBy(() -> trainingScenarioService.readyScenario(scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> {
                    ApiException apiException = (ApiException) exception;
                    assertThat(apiException.getErrorCode())
                            .isEqualTo(TrainingErrorCode.TRAINING_SCENARIO_REQUIRED_FIELD_MISSING);
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> result = (Map<String, List<String>>) apiException.getResult();
                    assertThat(result.get("missingFields")).containsExactly("name");
                });
    }

    @Test
    @DisplayName("이미 READY인 시나리오는 다시 READY 전환할 수 없다")
    void readyScenario_alreadyReady_throws() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));

        assertThatThrownBy(() -> trainingScenarioService.readyScenario(scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.INVALID_STATUS_TRANSITION);
    }

    // === getScenarios (deletable) ===

    @Test
    @DisplayName("훈련이 시작된 세션이 없는(세션이 없거나 SCHEDULED뿐인) 시나리오는 deletable=true로 응답한다")
    void getScenarios_scenarioWithoutSession_isDeletable() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(SCHOOL_NAME))
                .willReturn(List.of(scenario));
        given(trainingSessionRepository.findScenarioIdsWithStartedSession(List.of(scenarioId)))
                .willReturn(Set.of());
        given(trainingReportRepository.findReportIdsByScenarioIds(List.of(scenarioId)))
                .willReturn(List.of());

        List<ScenarioResponse> responses = trainingScenarioService.getScenarios(EMAIL);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getDeletable()).isTrue();
    }

    @Test
    @DisplayName("훈련이 시작된 세션이 하나라도 있는 시나리오는 deletable=false로 응답한다")
    void getScenarios_scenarioWithSession_isNotDeletable() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(SCHOOL_NAME))
                .willReturn(List.of(scenario));
        given(trainingSessionRepository.findScenarioIdsWithStartedSession(List.of(scenarioId)))
                .willReturn(Set.of(scenarioId));
        given(trainingReportRepository.findReportIdsByScenarioIds(List.of(scenarioId)))
                .willReturn(List.of());

        List<ScenarioResponse> responses = trainingScenarioService.getScenarios(EMAIL);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getDeletable()).isFalse();
    }

    @Test
    @DisplayName("리포트가 생성된 시나리오는 목록에서 reportId를 함께 응답한다")
    void getScenarios_scenarioWithReport_returnsReportId() {
        UUID scenarioId = UUID.randomUUID();
        String reportId = "abc123XYZ0";
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(SCHOOL_NAME))
                .willReturn(List.of(scenario));
        given(trainingSessionRepository.findScenarioIdsWithStartedSession(List.of(scenarioId)))
                .willReturn(Set.of(scenarioId));
        given(trainingReportRepository.findReportIdsByScenarioIds(List.of(scenarioId)))
                .willReturn(List.of(scenarioReportId(scenarioId, reportId)));

        List<ScenarioResponse> responses = trainingScenarioService.getScenarios(EMAIL);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getReportId()).isEqualTo(reportId);
    }

    @Test
    @DisplayName("리포트가 없는 시나리오는 목록에서 reportId가 null이다")
    void getScenarios_scenarioWithoutReport_returnsNullReportId() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(SCHOOL_NAME))
                .willReturn(List.of(scenario));
        given(trainingSessionRepository.findScenarioIdsWithStartedSession(List.of(scenarioId)))
                .willReturn(Set.of());
        given(trainingReportRepository.findReportIdsByScenarioIds(List.of(scenarioId)))
                .willReturn(List.of());

        List<ScenarioResponse> responses = trainingScenarioService.getScenarios(EMAIL);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getReportId()).isNull();
    }

    private TrainingReportRepository.ScenarioReportId scenarioReportId(UUID scenarioId, String reportId) {
        return new TrainingReportRepository.ScenarioReportId() {
            @Override
            public UUID getScenarioId() {
                return scenarioId;
            }

            @Override
            public String getReportId() {
                return reportId;
            }
        };
    }

    @Test
    @DisplayName("시나리오가 하나도 없으면 세션/리포트 조회 없이 빈 목록을 반환한다")
    void getScenarios_noScenarios_returnsEmptyListWithoutQueryingSessions() {
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findAllByAdmin_SchoolNameOrderByCreatedAtDesc(SCHOOL_NAME))
                .willReturn(List.of());

        List<ScenarioResponse> responses = trainingScenarioService.getScenarios(EMAIL);

        assertThat(responses).isEmpty();
        verify(trainingSessionRepository, never()).findScenarioIdsWithStartedSession(org.mockito.ArgumentMatchers.any());
        verify(trainingReportRepository, never()).findReportIdsByScenarioIds(org.mockito.ArgumentMatchers.any());
    }

    // === deleteScenario ===

    @Test
    @DisplayName("훈련이 시작된 세션이 없으면 시나리오를 삭제하고 시작 전 세션도 함께 지운다")
    void deleteScenario_withoutSession_deletesScenario() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(trainingSessionRepository.existsByScenario_IdAndStatusNot(scenarioId, TrainingStatus.SCHEDULED))
                .willReturn(false);

        trainingScenarioService.deleteScenario(scenarioId, EMAIL);

        verify(trainingSessionRepository).deleteAllByScenario_Id(scenarioId);
        verify(scenarioRepository).delete(scenario);
    }

    @Test
    @DisplayName("훈련이 시작된 세션이 하나라도 있으면 시나리오 삭제가 거부된다")
    void deleteScenario_withSession_throwsException() {
        UUID scenarioId = UUID.randomUUID();
        TrainingScenario scenario = scenarioWithId(scenarioId);
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(scenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, SCHOOL_NAME))
                .willReturn(Optional.of(scenario));
        given(trainingSessionRepository.existsByScenario_IdAndStatusNot(scenarioId, TrainingStatus.SCHEDULED))
                .willReturn(true);

        assertThatThrownBy(() -> trainingScenarioService.deleteScenario(scenarioId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SCENARIO_DELETE_NOT_ALLOWED);
        verify(trainingSessionRepository, never()).deleteAllByScenario_Id(scenarioId);
        verify(scenarioRepository, never()).delete(scenario);
    }
}
