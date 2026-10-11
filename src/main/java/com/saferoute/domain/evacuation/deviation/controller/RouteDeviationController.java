package com.saferoute.domain.evacuation.deviation.controller;

import com.saferoute.domain.evacuation.deviation.dto.RouteDeviationResponse;
import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.global.api.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "경로 이탈률", description = "유도등 안내 방향과 CCTV 탐지 결과를 비교한 경로 이탈률 조회 API")
@RestController
@RequestMapping("/api/v1/lights/{lightId}/deviation")
@RequiredArgsConstructor
public class RouteDeviationController {

    private final RouteDeviationService routeDeviationService;

    @Operation(
            summary = "유도등 경로 이탈률 조회",
            description = """
                    지정한 IoT 유도등이 훈련 세션 동안 안내한 방향과, 같은 통로를 감시하는
                    CCTV가 실제로 인원을 탐지한 위치를 시간 축으로 대조해 경로 이탈률을
                    계산합니다. totalObservedWindows는 유도등이 좌/우 중 한쪽을 가리키고 있던
                    관측 구간 수, deviatedWindows는 그중 안내 방향의 반대쪽 CCTV에서도
                    인원이 탐지되어 이탈로 집계된 구간 수이며, deviationRate는 그 비율입니다.

                    CCTV는 headcount/밀집도만 보고할 뿐 개별 인원의 이동 경로를 알 수 없으므로,
                    "좌/우 통로를 각각 감시하는 CCTV가 분리되어 있다"는 전제 하에 어느 쪽
                    CCTV에서 인원이 탐지됐는지를 실제 이동 방향의 대리 지표로 사용합니다. 유도등이
                    OFF이거나 BOTH(평상시 양방향 안내)를 가리키던 구간은 이탈 여부를 판단할
                    기준이 없어 집계에서 제외됩니다.

                    대상 유도등에 좌/우 안내 경로(guidance)가 아직 설정되지 않았거나, 좌/우 통로를
                    구분해 감시하는 CCTV 매핑을 찾을 수 없으면 오류가 발생합니다. 관측 구간이
                    하나도 없으면 totalObservedWindows/deviatedWindows는 0, deviationRate는
                    0.0으로 반환됩니다.
                    """
    )
    @GetMapping
    public ResponseEntity<ApiResponse<RouteDeviationResponse>> getDeviationRate(
            @PathVariable UUID lightId,
            @RequestParam UUID trainingSessionId,
            Authentication authentication
    ) {
        RouteDeviationResponse response =
                routeDeviationService.calculate(lightId, trainingSessionId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
