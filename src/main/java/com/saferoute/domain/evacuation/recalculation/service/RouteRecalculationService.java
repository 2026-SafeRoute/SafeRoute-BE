package com.saferoute.domain.evacuation.recalculation.service;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.service.IoTLightService;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.dto.response.CurrentRouteResponse;
import com.saferoute.domain.evacuation.recalculation.dto.response.RouteRecalculationDetailResponse;
import com.saferoute.domain.evacuation.recalculation.dto.response.RouteRecalculationResponse;
import com.saferoute.domain.evacuation.recalculation.dto.response.RouteRecalculationSummaryResponse;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationStatus;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.evacuation.service.EvacuationRoute;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.evacuation.service.FireProximity;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.EvacuationErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.config.AsyncConfig;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteRecalculationService {

    // CAUTION 1.5배, CROWDED 3배, VERY_CROWDED는 CurrentCongestionWeightProvider와 동일한
    // 배율(10배) - 전부 페널티로만 반영하고 완전 제외하지 않는다. trigger()의
    // requiresRouteRecalculation() 게이트 상 CAUTION은 현재 이 메서드까지 도달하지 않지만,
    // 표 전체를 그대로 반영해둔다.
    static final double CAUTION_WEIGHT_MULTIPLIER = 1.5;
    static final double CROWDED_WEIGHT_MULTIPLIER = 3.0;

    // triggerAsync()가 실행기 큐에 밀려 있다가 너무 늦게(관측 주기를 한참 넘겨) 실행되면
    // 건너뛴다 - 그 사이 같은 CCTV의 CONGESTION_ENDED가 동기로 먼저 처리돼 복구 PENDING을
    // 만들어 놨을 수 있는데, 뒤늦게 도착한 낡은 STARTED/LEVEL_UP이 그 복구를 취소하고 이미
    // 끝난 혼잡 기준으로 다시 우회 PENDING을 만들어버리면 안 되기 때문이다.
    private static final long MAX_ASYNC_TRIGGER_STALENESS_MS = 20_000L;

    private final RouteRecalculationRepository routeRecalculationRepository;
    private final EvacuationRouteService evacuationRouteService;
    private final IoTLightService ioTLightService;
    private final UserRepository userRepository;
    private final TrainingEventPublisher trainingEventPublisher;
    private final SchoolContextService schoolContextService;
    private final TrainingSessionRepository trainingSessionRepository;
    private final MapNodeJpaRepository mapNodeJpaRepository;
    private final MapEdgeJpaRepository mapEdgeJpaRepository;
    private final FloorGridCellRepository floorGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final FireZoneRepository fireZoneRepository;
    private final CurrentCongestionWeightProvider currentCongestionWeightProvider;

    // 혼잡 감지로 트리거되는 우회 경로 재탐색.
    // - 이번 호출 CCTV의 엣지/레벨뿐 아니라, 같은 층 다른 CCTV들의 현재 혼잡 상태
    //   (CurrentCongestionWeightProvider)까지 합쳐서 후보 경로 하나를 만든다 - CCTV 하나만
    //   피하다가 다른 CCTV의 혼잡 구간을 그대로 지나는 후보를 제안하지 않기 위함.
    // - 새로 계산한 후보가 기존 PENDING의 후보 경로와 같으면(실제로 바뀐 게 없으면) 반복
    //   트리거를 무시한다 - "같은 CCTV+레벨"이 아니라 "같은 후보 경로"가 기준이다. 여러
    //   CCTV가 번갈아 보고해도 PENDING엔 CCTV 하나만 기록되므로, CCTV 식별자로 비교하면
    //   실제론 안 바뀐 상황에서도 매번 취소+재생성이 반복된다.
    // - 새 판단이 필요하면 기존 PENDING을 모두 CANCELLED로 무효화하고 새로 계산한다.
    // - triggerType이 ENDED(혼잡 종료)면 끝난 CCTV 몫만 뺀 층 전체 혼잡 기준으로 복구 후보를 계산하고
    //   기존 PENDING을 정리한다(handleCongestionEnded 참고).
    @Transactional
    public void trigger(TrainingSession session, List<MapEdge> affectedEdges, CongestionLevel level,
            RecalculationTriggerType triggerType, String cctvCode, double density) {
        if (affectedEdges.isEmpty()) {
            return;
        }

        TrainingSession lockedSession = trainingSessionRepository.findByIdForUpdate(session.getId()).orElse(session);
        // triggerAsync()가 실행기 큐에 밀려 있는 동안 세션이 끝날 수 있다 - 그러면
        // TrainingSessionService.end()/forceEnd()/타임아웃이 이미 cancelAllPendingForSession()으로
        // 그 시점의 PENDING을 전부 정리한 뒤다. 그 정리 후에 들어온 지연된 트리거까지 걸러주지
        // 않으면, 아무도 다시 안 치워줄 유령 PENDING이 끝난 세션에 새로 생긴다.
        if (lockedSession.getStatus() != TrainingStatus.RUNNING) {
            log.info("세션이 이미 종료되어 재탐색을 건너뜀: sessionId={}, status={}",
                    lockedSession.getId(), lockedSession.getStatus());
            return;
        }
        // 현재 유효 경로는 항상 "시나리오 대표 startNode -> EXIT" 완전한 한 경로여야 하므로,
        // 혼잡 엣지의 fromNode가 아니라 시나리오의 대표 startNode에서 다시 계산한다.
        MapNode representativeStart = lockedSession.getScenario().getStartNode();
        if (representativeStart == null) {
            log.warn("시나리오에 대표 startNode가 없어 재탐색 승인 대기 항목을 생성하지 않음: sessionId={}",
                    lockedSession.getId());
            return;
        }
        UUID startNodeId = representativeStart.getId();
        UUID floorId = representativeStart.getFloor().getId();

        // 경로 탐색(findShortestRoute)은 한 층의 노드/엣지만 읽고 층간(계단) 연결은 그래프에 없다.
        // 그래서 시작 노드와 다른 층에서 감지된 혼잡은 어떤 우회 경로로도 반영할 수 없고, 그대로
        // 탐색하면 시작 노드를 그 층에서 찾지 못해 MAP_NODE_NOT_FOUND가 난다(시나리오는 건물
        // 단위라 CCTV가 시작 노드와 다른 층에 있는 조합이 실제로 가능하다). 설정 오류가 아니라
        // 정상 상황이므로 WARN이 아닌 INFO로 남기고 건너뛴다.
        // 이 검사는 ENDED 분기와 기존 PENDING 취소보다 앞이어야 한다 - 다른 층 이벤트가 같은 층의
        // 유효한 PENDING을 취소하거나, 승인 이력이 있는 세션의 복구 계산을 예외로 터뜨려
        // Pi 응답(EVENT_PROCESSING_FAILED)까지 번지게 하면 안 된다. LAZY 연관 때문에 엔티티
        // 동일성이 아니라 id 값으로 비교한다.
        List<MapEdge> sameFloorEdges = affectedEdges.stream()
                .filter(edge -> floorId.equals(edge.getFloor().getId()))
                .toList();
        if (sameFloorEdges.isEmpty()) {
            log.info("혼잡 구간이 시나리오 시작 노드와 다른 층이라 재탐색을 건너뜀: sessionId={}, cctvCode={}, "
                            + "edgeFloorId={}, startFloorId={}",
                    lockedSession.getId(), cctvCode, affectedEdges.get(0).getFloor().getId(), floorId);
            return;
        }
        affectedEdges = sameFloorEdges;
        MapEdge representativeEdge = affectedEdges.get(0);
        // 화재 확산이 만든 PENDING(FIRE_SPREAD)은 혼잡 판단과 무관하므로, 혼잡 트리거가 여기서
        // 건드리는 대상에서 제외한다 - 그대로 두면 혼잡 이벤트 하나가 여전히 유효한 화재 우회
        // 제안을 대체 없이 지워버릴 수 있다(triggerForFireSpread()가 대칭적으로 혼잡 PENDING을
        // 취급하는 방식과 맞춘 것).
        List<RouteRecalculation> existingPending = routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(session.getId(), RecalculationStatus.PENDING).stream()
                .filter(pending -> pending.getTriggerType() != RecalculationTriggerType.FIRE_SPREAD)
                .toList();

        if (triggerType == RecalculationTriggerType.ENDED) {
            handleCongestionEnded(lockedSession, floorId, startNodeId, representativeEdge, existingPending,
                    level, cctvCode, density);
            return;
        }

        RouteSnapshot previous = resolveActiveRoute(lockedSession, floorId, startNodeId);

        // 혼잡 우회 후보도 지금 이 시나리오가 그 층에 낸 화재 구간은 항상 제외한다 - 그렇지
        // 않으면 혼잡을 피하려다 화재 구간을 지나는 경로를 제안할 수 있다. 혼잡은(VERY_CROWDED
        // 포함) 더 이상 완전 제외하지 않고 항상 페널티로만 반영한다 - 화재/연기와 달리 실제
        // 통행 불가 사유가 아니기 때문이다(아래 mergedCongestionMultipliers 참고).
        Set<UUID> excludedEdgeIds = firedEdgeIdsForFloor(lockedSession.getScenario().getId(), floorId);
        FireProximity fireProximity = fireProximityForFloor(lockedSession.getScenario(), floorId);
        // 혼잡 재탐색은 화재 우회와 달리 안전 기능이 아니라 최적화이므로, 층 전체 혼잡 조회(다른
        // CCTV 몫)가 실패하면 그 불완전한 정보로 계산한 후보로 기존 PENDING을 섣불리 취소/대체
        // 하지 않는다 - 빈 맵으로 조용히 진행하면 다른 CCTV의 혼잡을 반영해 만들어진 정상적인
        // 기존 PENDING이, 이번 CCTV 가중치만으로 계산한 후보가 다르다는 이유로 취소될 수 있다.
        Optional<Map<UUID, Double>> congestionMultipliers =
                mergedCongestionMultipliers(lockedSession.getId(), floorId, affectedEdges, level);
        if (congestionMultipliers.isEmpty()) {
            return;
        }

        EvacuationRoute candidate;
        try {
            candidate = evacuationRouteService.findShortestRoute(
                    floorId, startNodeId, excludedEdgeIds, congestionMultipliers.get(), fireProximity);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND) {
                log.warn("우회 경로를 찾을 수 없어 재탐색 승인 대기 항목을 생성하지 않음: sessionId={}, edgeId={}",
                        lockedSession.getId(), representativeEdge.getId());
                // 새 후보는 못 찾았지만, 기존 PENDING은 이전 판단 기준이라 이미 낡았으므로 무효화한다.
                for (RouteRecalculation pending : existingPending) {
                    cancel(pending, "새 혼잡 판단으로 무효화됨");
                }
                return;
            }
            throw exception;
        }

        List<UUID> candidateNodeIds = candidate.path().stream().map(MapNode::getId).toList();
        if (candidateNodeIds.equals(previous.nodeIds())) {
            // 더 이상 우회가 필요 없다 - 떠 있던 PENDING이 있다면(이제는 낡은 제안이므로) 무효화한다.
            for (RouteRecalculation pending : existingPending) {
                cancel(pending, "새 혼잡 판단으로 무효화됨");
            }
            return;
        }

        // 이제 후보는 이번 호출 CCTV 하나가 아니라 층 전체 혼잡을 반영하므로, "같은 CCTV+레벨이면
        // 스킵"은 더 이상 안전하지 않다 - PENDING엔 CCTV 하나만 기록되므로 여러 CCTV가 번갈아
        // 보고하면 실제로는 아무것도 안 바뀌어도 기존 PENDING의 cctvCode와 계속 달라서 매번
        // 취소+재생성이 반복된다. 대신 계산된 후보 경로 자체가 기존 PENDING과 같으면 그 PENDING은
        // 건드리지 않고, 그 외의(=낡은) PENDING만 무효화한다.
        boolean sameCandidateAlreadyPending = false;
        for (RouteRecalculation pending : existingPending) {
            if (pending.getRecalculatedNodeIds().equals(candidateNodeIds)) {
                sameCandidateAlreadyPending = true;
            } else {
                cancel(pending, "새 혼잡 판단으로 무효화됨");
            }
        }
        if (sameCandidateAlreadyPending) {
            return;
        }

        savePending(lockedSession, representativeEdge, cctvCode, triggerType, level, density,
                previous, candidate, candidateNodeIds);
    }

    // 층 전체의 현재 혼잡 상태(CurrentCongestionWeightProvider, 모든 CCTV의 최신 관측값과 아직
    // 끝나지 않은 최신 즉시 이벤트 기준)와 이번 호출 자신의 가중치를 합친다(같은 엣지면 더 큰 배율
    // 사용). Provider도 즉시 이벤트를 반영하지만(#293), 이번 호출 몫은 계속 직접 더한다 - 관측값
    // 트리거는 이 호출의 레벨이 즉시 이벤트보다 클 수 있고, 저장소 조회가 이 호출의 저장을 아직
    // 못 볼 수도 있어서, 막 감지된 판단이 집계에서 빠지는 일이 없어야 한다.
    private Optional<Map<UUID, Double>> mergedCongestionMultipliers(
            UUID sessionId, UUID floorId, List<MapEdge> affectedEdges, CongestionLevel level) {
        Optional<Map<UUID, Double>> currentFloorWide = currentCongestionMultipliersOrFail(sessionId, floorId);
        if (currentFloorWide.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, Double> merged = new HashMap<>(currentFloorWide.get());
        weightMultipliersFor(affectedEdges, level)
                .forEach((edgeId, multiplier) -> merged.merge(edgeId, multiplier, Math::max));
        return Optional.of(merged);
    }

    // trigger()의 비동기 버전 (#250). STARTED/LEVEL_UP은 Pi가 관측 주기마다 보내는 관측값이 혼잡이
    // 지속되는 동안 반복 재시도해주므로, 디바이스 응답 경로에서 TrainingSession 행 락 대기와
    // 경로탐색을 떼어내도 한 번 실패해도 다음 관측값이 자연히 다시 트리거한다.
    //
    // ENDED(handleCongestionEnded로 가는 혼잡 종료 판단)는 Pi가 보내는 1회성 신호라 재시도 기회가 없으므로
    // 호출부에서 이 메서드가 아니라 동기 trigger()를 그대로 써야 한다 - 이 메서드는 ENDED를
    // 받지 않는다는 전제로 짜여 있지 않지만(trigger()에 그대로 위임), 정책상 ENDED는 여기로
    // 오면 안 된다. 호출부(CongestionObservationService/CongestionEventService)가 그 구분을 담당한다.
    //
    // MapEdge 엔티티가 아니라 id 목록을 받는다: 호출자가 들고 있는 MapEdge는 원래 요청의
    // 트랜잭션/영속성 컨텍스트에 묶여 있어서, floor 등 지연 로딩 필드가 아직 초기화되지
    // 않았다면 완전히 다른 스레드에서 도는 이 메서드가 건드리는 순간 LazyInitializationException이
    // 난다. 그래서 이 메서드 자신의 트랜잭션 안에서 id로 다시 읽어온다(reloadEdges).
    //
    // capturedAtMs(요청의 captured/detected 시각)로 신선도도 함께 확인한다 - 실행기 큐가
    // 밀려서 너무 늦게 실행된 낡은 판단이, 그 사이 동기로 먼저 끝난 ENDED 복구를 되돌리고
    // 낡은 혼잡 기준으로 새 우회 PENDING을 만드는 것을 막기 위함이다.
    //
    // self-invocation 주의: @Async는 외부에서 프록시를 통해 호출될 때만 적용되므로 반드시
    // 다른 빈(CongestionObservationService 등)이 호출해야 한다. 이 메서드 자신에 @Transactional을
    // 걸어 둔 이유도 self-invocation 때문이다 - trigger(...)가 내부에서 같은 인스턴스의
    // trigger()를 직접 호출(this.trigger(...))하면 그 호출은 프록시를 안 타서 trigger() 자신의
    // @Transactional이 적용되지 않는다. 이 메서드가 먼저 트랜잭션을 열어두면 그 안에서 실행되는
    // trigger()는 이미 열려 있는 트랜잭션에 그냥 참여(REQUIRED)하게 되어 문제가 없다
    // (triggerForFireSpread()가 REQUIRES_NEW를 쓰는 것과는 다른 문제 - 거기는 "이미 다른
    // 트랜잭션의 afterCommit 콜백 안"이라 새 트랜잭션을 강제해야 했던 것이고, 여기는 완전히
    // 새 스레드라 애초에 참여할 트랜잭션이 없다).
    @Async(AsyncConfig.CONGESTION_RECALCULATION_EXECUTOR)
    @Transactional
    public void triggerAsync(TrainingSession session, List<UUID> affectedEdgeIds, CongestionLevel level,
            RecalculationTriggerType triggerType, String cctvCode, double density, long capturedAtMs) {
        long stalenessMs = System.currentTimeMillis() - capturedAtMs;
        if (stalenessMs > MAX_ASYNC_TRIGGER_STALENESS_MS) {
            log.warn("재탐색 비동기 작업이 너무 오래 지체돼 건너뜀(낡은 판단이 더 최근 처리를 "
                            + "덮어쓰는 것을 방지): sessionId={}, cctvCode={}, triggerType={}, stalenessMs={}",
                    session.getId(), cctvCode, triggerType, stalenessMs);
            return;
        }
        List<MapEdge> affectedEdges = reloadEdges(affectedEdgeIds);
        trigger(session, affectedEdges, level, triggerType, cctvCode, density);
    }

    // findAllById는 순서를 보장하지 않으므로 호출자가 넘긴 순서로 재정렬한다(trigger()가
    // affectedEdges.get(0)을 대표 엣지로 쓰므로 순서가 바뀌면 안 된다). 그 사이 삭제된
    // 엣지는 조용히 걸러낸다.
    private List<MapEdge> reloadEdges(List<UUID> edgeIds) {
        Map<UUID, MapEdge> edgesById = mapEdgeJpaRepository.findAllById(edgeIds).stream()
                .collect(Collectors.toMap(MapEdge::getId, edge -> edge));
        return edgeIds.stream().map(edgesById::get).filter(Objects::nonNull).toList();
    }

    // 화재 확산으로 트리거되는 우회 경로 재탐색.
    // - affectedEdges는 "현재 그 층에서 불이 붙은 모든 구간"이어야 한다(이번 틱에 새로 옮겨붙은
    //   구간만이 아니라 누적본). 화재는 꺼지지 않으므로 매번 전체 화재 구간을 다시 완전 제외해야
    //   이전 스텝에서 제외했던 구간이 다시 후보 경로에 섞여 들어가는 일이 없다.
    // - 혼잡과 달리 화재는 가중치가 아니라 항상 완전 제외(hard exclusion)로 처리한다. 대신 후보 경로는
    //   그 시점의 층 전체 혼잡 상태(CurrentCongestionWeightProvider)를 가중치로 반영한다.
    // - CongestionLevel/cctvCode/density 개념이 없으므로 별도 팩토리(createPendingForFireSpread)로 저장한다.
    // - 기존 PENDING 중 이번에 새로 화재 구간이 된 엣지를 실제로 지나가는 건은, 새 대안 유무와
    //   무관하게 즉시 무효화한다(관리자가 나중에 화재 통과 경로를 실수로 승인하는 일을 막기 위함).
    //   화재와 무관한 나머지 PENDING은 "새 후보를 실제로 저장하기 직전"에만 무효화한다 - 우회
    //   경로를 못 찾거나 후보가 활성 경로와 같아 새로 저장할 게 없다면 그대로 둔다.
    // - 유일한 호출부(FireSpreadStepService)가 화재 확산 트랜잭션의 afterCommit 콜백에서 이
    //   메서드를 부른다. 그 시점엔 원 트랜잭션이 이미 물리적으로 커밋됐지만
    //   TransactionSynchronizationManager 정리는 아직 끝나지 않아서, 평범한 REQUIRED로는
    //   Spring이 "이미 트랜잭션 안"이라고 오판해 새 트랜잭션을 시작하지 않는다 - 그 상태에서
    //   findByIdForUpdate()의 비관적 락 쿼리가 "no transaction in progress"로 실패한다
    //   (실제 SpringBootTest로 재현 확인). REQUIRES_NEW로 항상 진짜 새 트랜잭션을 강제한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void triggerForFireSpread(TrainingSession session, List<MapEdge> affectedEdges) {
        if (affectedEdges.isEmpty()) {
            return;
        }

        // 넘어온 session은 화재 확산 트랜잭션에서 읽은(이미 커밋된) detached 엔티티라, 여기서 바꿔도 저장되지
        // 않는다. 락 조회에 실패했을 때 그걸로 대신 진행하면 routeBlockedAt이 남지 않아 경로 소멸 알림이
        // 틱마다 다시 발행되므로, 세션을 찾지 못하면(=삭제됨) 할 일이 없는 것으로 보고 건너뛴다.
        Optional<TrainingSession> locked = trainingSessionRepository.findByIdForUpdate(session.getId());
        if (locked.isEmpty()) {
            log.warn("화재 확산 재탐색 대상 세션을 찾을 수 없어 건너뜀: sessionId={}", session.getId());
            return;
        }
        TrainingSession lockedSession = locked.get();

        List<RouteRecalculation> existingPending = routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(session.getId(), RecalculationStatus.PENDING);
        List<RouteRecalculation> stillSafePending = new ArrayList<>();
        for (RouteRecalculation pending : existingPending) {
            if (crossesAnyEdge(pending.getRecalculatedNodeIds(), affectedEdges)) {
                cancel(pending, "화재 확산으로 무효화됨");
            } else {
                stillSafePending.add(pending);
            }
        }

        UUID floorId = affectedEdges.get(0).getFloor().getId();
        MapNode representativeStart = lockedSession.getScenario().getStartNode();
        if (representativeStart == null) {
            log.warn("시나리오에 대표 startNode가 없어 재탐색 승인 대기 항목을 생성하지 않음: sessionId={}",
                    lockedSession.getId());
            return;
        }
        UUID startNodeId = representativeStart.getId();
        // TrainingSessionService.start()의 FIRE_ORIGIN_START_FLOOR_MISMATCH 검사와 화재가 같은 층
        // 인접 셀로만 번지는 규칙 때문에 화재 구간은 항상 시작 노드 층에 있어 실제로는 타지 않는
        // 방어 코드다. 그래도 탐색이 시작 노드를 못 찾아 예외로 터지는 것보다는 건너뛰는 편이 안전하다.
        if (!floorId.equals(representativeStart.getFloor().getId())) {
            log.info("화재 구간이 시나리오 시작 노드와 다른 층이라 재탐색을 건너뜀: sessionId={}, "
                            + "edgeFloorId={}, startFloorId={}",
                    lockedSession.getId(), floorId, representativeStart.getFloor().getId());
            return;
        }

        RouteSnapshot previous = resolveActiveRoute(lockedSession, floorId, startNodeId);

        // 지금 안내 중인 활성 경로가 이번에 새로 화재 구간이 된 엣지와 무관하면 다시 계산할 필요가
        // 없다. 여기서 걸러내지 않으면, 화재와 무관한 곳에서 불이 나도(가중치/제외 없이 화재만
        // 피한) 후보가 현재 활성 경로(예: 이미 승인된 혼잡 우회)와 달라져 버려서, 여전히 유효한
        // 혼잡 우회를 원래의(다시 혼잡한) 직행 경로로 되돌리는 PENDING이 매 틱 반복 생성된다.
        MapEdge representativeEdge = findCrossedEdge(previous.nodeIds(), affectedEdges).orElse(null);
        if (representativeEdge == null) {
            return;
        }

        Set<UUID> excludedEdgeIds = affectedEdges.stream().map(MapEdge::getId).collect(Collectors.toSet());
        // 화재는 완전 제외하되, 지금 혼잡한 복도는 가중치로 피한다(제외가 아니라 페널티라 혼잡한 길밖에
        // 없어도 후보는 항상 나온다).
        Map<UUID, Double> congestionMultipliers = currentCongestionMultipliersOrEmpty(lockedSession.getId(), floorId);
        // 곧 불이 번질 구간은 제외하지 않고 페널티만 준다 - 불 옆 복도밖에 없으면 그래도 그 길로 안내한다.
        FireProximity fireProximity = fireProximityForFloor(lockedSession.getScenario(), floorId);
        EvacuationRoute candidate;
        try {
            candidate = evacuationRouteService.findShortestRoute(
                    floorId, startNodeId, excludedEdgeIds, congestionMultipliers, fireProximity);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND) {
                log.warn("화재로 막힌 구간을 피해 우회 경로를 찾을 수 없어 재탐색 승인 대기 항목을 생성하지 않음: sessionId={}",
                        lockedSession.getId());
                // 훈련을 실패 처리하지 않고 관리자에게 한 번만 알린다(종료 판단은 관리자 몫).
                // 이후 틱에서도 계속 막혀 있으므로 markRouteBlocked가 최초 1회만 true를 돌려준다.
                if (lockedSession.markRouteBlocked(Instant.now())) {
                    trainingEventPublisher.publishEvacuationRouteBlockedAfterCommit(lockedSession, floorId);
                }
                return;
            }
            throw exception;
        }

        List<UUID> candidateNodeIds = candidate.path().stream().map(MapNode::getId).toList();
        if (candidateNodeIds.equals(previous.nodeIds())) {
            return;
        }

        for (RouteRecalculation pending : stillSafePending) {
            cancel(pending, "화재 확산으로 무효화됨");
        }

        RouteRecalculation recalculation = save(RouteRecalculation.createPendingForFireSpread(
                lockedSession, representativeEdge, previous.nodeIds(), previous.totalWeight(),
                candidateNodeIds, candidate.totalWeight()));
        trainingEventPublisher.publishRouteRecalculationRequestedAfterCommit(recalculation);
    }

    // 혼잡 반영은 "더 나은 대안"을 위한 최적화일 뿐이고 화재 우회는 안전 기능이다. 혼잡 상태 저장소
    // (DynamoDB) 장애 같은 이유로 조회가 실패해도 화재 우회 후보 생성까지 막으면 안 되므로,
    // 실패하면 혼잡 없이(빈 맵) 계산한다.
    private Map<UUID, Double> currentCongestionMultipliersOrEmpty(UUID sessionId, UUID floorId) {
        try {
            return currentCongestionWeightProvider.currentMultipliers(sessionId, floorId);
        } catch (RuntimeException exception) {
            log.warn("현재 혼잡 상태를 조회하지 못해 혼잡 가중치 없이 화재 우회 경로를 계산: sessionId={}, floorId={}",
                    sessionId, floorId, exception);
            return Map.of();
        }
    }

    // currentCongestionMultipliersOrEmpty와 달리 실패를 빈 맵으로 삼키지 않는다 - 혼잡 재탐색
    // (trigger())은 화재 우회와 달리 안전 기능이 아니라 최적화라서, 조회 실패로 다른 CCTV의
    // 혼잡을 모르는 채 후보를 계산해 기존 PENDING을 섣불리 취소/대체하기보다는 호출부가 아예
    // 건너뛰고 기존 PENDING을 그대로 두는 쪽이 안전하다.
    private Optional<Map<UUID, Double>> currentCongestionMultipliersOrFail(UUID sessionId, UUID floorId) {
        try {
            return Optional.of(currentCongestionWeightProvider.currentMultipliers(sessionId, floorId));
        } catch (RuntimeException exception) {
            log.warn("현재 혼잡 상태를 조회하지 못해 혼잡 재탐색을 건너뜀(기존 PENDING 유지): sessionId={}, floorId={}",
                    sessionId, floorId, exception);
            return Optional.empty();
        }
    }

    // 끝난 CCTV 몫을 뺀 층 전체 혼잡을 조회한다(혼잡 종료 처리용). 실패 처리는 위와 같다.
    private Optional<Map<UUID, Double>> currentCongestionMultipliersOrFail(
            UUID sessionId, UUID floorId, Set<String> excludedCctvCodes) {
        try {
            return Optional.of(currentCongestionWeightProvider.currentMultipliers(
                    sessionId, floorId, excludedCctvCodes));
        } catch (RuntimeException exception) {
            log.warn("현재 혼잡 상태를 조회하지 못해 혼잡 종료 판단을 건너뜀(기존 PENDING 유지): sessionId={}, floorId={}",
                    sessionId, floorId, exception);
            return Optional.empty();
        }
    }

    // nodeIds(순서대로 이어진 경로)가 edges 중 하나라도 연속된 두 노드로 포함하면 그 구간을
    // 지나간다고 본다. 엣지 저장 방향과 실제 이동 방향이 다를 수 있어(양방향 통행) 순서 상관없이
    // 두 노드 쌍이 일치하는지만 확인한다.
    private boolean crossesAnyEdge(List<UUID> nodeIds, List<MapEdge> edges) {
        return findCrossedEdge(nodeIds, edges).isPresent();
    }

    // crossesAnyEdge와 같은 기준으로, 실제로 경로가 지나가는 첫 번째 엣지를 돌려준다.
    private Optional<MapEdge> findCrossedEdge(List<UUID> nodeIds, List<MapEdge> edges) {
        if (nodeIds.size() < 2 || edges.isEmpty()) {
            return Optional.empty();
        }
        for (MapEdge edge : edges) {
            UUID from = edge.getFromNode().getId();
            UUID to = edge.getToNode().getId();
            for (int i = 0; i < nodeIds.size() - 1; i++) {
                UUID a = nodeIds.get(i);
                UUID b = nodeIds.get(i + 1);
                if ((a.equals(from) && b.equals(to)) || (a.equals(to) && b.equals(from))) {
                    return Optional.of(edge);
                }
            }
        }
        return Optional.empty();
    }

    // 승인 시점 기준으로 그 재탐색이 제안한 경로가 현재 화재 구간을 지나는지 다시 확인한다.
    // trigger()/triggerForFireSpread() 호출 이후에도 화재가 더 번질 수 있어, 승인 직전에
    // 한 번 더 검증하지 않으면 관리자가 화재 통과 경로를 승인해버릴 수 있다.
    private boolean crossesCurrentFire(RouteRecalculation recalculation) {
        UUID floorId = recalculation.resolvedFloorId();
        UUID scenarioId = recalculation.getTrainingSession().getScenario().getId();
        return crossesAnyEdge(recalculation.getRecalculatedNodeIds(), firedEdgesForFloor(scenarioId, floorId));
    }

    // 그 시나리오가 그 층에 낸 화재 구간 중 현재 통행 불가한 MapEdge id 집합. 혼잡 우회 후보를
    // 계산할 때 이것도 함께 제외해야, 혼잡을 피하려다 화재 구간을 지나는 경로를 제안하는 일이 없다.
    private Set<UUID> firedEdgeIdsForFloor(UUID scenarioId, UUID floorId) {
        return firedEdgesForFloor(scenarioId, floorId).stream().map(MapEdge::getId).collect(Collectors.toSet());
    }

    // firedEdgeIdsForFloor의 공개 버전. TrainingSessionService.start()가 발화점을 markFired()한
    // 직후 계산하는 최초 안내 경로도 이 기준으로 화재 구간을 제외해야 한다(그러지 않으면 훈련
    // 시작 직후부터 발화점을 지나는 경로가 유도등에 반영될 수 있다).
    @Transactional(readOnly = true)
    public Set<UUID> firedEdgeIds(UUID scenarioId, UUID floorId) {
        return firedEdgeIdsForFloor(scenarioId, floorId);
    }

    // 같은 층을 다른 시나리오가 동시에(RUNNING) 쓸 수 있어 FloorGridCell.isFired만으로는 화재가
    // "이 시나리오"의 것인지 구분할 수 없다 - 그 시나리오의 FireZone에 속한 셀이면서 동시에
    // 현재 실제로 isFired=true인 셀만 화재 구간으로 인정한다(FireZone은 세션 종료 뒤에도 이력으로
    // 남을 수 있으므로 isFired 교집합 없이 FireZone만 보면 이미 꺼진 화재까지 잡힐 수 있다).
    private List<MapEdge> firedEdgesForFloor(UUID scenarioId, UUID floorId) {
        List<UUID> firedCellIds = firedCellIdsForFloor(scenarioId, floorId);
        if (firedCellIds.isEmpty()) {
            return List.of();
        }
        return edgesOfCells(firedCellIds);
    }

    // firedEdgesForFloor와 같은 기준(그 시나리오의 FireZone ∩ 현재 isFired)으로 본 화재 셀 id 목록.
    private List<UUID> firedCellIdsForFloor(UUID scenarioId, UUID floorId) {
        Set<UUID> scenarioFireCellIds = fireZoneRepository.findByScenario_IdAndFloor_Id(scenarioId, floorId).stream()
                .map(FireZone::getGridCellId)
                .collect(Collectors.toSet());
        if (scenarioFireCellIds.isEmpty()) {
            return List.of();
        }
        return floorGridCellRepository.findAllByFloor_IdAndIsFiredTrue(floorId).stream()
                .map(FloorGridCell::getId)
                .filter(scenarioFireCellIds::contains)
                .toList();
    }

    // 지금 화재 셀에서 확산 규칙(FireSpreadStepService와 같은 격자 상하좌우, walkable이고 아직 안 붙은 셀만)대로
    // FireProximity.MAX_GENERATION 세대 안에 불이 붙을 셀을 구하고, 그 셀을 지나는 엣지마다 "가장 빨리 불붙는
    // 세대"를 매긴다. 화재 셀 자체를 지나는 엣지는 excludedEdgeIds로 이미 제외되므로 여기서 뺀다.
    // 실제 확산은 직전 세대(frontier)에서만 번지지만, 안쪽 화재 셀의 이웃은 이미 불붙었거나 걸을 수 없는
    // 셀이라 전체 화재 셀에서 출발해도 결과가 같다.
    private FireProximity fireProximityForFloor(TrainingScenario scenario, UUID floorId) {
        List<UUID> firedCellIds = firedCellIdsForFloor(scenario.getId(), floorId);
        if (firedCellIds.isEmpty()) {
            return FireProximity.NONE;
        }
        Set<UUID> firedCellIdSet = Set.copyOf(firedCellIds);
        Map<Long, FloorGridCell> cellByPosition = new HashMap<>();
        for (FloorGridCell cell : floorGridCellRepository.findAllByFloor_Id(floorId)) {
            cellByPosition.put(gridPositionKey(cell.getRowIndex(), cell.getColumnIndex()), cell);
        }

        Map<UUID, Integer> generationByCellId = new HashMap<>();
        ArrayDeque<FloorGridCell> frontier = new ArrayDeque<>();
        for (FloorGridCell cell : cellByPosition.values()) {
            if (firedCellIdSet.contains(cell.getId())) {
                generationByCellId.put(cell.getId(), 0);
                frontier.add(cell);
            }
        }
        int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        while (!frontier.isEmpty()) {
            FloorGridCell cell = frontier.poll();
            int generation = generationByCellId.get(cell.getId());
            if (generation >= FireProximity.MAX_GENERATION) {
                continue;
            }
            for (int[] direction : directions) {
                FloorGridCell neighbor = cellByPosition.get(gridPositionKey(
                        cell.getRowIndex() + direction[0], cell.getColumnIndex() + direction[1]));
                if (neighbor == null || !neighbor.isWalkable() || neighbor.isFired()
                        || generationByCellId.containsKey(neighbor.getId())) {
                    continue;
                }
                generationByCellId.put(neighbor.getId(), generation + 1);
                frontier.add(neighbor);
            }
        }
        firedCellIdSet.forEach(generationByCellId::remove);
        if (generationByCellId.isEmpty()) {
            return FireProximity.NONE;
        }

        Set<UUID> firedEdgeIds = edgesOfCells(firedCellIds).stream().map(MapEdge::getId).collect(Collectors.toSet());
        Map<UUID, Integer> generationByEdgeId = new HashMap<>();
        for (MapEdgeGridCell mapping
                : mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.copyOf(generationByCellId.keySet()))) {
            UUID edgeId = mapping.getMapEdge().getId();
            if (firedEdgeIds.contains(edgeId)) {
                continue;
            }
            generationByEdgeId.merge(edgeId, generationByCellId.get(mapping.getGridCell().getId()), Math::min);
        }
        double spreadIntervalSec = scenario.getFireSpreadSpeed().tickInterval().toSeconds();
        return new FireProximity(generationByEdgeId, spreadIntervalSec);
    }

    private static long gridPositionKey(int row, int column) {
        return ((long) row << 32) | (column & 0xFFFFFFFFL);
    }

    // 아직 훈련이 시작되지 않은 시나리오의 최초 발화점(관리자가 수동 지정한 셀)이 막는 MapEdge id 집합.
    // 확산으로 생긴 FireZone은 이전 세션의 이력일 수 있어 포함하지 않는다(TrainingSessionService.start()가
    // 발화점을 활성화할 때와 같은 기준).
    private Set<UUID> originEdgeIdsForFloor(UUID scenarioId, UUID floorId) {
        List<UUID> originCellIds = fireZoneRepository.findByScenario_IdAndIsManualAddTrue(scenarioId).stream()
                .filter(zone -> floorId.equals(zone.getFloorId()))
                .map(FireZone::getGridCellId)
                .toList();
        if (originCellIds.isEmpty()) {
            return Set.of();
        }
        return edgesOfCells(originCellIds).stream().map(MapEdge::getId).collect(Collectors.toSet());
    }

    private List<MapEdge> edgesOfCells(List<UUID> gridCellIds) {
        return mapEdgeGridCellRepository.findAllByGridCell_IdIn(gridCellIds).stream()
                .map(MapEdgeGridCell::getMapEdge)
                .distinct()
                .toList();
    }

    // VERY_CROWDED도 완전 제외가 아니라 큰 페널티(CurrentCongestionWeightProvider와 동일한
    // 배율)로만 반영한다 - 화재/연기와 달리 혼잡은 실제로 통행이 불가능한 게 아니므로, 여러
    // 구간이 동시에 VERY_CROWDED여도 경로 자체가 안 나오는(EVACUATION_ROUTE_NOT_FOUND) 일이
    // 없어야 한다. 화재 우회(triggerForFireSpread)가 이미 이 정책이다 - 시스템 전체를
    // "화재=완전 차단 / 혼잡=페널티" 하나의 규칙으로 통일한다.
    private Map<UUID, Double> weightMultipliersFor(List<MapEdge> affectedEdges, CongestionLevel level) {
        double multiplier = switch (level) {
            case CAUTION -> CAUTION_WEIGHT_MULTIPLIER;
            case CROWDED -> CROWDED_WEIGHT_MULTIPLIER;
            case VERY_CROWDED -> CurrentCongestionWeightProvider.VERY_CROWDED_WEIGHT_MULTIPLIER;
            case NORMAL -> 1.0;
        };
        if (multiplier == 1.0) {
            return Map.of();
        }
        return affectedEdges.stream().collect(Collectors.toMap(
                MapEdge::getId, edge -> multiplier, (left, right) -> left));
    }

    // 혼잡 종료(ENDED) 처리. 끝난 CCTV 하나만 보고 "정상(직행) 경로로 복구"하면 다른 CCTV가 아직 혼잡한
    // 구간으로 다시 안내될 수 있으므로, 층 전체의 현재 혼잡(끝난 CCTV 몫만 제외)과 화재를 반영한 경로를
    // 기준으로 삼는다 - trigger()의 혼잡 우회와 같은 기준이다.
    // - 혼잡 상태 조회가 실패하면 불완전한 정보로 판단하지 않고 기존 PENDING을 그대로 둔 채 건너뛴다.
    // - 기존 PENDING은 일괄 취소하지 않는다. 이번 후보와 같은 PENDING은 남기고(다른 CCTV 혼잡 때문에
    //   생긴 여전히 유효한 제안일 수 있다) 다른 것만 취소한다.
    // - 새 PENDING(복구 제안)은 승인된 우회가 있을 때만 만든다. 승인된 우회가 없는 상태의 우회 제안은
    //   각 CCTV의 LEVEL_UP이 전담하므로, ENDED가 다른 CCTV의 우회를 새로 제안하지 않는다(관리자가
    //   반려한 제안이 다른 CCTV의 종료로 되살아나는 일을 막는다).
    private void handleCongestionEnded(TrainingSession session, UUID floorId, UUID startNodeId,
            MapEdge triggerEdge, List<RouteRecalculation> existingPending, CongestionLevel level,
            String cctvCode, double density) {
        Set<String> endedCctvCodes = cctvCode == null ? Set.of() : Set.of(cctvCode);
        Optional<Map<UUID, Double>> congestionMultipliers =
                currentCongestionMultipliersOrFail(session.getId(), floorId, endedCctvCodes);
        if (congestionMultipliers.isEmpty()) {
            return;
        }

        // 혼잡이 끝나 복구하려는 순간에도 그 사이 화재가 번졌을 수 있으므로, 후보 역시 지금 이
        // 시나리오가 낸 화재 구간은 제외한다 - 그렇지 않으면 "정상 경로"라는 이유로 화재 구간을
        // 지나는 복구 후보를 제안할 수 있다.
        Set<UUID> excludedEdgeIds = firedEdgeIdsForFloor(session.getScenario().getId(), floorId);
        FireProximity fireProximity = fireProximityForFloor(session.getScenario(), floorId);
        EvacuationRoute candidate;
        try {
            candidate = evacuationRouteService.findShortestRoute(
                    floorId, startNodeId, excludedEdgeIds, congestionMultipliers.get(), fireProximity);
        } catch (ApiException exception) {
            if (exception.getErrorCode() == EvacuationErrorCode.EVACUATION_ROUTE_NOT_FOUND) {
                log.warn("화재를 피한 복구 경로를 찾을 수 없어 재탐색 승인 대기 항목을 생성하지 않음: sessionId={}, edgeId={}",
                        session.getId(), triggerEdge.getId());
                // 새 후보는 못 찾았지만, 기존 PENDING은 이전 판단 기준이라 이미 낡았으므로 무효화한다.
                existingPending.forEach(pending -> cancel(pending, "혼잡 종료로 무효화됨"));
                return;
            }
            throw exception;
        }
        List<UUID> candidateNodeIds = candidate.path().stream().map(MapNode::getId).toList();

        Optional<RouteRecalculation> latestApproved = routeRecalculationRepository
                .findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                        session.getId(), RecalculationStatus.APPROVED);
        RouteSnapshot previous = latestApproved
                .map(approved -> new RouteSnapshot(approved.getRecalculatedNodeIds(), approved.getTotalWeight()))
                .orElseGet(() -> resolveActiveRoute(session, floorId, startNodeId));

        if (candidateNodeIds.equals(previous.nodeIds())) {
            // 후보가 현재 활성 경로와 같다 - 바꿀 게 없으므로 떠 있던 PENDING은 낡은 제안이다.
            existingPending.forEach(pending -> cancel(pending, "혼잡 종료로 무효화됨"));
            return;
        }

        boolean sameCandidateAlreadyPending = false;
        for (RouteRecalculation pending : existingPending) {
            if (pending.getRecalculatedNodeIds().equals(candidateNodeIds)) {
                sameCandidateAlreadyPending = true;
            } else {
                cancel(pending, "혼잡 종료로 무효화됨");
            }
        }
        if (sameCandidateAlreadyPending || latestApproved.isEmpty()) {
            return;
        }

        savePending(session, triggerEdge, cctvCode, RecalculationTriggerType.ENDED, level, density,
                previous, candidate, candidateNodeIds);
    }

    // "현재 활성 경로"를 별도로 저장하지 않으므로, 세션에서 가장 최근 승인된 경로가 있으면 그것을,
    // 없으면 대표 시작점 기준 정상(직행) 경로를 활성 경로로 취급한다.
    private RouteSnapshot resolveActiveRoute(TrainingSession session, UUID floorId, UUID startNodeId) {
        Optional<RouteRecalculation> latestApproved = routeRecalculationRepository
                .findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(
                        session.getId(), RecalculationStatus.APPROVED);
        if (latestApproved.isPresent()) {
            RouteRecalculation approved = latestApproved.get();
            return new RouteSnapshot(approved.getRecalculatedNodeIds(), approved.getTotalWeight());
        }

        try {
            // [의도적 설계 결정 - 이슈 #247] 여기서 화재를 제외하면 안 된다: triggerForFireSpread()의
            // candidate도 같은 시나리오+층 기준으로 "현재 화재 구간"을 제외해 계산하므로, 승인된
            // 경로가 아직 없는 상태에서 previous에도 똑같이 화재를 제외해버리면 두 값이 항상
            // 같아져서 candidateNodeIds.equals(previous.nodeIds())가 영원히 true가 된다 - 즉 승인된
            // 경로가 없는 세션에서는 화재가 아무리 번져도 다시는 PENDING이 안 생기게 된다(직접
            // 재현해서 확인함, TrainingSessionServiceRealRecalculationIntegrationTest류 참고).
            //
            // 이 부정확함(previous가 화재를 모름)은 의도적으로 남겨뒀다: 실패 방향이 안전 쪽이다.
            // "불필요한 PENDING이 한 번 더 뜬다"가 "떠야 할 PENDING이 안 뜬다"보다 훨씬 싸다
            // (화재 확산이 반복되면 기존 PENDING을 취소하고 새 PENDING을 다시 만들 수 있음). 근본 해결(훈련 시작 시 초기
            // 경로를 시스템 자동 승인 APPROVED 레코드로 남겨서 이 폴백 자체를 거의 안 타게 만드는
            // 것)은 triggerEdge nullable화 + DB 수동 마이그레이션 + 재탐색 이력 노출 여부 결정이
            // 필요해 비용이 더 크다고 판단해 보류했다.
            //
            // 재검토 조건: (1) 관리자가 "현재 경로와 동일한 PENDING"을 노이즈로 느낀다는 피드백이
            // 쌓이면, (2) 혼잡도·위험도 가중치(α, β) 같은 걸 도입해 previous/candidate 비교 기준
            // 자체가 바뀌는 시점에 함께 재평가한다. crossesAnyEdge를 활용한 가드 테스트가
            // trigger_...NoApprovedHistoryPendingCreatedOnce 계열 테스트에 있으니, 여길 고칠 땐
            // 그 테스트가 먼저 깨지는지 확인할 것.
            EvacuationRoute direct = evacuationRouteService.findShortestRoute(floorId, startNodeId);
            List<UUID> nodeIds = direct.path().stream().map(node -> node.getId()).toList();
            return new RouteSnapshot(nodeIds, direct.totalWeight());
        } catch (ApiException exception) {
            // 정상 경로조차 없으면(EXIT 미지정 등) 비교 기준 없이 후보만 제시한다.
            return new RouteSnapshot(List.of(), 0.0);
        }
    }

    private void savePending(TrainingSession session, MapEdge triggerEdge, String cctvCode,
            RecalculationTriggerType triggerType, CongestionLevel level, double density,
            RouteSnapshot previous, EvacuationRoute candidate, List<UUID> candidateNodeIds) {
        RouteRecalculation recalculation = save(RouteRecalculation.createPending(
                session, triggerEdge, cctvCode, triggerType, level, density,
                previous.nodeIds(), previous.totalWeight(), candidateNodeIds, candidate.totalWeight()));
        trainingEventPublisher.publishRouteRecalculationRequestedAfterCommit(recalculation);
    }

    private RouteRecalculation save(RouteRecalculation recalculation) {
        return routeRecalculationRepository.save(recalculation);
    }

    private void cancel(RouteRecalculation recalculation, String reason) {
        recalculation.cancel(Instant.now(), reason);
        // 취소 이벤트 데이터(RouteRecalculationEventData)는 커밋 후 콜백에서 만들어지는데, 그때 지연 로딩
        // 컬렉션(recalculatedNodeIds)을 처음 읽게 되면 트랜잭션 밖 지연 로딩에 기대게 된다. 이 경로에서
        // 아직 한 번도 읽지 않았을 수 있으므로(예: 도면 변경으로 무효화) 트랜잭션 안에서 미리 초기화한다.
        Hibernate.initialize(recalculation.getRecalculatedNodeIds());
        trainingEventPublisher.publishRouteRecalculationCancelledAfterCommit(recalculation);
    }

    // 훈련 세션 종료(정상/강제/타임아웃) 시 그 세션의 남은 PENDING을 전부 무효화한다.
    @Transactional
    public void cancelAllPendingForSession(UUID sessionId, String reason) {
        List<RouteRecalculation> pendingList = routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(sessionId, RecalculationStatus.PENDING);
        for (RouteRecalculation pending : pendingList) {
            cancel(pending, reason);
        }
    }

    @Transactional(readOnly = true)
    public List<RouteRecalculationSummaryResponse> getRecalculations(
            UUID trainingSessionId, RecalculationStatus status, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(trainingSessionId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));
        List<RouteRecalculation> recalculations = status != null
                ? routeRecalculationRepository
                        .findAllByTrainingSession_IdAndStatusAndTrainingSession_Scenario_Building_SchoolNameOrderByRequestedAtDesc(
                                trainingSessionId, status, schoolName)
                : routeRecalculationRepository
                        .findAllByTrainingSession_IdAndTrainingSession_Scenario_Building_SchoolNameOrderByRequestedAtDesc(
                                trainingSessionId, schoolName);
        return recalculations.stream().map(RouteRecalculationSummaryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public RouteRecalculationDetailResponse getRecalculationDetail(UUID recalculationId, String email) {
        return RouteRecalculationDetailResponse.from(findOrThrow(recalculationId, email));
    }

    // "지금 안내되고 있는 경로"를 세션 단위로 한 번에, 도면에 바로 그릴 수 있는 노드 상세
    // 목록으로 반환한다. resolveActiveRoute()와 동일한 우선순위(가장 최근 승인된 재탐색 >
    // 시나리오 대표 startNode 기준 최단 경로)를 쓰지만, 특정 triggerEdge에 종속되지 않고
    // 세션 전체에서 가장 최근 승인 건을 찾는다는 점이 다르다.
    // 세션 상태(RUNNING 여부)는 검증하지 않는다 - SCHEDULED 세션은 아직 재탐색이 없으므로
    // 자연히 최단 경로로, COMPLETED/ERROR 세션은 종료 시점까지의 마지막 승인 경로로 응답된다.
    @Transactional(readOnly = true)
    public CurrentRouteResponse getCurrentRoute(UUID sessionId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        TrainingSession session = trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));
        TrainingScenario scenario = session.getScenario();

        Optional<RouteRecalculation> latestApproved = routeRecalculationRepository
                .findFirstByTrainingSession_IdAndStatusOrderByResolvedAtDesc(sessionId, RecalculationStatus.APPROVED);
        if (latestApproved.isPresent()) {
            RouteRecalculation approved = latestApproved.get();
            List<CurrentRouteResponse.NodePoint> path = toNodePoints(approved.getRecalculatedNodeIds());
            UUID startNodeId = path.isEmpty() ? null : path.get(0).nodeId();
            return new CurrentRouteResponse(
                    sessionId,
                    scenario.getId(),
                    scenario.getBuildingId(),
                    approved.resolvedFloorId(),
                    startNodeId,
                    CurrentRouteResponse.RouteSource.RECALCULATED,
                    path,
                    approved.getTotalWeight(),
                    approved.getResolvedAt());
        }

        MapNode representativeStart = scenario.getStartNode();
        if (representativeStart == null) {
            throw new ApiException(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
        }
        UUID floorId = representativeStart.getFloor().getId();
        // 시작 전(SCHEDULED)에는 발화점 셀이 아직 isFired=false다(evacuation-setup은 isFired를 켜지 않고
        // 훈련 시작 시점에 켠다). isFired 기준으로만 제외하면 시작 전 화면이 화재를 지나는 경로를 보여주므로,
        // 시작 전에는 시나리오에 등록된 최초 발화점 구간을 기준으로 제외한다.
        Set<UUID> excludedEdgeIds = session.getStatus() == TrainingStatus.SCHEDULED
                ? originEdgeIdsForFloor(scenario.getId(), floorId)
                : firedEdgeIdsForFloor(scenario.getId(), floorId);
        EvacuationRoute directRoute =
                evacuationRouteService.findShortestRoute(floorId, representativeStart.getId(), excludedEdgeIds);
        List<CurrentRouteResponse.NodePoint> path = directRoute.path().stream()
                .map(CurrentRouteResponse.NodePoint::from)
                .toList();
        return new CurrentRouteResponse(
                sessionId,
                scenario.getId(),
                scenario.getBuildingId(),
                floorId,
                representativeStart.getId(),
                CurrentRouteResponse.RouteSource.INITIAL,
                path,
                directRoute.totalWeight(),
                session.getCreatedAt());
    }

    // RouteRecalculation은 노드 id만 저장하므로(@ElementCollection), 도면에 그릴 이름/타입/좌표를
    // 채우려면 MapNode를 다시 조회해야 한다. findAllById는 순서를 보장하지 않으므로 원래
    // 저장된 순서(출발 -> 도착)대로 재정렬한다.
    private List<CurrentRouteResponse.NodePoint> toNodePoints(List<UUID> nodeIds) {
        Map<UUID, MapNode> nodesById = mapNodeJpaRepository.findAllById(nodeIds).stream()
                .collect(Collectors.toMap(MapNode::getId, node -> node));
        // 도면 재업로드·초기화로 지워진 노드는 id만 남아 있으므로 건너뛴다(그대로 넘기면 NodePoint.from(null)에서 NPE).
        return nodeIds.stream()
                .map(nodesById::get)
                .filter(Objects::nonNull)
                .map(CurrentRouteResponse.NodePoint::from)
                .toList();
    }

    // ROUTE_RECALCULATION_CROSSES_FIRE로 거부하는 경로에서 cancel()로 무효화까지 함께 한다
    // (아래 참고). ApiException은 RuntimeException이라 기본 규칙대로면 그 cancel()까지 롤백돼
    // 무효화가 그대로 사라지므로, 이 메서드에서는 ApiException을 롤백 대상에서 제외한다.
    // 다른 예외 경로(NOT_FOUND 등)는 전부 그 이전에, 아무것도 변경하기 전에 발생하므로 영향 없다.
    @Transactional(noRollbackFor = ApiException.class)
    public RouteRecalculationResponse approve(UUID recalculationId, String approverEmail) {
        String schoolName = schoolContextService.getSchoolName(approverEmail);
        UUID sessionId = routeRecalculationRepository
                .findTrainingSessionIdByIdAndSchoolName(recalculationId, schoolName)
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.ROUTE_RECALCULATION_NOT_FOUND));
        trainingSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

        RouteRecalculation recalculation = routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(recalculationId, schoolName)
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.ROUTE_RECALCULATION_NOT_FOUND));
        validatePending(recalculation);
        if (recalculation.getTriggerEdge() == null) {
            // 트리거 엣지가 지워졌다는 건 도면이 재업로드·초기화돼 그래프가 바뀌었다는 뜻이라, 후보 경로의 노드도
            // 더 이상 존재하지 않는다. 승인해도 안내할 수 없으므로 무효화하고 거부한다 (위 화재 통과 거부와 같은 이유로
            // 무효화를 함께 커밋한다 - noRollbackFor = ApiException).
            cancel(recalculation, "도면 변경으로 무효화됨");
            throw new ApiException(EvacuationErrorCode.ROUTE_RECALCULATION_GRAPH_CHANGED);
        }
        if (crossesCurrentFire(recalculation)) {
            // 승인 거부로 끝내면 이 PENDING은 화재가 다시 번지기 전까지(=triggerForFireSpread가
            // 다시 호출되기 전까지) 아무도 정리해주지 않아 승인 대기 목록에 그대로 남는다.
            // 관리자가 놓치면 나중에 실수로 승인할 위험이 있으므로, 거부와 동시에 무효화한다.
            cancel(recalculation, "화재 구간을 지나는 경로라 승인이 거부되어 무효화됨");
            throw new ApiException(EvacuationErrorCode.ROUTE_RECALCULATION_CROSSES_FIRE);
        }
        User approver = findUserOrThrow(approverEmail);
        List<RouteRecalculation> siblingPending = routeRecalculationRepository
                .findAllByTrainingSession_IdAndStatus(
                        sessionId, RecalculationStatus.PENDING);

        recalculation.approve(Instant.now(), approver);
        siblingPending.stream()
                .filter(pending -> !pending.getId().equals(recalculation.getId()))
                .forEach(pending -> cancel(pending, "다른 경로 승인으로 무효화됨"));
        trainingEventPublisher.publishEvacuationRouteUpdatedAfterCommit(recalculation);
        applyApprovedRouteGuidance(sessionId, recalculation);

        return RouteRecalculationResponse.from(recalculation);
    }

    // 승인된 경로 위 유도등만 바꾸면 이전 경로에만 있던 유도등이 혼잡/화재 구간 쪽 옛 방향을 그대로
    // 가리키므로, 그 층 전체 유도등을 "출구로 가는 다음 노드" 방향으로 갱신한다(승인 경로가 우선).
    // IoTLightService가 이 서비스를 의존하면 순환이 생기므로 제외 엣지(화재)와 혼잡 배율은 여기서
    // 계산해 넘긴다. 혼잡은 CurrentCongestionWeightProvider 정책 그대로 VERY_CROWDED도 제외하지 않고
    // 페널티로만 반영한다 - 혼잡한 길밖에 없어도 경로 밖 유도등이 평상시로 꺼져버리지 않게 하기 위함이다.
    // approve()는 noRollbackFor = ApiException이라 여기서 예외가 나면 승인이 반쯤 커밋된 상태로 남을
    // 수 있다. 다음 홉은 부가 정보일 뿐이므로 계산이 실패하면 승인 경로만 반영하도록 흡수한다.
    private void applyApprovedRouteGuidance(UUID sessionId, RouteRecalculation recalculation) {
        UUID floorId = recalculation.resolvedFloorId();
        Map<UUID, UUID> nextHops;
        try {
            TrainingScenario scenario = recalculation.getTrainingSession().getScenario();
            Set<UUID> excludedEdgeIds = firedEdgeIdsForFloor(scenario.getId(), floorId);
            Map<UUID, Double> congestionMultipliers = currentCongestionMultipliersOrEmpty(sessionId, floorId);
            FireProximity fireProximity = fireProximityForFloor(scenario, floorId);
            nextHops = evacuationRouteService.computeNextHops(
                    floorId, excludedEdgeIds, congestionMultipliers, fireProximity);
        } catch (RuntimeException exception) {
            log.warn("층 전체 다음 홉 계산에 실패해 승인된 경로 위 유도등만 반영: sessionId={}, floorId={}",
                    sessionId, floorId, exception);
            nextHops = Map.of();
        }
        ioTLightService.applyFloorGuidance(floorId, recalculation.getRecalculatedNodeIds(), nextHops);
    }

    @Transactional
    public RouteRecalculationResponse reject(UUID recalculationId, String rejecterEmail, String reason) {
        RouteRecalculation recalculation = findOrThrow(recalculationId, rejecterEmail);
        validatePending(recalculation);
        User rejecter = findUserOrThrow(rejecterEmail);

        recalculation.reject(Instant.now(), rejecter, reason);
        trainingEventPublisher.publishRouteRecalculationRejectedAfterCommit(recalculation);

        return RouteRecalculationResponse.from(recalculation);
    }

    private RouteRecalculation findOrThrow(UUID recalculationId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        return routeRecalculationRepository
                .findByIdAndTrainingSession_Scenario_Building_SchoolName(recalculationId, schoolName)
                .orElseThrow(() -> new ApiException(EvacuationErrorCode.ROUTE_RECALCULATION_NOT_FOUND));
    }

    private User findUserOrThrow(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.ADMIN_NOT_FOUND));
    }

    // 상태 전이 검증은 엔티티가 아니라 여기서 한다
    // (TrainingSessionService.start()/end()/forceEnd(), IoTLightService와 동일한 컨벤션).
    private void validatePending(RouteRecalculation recalculation) {
        if (recalculation.getStatus() != RecalculationStatus.PENDING) {
            throw new ApiException(EvacuationErrorCode.INVALID_RECALCULATION_STATUS_TRANSITION);
        }
    }

    private record RouteSnapshot(List<UUID> nodeIds, double totalWeight) {
    }
}
