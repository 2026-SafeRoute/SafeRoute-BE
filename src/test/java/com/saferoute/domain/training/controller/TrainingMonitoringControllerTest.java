package com.saferoute.domain.training.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.training.dto.CurrentCctvStateListResponse;
import com.saferoute.domain.training.dto.CurrentCctvStateResponse;
import com.saferoute.domain.training.dto.MonitoringCameraListResponse;
import com.saferoute.domain.training.dto.MonitoringContextResponse;
import com.saferoute.domain.training.dto.MonitoringCameraResponse;
import com.saferoute.domain.training.dto.MonitoringEventListResponse;
import com.saferoute.domain.training.dto.MonitoringEventResponse;
import com.saferoute.domain.training.dto.MonitoringEventSeverity;
import com.saferoute.domain.training.dto.MonitoringEventType;
import com.saferoute.domain.training.dto.MonitoringFrameListResponse;
import com.saferoute.domain.training.dto.MonitoringFrameResponse;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.service.TrainingMonitoringService;
import com.saferoute.global.api.code.ErrorCode;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.S3ErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.config.SecurityConfig;
import com.saferoute.global.security.JwtAuthenticationFilter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = TrainingMonitoringController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {JwtAuthenticationFilter.class, SecurityConfig.class}
        )
)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "manager@saferoute.com")
class TrainingMonitoringControllerTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CCTV_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND_CCTV_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String EMAIL = "manager@saferoute.com";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrainingMonitoringService trainingMonitoringService;

    @Test
    void 모니터링_세션_정보를_공통_응답으로_반환한다() throws Exception {
        MonitoringContextResponse context = new MonitoringContextResponse(
                SESSION_ID,
                "3학년 A동 화재 대피 훈련",
                "A동",
                TrainingStatus.RUNNING,
                1_787_722_000_000L,
                null,
                95L,
                2,
                15
        );
        given(trainingMonitoringService.getContext(SESSION_ID, EMAIL)).willReturn(context);

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/context", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("TRAINING_SUCCESS_011"))
                .andExpect(jsonPath("$.result.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.result.scenarioName").value("3학년 A동 화재 대피 훈련"))
                .andExpect(jsonPath("$.result.buildingName").value("A동"))
                .andExpect(jsonPath("$.result.status").value("RUNNING"))
                .andExpect(jsonPath("$.result.startedAt").value(1_787_722_000_000L))
                .andExpect(jsonPath("$.result.endedAt").value((Object) null))
                .andExpect(jsonPath("$.result.elapsedSeconds").value(95))
                .andExpect(jsonPath("$.result.snapshotIntervalSec").value(2))
                .andExpect(jsonPath("$.result.stateStaleAfterSec").value(15));
    }

    @Test
    void 모니터링_정보_조회시_세션을_찾을_수_없으면_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getContext(SESSION_ID, EMAIL))
                .willThrow(new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/context", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRAINING001"));
    }

    @Test
    void 카메라별_최신_캡처_목록을_공통_응답으로_반환한다() throws Exception {
        MonitoringCameraResponse firstCamera = new MonitoringCameraResponse(
                CCTV_ID,
                "CCTV_001",
                "CAM-1",
                "A동",
                "3층",
                "A동 3층",
                "https://example.com/frame.jpg",
                1_787_722_095_000L,
                1_787_725_695_000L
        );
        MonitoringCameraResponse secondCamera = new MonitoringCameraResponse(
                SECOND_CCTV_ID,
                "CCTV_002",
                "CAM-2",
                "A동",
                "4층",
                "A동 4층",
                "https://example.com/second-frame.jpg",
                1_787_722_096_000L,
                1_787_725_696_000L
        );
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willReturn(new MonitoringCameraListResponse(
                        SESSION_ID,
                        List.of(firstCamera, secondCamera)
                ));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("TRAINING_SUCCESS_006"))
                .andExpect(jsonPath("$.result.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.result.cameras[0].cctvId").value(CCTV_ID.toString()))
                .andExpect(jsonPath("$.result.cameras[0].code").value("CCTV_001"))
                .andExpect(jsonPath("$.result.cameras[0].floorName").value("3층"))
                .andExpect(jsonPath("$.result.cameras[0].thumbnailUrl")
                        .value("https://example.com/frame.jpg"))
                .andExpect(jsonPath("$.result.cameras[0].capturedAt")
                        .value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.cameras[0].urlExpiresAt")
                        .value(1_787_725_695_000L))
                .andExpect(jsonPath("$.result.cameras[1].cctvId")
                        .value(SECOND_CCTV_ID.toString()))
                .andExpect(jsonPath("$.result.cameras[1].code").value("CCTV_002"))
                .andExpect(jsonPath("$.result.cameras[1].thumbnailUrl")
                        .value("https://example.com/second-frame.jpg"))
                .andExpect(jsonPath("$.result.cameras[1].capturedAt")
                        .value(1_787_722_096_000L));
    }

    @Test
    void 일부_CCTV에만_캡처가_있어도_전체_목록과_null_이미지_필드를_반환한다() throws Exception {
        MonitoringCameraResponse capturedCamera = new MonitoringCameraResponse(
                CCTV_ID,
                "CCTV_001",
                "CAM-1",
                "A동",
                "1층",
                "A동 1층",
                "https://example.com/frame.jpg",
                1_787_722_095_000L,
                1_787_725_695_000L
        );
        MonitoringCameraResponse pendingCamera = new MonitoringCameraResponse(
                SECOND_CCTV_ID,
                "CCTV_002",
                "CAM-2",
                "A동",
                "2층",
                "A동 2층",
                null,
                null,
                null
        );
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willReturn(new MonitoringCameraListResponse(
                        SESSION_ID,
                        List.of(capturedCamera, pendingCamera)
                ));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.cameras.length()").value(2))
                .andExpect(jsonPath("$.result.cameras[0].thumbnailUrl")
                        .value("https://example.com/frame.jpg"))
                .andExpect(jsonPath("$.result.cameras[1].code").value("CCTV_002"))
                .andExpect(jsonPath("$.result.cameras[1].thumbnailUrl").value((Object) null))
                .andExpect(jsonPath("$.result.cameras[1].capturedAt").value((Object) null))
                .andExpect(jsonPath("$.result.cameras[1].urlExpiresAt").value((Object) null));
    }

    @Test
    void 캡처가_없는_카메라는_이미지_필드를_null로_반환한다() throws Exception {
        MonitoringCameraResponse camera = new MonitoringCameraResponse(
                CCTV_ID,
                "CCTV_001",
                "CAM-1",
                "A동",
                "1층",
                "A동 1층",
                null,
                null,
                null
        );
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willReturn(new MonitoringCameraListResponse(SESSION_ID, List.of(camera)));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.cameras[0].thumbnailUrl").value((Object) null))
                .andExpect(jsonPath("$.result.cameras[0].capturedAt").value((Object) null))
                .andExpect(jsonPath("$.result.cameras[0].urlExpiresAt").value((Object) null));
    }

    @Test
    void 세션을_찾을_수_없으면_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willThrow(new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRAINING001"));
    }

    @Test
    void 다른_학교의_세션이면_존재_여부를_노출하지_않고_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willThrow(new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("TRAINING001"))
                .andExpect(jsonPath("$.message").value("훈련 세션을 찾을 수 없습니다."));
    }

    @Test
    void S3_조회_URL_발급에_실패하면_500과_정의된_오류_응답을_반환한다() throws Exception {
        given(trainingMonitoringService.getCameras(SESSION_ID, EMAIL))
                .willThrow(new ApiException(S3ErrorCode.PRESIGNED_GET_URL_GENERATION_FAILED));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.isSuccess").value(false))
                .andExpect(jsonPath("$.code").value("S3_ERROR_005"))
                .andExpect(jsonPath("$.message")
                        .value("S3 이미지 조회 URL 발급에 실패했습니다."));
    }

    @Test
    void CCTV별_현재_혼잡_상태를_공통_응답으로_반환한다() throws Exception {
        CurrentCctvStateResponse state = new CurrentCctvStateResponse(
                CCTV_ID,
                "CCTV_001",
                "CAM-1",
                "A동",
                "3층",
                "A동 3층",
                8.6,
                12,
                0.42,
                CongestionLevel.CROWDED,
                1_787_722_095_000L,
                false,
                3L
        );
        given(trainingMonitoringService.getCurrentStates(SESSION_ID, EMAIL))
                .willReturn(new CurrentCctvStateListResponse(SESSION_ID, 1_787_722_095_000L, List.of(state)));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/current-states", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("TRAINING_SUCCESS_010"))
                .andExpect(jsonPath("$.result.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.result.observedAt").value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.states[0].cctvId").value(CCTV_ID.toString()))
                .andExpect(jsonPath("$.result.states[0].cctvCode").value("CCTV_001"))
                .andExpect(jsonPath("$.result.states[0].avgHeadcount").value(8.6))
                .andExpect(jsonPath("$.result.states[0].peakHeadcount").value(12))
                .andExpect(jsonPath("$.result.states[0].density").value(0.42))
                .andExpect(jsonPath("$.result.states[0].congestionLevel").value("CROWDED"))
                .andExpect(jsonPath("$.result.states[0].lastDetectedAt").value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.states[0].stale").value(false))
                .andExpect(jsonPath("$.result.states[0].configVersion").value(3));
    }

    @Test
    void 상태가_없는_CCTV는_null_필드와_stale_true로_반환한다() throws Exception {
        CurrentCctvStateResponse state = new CurrentCctvStateResponse(
                CCTV_ID,
                "CCTV_002",
                "CAM-2",
                "A동",
                "1층",
                "A동 1층",
                null,
                null,
                null,
                null,
                null,
                true,
                null
        );
        given(trainingMonitoringService.getCurrentStates(SESSION_ID, EMAIL))
                .willReturn(new CurrentCctvStateListResponse(SESSION_ID, 1_787_722_095_000L, List.of(state)));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/current-states", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.states[0].avgHeadcount").value((Object) null))
                .andExpect(jsonPath("$.result.states[0].congestionLevel").value((Object) null))
                .andExpect(jsonPath("$.result.states[0].stale").value(true));
    }

    @Test
    void 현재_상태_조회시_세션을_찾을_수_없으면_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getCurrentStates(SESSION_ID, EMAIL))
                .willThrow(new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/current-states", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRAINING001"));
    }

    @Test
    void 카메라별_프레임_목록을_공통_응답으로_반환한다() throws Exception {
        MonitoringFrameResponse frame = new MonitoringFrameResponse(
                "3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11",
                1_787_722_095_000L,
                1_787_722_090_000L,
                1_787_722_095_000L,
                "https://example.com/frame.jpg",
                1_787_725_695_000L,
                12,
                0.42,
                CongestionLevel.CROWDED
        );
        given(trainingMonitoringService.getFrames(SESSION_ID, CCTV_ID, 20, null, EMAIL))
                .willReturn(new MonitoringFrameListResponse(
                        SESSION_ID, CCTV_ID, List.of(frame), "next-cursor", true, 137L
                ));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras/{cctvId}/frames",
                        SESSION_ID, CCTV_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("TRAINING_SUCCESS_007"))
                .andExpect(jsonPath("$.result.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.result.cctvId").value(CCTV_ID.toString()))
                .andExpect(jsonPath("$.result.frames[0].frameId")
                        .value("3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11"))
                .andExpect(jsonPath("$.result.frames[0].capturedAt").value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.frames[0].windowStart").value(1_787_722_090_000L))
                .andExpect(jsonPath("$.result.frames[0].windowEnd").value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.frames[0].imageUrl")
                        .value("https://example.com/frame.jpg"))
                .andExpect(jsonPath("$.result.frames[0].urlExpiresAt").value(1_787_725_695_000L))
                .andExpect(jsonPath("$.result.frames[0].headcount").value(12))
                .andExpect(jsonPath("$.result.frames[0].density").value(0.42))
                .andExpect(jsonPath("$.result.frames[0].congestionLevel").value("CROWDED"))
                .andExpect(jsonPath("$.result.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.result.hasNext").value(true))
                .andExpect(jsonPath("$.result.totalCount").value(137));
    }

    @Test
    void limit과_cursor_쿼리파라미터를_서비스에_그대로_전달한다() throws Exception {
        given(trainingMonitoringService.getFrames(SESSION_ID, CCTV_ID, 5, "prev-cursor", EMAIL))
                .willReturn(new MonitoringFrameListResponse(SESSION_ID, CCTV_ID, List.of(), null, false, 0L));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras/{cctvId}/frames",
                        SESSION_ID, CCTV_ID)
                        .param("limit", "5")
                        .param("cursor", "prev-cursor")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.frames").isEmpty())
                .andExpect(jsonPath("$.result.hasNext").value(false));
    }

    @Test
    void limit이_범위를_벗어나면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras/{cctvId}/frames",
                        SESSION_ID, CCTV_ID)
                        .param("limit", "0")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 세션이_속한_건물의_CCTV가_아니면_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getFrames(SESSION_ID, CCTV_ID, 20, null, EMAIL))
                .willThrow(new ApiException(CctvErrorCode.CCTV_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras/{cctvId}/frames",
                        SESSION_ID, CCTV_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CCTV001"));
    }

    @Test
    void cursor_형식이_올바르지_않으면_400을_반환한다() throws Exception {
        given(trainingMonitoringService.getFrames(SESSION_ID, CCTV_ID, 20, "invalid-cursor", EMAIL))
                .willThrow(new ApiException(ErrorCode.INVALID_INPUT));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/cameras/{cctvId}/frames",
                        SESSION_ID, CCTV_ID)
                        .param("cursor", "invalid-cursor")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test
    void 이벤트_타임라인을_공통_응답으로_반환한다() throws Exception {
        MonitoringEventResponse event = new MonitoringEventResponse(
                "3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11",
                MonitoringEventType.CONGESTION_STARTED,
                MonitoringEventSeverity.WARNING,
                1_787_722_095_000L,
                "CCTV_001",
                CongestionLevel.CAUTION,
                "혼잡 감지 · CCTV_001"
        );
        given(trainingMonitoringService.getEvents(SESSION_ID, null, 20, null, EMAIL))
                .willReturn(new MonitoringEventListResponse(SESSION_ID, List.of(event), "next-cursor", true));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("TRAINING_SUCCESS_009"))
                .andExpect(jsonPath("$.result.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.result.events[0].eventId")
                        .value("3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11"))
                .andExpect(jsonPath("$.result.events[0].type").value("CONGESTION_STARTED"))
                .andExpect(jsonPath("$.result.events[0].severity").value("WARNING"))
                .andExpect(jsonPath("$.result.events[0].occurredAt").value(1_787_722_095_000L))
                .andExpect(jsonPath("$.result.events[0].cctvCode").value("CCTV_001"))
                .andExpect(jsonPath("$.result.events[0].congestionLevel").value("CAUTION"))
                .andExpect(jsonPath("$.result.events[0].message").value("혼잡 감지 · CCTV_001"))
                .andExpect(jsonPath("$.result.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.result.hasNext").value(true));
    }

    @Test
    void cctvCode_쿼리파라미터를_서비스에_그대로_전달한다() throws Exception {
        given(trainingMonitoringService.getEvents(SESSION_ID, "CCTV_001", 20, null, EMAIL))
                .willReturn(new MonitoringEventListResponse(SESSION_ID, List.of(), null, false));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .param("cctvCode", "CCTV_001")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.events").isEmpty());
    }

    @Test
    void 이벤트_타임라인_조회시_limit과_cursor_쿼리파라미터를_서비스에_그대로_전달한다() throws Exception {
        given(trainingMonitoringService.getEvents(SESSION_ID, null, 5, "prev-cursor", EMAIL))
                .willReturn(new MonitoringEventListResponse(SESSION_ID, List.of(), null, false));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .param("limit", "5")
                        .param("cursor", "prev-cursor")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.events").isEmpty());
    }

    @Test
    void 이벤트_타임라인_조회_limit이_범위를_벗어나면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .param("limit", "0")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 이벤트_타임라인_조회시_cursor_형식이_올바르지_않으면_400을_반환한다() throws Exception {
        given(trainingMonitoringService.getEvents(SESSION_ID, null, 20, "invalid-cursor", EMAIL))
                .willThrow(new ApiException(ErrorCode.INVALID_INPUT));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .param("cursor", "invalid-cursor")
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400"));
    }

    @Test
    void 이벤트_타임라인_조회시_세션을_찾을_수_없으면_404를_반환한다() throws Exception {
        given(trainingMonitoringService.getEvents(SESSION_ID, null, 20, null, EMAIL))
                .willThrow(new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/sessions/{sessionId}/monitoring/events", SESSION_ID)
                        .principal(new UsernamePasswordAuthenticationToken(EMAIL, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRAINING001"));
    }

}
