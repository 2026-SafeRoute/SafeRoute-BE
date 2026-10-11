package com.saferoute.domain.congestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.congestion.dto.response.CongestionConfigQueryResponse;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CongestionConfigQueryServiceTest {

    @Mock
    private CctvGridCellRepository cctvGridCellRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private CongestionConfigService congestionConfigService;

    private CongestionConfigQueryService service;

    private UUID cctvId;
    private UUID buildingId;
    private Cctv cctv;

    @BeforeEach
    void setUp() {
        service = new CongestionConfigQueryService(
                cctvGridCellRepository, trainingSessionRepository, congestionConfigService);

        cctvId = UUID.randomUUID();
        buildingId = UUID.randomUUID();
        Building building = mock(Building.class);
        given(building.getId()).willReturn(buildingId);
        Floor floor = mock(Floor.class);
        given(floor.getBuilding()).willReturn(building);
        // RUNNING 세션이 없는 케이스에서는 감시 면적을 계산하지 않아 이 스텁을 쓰지 않는다.
        org.mockito.Mockito.lenient().when(floor.getGridCellSizeMeter()).thenReturn(0.5);
        MapNode node = mock(MapNode.class);
        given(node.getFloor()).willReturn(floor);
        cctv = mock(Cctv.class);
        // RUNNING 세션이 없는 케이스에서는 GridCell 개수를 조회하지 않아 이 스텁을 쓰지 않는다.
        org.mockito.Mockito.lenient().when(cctv.getId()).thenReturn(cctvId);
        given(cctv.getCode()).willReturn("CCTV_001");
        given(cctv.getCustomNode()).willReturn(node);

        given(congestionConfigService.getConfig()).willReturn(CongestionConfig.createDefault());
    }

    @Test
    @DisplayName("건물에 RUNNING 세션이 없으면 trainingActive=false와 configVersion만 응답한다")
    void getConfigFor_noRunningSession_returnsInactive() {
        given(trainingSessionRepository.findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(
                TrainingStatus.RUNNING, buildingId)).willReturn(Optional.empty());

        CongestionConfigQueryResponse response = service.getConfigFor(cctv);

        assertThat(response.trainingActive()).isFalse();
        assertThat(response.trainingSessionId()).isNull();
        assertThat(response.cctvCode()).isEqualTo("CCTV_001");
        assertThat(response.monitoredAreaM2()).isNull();
        assertThat(response.configVersion()).isEqualTo(1L);
        assertThat(response.congestionThresholds()).isNull();
    }

    @Test
    @DisplayName("건물에 RUNNING 세션이 있으면 훈련 정보와 감시 면적, 판정 설정을 모두 응답한다")
    void getConfigFor_runningSession_returnsActiveConfig() {
        UUID sessionId = UUID.randomUUID();
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(
                TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(cctvGridCellRepository.countByCctv_Id(cctvId)).willReturn(8);

        CongestionConfigQueryResponse response = service.getConfigFor(cctv);

        assertThat(response.trainingActive()).isTrue();
        assertThat(response.trainingSessionId()).isEqualTo(sessionId.toString());
        assertThat(response.cctvCode()).isEqualTo("CCTV_001");
        assertThat(response.monitoredAreaM2()).isEqualTo(2.0);
        assertThat(response.configVersion()).isEqualTo(1L);
        assertThat(response.snapshotIntervalSec()).isEqualTo(1);
        assertThat(response.congestionThresholds().cautionFrom()).isEqualTo(2.0);
        assertThat(response.eventDetection().cooldownSec()).isEqualTo(30);
    }
}
