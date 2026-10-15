package com.saferoute.domain.floor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.saferoute.domain.analysis.dto.AnalyseFloorResponse;
import com.saferoute.domain.analysis.service.FloorAnalysisResultService;
import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.evacuation.graph.entity.CustomDeviceType;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.floor.dto.request.UploadFloorRequest;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.dto.S3UploadResponse;
import com.saferoute.infrastructure.s3.service.S3Service;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 훈련 기록(재계산 이력), 시나리오 시작 노드, 유도등 안내 설정이 노드·엣지를 참조하는 층에서
// 도면 재업로드·초기화가 FK 위반(500)으로 실패하던 문제를 실제 DB로 재현·검증한다 (이슈 #280).
// 훈련 기록은 남기고 참조만 끊어야 한다.
@SpringBootTest
class FloorGraphReplacementIntegrationTest {

    private static final String SCHOOL_NAME = "도면교체 테스트교";

    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MapNodeJpaRepository mapNodeRepository;
    @Autowired
    private MapEdgeJpaRepository mapEdgeRepository;
    @Autowired
    private CctvJpaRepository cctvRepository;
    @Autowired
    private IoTLightJpaRepository ioTLightRepository;
    @Autowired
    private TrainingScenarioRepository trainingScenarioRepository;
    @Autowired
    private TrainingSessionRepository trainingSessionRepository;
    @Autowired
    private RouteRecalculationRepository routeRecalculationRepository;
    @Autowired
    private FloorService floorService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FloorAnalysisResultService floorAnalysisResultService;

    @MockitoBean
    private S3Service s3Service;

    private record Fixture(UUID buildingId, UUID floorId, UUID scenarioId, UUID sessionId,
                           UUID recalculationId, String email) {
    }

    @Test
    void clearFloorMap_withTrainingHistory_keepsHistoryAndDetachesReferences() {
        Fixture fixture = transactionTemplate.execute(status -> createFloorWithTrainingHistory("clear"));

        assertThatCode(() -> floorService.clearFloorMap(fixture.buildingId(), fixture.floorId(), fixture.email()))
                .doesNotThrowAnyException();

        assertHistoryKeptAndReferencesDetached(fixture);
    }

    @Test
    void uploadFloor_withTrainingHistory_keepsHistoryAndDetachesReferences() {
        Fixture fixture = transactionTemplate.execute(status -> createFloorWithTrainingHistory("upload"));
        given(s3Service.upload(any())).willReturn(
                new S3UploadResponse("test-bucket", "floors/new.png", "s3://test-bucket/floors/new.png", 3, "image/png"));
        UploadFloorRequest request = new UploadFloorRequest(
                1, 20.0, 10.0, new MockMultipartFile("file", "new.png", "image/png", new byte[] {1, 2, 3}));

        assertThatCode(() -> floorService.uploadFloor(fixture.buildingId(), request, fixture.email()))
                .doesNotThrowAnyException();

        assertHistoryKeptAndReferencesDetached(fixture);
    }

    @Test
    void clearFloorMap_whileTrainingRunningInBuilding_isRejectedAndGraphKept() {
        Fixture fixture = transactionTemplate.execute(status -> {
            Fixture created = createFloorWithTrainingHistory("running");
            startAnotherTraining(created);
            return created;
        });

        assertThatThrownBy(() -> floorService.clearFloorMap(fixture.buildingId(), fixture.floorId(), fixture.email()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);

        assertThat(mapEdgeRepository.findAllByFloor_Id(fixture.floorId())).isNotEmpty();
    }

    // AI 분석은 요청 후 결과가 오기까지 시간이 걸려, 그 사이 훈련이 시작될 수 있다. 결과를 반영하는 시점에도
    // 막혀야 진행 중인 훈련의 그래프가 교체되지 않는다.
    @Test
    void persistAnalysisResult_whenTrainingStartedDuringAnalysis_isRejectedAndGraphKept() {
        Fixture fixture = transactionTemplate.execute(status -> {
            Fixture created = createFloorWithTrainingHistory("analysis");
            startAnotherTraining(created);
            return created;
        });
        AnalyseFloorResponse emptyResult = new AnalyseFloorResponse(100, 50, List.of(), List.of(), Map.of());

        assertThatThrownBy(() -> floorAnalysisResultService.persistAnalysisResult(fixture.floorId(), emptyResult))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);

        assertThat(mapEdgeRepository.findAllByFloor_Id(fixture.floorId())).isNotEmpty();
    }

    private void startAnotherTraining(Fixture fixture) {
        Building building = buildingRepository.findById(fixture.buildingId()).orElseThrow();
        User admin = userRepository.findByEmail(fixture.email()).orElseThrow();
        TrainingScenario another = trainingScenarioRepository.save(TrainingScenario.create(
                "진행 중 시나리오", 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, null));
        trainingSessionRepository.save(TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(30), admin, another));
    }

    private void assertHistoryKeptAndReferencesDetached(Fixture fixture) {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(mapEdgeRepository.findAllByFloor_Id(fixture.floorId())).isEmpty();

            RouteRecalculation history = routeRecalculationRepository.findById(fixture.recalculationId())
                    .orElseThrow();
            assertThat(history.getTriggerEdge()).isNull();
            // 엣지가 지워져도 이력 조회·승인 검증에 쓰는 층 정보는 생성 시점에 저장해 둔 값으로 남는다.
            assertThat(history.resolvedFloorId()).isEqualTo(fixture.floorId());
            assertThat(history.getTrainingSession().getId()).isEqualTo(fixture.sessionId());

            TrainingScenario scenario = trainingScenarioRepository.findById(fixture.scenarioId()).orElseThrow();
            assertThat(scenario.getStartNode()).isNull();
        });
    }

    // 같은 층에 START/EXIT 노드와 엣지, CCTV, CCTV를 배정받고 안내 설정까지 된 유도등이 있고,
    // 그 층에서 진행된(이미 종료된) 훈련의 재계산 이력이 엣지를 참조하는 상태를 만든다.
    private Fixture createFloorWithTrainingHistory(String suffix) {
        Building building = buildingRepository.save(Building.create(
                "도면교체 건물 " + suffix, "부산광역시 해운대구 우동 1-" + suffix, BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(10.0, 20.0, "floors/old-" + suffix + ".png");
        floorRepository.save(floor);

        String email = "graph-replace-" + suffix + "@saferoute.com";
        User admin = userRepository.save(User.create(
                "gr-" + suffix, "password", email, UserRole.MANAGER, SCHOOL_NAME));

        MapNode start = mapNodeRepository.save(MapNode.create(floor, "START", NodeType.START, "출발", 0.5, 0.5, false));
        MapNode exit = mapNodeRepository.save(MapNode.create(floor, "EXIT", NodeType.EXIT, "출구", 0.1, 0.5, true));
        MapNode hall = mapNodeRepository.save(MapNode.create(floor, "HALL", NodeType.HALLWAY, "복도", 0.9, 0.5, false));
        MapEdge toExit = mapEdgeRepository.save(MapEdge.create(floor, start, exit, 1.0, true));
        MapEdge toHall = mapEdgeRepository.save(MapEdge.create(floor, start, hall, 1.0, true));

        MapNode cctvNode = mapNodeRepository.save(
                MapNode.createCustom(floor, "CCTV-N", "CCTV", 0.4, 0.4, CustomDeviceType.CCTV));
        Cctv cctv = cctvRepository.save(Cctv.create("CCTV-" + suffix, "복도 CCTV", cctvNode));
        MapNode lightNode = mapNodeRepository.save(
                MapNode.createCustom(floor, "LIGHT-N", "유도등", 0.6, 0.6, CustomDeviceType.GUIDE_LIGHT));
        IoTLight light = IoTLight.create("LIGHT-" + suffix, "유도등", lightNode);
        light.configureGuidance(start, toExit, toHall);
        light.assignCctv(cctv);
        ioTLightRepository.save(light);

        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "도면교체 시나리오 " + suffix, 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, start));
        TrainingSession session = TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(120), admin, scenario);
        session.complete(Instant.now().minusSeconds(10));
        trainingSessionRepository.save(session);

        RouteRecalculation history = RouteRecalculation.createPending(
                session, toExit, "CCTV-" + suffix, RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(start.getId(), exit.getId()), 1.0, List.of(start.getId(), hall.getId()), 1.0);
        history.approve(Instant.now().minusSeconds(60), admin);
        routeRecalculationRepository.save(history);

        return new Fixture(building.getId(), floor.getId(), scenario.getId(), session.getId(),
                history.getId(), email);
    }
}
