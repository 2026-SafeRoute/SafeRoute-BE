package com.saferoute.domain.evacuation.recalculation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.device.entity.LightCommand;
import com.saferoute.domain.device.entity.LightCommandStatus;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.device.repository.LightCommandJpaRepository;
import com.saferoute.domain.device.service.IoTLightDirectionStore;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.telemetry.dynamo.repository.LightDirectionEventRepository;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

// approve()가 승인된 경로 위 유도등만이 아니라 그 층 전체 유도등을 "출구로 가는 다음 노드" 방향으로
// 실제 DB/트랜잭션 위에서 갱신하는지 검증한다 (#260). 단위 테스트(Mockito)는 호출 인자만 확인할 뿐,
// 실제 그래프로 계산한 다음 홉이 유도등 방향/LightCommand 적재로 이어지는지, 그리고 noRollbackFor =
// ApiException인 approve() 트랜잭션 아래에서 다음 홉 계산 실패가 승인을 깨뜨리지 않는지는 못 잡는다.
//
// 그래프(같은 층, 모두 양방향):
//
//        A ---(1)--- X(EXIT)
//        |           |
//       (1)         (2)
//        |           |
//   START(S) --(2)-- B
//
// 이전 경로는 S -> A -> X, A-X 구간이 혼잡해져 승인된 새 경로는 S -> B -> X다.
// 혼잡 배율(A-X x10)을 반영하면 A에서도 출구로 가는 가장 빠른 길은 A -> S -> B -> X(비용 5)이므로,
// 새 경로 밖에 있는 A의 유도등은 옛 방향(X 쪽)이 아니라 S 쪽으로 바뀌어야 한다.
@SpringBootTest
class RouteRecalculationServiceApproveGuidanceIntegrationTest {

    private static final String SCHOOL_NAME = "승인 유도등 테스트교";

    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TrainingScenarioRepository trainingScenarioRepository;
    @Autowired
    private TrainingSessionRepository trainingSessionRepository;
    @Autowired
    private MapNodeJpaRepository mapNodeRepository;
    @Autowired
    private MapEdgeJpaRepository mapEdgeRepository;
    @Autowired
    private CctvJpaRepository cctvRepository;
    @Autowired
    private IoTLightJpaRepository iotLightRepository;
    @Autowired
    private LightCommandJpaRepository lightCommandRepository;
    @Autowired
    private IoTLightDirectionStore directionStore;
    @Autowired
    private RouteRecalculationRepository routeRecalculationRepository;
    @Autowired
    private RouteRecalculationService routeRecalculationService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @MockitoSpyBean
    private EvacuationRouteService evacuationRouteService;
    // 혼잡 상태 저장소(DynamoDB)는 테스트 환경에 없으므로 혼잡 배율을 직접 주입한다.
    @MockitoBean
    private CurrentCongestionWeightProvider currentCongestionWeightProvider;
    // 유도등 방향 전환 이력(DynamoDB) 기록은 이 테스트의 관심사가 아니다.
    @MockitoBean
    private LightDirectionEventRepository lightDirectionEventRepository;

    @Test
    void approve_updatesEveryGuidedLightOnFloorTowardNextHop() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        given(currentCongestionWeightProvider.currentMultipliers(any(), any()))
                .willReturn(Map.of(fixture.congestedEdgeId(), 10.0));

        routeRecalculationService.approve(fixture.recalculationId(), fixture.approverEmail());

        RouteRecalculation approved = routeRecalculationRepository.findById(fixture.recalculationId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(RecalculationStatus.APPROVED);

        // 승인 경로(S -> B -> X) 위 유도등은 승인 경로를 따른다.
        assertThat(directionStore.get(fixture.lightS())).isEqualTo(IoTLightDirection.RIGHT);
        // 이번 버그의 핵심: 새 경로 밖(A)이라 예전에는 옛 방향(X 쪽, RIGHT)이 그대로 남던 유도등이
        // 혼잡을 피하는 다음 홉(S 쪽, LEFT)으로 바뀐다.
        assertThat(directionStore.get(fixture.lightA())).isEqualTo(IoTLightDirection.LEFT);
        assertThat(pendingCommands(fixture.lightA())).singleElement()
                .extracting(LightCommand::getDirection).isEqualTo(IoTLightDirection.LEFT);
        // 출구 노드 자체의 유도등은 다음 홉이 없어 평상시(BOTH)로 둔다.
        assertThat(directionStore.get(fixture.lightX())).isEqualTo(IoTLightDirection.BOTH);
        // 이미 같은 방향이던 유도등에는 의미 없는 명령을 적재하지 않는다.
        assertThat(directionStore.get(fixture.lightB())).isEqualTo(IoTLightDirection.RIGHT);
        assertThat(pendingCommands(fixture.lightB())).isEmpty();
        // 다른 층 유도등은 건드리지 않는다.
        assertThat(directionStore.get(fixture.lightOtherFloor())).isEqualTo(IoTLightDirection.OFF);
        assertThat(pendingCommands(fixture.lightOtherFloor())).isEmpty();
    }

    @Test
    void approve_whenNextHopComputationFails_stillCommitsApprovalAndAppliesPathGuidance() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        given(currentCongestionWeightProvider.currentMultipliers(any(), any()))
                .willReturn(Map.of(fixture.congestedEdgeId(), 10.0));
        doThrow(new IllegalStateException("next hop failure"))
                .when(evacuationRouteService).computeNextHops(any(), any(), any(), any());

        routeRecalculationService.approve(fixture.recalculationId(), fixture.approverEmail());

        // 다음 홉은 부가 정보라 계산이 터져도 승인 자체는 롤백되지 않고 커밋돼 있어야 한다.
        RouteRecalculation approved = routeRecalculationRepository.findById(fixture.recalculationId()).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(RecalculationStatus.APPROVED);
        assertThat(approved.getResolvedAt()).isNotNull();

        // 승인 경로 위 유도등은 그대로 반영되고, 경로 밖 유도등은 옛 방향을 남기지 않고 BOTH가 된다.
        assertThat(directionStore.get(fixture.lightS())).isEqualTo(IoTLightDirection.RIGHT);
        assertThat(directionStore.get(fixture.lightB())).isEqualTo(IoTLightDirection.RIGHT);
        assertThat(directionStore.get(fixture.lightA())).isEqualTo(IoTLightDirection.BOTH);
    }

    private List<LightCommand> pendingCommands(UUID lightId) {
        return lightCommandRepository.findAllByLight_IdAndStatus(lightId, LightCommandStatus.PENDING);
    }

    private Fixture createFixture() {
        // 테스트 메서드마다 같은 인메모리 DB에 픽스처를 만들므로 유니크 제약이 걸린 값에 접미사를 붙인다.
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Building building = buildingRepository.save(Building.create(
                "승인유도등 " + suffix, "부산광역시 해운대구 우동 7-7", BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = floorRepository.save(newFloor(building, 1, "approve-guidance-1-" + suffix));
        Floor otherFloor = floorRepository.save(newFloor(building, 2, "approve-guidance-2-" + suffix));

        MapNode start = mapNodeRepository.save(MapNode.create(floor, "S", NodeType.HALLWAY, "출발", 0.1, 0.5, false));
        MapNode nodeA = mapNodeRepository.save(MapNode.create(floor, "A", NodeType.HALLWAY, "A", 0.1, 0.1, false));
        MapNode nodeB = mapNodeRepository.save(MapNode.create(floor, "B", NodeType.HALLWAY, "B", 0.9, 0.9, false));
        MapNode exit = mapNodeRepository.save(MapNode.create(floor, "X", NodeType.EXIT, "출구", 0.9, 0.1, true));

        MapEdge startA = mapEdgeRepository.save(MapEdge.create(floor, start, nodeA, 1.0, true));
        MapEdge aExit = mapEdgeRepository.save(MapEdge.create(floor, nodeA, exit, 1.0, true));
        MapEdge startB = mapEdgeRepository.save(MapEdge.create(floor, start, nodeB, 2.0, true));
        MapEdge bExit = mapEdgeRepository.save(MapEdge.create(floor, nodeB, exit, 2.0, true));

        // 다른 층: 이 층 유도등은 승인(시작 노드 층 기준)에 영향받지 않아야 한다.
        MapNode otherDecision = mapNodeRepository.save(
                MapNode.create(otherFloor, "D2", NodeType.HALLWAY, "다른 층 분기", 0.5, 0.5, false));
        MapNode otherLeft = mapNodeRepository.save(
                MapNode.create(otherFloor, "L2", NodeType.HALLWAY, "다른 층 왼쪽", 0.1, 0.5, false));
        MapNode otherRight = mapNodeRepository.save(
                MapNode.create(otherFloor, "R2", NodeType.HALLWAY, "다른 층 오른쪽", 0.9, 0.5, false));
        MapEdge otherLeftEdge = mapEdgeRepository.save(MapEdge.create(otherFloor, otherDecision, otherLeft, 1.0, true));
        MapEdge otherRightEdge = mapEdgeRepository.save(MapEdge.create(otherFloor, otherDecision, otherRight, 1.0, true));

        Cctv cctv = cctvRepository.save(Cctv.create(
                "CCTV_" + suffix, "승인 유도등 CCTV",
                mapNodeRepository.save(MapNode.createCustom(floor, "CCTV_" + suffix, "CCTV", 0.5, 0.5))));

        IoTLight lightS = saveLight(floor, "LS_" + suffix, cctv, start, startA, startB);
        IoTLight lightA = saveLight(floor, "LA_" + suffix, cctv, nodeA, startA, aExit);
        IoTLight lightB = saveLight(floor, "LB_" + suffix, cctv, nodeB, startB, bExit);
        IoTLight lightX = saveLight(floor, "LX_" + suffix, cctv, exit, aExit, bExit);
        IoTLight lightOther = saveLight(otherFloor, "LO_" + suffix, cctv, otherDecision, otherLeftEdge, otherRightEdge);

        // 훈련 시작 시 최초 경로(S -> A -> X)를 따라 안내하고 있던 상태를 재현한다.
        directionStore.update(lightS.getId(), IoTLightDirection.LEFT);   // S -> A
        directionStore.update(lightA.getId(), IoTLightDirection.RIGHT);  // A -> X
        directionStore.update(lightB.getId(), IoTLightDirection.RIGHT);  // 새 경로 방향과 이미 같다

        String approverEmail = "approve-guidance-" + suffix + "@saferoute.com";
        User admin = userRepository.save(User.create(
                "ag-" + suffix, "password", approverEmail, UserRole.MANAGER, SCHOOL_NAME));
        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "승인 유도등 테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, start));
        TrainingSession session = trainingSessionRepository.save(TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(30), admin, scenario));

        RouteRecalculation recalculation = routeRecalculationRepository.save(RouteRecalculation.createPending(
                session, aExit, "CCTV_" + suffix, RecalculationTriggerType.STARTED, CongestionLevel.CROWDED, 3.5,
                List.of(start.getId(), nodeA.getId(), exit.getId()), 2.0,
                List.of(start.getId(), nodeB.getId(), exit.getId()), 4.0));

        return new Fixture(recalculation.getId(), approverEmail, aExit.getId(), lightS.getId(), lightA.getId(),
                lightB.getId(), lightX.getId(), lightOther.getId());
    }

    private Floor newFloor(Building building, int floorNumber, String imageKey) {
        Floor floor = Floor.create(building, floorNumber);
        floor.upload(3.0, 4.0, "floors/" + imageKey + ".png");
        return floor;
    }

    private IoTLight saveLight(Floor floor, String code, Cctv cctv, MapNode decisionNode,
            MapEdge leftEdge, MapEdge rightEdge) {
        IoTLight light = IoTLight.create(code, code,
                mapNodeRepository.save(MapNode.createCustom(floor, code, code, 0.5, 0.5)));
        light.assignCctv(cctv);
        light.configureGuidance(decisionNode, leftEdge, rightEdge);
        return iotLightRepository.save(light);
    }

    private record Fixture(UUID recalculationId, String approverEmail, UUID congestedEdgeId, UUID lightS,
            UUID lightA, UUID lightB, UUID lightX, UUID lightOtherFloor) {
    }
}
