package com.saferoute.domain.training.controller;

import com.saferoute.domain.training.dto.CurrentCctvStateListApiResponse;
import com.saferoute.domain.training.dto.CurrentCctvStateListResponse;
import com.saferoute.domain.training.dto.MonitoringCameraListApiResponse;
import com.saferoute.domain.training.dto.MonitoringCameraListResponse;
import com.saferoute.domain.training.dto.MonitoringContextApiResponse;
import com.saferoute.domain.training.dto.MonitoringContextResponse;
import com.saferoute.domain.training.dto.MonitoringEventListApiResponse;
import com.saferoute.domain.training.dto.MonitoringEventListResponse;
import com.saferoute.domain.training.dto.MonitoringFrameListApiResponse;
import com.saferoute.domain.training.dto.MonitoringFrameListResponse;
import com.saferoute.domain.training.service.TrainingMonitoringService;
import com.saferoute.global.api.response.ApiResponse;
import com.saferoute.global.api.response.TrainingSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "훈련 모니터링",
        description = "훈련 세션의 카메라별 최신 주기 캡처, CCTV별 현재 혼잡 상태, 프레임, 이벤트 타임라인 조회 API. "
                + "세션 상태와 무관하게 조회 가능하며 종료된 세션은 마지막으로 저장된 데이터를 그대로 반환한다."
)
@RestController
@RequestMapping("/api/v1/sessions/{sessionId}/monitoring")
@RequiredArgsConstructor
@Validated
public class TrainingMonitoringController {

    private final TrainingMonitoringService trainingMonitoringService;

    @Operation(
            summary = "카메라별 최신 캡처 목록 조회",
            description = """
                    훈련 세션의 건물에 설치된 활성 CCTV 카드 목록을 반환합니다. 세션 상태와 무관하게
                    조회할 수 있으며, 종료된 세션은 훈련 중 마지막으로 저장된 캡처를 그대로 보여줍니다.

                    각 카메라의 thumbnailUrl은 최신 Observation의 monitoringImageKey로 발급한
                    S3 presigned GET URL이며 영구 URL이 아닙니다. capturedAt과 urlExpiresAt은 모두
                    Unix epoch milliseconds 단위입니다.

                    아직 캡처가 없는 카메라도 목록에 포함되며 thumbnailUrl, capturedAt,
                    urlExpiresAt이 모두 null로 반환됩니다. 클라이언트는 이 경우 placeholder를
                    표시해야 합니다. 'n초 전 캡처'와 같은 상대 시간은 capturedAt을 기준으로
                    클라이언트에서 계산합니다.

                    목록은 층 번호 오름차순, 같은 층에서는 CCTV 코드 오름차순으로 정렬됩니다.
                    비활성 CCTV는 반환하지 않습니다. 최신 상태가 필요하면 화면에서 주기적으로
                    이 API를 다시 호출해 주세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "모니터링 카메라 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = MonitoringCameraListApiResponse.class
                            ),
                            examples = {
                                    @ExampleObject(
                                            name = "최신 캡처가 있는 카메라",
                                            value = """
                                                    {
                                                      "isSuccess": true,
                                                      "code": "TRAINING_SUCCESS_006",
                                                      "message": "모니터링 카메라 목록 조회에 성공했습니다.",
                                                      "result": {
                                                        "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                        "cameras": [
                                                          {
                                                            "cctvId": "67b86e33-7874-494c-855f-e591e7847c09",
                                                            "code": "CCTV_001",
                                                            "name": "CAM-1",
                                                            "buildingName": "A동",
                                                            "floorName": "3층",
                                                            "location": "A동 3층",
                                                            "thumbnailUrl": "https://example-bucket.s3.amazonaws.com/training/session/monitoring/CCTV_001/frame.jpg",
                                                            "capturedAt": 1787722095000,
                                                            "urlExpiresAt": 1787725695000
                                                          }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "캡처가 없는 카메라",
                                            value = """
                                                    {
                                                      "isSuccess": true,
                                                      "code": "TRAINING_SUCCESS_006",
                                                      "message": "모니터링 카메라 목록 조회에 성공했습니다.",
                                                      "result": {
                                                        "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                        "cameras": [
                                                          {
                                                            "cctvId": "8b767966-b423-4d1b-bc2a-4107de97ad72",
                                                            "code": "CCTV_002",
                                                            "name": "CAM-2",
                                                            "buildingName": "A동",
                                                            "floorName": "1층",
                                                            "location": "A동 1층",
                                                            "thumbnailUrl": null,
                                                            "capturedAt": null,
                                                            "urlExpiresAt": null
                                                          }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT가 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON401",
                                      "message": "인증이 필요합니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "MANAGER 권한이 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON403",
                                      "message": "접근 권한이 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나 요청자와 다른 학교의 세션",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "TRAINING001",
                                      "message": "훈련 세션을 찾을 수 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "S3 presigned GET URL 발급 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "S3_ERROR_005",
                                      "message": "S3 이미지 조회 URL 발급에 실패했습니다."
                                    }
                                    """)
                    )
            )
    })
    @GetMapping("/cameras")
    public ResponseEntity<ApiResponse<MonitoringCameraListResponse>> getCameras(
            @Parameter(
                    description = "조회할 훈련 세션의 UUID (세션 상태와 무관하게 조회 가능)",
                    required = true,
                    example = "d669294e-55e1-4c00-bf67-229d89b76948"
            )
            @PathVariable UUID sessionId,
            Authentication authentication
    ) {
        MonitoringCameraListResponse response =
                trainingMonitoringService.getCameras(sessionId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                TrainingSuccessCode.MONITORING_CAMERA_LIST_FOUND,
                response
        ));
    }

    @Operation(
            summary = "모니터링 세션 정보 조회",
            description = """
                    모니터링 상세 화면 헤더에 필요한 세션 기본 정보(시나리오명, 건물명, 상태,
                    시작/종료 시각, 경과 시간)와 전역 설정값(저장 간격, CCTV 현재 상태 stale
                    판정 기준)을 한 번에 반환합니다.

                    startedAt/endedAt은 훈련 전체의 시작/종료 시각입니다. 프레임 목록
                    (GET .../monitoring/cameras/{cctvId}/frames)의 windowStart/windowEnd
                    (개별 프레임의 분석 구간)와는 다른 시간 축이므로 혼동하지 마세요.

                    이 API는 다른 모니터링 조회 API와 동일하게 세션 상태와 무관하게 조회할 수
                    있습니다. 아직 시작 전(SCHEDULED)인 세션은 startedAt/elapsedSeconds가 모두
                    null입니다. RUNNING이면 elapsedSeconds가 현재 시각 기준으로 계속 늘어나고,
                    종료된 세션이면 종료 시각 기준으로 고정된 값이 반환됩니다.

                    snapshotIntervalSec, stateStaleAfterSec는 세션별 값이 아니라 전역 혼잡 설정
                    (CongestionConfig)의 현재 값입니다. 훈련 진행 중 관리자가 설정을 바꾸면
                    이 API가 반환하는 값도 함께 바뀝니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "모니터링 세션 정보 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = MonitoringContextApiResponse.class
                            ),
                            examples = @ExampleObject(
                                    name = "진행 중인 세션",
                                    value = """
                                            {
                                              "isSuccess": true,
                                              "code": "TRAINING_SUCCESS_011",
                                              "message": "모니터링 세션 정보 조회에 성공했습니다.",
                                              "result": {
                                                "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                "scenarioName": "3학년 A동 화재 대피 훈련",
                                                "buildingName": "A동",
                                                "status": "RUNNING",
                                                "startedAt": 1787722000000,
                                                "endedAt": null,
                                                "elapsedSeconds": 95,
                                                "snapshotIntervalSec": 2,
                                                "stateStaleAfterSec": 15
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT가 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON401",
                                      "message": "인증이 필요합니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "MANAGER 권한이 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON403",
                                      "message": "접근 권한이 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나 요청자와 다른 학교의 세션",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "TRAINING001",
                                      "message": "훈련 세션을 찾을 수 없습니다."
                                    }
                                    """)
                    )
            )
    })
    @GetMapping("/context")
    public ResponseEntity<ApiResponse<MonitoringContextResponse>> getContext(
            @Parameter(
                    description = "조회할 훈련 세션의 UUID (세션 상태와 무관하게 조회 가능)",
                    required = true,
                    example = "d669294e-55e1-4c00-bf67-229d89b76948"
            )
            @PathVariable UUID sessionId,
            Authentication authentication
    ) {
        MonitoringContextResponse response =
                trainingMonitoringService.getContext(sessionId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                TrainingSuccessCode.MONITORING_CONTEXT_FOUND,
                response
        ));
    }

    @Operation(
            summary = "CCTV별 현재 혼잡 상태 조회",
            description = """
                    훈련 세션의 건물에 설치된 활성 CCTV별 현재 혼잡 상태를 반환합니다. 세션 상태와
                    무관하게 조회할 수 있으며, 종료된 세션은 훈련 중 마지막으로 저장된 상태를 그대로
                    보여줍니다(실시간으로 갱신되지 않습니다).

                    기준 데이터는 snapshotIntervalSec 주기(기본 2초) Observation입니다. 혼잡 시작/상승/해소 즉시 이벤트는
                    이 상태를 갱신하지 않으며, 이벤트 타임라인(GET /monitoring/events)과 WebSocket
                    알림에만 반영됩니다.

                    avgHeadcount는 최근 관측 구간의 평균 인원, peakHeadcount는 같은 구간의
                    순간 최대 인원입니다. density와 congestionLevel은 서버가 계산한 값을 그대로
                    반환합니다.

                    아직 상태가 없는 CCTV도 목록에서 누락되지 않고 avgHeadcount 등이 모두 null인
                    상태로 포함되며, 이때 stale은 항상 true입니다. 마지막 관측(lastDetectedAt) 이후
                    설정된 stateStaleAfterSec가 지난 CCTV도 stale이 true로 반환되므로, 클라이언트는
                    congestionLevel이 남아있더라도 stale이 true면 이를 NORMAL이 아니라 '정보 없음/
                    오래됨'으로 표시해야 합니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "CCTV별 현재 혼잡 상태 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = CurrentCctvStateListApiResponse.class
                            ),
                            examples = {
                                    @ExampleObject(
                                            name = "상태가 있는 CCTV",
                                            value = """
                                                    {
                                                      "isSuccess": true,
                                                      "code": "TRAINING_SUCCESS_010",
                                                      "message": "CCTV 현재 혼잡 상태 조회에 성공했습니다.",
                                                      "result": {
                                                        "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                        "observedAt": 1787722095000,
                                                        "states": [
                                                          {
                                                            "cctvId": "67b86e33-7874-494c-855f-e591e7847c09",
                                                            "cctvCode": "CCTV_001",
                                                            "cctvName": "CAM-1",
                                                            "buildingName": "A동",
                                                            "floorName": "3층",
                                                            "location": "A동 3층",
                                                            "avgHeadcount": 8.6,
                                                            "peakHeadcount": 12,
                                                            "density": 0.42,
                                                            "congestionLevel": "CROWDED",
                                                            "lastDetectedAt": 1787722095000,
                                                            "stale": false,
                                                            "configVersion": 3
                                                          }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "상태가 없거나 오래된 CCTV",
                                            value = """
                                                    {
                                                      "isSuccess": true,
                                                      "code": "TRAINING_SUCCESS_010",
                                                      "message": "CCTV 현재 혼잡 상태 조회에 성공했습니다.",
                                                      "result": {
                                                        "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                        "observedAt": 1787722095000,
                                                        "states": [
                                                          {
                                                            "cctvId": "8b767966-b423-4d1b-bc2a-4107de97ad72",
                                                            "cctvCode": "CCTV_002",
                                                            "cctvName": "CAM-2",
                                                            "buildingName": "A동",
                                                            "floorName": "1층",
                                                            "location": "A동 1층",
                                                            "avgHeadcount": null,
                                                            "peakHeadcount": null,
                                                            "density": null,
                                                            "congestionLevel": null,
                                                            "lastDetectedAt": null,
                                                            "stale": true,
                                                            "configVersion": null
                                                          }
                                                        ]
                                                      }
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT가 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON401",
                                      "message": "인증이 필요합니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "MANAGER 권한이 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON403",
                                      "message": "접근 권한이 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나 요청자와 다른 학교의 세션",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "TRAINING001",
                                      "message": "훈련 세션을 찾을 수 없습니다."
                                    }
                                    """)
                    )
            )
    })
    @GetMapping("/current-states")
    public ResponseEntity<ApiResponse<CurrentCctvStateListResponse>> getCurrentStates(
            @Parameter(
                    description = "조회할 훈련 세션의 UUID (세션 상태와 무관하게 조회 가능)",
                    required = true,
                    example = "d669294e-55e1-4c00-bf67-229d89b76948"
            )
            @PathVariable UUID sessionId,
            Authentication authentication
    ) {
        CurrentCctvStateListResponse response =
                trainingMonitoringService.getCurrentStates(sessionId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                TrainingSuccessCode.MONITORING_CURRENT_STATE_LIST_FOUND,
                response
        ));
    }

    @Operation(
            summary = "카메라별 프레임 목록 조회",
            description = """
                    특정 CCTV에서 캡처된 프레임을 최신순으로 페이지네이션 조회합니다.
                    상세 모니터링 화면의 큰 이미지와 하단 프레임 탐색 UI에 사용됩니다. 세션 상태와
                    무관하게 조회할 수 있으며, 종료된 세션은 훈련 중 마지막으로 저장된 프레임까지
                    그대로 조회됩니다.

                    cursor를 생략하면 가장 최신 프레임부터 반환합니다. 다음 페이지가 있으면
                    응답의 nextCursor를 다음 요청의 cursor로 그대로 전달하면 되며, hasNext가
                    false이면 더 이상 조회할 프레임이 없다는 뜻입니다.

                    각 프레임의 imageUrl은 S3 presigned GET URL이며 영구 URL이 아닙니다.
                    이미지 업로드가 아직 끝나지 않은 프레임은 imageUrl, urlExpiresAt이 null로
                    반환됩니다. capturedAt은 Unix epoch milliseconds 단위입니다.

                    windowStart/windowEnd는 이 프레임이 대표하는 분석 구간(snapshotIntervalSec, 기본 2초)의
                    시작/종료 시각이고, capturedAt은 그 구간을 대표해 저장된 프레임이 실제로
                    촬영된 시각입니다. 훈련 전체의 시작/종료 시각(GET .../monitoring/context의
                    startedAt/endedAt)과는 다른 시간 축이므로 혼동하지 마세요.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "카메라별 프레임 목록 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = MonitoringFrameListApiResponse.class
                            ),
                            examples = @ExampleObject(
                                    name = "프레임 목록",
                                    value = """
                                            {
                                              "isSuccess": true,
                                              "code": "TRAINING_SUCCESS_007",
                                              "message": "카메라별 프레임 목록 조회에 성공했습니다.",
                                              "result": {
                                                "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                "cctvId": "67b86e33-7874-494c-855f-e591e7847c09",
                                                "frames": [
                                                  {
                                                    "frameId": "3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11",
                                                    "capturedAt": 1787722095000,
                                                    "windowStart": 1787722090000,
                                                    "windowEnd": 1787722095000,
                                                    "imageUrl": "https://example-bucket.s3.amazonaws.com/training/session/monitoring/CCTV_001/frame.jpg",
                                                    "urlExpiresAt": 1787725695000,
                                                    "headcount": 12,
                                                    "density": 0.42,
                                                    "congestionLevel": "CROWDED"
                                                  }
                                                ],
                                                "nextCursor": "MTc4NzcyMjA5NTAwMA",
                                                "hasNext": true,
                                                "totalCount": 137
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "cursor 형식이 올바르지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON400",
                                      "message": "입력값이 올바르지 않습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT가 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON401",
                                      "message": "인증이 필요합니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "MANAGER 권한이 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON403",
                                      "message": "접근 권한이 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나, 요청자와 다른 학교의 세션이거나, 세션이 속한 건물의 CCTV가 아님",
                    content = @Content(
                            mediaType = "application/json",
                            examples = {
                                    @ExampleObject(
                                            name = "세션을 찾을 수 없음",
                                            value = """
                                                    {
                                                      "isSuccess": false,
                                                      "code": "TRAINING001",
                                                      "message": "훈련 세션을 찾을 수 없습니다."
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "CCTV를 찾을 수 없음",
                                            value = """
                                                    {
                                                      "isSuccess": false,
                                                      "code": "CCTV001",
                                                      "message": "CCTV를 찾을 수 없습니다."
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "500",
                    description = "S3 presigned GET URL 발급 실패",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "S3_ERROR_005",
                                      "message": "S3 이미지 조회 URL 발급에 실패했습니다."
                                    }
                                    """)
                    )
            )
    })
    @GetMapping("/cameras/{cctvId}/frames")
    public ResponseEntity<ApiResponse<MonitoringFrameListResponse>> getFrames(
            @Parameter(
                    description = "조회할 훈련 세션의 UUID (세션 상태와 무관하게 조회 가능)",
                    required = true,
                    example = "d669294e-55e1-4c00-bf67-229d89b76948"
            )
            @PathVariable UUID sessionId,
            @Parameter(
                    description = "프레임을 조회할 CCTV의 UUID",
                    required = true,
                    example = "67b86e33-7874-494c-855f-e591e7847c09"
            )
            @PathVariable UUID cctvId,
            @Parameter(description = "한 페이지에 조회할 프레임 개수", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @Parameter(
                    description = "이전 응답의 nextCursor. 생략하면 최신 프레임부터 조회",
                    example = "MTc4NzcyMjA5NTAwMA"
            )
            @RequestParam(required = false) String cursor,
            Authentication authentication
    ) {
        MonitoringFrameListResponse response = trainingMonitoringService.getFrames(
                sessionId, cctvId, limit, cursor, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                TrainingSuccessCode.MONITORING_FRAME_LIST_FOUND,
                response
        ));
    }

    @Operation(
            summary = "이벤트 타임라인 조회",
            description = """
                    훈련 세션에서 발생한 혼잡 감지 이벤트(CongestionEventItem)와 경로 재탐색 이벤트
                    (RouteRecalculation)를 발생 시각 최신순으로 통합해 커서 페이지네이션으로
                    반환합니다. 세션 상태와 무관하게 조회할 수 있습니다.

                    cursor를 생략하면 가장 최신 이벤트부터 반환합니다. 다음 페이지가 있으면
                    응답의 nextCursor를 다음 요청의 cursor로 그대로 전달하면 되며, hasNext가
                    false이면 더 이상 조회할 이벤트가 없다는 뜻입니다.

                    경로 재탐색 한 건은 요청 시점 이벤트 하나로 시작해, 관리자가 승인/거절하거나
                    시스템이 자동 취소하면 해소 시점 이벤트가 하나 더 추가됩니다.

                    cctvCode를 지정하면 해당 CCTV와 관련된 이벤트만 반환합니다. 이 필터는 DynamoDB
                    쿼리 단계에서 적용되므로, 다른 CCTV 이벤트가 많아도 원하는 CCTV의 이벤트가
                    누락되지 않습니다.

                    AI 분석 시작, 경로 이탈, 위험 구역 진입 이벤트는 아직 Pi/AI 쪽 이벤트 수신
                    계약이 없어 이 API에 포함되지 않습니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "이벤트 타임라인 조회 성공",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(
                                    implementation = MonitoringEventListApiResponse.class
                            ),
                            examples = @ExampleObject(
                                    name = "이벤트 타임라인",
                                    value = """
                                            {
                                              "isSuccess": true,
                                              "code": "TRAINING_SUCCESS_009",
                                              "message": "모니터링 이벤트 타임라인 조회에 성공했습니다.",
                                              "result": {
                                                "sessionId": "d669294e-55e1-4c00-bf67-229d89b76948",
                                                "events": [
                                                  {
                                                    "eventId": "3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11",
                                                    "type": "CONGESTION_STARTED",
                                                    "severity": "WARNING",
                                                    "occurredAt": 1787722095000,
                                                    "cctvCode": "CCTV_001",
                                                    "congestionLevel": "CAUTION",
                                                    "message": "혼잡 감지 · CCTV_001"
                                                  }
                                                ],
                                                "nextCursor": "MTc4NzcyMjA5NTAwMHwzYzlmN2UyYS0zYjM5LTRmMGEtOWYwYS02YTJiNmIxZjVhMTE",
                                                "hasNext": true
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "cursor 형식이 올바르지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON400",
                                      "message": "입력값이 올바르지 않습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "JWT가 없거나 유효하지 않음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON401",
                                      "message": "인증이 필요합니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "MANAGER 권한이 없음",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "COMMON403",
                                      "message": "접근 권한이 없습니다."
                                    }
                                    """)
                    )
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "세션이 없거나 요청자와 다른 학교의 세션",
                    content = @Content(
                            mediaType = "application/json",
                            examples = @ExampleObject(value = """
                                    {
                                      "isSuccess": false,
                                      "code": "TRAINING001",
                                      "message": "훈련 세션을 찾을 수 없습니다."
                                    }
                                    """)
                    )
            )
    })
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<MonitoringEventListResponse>> getEvents(
            @Parameter(
                    description = "조회할 훈련 세션의 UUID (세션 상태와 무관하게 조회 가능)",
                    required = true,
                    example = "d669294e-55e1-4c00-bf67-229d89b76948"
            )
            @PathVariable UUID sessionId,
            @Parameter(description = "특정 CCTV의 이벤트만 조회하려면 지정", example = "CCTV_001")
            @RequestParam(required = false) String cctvCode,
            @Parameter(description = "한 페이지에 조회할 이벤트 개수", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @Parameter(
                    description = "이전 응답의 nextCursor. 생략하면 최신 이벤트부터 조회",
                    example = "MTc4NzcyMjA5NTAwMHwzYzlmN2UyYS0zYjM5LTRmMGEtOWYwYS02YTJiNmIxZjVhMTE"
            )
            @RequestParam(required = false) String cursor,
            Authentication authentication
    ) {
        MonitoringEventListResponse response = trainingMonitoringService.getEvents(
                sessionId, cctvCode, limit, cursor, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(
                TrainingSuccessCode.MONITORING_EVENT_LIST_FOUND,
                response
        ));
    }
}
