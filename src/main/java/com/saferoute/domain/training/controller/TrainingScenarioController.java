package com.saferoute.domain.training.controller;

import com.saferoute.domain.training.dto.CreateScenarioDraftRequest;
import com.saferoute.domain.training.dto.FireZoneResponse;
import com.saferoute.domain.training.dto.ScenarioResponse;
import com.saferoute.domain.training.dto.UpdateScenarioRequest;
import com.saferoute.domain.training.service.FireZoneService;
import com.saferoute.domain.training.service.TrainingScenarioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "훈련 시나리오", description = "훈련 시나리오 등록/조회/수정/삭제 API")
@RestController
@RequestMapping("/api/v1/scenarios")
@RequiredArgsConstructor
public class TrainingScenarioController {

    private final TrainingScenarioService scenarioService;
    private final FireZoneService fireZoneService;

    // GET /api/v1/scenarios
    @Operation(
            summary = "훈련 시나리오 목록 조회",
            description = """
                    요청자 학교 소속의 모든 훈련 시나리오를 생성일 최신순으로 반환합니다. 아직
                    건물을 지정하지 않은 DRAFT 시나리오도 작성자(admin) 소속 기준으로 함께
                    조회됩니다.

                    각 시나리오의 deletable은 훈련이 시작된 세션(SCHEDULED가 아닌 세션)이 하나도
                    없을 때만 true입니다. 세션이 없거나 전부 시작 전(SCHEDULED)이면 true이고,
                    시작된 세션이 하나라도 있으면 과거 훈련 기록 보존을 위해 삭제가 막히므로,
                    프론트는 deletable = false인 시나리오에서는 삭제 버튼을 비활성화해야
                    합니다. reportId는 해당 시나리오의 훈련 리포트가 이미 생성되어 있으면 그
                    id를, 아직 없으면 null을 반환합니다.

                    status 필드는 DRAFT/READY 사이는 이 도메인의 상태 전환 API(POST .../drafts,
                    POST .../ready)로, 그 이후(IN_PROGRESS/COMPLETED/ERROR)는 연결된 세션의
                    생명주기에 따라 서버가 자동으로 갱신합니다. 이 목록 조회 API로 직접 바꿀 수는
                    없습니다.
                    """
    )
    @GetMapping
    public ResponseEntity<List<ScenarioResponse>> getScenarios(Authentication authentication) {
        return ResponseEntity.ok(scenarioService.getScenarios(authentication.getName()));
    }

    // GET /api/v1/scenarios/{scenarioId}
    @Operation(
            summary = "훈련 시나리오 단건 조회",
            description = """
                    요청자 학교 소속 시나리오 중 scenarioId에 해당하는 시나리오 상세 정보를
                    반환합니다. DRAFT 시나리오는 건물이 없을 수 있어 작성자(admin) 소속 기준으로
                    조회됩니다.

                    목록 조회와 달리 deletable, reportId 필드는 항상 null로 반환되므로 이 값은
                    목록 조회(GET /api/v1/scenarios) 결과를 사용해야 합니다.

                    다른 학교 소속이거나 존재하지 않는 scenarioId를 요청하면 조회에 실패합니다.
                    """
    )
    @GetMapping("/{scenarioId}")
    public ResponseEntity<ScenarioResponse> getScenario(
            @PathVariable UUID scenarioId,
            Authentication authentication) {
        return ResponseEntity.ok(scenarioService.getScenario(scenarioId, authentication.getName()));
    }

    // POST /api/v1/scenarios/drafts
    @Operation(
            summary = "훈련 시나리오 DRAFT 생성",
            description = """
                    시나리오 작성 화면에 진입할 때 미완성 상태로 임시 저장합니다. name,
                    buildingId, expectedParticipants, scheduledAt을 포함한 모든 필드가
                    선택값이며, 비워 둔 필드는 null로 저장됩니다.

                    작성자(admin)는 요청 바디로 받지 않고 JWT 인증 사용자로 고정됩니다.
                    isTemplate을 지정하지 않으면 false, fireSpreadSpeed를 지정하지 않으면
                    MEDIUM으로 기본 처리됩니다.

                    buildingId를 지정하면 요청자와 같은 학교 소속 건물이어야 하며, 조건을
                    만족하지 못하면 생성에 실패합니다.

                    생성 직후 상태는 DRAFT이며, 발화점·START 설정(evacuation-setup), 훈련
                    세션 생성 등 훈련 실행과 관련된 API는 DRAFT 상태에서 모두 차단됩니다(409).
                    PATCH /api/v1/scenarios/{scenarioId}로 나머지 필드를 채운 뒤
                    POST /api/v1/scenarios/{scenarioId}/ready로 작성을 완료해야 합니다.
                    """
    )
    @PostMapping("/drafts")
    public ResponseEntity<ScenarioResponse> createDraft(
            @Valid @RequestBody CreateScenarioDraftRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(scenarioService.createDraft(request, authentication.getName()));
    }

    // PATCH /api/v1/scenarios/{scenarioId}
    @Operation(
            summary = "훈련 시나리오 수정",
            description = """
                    요청 바디에 값이 채워진 필드만 부분 수정합니다. null인 필드는 기존 값을
                    그대로 유지하므로, 값을 지우고 싶다고 해서 null을 보내면 변경되지 않습니다.

                    DRAFT/READY 상태에서만 수정할 수 있습니다. IN_PROGRESS/COMPLETED/ERROR
                    상태의 시나리오를 수정하려 하면 409로 거부됩니다.

                    buildingId는 이 API로 지정·변경할 수 있습니다(요청자와 같은 학교 소속
                    건물이어야 함). adminId, status는 이 API로 변경할 수 없습니다. startNodeId도
                    이 API로 변경하지 않으며, POST .../evacuation-setup으로 설정한 값이 그대로
                    유지됩니다.
                    """
    )
    @PatchMapping("/{scenarioId}")
    public ResponseEntity<ScenarioResponse> updateScenario(
            @PathVariable UUID scenarioId,
            @Valid @RequestBody UpdateScenarioRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(scenarioService.updateScenario(scenarioId, request, authentication.getName()));
    }

    // POST /api/v1/scenarios/{scenarioId}/ready
    @Operation(
            summary = "훈련 시나리오 작성 완료 (DRAFT → READY)",
            description = """
                    DRAFT 시나리오의 기본 정보 작성을 완료하고 READY로 전환합니다. DRAFT가
                    아닌 시나리오에 호출하면 409(INVALID_STATUS_TRANSITION)로 거부됩니다.

                    name, buildingId, expectedParticipants, scheduledAt, adminId,
                    fireSpreadSpeed가 모두 채워져 있어야 합니다. targetEvacuationSec은 항상
                    10분(600초)으로 고정되며 요청으로 지정하거나 바꿀 수 없습니다.
                    하나라도 비어 있으면 400(TRAINING_SCENARIO_REQUIRED_FIELD_MISSING)과 함께
                    result.missingFields에 누락된 필드 이름 목록을 반환합니다.

                    이 API는 발화점(fire origin)이나 훈련 시작점(START 노드) 설정을 요구하지
                    않습니다. READY 전환 이후 시나리오 설정 화면(재사용 캔버스)에서
                    POST /api/v1/scenarios/{scenarioId}/evacuation-setup으로 별도로
                    설정합니다.
                    """
    )
    @PostMapping("/{scenarioId}/ready")
    public ResponseEntity<ScenarioResponse> readyScenario(
            @PathVariable UUID scenarioId,
            Authentication authentication) {
        return ResponseEntity.ok(scenarioService.readyScenario(scenarioId, authentication.getName()));
    }

    // DELETE /api/v1/scenarios/{scenarioId}
    @Operation(
            summary = "훈련 시나리오 삭제",
            description = """
                    시나리오를 삭제합니다. 훈련이 시작된 세션(RUNNING, 종료된 세션 등 SCHEDULED가
                    아닌 세션)이 하나라도 있으면 과거 훈련 기록 보존을 위해 삭제가 거부됩니다.
                    시작 전(SCHEDULED) 세션만 있는 경우에는 그 세션도 함께 삭제됩니다. 프론트는
                    목록 조회 응답의 deletable 필드로 삭제 가능 여부를 먼저 확인해야 합니다.

                    성공 시 본문 없이 204 No Content를 반환합니다.
                    """
    )
    @DeleteMapping("/{scenarioId}")
    public ResponseEntity<Void> deleteScenario(
            @PathVariable UUID scenarioId,
            Authentication authentication) {
        scenarioService.deleteScenario(scenarioId, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    // GET /api/v1/scenarios/{scenarioId}/fire-origin
    @Operation(
            summary = "시나리오 최초 발화점 단독 조회(하위 호환용)",
            description = """
                    POST /api/v1/scenarios/{scenarioId}/evacuation-setup으로 설정한 최초
                    발화점(FireZone.isManualAdd = true) 목록을 반환합니다. 시나리오당 최초
                    발화점은 최대 1개이므로 빈 배열 또는 원소 1개의 배열이 반환됩니다.

                    신규 시나리오 설정 화면은 발화점과 훈련 시작점을 한 번에 봐야 하므로
                    이 API 대신 GET /api/v1/scenarios/{scenarioId}/evacuation-setup을
                    사용해야 합니다. 이 API는 발화점 단독 조회가 필요한 기존 호출부를 위해
                    하위 호환용으로만 유지됩니다.

                    화재 확산 시뮬레이션이 생성한 FireZone(isManualAdd = false)은 포함되지
                    않으며, 아직 발화점을 설정하지 않았다면 빈 배열을 반환합니다.

                    다른 학교 소속이거나 존재하지 않는 scenarioId를 요청하면 조회에 실패합니다.
                    """
    )
    @GetMapping("/{scenarioId}/fire-origin")
    public ResponseEntity<List<FireZoneResponse>> getFireOrigin(
            @PathVariable UUID scenarioId,
            Authentication authentication) {
        return ResponseEntity.ok(fireZoneService.getFireOrigins(scenarioId, authentication.getName()));
    }
}
