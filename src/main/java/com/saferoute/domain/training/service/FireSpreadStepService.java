package com.saferoute.domain.training.service;

import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 세션 하나의 화재 확산을 1스텝 진행한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class FireSpreadStepService {

    private final TrainingSessionRepository sessionRepository;
    private final FireZoneRepository fireZoneRepository;
    private final FloorGridCellRepository gridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final RouteRecalculationService routeRecalculationService;
    private final TrainingEventPublisher eventPublisher;

    @Transactional
    public void spreadOneStep(UUID sessionId) {
        TrainingSession session = sessionRepository.getReferenceById(sessionId);

        Duration interval = session.getScenario().getFireSpreadSpeed().tickInterval();

        Instant lastSpreadAt = session.getLastSpreadAt() != null ? session.getLastSpreadAt() : session.getStartedAt();
        if (Duration.between(lastSpreadAt, Instant.now()).compareTo(interval) < 0) {
            return;
        }

        UUID scenarioId = session.getScenario().getId();
        int currentGen = session.getCurrentGeneration();

        List<FireZone> frontier = fireZoneRepository
                .findByScenario_IdAndSpreadGeneration(scenarioId, currentGen);

        if (frontier.isEmpty()) {
            return; // 더 이상 번질 곳 없음
        }

        int nextGen = currentGen + 1;
        List<FireZone> newlyFired = new ArrayList<>();

        for (FireZone fz : frontier) {
            FloorGridCell cell = fz.getGridCell();
            for (FloorGridCell neighbor : gridCellRepository
                    .findAdjacent(cell.getFloor().getId(), cell.getRowIndex(), cell.getColumnIndex())) {
                if (neighbor.isWalkable() && !neighbor.isFired()) {
                    neighbor.markFired();
                    newlyFired.add(FireZone.createSpread(fz.getScenario(), fz.getFloor(), neighbor, nextGen));
                }
            }
        }

        if (!newlyFired.isEmpty()) {
            fireZoneRepository.saveAll(newlyFired);
        }
        session.advanceSpread(nextGen, Instant.now());

        eventPublisher.publishFireSpreadUpdatedAfterCommit(sessionId, nextGen, newlyFired);

        scheduleRouteRecalculationAfterCommit(session, newlyFired);
    }

    // triggerForFireSpread()를 이 메서드(spreadOneStep)와 같은 트랜잭션에서 직접 호출하면,
    // 재탐색 쪽에서 던진 예외(EXIT 미지정, DB 제약 위반 등 무엇이든)가 공유 트랜잭션을
    // rollback-only로 만들어서 이미 반영한 셀 발화·FireZone 저장·세대 증가까지 통째로
    // 롤백돼버린다 - 여기서 try/catch로 감싸는 것만으로는 막을 수 없다(내부 @Transactional
    // 메서드가 이미 트랜잭션을 rollback-only로 표시한 뒤이기 때문). FireSpreadService는 실패를
    // 로그만 남기고 다음 틱에서 다시 시도하므로, 같은 이유로 계속 실패하면 화재가 영영 멈춘다.
    // afterCommit 콜백은 화재 확산 트랜잭션이 이미 커밋된 뒤 트랜잭션 없는 상태에서 실행되므로,
    // 그 안에서 @Transactional(REQUIRED)인 triggerForFireSpread를 호출하면 완전히 새 트랜잭션이
    // 열려 재탐색 실패가 화재 확산에 영향을 주지 않는다 (TrainingEventPublisher의 AfterCommit
    // 발행 패턴과 동일한 컨벤션).
    private void scheduleRouteRecalculationAfterCommit(TrainingSession session, List<FireZone> newlyFired) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            triggerRouteRecalculationForNewlyFired(session, newlyFired);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            triggerRouteRecalculationForNewlyFired(session, newlyFired);
                        } catch (RuntimeException exception) {
                            log.error("커밋 후 화재 확산 경로 재탐색 실패: sessionId={}", session.getId(), exception);
                        }
                    }
                }
        );
    }

    // 새로 옮겨붙은 셀이 걸린 층마다, "현재 그 층에서 불이 붙은 모든 셀"을 기준으로 영향받는
    // MapEdge를 다시 구해 재탐색을 트리거한다. 이번 틱에 새로 옮겨붙은 셀만 넘기면 이전 틱에
    // 이미 제외됐던 구간이 이번 후보 경로에 다시 섞여 들어갈 수 있으므로 반드시 누적 상태로 조회한다.
    // 같은 층을 다른 시나리오가 동시에(RUNNING) 쓸 수 있어, FloorGridCell.isFired만으로 걸러내면
    // 이 세션과 무관한 다른 시나리오의 화재까지 영향받은 엣지로 잡힐 수 있다 - 이 세션의
    // scenario가 그 층에 낸 FireZone에 속한 셀로 한 번 더 교집합을 취한다.
    private void triggerRouteRecalculationForNewlyFired(TrainingSession session, List<FireZone> newlyFired) {
        UUID scenarioId = session.getScenario().getId();
        Set<UUID> affectedFloorIds = newlyFired.stream()
                .map(FireZone::getFloorId)
                .collect(Collectors.toSet());

        for (UUID floorId : affectedFloorIds) {
            Set<UUID> scenarioFireCellIds = fireZoneRepository.findByScenario_IdAndFloor_Id(scenarioId, floorId)
                    .stream()
                    .map(FireZone::getGridCellId)
                    .collect(Collectors.toSet());
            if (scenarioFireCellIds.isEmpty()) {
                continue;
            }
            List<UUID> firedCellIds = gridCellRepository.findAllByFloor_IdAndIsFiredTrue(floorId).stream()
                    .map(FloorGridCell::getId)
                    .filter(scenarioFireCellIds::contains)
                    .toList();
            if (firedCellIds.isEmpty()) {
                continue;
            }
            List<MapEdge> affectedEdges = mapEdgeGridCellRepository.findAllByGridCell_IdIn(firedCellIds).stream()
                    .map(MapEdgeGridCell::getMapEdge)
                    .distinct()
                    .toList();
            if (!affectedEdges.isEmpty()) {
                routeRecalculationService.triggerForFireSpread(session, affectedEdges);
            }
        }
    }
}
