package com.saferoute.domain.congestion.controller;

import com.saferoute.domain.congestion.dto.request.ReportObservationRequest;
import com.saferoute.domain.congestion.dto.response.ObservationResponse;
import com.saferoute.domain.congestion.service.CongestionObservationService;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.service.DeviceAuthorizationService;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.global.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "혼잡도", description = "Pi 주기 관측값 수신 API")
@RestController
@RequestMapping("/api/v1/device/congestion-observations")
@RequiredArgsConstructor
public class CongestionObservationController {

    private final DeviceAuthorizationService deviceAuthorizationService;
    private final CongestionObservationService congestionObservationService;

    @Operation(
            summary = "주기 관측값 수신",
            description = """
                    Pi가 snapshotIntervalSec 주기(기본 2초)로 보내는 혼잡 관측값(평균/최대 인원, 이미지 Snapshot 인원,
                    표본 수 등)을 저장합니다.
                    Pi가 보낸 avgHeadcount와 해당 CCTV의 감시 면적(GridCell 기반)으로 BE가 직접
                    density를 계산하고, 그 값을 congestionThresholds 기준으로 판정한 congestionLevel을
                    함께 저장합니다. Pi가 보낸 localDensity/localCongestionLevel 같은 값은 없으며,
                    이 응답의 density/congestionLevel이 최종 판정값입니다.

                    frameHeadcount가 있으면 같은 감시 면적으로 frameDensity와 frameCongestionLevel도
                    계산해 저장합니다. 상세 모니터링 프레임 조회의 기존 headcount/density/congestionLevel은
                    이 이미지 Snapshot 기준 값을 반환합니다. 구버전 Pi 요청과 기존 DynamoDB 데이터에는
                    frameHeadcount가 없을 수 있으며, 이 경우 기존 최대 인원/평균 밀집도 값으로 대체합니다.

                    eventId 기준으로 멱등 처리됩니다. 같은 eventId로 재전송하면 새로 만들지 않고
                    저장된 기존 관측값을 그대로 반환하며, 최초 저장 시에만 201 Created, 이미
                    존재하면 200 OK로 응답합니다. monitoringImageKey를 함께 보내면 형식과
                    세션/CCTV/캡처시각 신원이 검증된 뒤 저장되고, 비어 있으면 이미지 없음(null)으로
                    처리됩니다. 응답의 expiresAt은 이 관측값 레코드(DynamoDB TTL)의 만료 시각입니다.

                    congestionLevel이 CROWDED 이상이면 해당 CCTV가 감시하는 모든 경로 구간(MapEdge)에
                    대해 경로 재계산이 비동기로 트리거됩니다. 관측값은 즉시 이벤트(CONGESTION_ENDED)와
                    달리 상태 전환 구분이 없어, 정상 복귀만으로는 재계산이 트리거되지 않습니다.
                    """
    )
    @PostMapping
    public ResponseEntity<ObservationResponse> reportObservation(
            @AuthenticationPrincipal DevicePrincipal principal,
            @Valid @RequestBody ReportObservationRequest request
    ) {
        Cctv cctv = deviceAuthorizationService.validateCctv(principal, request.cctvCode());
        IdempotentSaveResult<ObservationItem> saveResult =
                congestionObservationService.reportObservation(cctv, request);
        ObservationResponse response = ObservationResponse.from(saveResult.item());
        HttpStatus status = saveResult.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }
}
