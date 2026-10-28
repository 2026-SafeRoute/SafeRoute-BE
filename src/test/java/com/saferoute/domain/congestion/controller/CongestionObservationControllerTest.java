package com.saferoute.domain.congestion.controller;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saferoute.domain.congestion.dto.request.ReportObservationRequest;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.congestion.service.CongestionObservationService;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.service.DeviceAuthorizationService;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.global.config.SecurityConfig;
import com.saferoute.global.security.DeviceAuthenticationFilter;
import com.saferoute.global.security.JwtAuthenticationFilter;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = CongestionObservationController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        JwtAuthenticationFilter.class,
                        DeviceAuthenticationFilter.class,
                        SecurityConfig.class
                }
        )
)
@AutoConfigureMockMvc(addFilters = false)
class CongestionObservationControllerTest {

    private static final UUID OBSERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceAuthorizationService deviceAuthorizationService;

    @MockitoBean
    private CongestionObservationService congestionObservationService;

    private ReportObservationRequest validRequest() {
        return new ReportObservationRequest(
                UUID.randomUUID(), UUID.randomUUID(), "CCTV_001",
                5.0, 8, 7, 25, 1_000L, 2_000L, 2_000L, 1L, null
        );
    }

    private ObservationItem observation() {
        return ObservationItem.create(
                OBSERVATION_ID, UUID.randomUUID(), null, "CCTV_001",
                5.0, 8, 25, 1.25, CongestionLevel.NORMAL,
                1_000L, 2_000L, 2_000L, null, 1L
        );
    }

    @Test
    @DisplayName("처음 수신한 eventId이면 관측값과 201을 반환한다")
    void reportObservation_returnsCreated() throws Exception {
        given(deviceAuthorizationService.validateCctv(any(), org.mockito.ArgumentMatchers.eq("CCTV_001")))
                .willReturn(Mockito.mock(Cctv.class));
        given(congestionObservationService.reportObservation(any(), any()))
                .willReturn(IdempotentSaveResult.created(observation()));

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(OBSERVATION_ID.toString()))
                .andExpect(jsonPath("$.congestionLevel").value("NORMAL"));
    }

    @Test
    @DisplayName("중복 eventId이면 기존 관측값과 200을 반환한다")
    void reportObservation_returnsExistingObservation() throws Exception {
        given(deviceAuthorizationService.validateCctv(any(), org.mockito.ArgumentMatchers.eq("CCTV_001")))
                .willReturn(Mockito.mock(Cctv.class));
        given(congestionObservationService.reportObservation(any(), any()))
                .willReturn(IdempotentSaveResult.existing(observation()));

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("edgeId, density, congestionLevel 필드가 없어도(Pi가 안 보내므로) 400이 나지 않는다")
    void reportObservation_doesNotRequireEdgeIdOrDensityFields() throws Exception {
        ObjectNode body = objectMapper.valueToTree(validRequest());
        assertNoField(body, "edgeId");
        assertNoField(body, "density");
        assertNoField(body, "congestionLevel");

        given(deviceAuthorizationService.validateCctv(any(), org.mockito.ArgumentMatchers.eq("CCTV_001")))
                .willReturn(Mockito.mock(Cctv.class));
        given(congestionObservationService.reportObservation(any(), any()))
                .willReturn(IdempotentSaveResult.created(observation()));

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("구버전 Pi 요청은 frameHeadcount가 없어도 허용한다")
    void reportObservation_allowsMissingFrameHeadcount() throws Exception {
        ObjectNode body = objectMapper.valueToTree(validRequest());
        body.remove("frameHeadcount");
        given(deviceAuthorizationService.validateCctv(any(), org.mockito.ArgumentMatchers.eq("CCTV_001")))
                .willReturn(Mockito.mock(Cctv.class));
        given(congestionObservationService.reportObservation(any(), any()))
                .willReturn(IdempotentSaveResult.created(observation()));

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReportObservationRequest> requestCaptor =
                ArgumentCaptor.forClass(ReportObservationRequest.class);
        Mockito.verify(congestionObservationService).reportObservation(any(), requestCaptor.capture());
        assertNull(requestCaptor.getValue().frameHeadcount());
    }

    // Snapshot 프레임이 집계 구간 밖에서 찍히면 peak보다 클 수 있다 - 그 하나 때문에 그 초의 관측값 전체를
    // 400으로 버리지 않는다.
    @Test
    @DisplayName("frameHeadcount가 peakHeadcount보다 커도 관측값을 받아들인다")
    void reportObservation_acceptsFrameHeadcountAbovePeak() throws Exception {
        ObjectNode body = objectMapper.valueToTree(validRequest());
        body.put("frameHeadcount", 9);
        given(deviceAuthorizationService.validateCctv(any(), org.mockito.ArgumentMatchers.eq("CCTV_001")))
                .willReturn(Mockito.mock(Cctv.class));
        given(congestionObservationService.reportObservation(any(), any()))
                .willReturn(IdempotentSaveResult.created(observation()));

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("avgHeadcount가 없으면 400을 반환한다")
    void reportObservation_returnsBadRequestWhenAvgHeadcountMissing() throws Exception {
        ObjectNode body = objectMapper.valueToTree(validRequest());
        body.remove("avgHeadcount");

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("windowEnd가 windowStart보다 이르면 400을 반환한다")
    void reportObservation_returnsBadRequestWhenWindowInvalid() throws Exception {
        ObjectNode body = objectMapper.valueToTree(validRequest());
        body.put("windowStart", 5_000L);
        body.put("windowEnd", 1_000L);

        mockMvc.perform(post("/api/v1/device/congestion-observations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    private void assertNoField(ObjectNode body, String field) {
        if (body.has(field)) {
            throw new AssertionError("validRequest()에 " + field + " 필드가 있으면 안 됩니다 (Pi가 보내지 않음)");
        }
    }
}
