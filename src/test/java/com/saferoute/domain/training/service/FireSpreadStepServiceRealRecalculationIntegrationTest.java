package com.saferoute.domain.training.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// RouteRecalculationService를 목으로 대체하지 않고 실제 빈으로 구동해, afterCommit 콜백에서
// 트리거된 triggerForFireSpread()의 쓰기(기존 PENDING 무효화 + 새 PENDING 저장)가 spreadOneStep()의
// 화재 확산 트랜잭션과 별개로 실제 DB에 커밋되는지 검증한다. 이게 통과하면 REQUIRED 전파의
// triggerForFireSpread()가 afterCommit(원 트랜잭션이 이미 커밋된 뒤, 트랜잭션 없는 상태) 안에서
// 호출될 때 항상 새 트랜잭션을 여는 것이 확인된다 - 별도 REQUIRES_NEW 빈 메서드 없이도 안전하다.
@SpringBootTest
class FireSpreadStepServiceRealRecalculationIntegrationTest {

    private static final String SCHOOL_NAME = "화재재탐색 테스트교";

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
    private FloorGridCellRepository floorGridCellRepository;
    @Autowired
    private MapNodeJpaRepository mapNodeRepository;
    @Autowired
    private MapEdgeJpaRepository mapEdgeRepository;
    @Autowired
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;
    @Autowired
    private FireZoneRepository fireZoneRepository;
    @Autowired
    private RouteRecalculationRepository routeRecalculationRepository;
    @Autowired
    private FireSpreadStepService fireSpreadStepService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void fireTriggeredRecalculation_commitsIndependentlyAfterFireSpreadTransaction() {
        UUID sessionId = transactionTemplate.execute(status -> createRunningSessionWithDetourableFire());

        fireSpreadStepService.spreadOneStep(sessionId);

        List<RouteRecalculation> recalculations =
                routeRecalculationRepository.findAllByTrainingSession_IdOrderByRequestedAtDesc(sessionId);

        // 기존(무관한) PENDING은 화재 우회 후보가 실제로 저장될 때 함께 무효화되고,
        // 새 FIRE_SPREAD PENDING이 저장된다 - 둘 다 spreadOneStep() 트랜잭션이 아니라
        // afterCommit 콜백 안에서 열린 (REQUIRED이지만 원 트랜잭션 종료 후라 사실상 새로운)
        // triggerForFireSpread() 트랜잭션에서 커밋된 결과다.
        assertThat(recalculations).hasSize(2);
        assertThat(recalculations).extracting(RouteRecalculation::getStatus)
                .containsExactlyInAnyOrder(RecalculationStatus.CANCELLED, RecalculationStatus.PENDING);

        RouteRecalculation firePending = recalculations.stream()
                .filter(r -> r.getStatus() == RecalculationStatus.PENDING)
                .findFirst().orElseThrow();
        assertThat(firePending.getTriggerType()).isEqualTo(RecalculationTriggerType.FIRE_SPREAD);
        assertThat(firePending.getCctvCode()).isNull();
        assertThat(firePending.getCongestionLevel()).isNull();
        assertThat(trainingSessionRepository.findById(sessionId).orElseThrow().getRouteBlockedAt()).isNull();
    }

    // 우회 경로가 없을 때 경로 탐색의 "경로 없음" 예외가 재탐색 트랜잭션을 rollback-only로 만들면,
    // 화재를 지나는 PENDING 무효화와 경로 소멸 기록이 커밋 시점에 통째로 롤백된다(운영 로그의
    // "커밋 후 화재 확산 경로 재탐색 실패"). 실제 빈과 DB로 둘 다 커밋되는지 확인한다.
    @Test
    void noDetour_commitsCrossingPendingCancellationAndRouteBlockedAt() {
        UUID sessionId = transactionTemplate.execute(status -> createRunningSessionWithoutDetour());

        fireSpreadStepService.spreadOneStep(sessionId);

        List<RouteRecalculation> recalculations =
                routeRecalculationRepository.findAllByTrainingSession_IdOrderByRequestedAtDesc(sessionId);
        assertThat(recalculations).hasSize(1);
        assertThat(recalculations.get(0).getStatus()).isEqualTo(RecalculationStatus.CANCELLED);

        TrainingSession session = trainingSessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getRouteBlockedAt()).isNotNull();
    }

    private UUID createRunningSessionWithDetourableFire() {
        Building building = buildingRepository.save(Building.create(
                "화재 재탐색 테스트 건물", "부산광역시 해운대구 우동 123-45", BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(3.0, 4.0, "floors/fire-recalc.png");
        floorRepository.save(floor);

        MapNode start = mapNodeRepository.save(MapNode.create(
                floor, "START", NodeType.HALLWAY, "출발", 0.5, 0.5, false));
        MapNode exitA = mapNodeRepository.save(MapNode.create(
                floor, "EXIT_A", NodeType.EXIT, "출구A", 0.1, 0.5, true));
        MapNode exitB = mapNodeRepository.save(MapNode.create(
                floor, "EXIT_B", NodeType.EXIT, "출구B", 0.9, 0.5, true));

        // 더 짧은 A쪽이 기본(직행) 경로로 선택되도록 거리를 다르게 둔다.
        MapEdge edgeToA = mapEdgeRepository.save(MapEdge.create(floor, start, exitA, 1.0, true));
        mapEdgeRepository.save(MapEdge.create(floor, start, exitB, 5.0, true));

        // edgeToA에 매핑된 셀은 이미(이번 틱 이전부터) 발화 상태로 둔다 - "누적 화재 상태" 조회
        // (findAllByFloor_IdAndIsFiredTrue)로 영향받는 엣지를 구하는 경로를 검증하기 위함이다.
        FloorGridCell firedCell = floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 0, true, 0.3, 0.5));
        firedCell.markFired();
        floorGridCellRepository.save(firedCell);
        mapEdgeGridCellRepository.save(MapEdgeGridCell.create(edgeToA, firedCell));

        User admin = userRepository.save(User.create(
                "fire-recalc-admin", "password", "fire-recalc@saferoute.com", UserRole.MANAGER, SCHOOL_NAME));

        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "화재 재탐색 테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, start));

        // 같은 층을 다른 시나리오가 동시에 쓸 수 있어 FloorGridCell.isFired만으로는 부족하다 -
        // firedCell이 "이 시나리오"의 화재 구간이라는 FireZone 기록도 함께 남긴다(RouteRecalculationService/
        // FireSpreadStepService의 시나리오 범위 필터를 실제로 통과하는지 검증).
        fireZoneRepository.save(FireZone.createOrigin(scenario, floor, firedCell));

        TrainingSession session = TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(30), admin, scenario);
        trainingSessionRepository.save(session);

        // 이 화재 틱과 무관한 기존 PENDING(임의의 CCTV 트리거). 화재 우회 후보가 실제로
        // 저장될 때 함께 무효화되는지 검증하기 위한 픽스처.
        RouteRecalculation unrelatedPending = RouteRecalculation.createPending(
                session, edgeToA, "CCTV_UNRELATED", RecalculationTriggerType.STARTED,
                com.saferoute.domain.congestion.entity.CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(UUID.randomUUID()), 12.5);
        routeRecalculationRepository.save(unrelatedPending);

        // 발화 원점(frontier, generation 0)을 firedCell의 인접 셀(0,1)에 두고, 그 옆(0,2)에
        // 아직 안 붙은 walkable 셀을 하나 더 둔다 - spreadOneStep()이 이번 틱에 (0,2)로
        // 새로 옮겨붙여야(newlyFired) triggerForFireSpread 트리거 경로 자체가 실행된다.
        // firedCell(0,0)은 이미 발화 상태라 이번 틱엔 다시 옮겨붙지 않지만(중복 방지),
        // 누적 화재 셀 조회에는 여전히 포함된다.
        FloorGridCell originCell = floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 1, true, 0.35, 0.5));
        floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 2, true, 0.4, 0.5));
        fireZoneRepository.save(FireZone.createOrigin(scenario, floor, originCell));

        return session.getId();
    }

    // 출구가 하나뿐이고 그 출구로 가는 유일한 엣지가 이미 화재 구간이라 우회 경로가 없는 세션.
    // 기존 PENDING은 그 화재 엣지를 지나도록 둬서, 새 후보가 없어도 무효화돼야 하는 경우를 만든다.
    private UUID createRunningSessionWithoutDetour() {
        Building building = buildingRepository.save(Building.create(
                "경로 소멸 테스트 건물", "부산광역시 해운대구 우동 123-46", BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(3.0, 4.0, "floors/fire-blocked.png");
        floorRepository.save(floor);

        MapNode start = mapNodeRepository.save(MapNode.create(
                floor, "START", NodeType.HALLWAY, "출발", 0.5, 0.5, false));
        MapNode exit = mapNodeRepository.save(MapNode.create(
                floor, "EXIT", NodeType.EXIT, "출구", 0.1, 0.5, true));
        MapEdge onlyEdge = mapEdgeRepository.save(MapEdge.create(floor, start, exit, 1.0, true));

        FloorGridCell firedCell = floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 0, true, 0.3, 0.5));
        firedCell.markFired();
        floorGridCellRepository.save(firedCell);
        mapEdgeGridCellRepository.save(MapEdgeGridCell.create(onlyEdge, firedCell));

        User admin = userRepository.save(User.create(
                "fire-blocked-admin", "password", "fire-blocked@saferoute.com", UserRole.MANAGER, SCHOOL_NAME));
        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "경로 소멸 테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, start));
        fireZoneRepository.save(FireZone.createOrigin(scenario, floor, firedCell));

        TrainingSession session = TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(30), admin, scenario);
        trainingSessionRepository.save(session);

        routeRecalculationRepository.save(RouteRecalculation.createPending(
                session, onlyEdge, "CCTV_BLOCKED", RecalculationTriggerType.STARTED,
                com.saferoute.domain.congestion.entity.CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 10.0, List.of(start.getId(), exit.getId()), 12.5));

        // 이번 틱에 (0,2)로 새로 옮겨붙어야 재탐색이 트리거된다 (위 픽스처와 같은 이유).
        FloorGridCell originCell = floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 1, true, 0.35, 0.5));
        floorGridCellRepository.save(
                FloorGridCell.create(floor, 0, 2, true, 0.4, 0.5));
        fireZoneRepository.save(FireZone.createOrigin(scenario, floor, originCell));

        return session.getId();
    }
}
