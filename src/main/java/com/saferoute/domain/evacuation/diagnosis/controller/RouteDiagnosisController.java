package com.saferoute.domain.evacuation.diagnosis.controller;

import com.saferoute.domain.evacuation.diagnosis.dto.FloorRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.dto.ScenarioRouteDiagnosisResponse;
import com.saferoute.domain.evacuation.diagnosis.service.RouteDiagnosisService;
import com.saferoute.global.api.response.ApiResponse;
import com.saferoute.global.api.response.EvacuationSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "경로 연결 진단", description = "경로 재탐색이 가능한 도면·시나리오인지 미리 확인하는 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RouteDiagnosisController {

    private final RouteDiagnosisService routeDiagnosisService;

    @Operation(
            summary = "층 경로 연결 진단",
            description = """
                    도면 관리 화면의 훈련 준비 체크리스트 "대체 경로" 항목에 사용합니다.
                    시작 지점이 정해지지 않은 화면이므로 모든 시작 후보를 기준으로 그래프 연결만 확인합니다.

                    - unreachableNodeIds: 어떤 탈출구(EXIT 대상)에도 도달할 수 없는 노드.
                      CCTV·유도등 위치 노드(CUSTOM)와 방 노드(ROOM)는 대피 경로 노드가 아니므로 포함하지 않습니다.
                    - undesignatedStairNodeIds: 타입이 STAIR·EXIT인데 탈출구로 지정하지 않은 노드. 참고 정보라
                      ready에는 영향이 없습니다.
                    - startCandidates: 시작 후보(START 타입, 또는 시작 후보로 지정한 DOOR)마다 갈 수 있는 탈출구 수.
                    - ready: 탈출구가 있고, 끊긴 노드가 없고, 모든 시작 후보가 탈출구 2곳 이상에 닿으면 true.

                    엣지 방향(단방향/양방향)을 경로 탐색과 같은 기준으로 반영합니다. 결과는 저장하지 않으며
                    훈련 시작을 막지 않습니다(안내용).
                    """
    )
    @GetMapping("/floors/{floorId}/route-diagnosis")
    public ResponseEntity<ApiResponse<FloorRouteDiagnosisResponse>> diagnoseFloor(
            @PathVariable UUID floorId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                EvacuationSuccessCode.FLOOR_ROUTE_DIAGNOSIS_FOUND,
                routeDiagnosisService.diagnoseFloor(floorId, authentication.getName())));
    }

    @Operation(
            summary = "시나리오 경로 진단 (화재 확산 시뮬레이션)",
            description = """
                    시나리오 화면의 훈련 미리보기와 시나리오 시작 확인창에 사용합니다.
                    최초 발화 위치에서 실제 훈련과 같은 규칙(걸을 수 있는 상하좌우 셀로 한 칸씩, 시나리오
                    확산 속도별 간격)으로 화재 확산을 메모리에서 계산하고, 불이 안내 경로에 닿을 때마다
                    실제 재탐색과 같은 기준으로 우회 경로를 찾아 예상 진행을 돌려줍니다.

                    - events: RECALCULATED(재탐색으로 경로가 바뀜) / BLOCKED(우회 경로가 없어 막힘), 시간순.
                    - recalculationCount: 예상 재탐색 횟수.
                    - blockedAfterSeconds: 모든 경로가 막히는 예상 시점. 훈련 제한 시간(10분) 안에 막히지 않으면 null.
                    - unreachableExitNodeIds: 탈출구로 지정됐지만 훈련 시작 시점(최초 발화 지점만 막힌 상태)에
                      시작 지점에서 갈 수 없는 탈출구. 엣지가 끊긴 경우와 발화 지점에 막힌 경우를 모두 포함합니다.
                    - undesignatedStairNodeIds: 타입이 STAIR·EXIT인데 탈출구로 지정하지 않은 노드.
                    - hasAlternativeRoute: 불이 안내 경로에 처음 닿았을 때 재탐색이 일어나면 true.
                      닿자마자 막히면 false이며, 이때 시작 확인창을 띄웁니다. 불이 경로에 닿지 않으면 true입니다.

                    가정: 관리자가 재탐색 요청을 바로 승인하고, 혼잡은 없다고 봅니다. 결과는 저장하지 않습니다.
                    시작 지점·발화 위치가 없거나, 출구가 없거나, 처음부터 출구로 갈 수 없으면 훈련 시작과 같은
                    오류를 돌려줍니다.
                    """
    )
    @GetMapping("/scenarios/{scenarioId}/route-diagnosis")
    public ResponseEntity<ApiResponse<ScenarioRouteDiagnosisResponse>> diagnoseScenario(
            @PathVariable UUID scenarioId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                EvacuationSuccessCode.SCENARIO_ROUTE_DIAGNOSIS_FOUND,
                routeDiagnosisService.diagnoseScenario(scenarioId, authentication.getName())));
    }
}
