package com.saferoute.domain.evacuation.recalculation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.congestion.entity.CongestionLevel;
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
import com.saferoute.global.config.AsyncConfig;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

// triggerAsync()가 self-invocation으로 trigger()를 호출하는 구조라, @Async 프록시를 통해
// 실제로 다른 스레드에서 실행됐을 때도 trigger() 내부의 비관적 락 쿼리/JPA 쓰기가 "트랜잭션
// 없음" 오류 없이 끝까지 커밋되는지를 목이 아닌 진짜 스레드/트랜잭션 경계로 검증한다
// (triggerForFireSpread()에 REQUIRES_NEW가 필요했던 것과 같은 종류의 함정, #250).
@SpringBootTest
class RouteRecalculationServiceAsyncIntegrationTest {

    private static final String SCHOOL_NAME = "비동기 재탐색 테스트교";

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
    private RouteRecalculationRepository routeRecalculationRepository;
    @Autowired
    private RouteRecalculationService routeRecalculationService;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    @Qualifier(AsyncConfig.CONGESTION_RECALCULATION_EXECUTOR)
    private Executor congestionRecalculationExecutor;
    @MockitoSpyBean
    private EvacuationRouteService evacuationRouteService;
    // 실제 DynamoDB 없이 도는 테스트 환경에서 CurrentCongestionWeightProvider.currentMultipliers()를
    // 그대로 호출하면 항상 실패한다(#265: 조회가 실패하면 trigger()가 기존 PENDING을 그대로 두고
    // 건너뛰도록 바뀌어서, 목으로 막지 않으면 이 테스트의 "PENDING이 생기는지" 자체를 검증할 수
    // 없다). 이 테스트는 @Async 플러밍/트랜잭션 경계를 보는 게 목적이라 혼잡 조회는 항상
    // 성공(= 다른 CCTV 혼잡 없음)으로 고정한다.
    @MockitoBean
    private CurrentCongestionWeightProvider currentCongestionWeightProvider;

    @Test
    void triggerAsync_persistsPendingRecalculationWithoutThrowing() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        given(currentCongestionWeightProvider.currentMultipliers(any(), any())).willReturn(Map.of());

        routeRecalculationService.triggerAsync(
                fixture.session(), edgeIds(fixture.congestedCorridor()), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5, System.currentTimeMillis());

        RouteRecalculation pending = awaitPendingRecalculation(fixture.session().getId());

        assertThat(pending.getTriggerType()).isEqualTo(RecalculationTriggerType.LEVEL_UP);
        assertThat(pending.getCongestionLevel()).isEqualTo(CongestionLevel.CROWDED);
        assertThat(pending.getCctvCode()).isEqualTo("CCTV_001");
        // recalculatedNodeIds는 @ElementCollection(지연 로딩)이라 세션이 열려 있을 때 읽어야
        // 한다 - 왼쪽(혼잡) 코너를 피해 오른쪽 코너를 새 후보로 골랐는지까지 확인한다.
        List<UUID> recalculatedNodeIds = transactionTemplate.execute(status -> List.copyOf(
                routeRecalculationRepository.findById(pending.getId()).orElseThrow().getRecalculatedNodeIds()));
        assertThat(recalculatedNodeIds).contains(fixture.midRight().getId());
    }

    // 위 테스트는 "언젠가 PENDING이 생긴다"만 증명해서, triggerAsync()가 실수로 동기 실행되게
    // 바뀌어도(예: @Async 설정 누락) 똑같이 통과한다. 여기서는 재탐색의 무거운 부분
    // (findShortestRoute)을 래치로 묶어두고, 호출자가 그 작업이 끝나기 전에 triggerAsync()
    // 호출에서 돌아오는지를 직접 확인한다 - 동기로 실행된다면 호출자 스레드 자신이 release
    // 래치를 내리기 전에 먼저 그 안에서 멈춰 데드락이 나고, @Timeout이 테스트를 실패시킨다.
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void triggerAsync_returnsToCallerBeforeRecalculationWorkCompletes() throws InterruptedException {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        given(currentCongestionWeightProvider.currentMultipliers(any(), any())).willReturn(Map.of());

        CountDownLatch enteredWork = new CountDownLatch(1);
        CountDownLatch releaseWork = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredWork.countDown();
            releaseWork.await();
            return invocation.callRealMethod();
        }).when(evacuationRouteService).findShortestRoute(any(), any(), any(), any(), any());

        routeRecalculationService.triggerAsync(
                fixture.session(), edgeIds(fixture.congestedCorridor()), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_001", 3.5, System.currentTimeMillis());

        // triggerAsync() 호출이 여기까지 돌아왔다는 것 자체가, 저 래치 안에 갇힌 작업을
        // 기다리지 않았다는 뜻이다 - 동기 실행이었다면 바로 위 호출에서 이미 막혀 있었을 것.
        // 아래 단언 중 하나라도 실패해도 releaseWork는 반드시 내려야 한다 - 안 그러면
        // findShortestRoute 안에서 대기 중인 비동기 스레드가 영원히 풀려나지 못해서, 작은
        // 전용 스레드풀(core 2개)의 스레드 하나를 테스트가 끝난 뒤에도 영구히 묶어버린다.
        try {
            assertThat(enteredWork.await(2, TimeUnit.SECONDS))
                    .as("비동기 작업이 findShortestRoute까지 진입했어야 한다")
                    .isTrue();
            assertThat(routeRecalculationRepository
                    .findAllByTrainingSession_IdAndStatus(fixture.session().getId(), RecalculationStatus.PENDING))
                    .as("무거운 작업이 아직 release되지 않았으니 PENDING이 생기면 안 된다")
                    .isEmpty();
        } finally {
            releaseWork.countDown();
        }

        RouteRecalculation pending = awaitPendingRecalculation(fixture.session().getId());
        assertThat(pending.getTriggerType()).isEqualTo(RecalculationTriggerType.LEVEL_UP);
    }

    // === 시작 노드와 다른 층의 혼잡 (#258) ===
    // 경로 탐색은 한 층의 그래프만 읽어서, 시작 노드와 다른 층의 CCTV 혼잡을 그대로 탐색하면
    // MAP_NODE_NOT_FOUND가 난다. 단위 테스트는 가드의 분기만 확인할 뿐, 실제 비동기 스레드/동기 ENDED
    // 경로에서 예외 없이 끝나는지는 못 잡는다.

    @Test
    void triggerAsync_triggerEdgeOnDifferentFloorThanStartNode_skipsWithoutPendingOrRouteSearch() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());

        routeRecalculationService.triggerAsync(
                fixture.session(), edgeIds(List.of(fixture.otherFloorEdge())), CongestionLevel.CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_OTHER_FLOOR", 3.5, System.currentTimeMillis());
        awaitAsyncExecutorIdle();

        // 가드가 없으면 시작 노드를 다른 층에서 찾다가 비동기 스레드에서 예외가 나므로 탐색 호출까지 간다.
        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), any(), any(), any());
        assertThat(routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(fixture.session().getId(), RecalculationStatus.PENDING))
                .isEmpty();
    }

    @Test
    void trigger_triggerEdgeOnDifferentFloor_doesNotCancelExistingPending() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        UUID existingId = routeRecalculationRepository.save(RouteRecalculation.createPending(
                fixture.session(), fixture.congestedCorridor().get(0), "CCTV_001",
                RecalculationTriggerType.LEVEL_UP, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 2.0, List.of(fixture.midRight().getId()), 3.0)).getId();

        assertThatCode(() -> routeRecalculationService.trigger(
                fixture.session(), List.of(fixture.otherFloorEdge()), CongestionLevel.VERY_CROWDED,
                RecalculationTriggerType.LEVEL_UP, "CCTV_OTHER_FLOOR", 5.5))
                .doesNotThrowAnyException();

        // 다른 층 이벤트가 같은 층의 유효한 PENDING을 "새 혼잡 판단으로 무효화"해버리면 안 된다.
        RouteRecalculation existing = routeRecalculationRepository.findById(existingId).orElseThrow();
        assertThat(existing.getStatus()).isEqualTo(RecalculationStatus.PENDING);
    }

    @Test
    void trigger_ended_triggerEdgeOnDifferentFloor_withApprovedHistory_doesNotThrowAndCreatesNothing() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        RouteRecalculation approved = RouteRecalculation.createPending(
                fixture.session(), fixture.congestedCorridor().get(0), "CCTV_001",
                RecalculationTriggerType.LEVEL_UP, CongestionLevel.CROWDED, 3.5,
                List.of(UUID.randomUUID()), 2.0, List.of(fixture.midRight().getId()), 3.0);
        approved.approve(Instant.now(), fixture.session().getAdmin());
        routeRecalculationRepository.save(approved);

        // 이 경로는 동기(trigger -> handleCongestionEnded)라, 가드가 없으면 MAP_NODE_NOT_FOUND가 그대로
        // Pi 요청(EVENT_PROCESSING_FAILED)까지 올라간다.
        assertThatCode(() -> routeRecalculationService.trigger(
                fixture.session(), List.of(fixture.otherFloorEdge()), CongestionLevel.NORMAL,
                RecalculationTriggerType.ENDED, "CCTV_OTHER_FLOOR", 1.0))
                .doesNotThrowAnyException();

        verify(evacuationRouteService, never()).findShortestRoute(any(), any(), anySet());
        assertThat(routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(fixture.session().getId(), RecalculationStatus.PENDING))
                .isEmpty();
    }

    // 비동기 작업이 끝났음을 확인할 신호가 없는(아무것도 저장하지 않는) 케이스를 위해, 전용 실행기가
    // 제출받은 작업을 모두 끝낼 때까지 기다린다.
    private void awaitAsyncExecutorIdle() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) congestionRecalculationExecutor;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (Instant.now().isBefore(deadline)) {
            if (executor.getThreadPoolExecutor().getTaskCount()
                    == executor.getThreadPoolExecutor().getCompletedTaskCount()) {
                return;
            }
            sleepQuietly();
        }
        throw new AssertionError("비동기 실행기가 제한 시간 내에 작업을 마치지 못했습니다");
    }

    private static List<UUID> edgeIds(List<MapEdge> edges) {
        return edges.stream().map(MapEdge::getId).toList();
    }

    private RouteRecalculation awaitPendingRecalculation(UUID sessionId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (Instant.now().isBefore(deadline)) {
            Optional<RouteRecalculation> found = routeRecalculationRepository
                    .findAllByTrainingSession_IdAndStatus(sessionId, RecalculationStatus.PENDING)
                    .stream()
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            sleepQuietly();
        }
        throw new AssertionError("triggerAsync()가 제한 시간 내에 PENDING 재탐색을 만들지 못했습니다");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // 왼쪽 코너(짧음, start-midLeft-exit)와 오른쪽 코너(길지만 덜 혼잡, start-midRight-exit)
    // 두 경로를 만들어, 왼쪽이 혼잡(CROWDED, 3배 가중치)해지면 재탐색이 실제로 오른쪽을
    // 새 후보로 고르는지까지 확인한다 - 경로가 하나뿐이면 가중치를 올려도 후보가 이전과
    // 같아져(trigger()가 "달라진 게 없다"며 PENDING을 만들지 않음) 비동기 동작 자체를
    // 검증할 수 없다.
    private Fixture createFixture() {
        Building building = buildingRepository.save(Building.create(
                "비동기 재탐색 테스트 건물", "부산광역시 해운대구 우동 1-1", BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(3.0, 4.0, "floors/async-recalculation.png");
        floorRepository.save(floor);

        MapNode startNode = mapNodeRepository.save(MapNode.create(
                floor, "START", NodeType.HALLWAY, "출발", 0.1, 0.5, false));
        MapNode midLeft = mapNodeRepository.save(MapNode.create(
                floor, "MID_LEFT", NodeType.HALLWAY, "왼쪽 코너", 0.5, 0.2, false));
        MapNode midRight = mapNodeRepository.save(MapNode.create(
                floor, "MID_RIGHT", NodeType.HALLWAY, "오른쪽 코너", 0.5, 0.8, false));
        MapNode exitNode = mapNodeRepository.save(MapNode.create(
                floor, "EXIT", NodeType.EXIT, "출구", 0.9, 0.5, true));

        MapEdge leftIn = mapEdgeRepository.save(MapEdge.create(floor, startNode, midLeft, 1.0, true));
        MapEdge leftOut = mapEdgeRepository.save(MapEdge.create(floor, midLeft, exitNode, 1.0, true));
        mapEdgeRepository.save(MapEdge.create(floor, startNode, midRight, 1.5, true));
        mapEdgeRepository.save(MapEdge.create(floor, midRight, exitNode, 1.5, true));

        // 시작 노드와 다른 층(2층)에 있는 혼잡 엣지
        Floor otherFloor = Floor.create(building, 2);
        otherFloor.upload(3.0, 4.0, "floors/async-recalculation-2.png");
        floorRepository.save(otherFloor);
        MapNode otherFrom = mapNodeRepository.save(MapNode.create(
                otherFloor, "OTHER_FROM", NodeType.HALLWAY, "다른 층 출발", 0.2, 0.5, false));
        MapNode otherTo = mapNodeRepository.save(MapNode.create(
                otherFloor, "OTHER_TO", NodeType.HALLWAY, "다른 층 도착", 0.8, 0.5, false));
        MapEdge otherFloorEdge = mapEdgeRepository.save(MapEdge.create(otherFloor, otherFrom, otherTo, 1.0, true));

        // 테스트 메서드 여러 개가 각자 createFixture()를 호출하므로(같은 인메모리 DB를 공유),
        // username/email이 고정 문자열이면 두 번째 호출에서 유니크 제약 위반이 난다.
        // username은 길이 제한(2~20자)이 있어 접두사를 짧게 둔다.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = userRepository.save(User.create(
                "async-" + suffix, "password", "async-recalc-admin-" + suffix + "@saferoute.com",
                UserRole.MANAGER, SCHOOL_NAME));

        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "비동기 재탐색 테스트 시나리오", 10, Instant.now(), false, FireSpreadSpeed.FAST,
                building, admin, startNode));

        TrainingSession session = TrainingSession.create(
                TrainingStatus.RUNNING, Instant.now().minusSeconds(30), admin, scenario);
        trainingSessionRepository.save(session);

        return new Fixture(session, List.of(leftIn, leftOut), midRight, otherFloorEdge);
    }

    private record Fixture(TrainingSession session, List<MapEdge> congestedCorridor, MapNode midRight,
            MapEdge otherFloorEdge) {
    }
}
