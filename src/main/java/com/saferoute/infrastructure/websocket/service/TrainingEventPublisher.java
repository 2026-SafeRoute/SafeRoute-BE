package com.saferoute.infrastructure.websocket.service;

import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.infrastructure.websocket.dto.CongestionEventData;
import com.saferoute.infrastructure.websocket.dto.CongestionEventImageUpdatedData;
import com.saferoute.infrastructure.websocket.dto.CongestionEventReceivedData;
import com.saferoute.infrastructure.websocket.dto.CongestionImageUpdatedData;
import com.saferoute.infrastructure.websocket.dto.EvacuationRouteBlockedEventData;
import com.saferoute.infrastructure.websocket.dto.FireSpreadEventData;
import com.saferoute.infrastructure.websocket.dto.GeneralMonitoringEventReceivedData;
import com.saferoute.infrastructure.websocket.dto.IoTLightEventMessage;
import com.saferoute.infrastructure.websocket.dto.IoTLightStatusEventData;
import com.saferoute.infrastructure.websocket.dto.RouteRecalculationEventData;
import com.saferoute.infrastructure.websocket.dto.TrainingEventMessage;
import com.saferoute.infrastructure.websocket.dto.TrainingEventType;
import com.saferoute.infrastructure.websocket.dto.TrainingStatusEventData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 관리자 대시보드로 보내는 WebSocket 이벤트 발행 책임을 이 클래스가 전담한다.
// SimpMessagingTemplate을 다른 서비스에 직접 주입하지 않고 항상 이 클래스를 거친다.
// 훈련 세션 이벤트는 /topic/training-sessions/{sessionId}, 유도등 이벤트는 층 도면 화면이
// 층 전체를 한 번에 보여주는 구조라 /topic/floors/{floorId}/lights로 발행한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingEventPublisher {

    private static final String SESSION_TOPIC_PREFIX = "/topic/training-sessions/";
    private static final String FLOOR_LIGHTS_TOPIC_PREFIX = "/topic/floors/";
    private static final String FLOOR_LIGHTS_TOPIC_SUFFIX = "/lights";

    private final SimpMessagingTemplate messagingTemplate;

    // 트랜잭션 커밋 시점과 무관하게 즉시 발행한다.
    // 커밋 이후에만 발행해야 하는 일반적인 경우에는 publishTrainingStatusUpdatedAfterCommit을 사용한다.
    public void publishTrainingStatusUpdated(TrainingSession session) {
        TrainingEventMessage<TrainingStatusEventData> message = TrainingEventMessage.of(
                TrainingEventType.TRAINING_STATUS_UPDATED,
                session.getId(),
                TrainingStatusEventData.from(session)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + session.getId(), message);

        log.debug(
                "훈련 상태 이벤트 발행: sessionId={}, status={}",
                session.getId(),
                session.getStatus()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 이벤트를 발행한다.
    // 트랜잭션이 롤백되면 발행하지 않는다.
    // afterCommit() 콜백은 커밋이 끝난 뒤 같은 스레드에서 즉시 실행되므로,
    // 여기서 예외가 그대로 튀어나가면 DB 반영은 이미 끝났는데도 호출자에게는
    // 마치 요청 전체가 실패한 것처럼 보일 수 있다. 그래서 발행 실패는 여기서
    // 잡아서 로그만 남기고 삼킨다 — WebSocket 발행 실패가 REST 응답/트랜잭션
    // 성공 여부에 영향을 주면 안 되기 때문이다.
    public void publishTrainingStatusUpdatedAfterCommit(TrainingSession session) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishTrainingStatusUpdated(session);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publishTrainingStatusUpdated(session);
                        } catch (RuntimeException exception) {
                            log.error(
                                    "커밋 후 훈련 상태 이벤트 발행 실패: sessionId={}",
                                    session.getId(),
                                    exception
                            );
                        }
                    }
                }
        );
    }

    // floorId는 light.getCustomNode().getFloor().getId()로 조회한다.
    // direction은 IoTLight 엔티티가 아니라 IoTLightDirectionStore(서버 메모리)가 원천이므로 별도 파라미터로 받는다.
    public void publishIoTLightStatusUpdated(IoTLight light, IoTLightDirection direction) {
        UUID floorId = light.getCustomNode().getFloor().getId();
        IoTLightEventMessage<IoTLightStatusEventData> message = IoTLightEventMessage.of(
                TrainingEventType.IOT_LIGHT_STATUS_UPDATED,
                floorId,
                new IoTLightStatusEventData(light.getId(), direction, Instant.now())
        );

        messagingTemplate.convertAndSend(FLOOR_LIGHTS_TOPIC_PREFIX + floorId + FLOOR_LIGHTS_TOPIC_SUFFIX, message);

        log.debug(
                "유도등 상태 이벤트 발행: lightId={}, floorId={}, direction={}",
                light.getId(),
                floorId,
                direction
        );
    }

    // 훈련 상태 이벤트와 동일하게, DB 트랜잭션이 실제로 커밋된 이후에만 발행하고
    // 발행 실패는 로그만 남기고 삼킨다 (publishTrainingStatusUpdatedAfterCommit 참고).
    public void publishIoTLightStatusUpdatedAfterCommit(IoTLight light, IoTLightDirection direction) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishIoTLightStatusUpdated(light, direction);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publishIoTLightStatusUpdated(light, direction);
                        } catch (RuntimeException exception) {
                            log.error(
                                    "커밋 후 유도등 상태 이벤트 발행 실패: lightId={}",
                                    light.getId(),
                                    exception
                            );
                        }
                    }
                }
        );
    }

    // 혼잡 이벤트 수신 시 즉시 발행한다 (DynamoDB 저장은 DB 트랜잭션과 무관하므로 AfterCommit 버전을 두지 않는다).
    // CCTV당 1 Observation = 1 이벤트 발행 원칙에 따라 영향받는 Edge 전체를 한 번에 담아 한 번만 발행한다 (이슈 #192).
    public void publishCongestionUpdated(UUID sessionId, List<UUID> affectedEdgeIds, ObservationItem item) {
        TrainingEventMessage<CongestionEventData> message = TrainingEventMessage.of(
                TrainingEventType.CONGESTION_UPDATED,
                sessionId,
                CongestionEventData.from(affectedEdgeIds, item)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "혼잡도 이벤트 발행: sessionId={}, affectedEdgeIds={}, level={}",
                sessionId,
                affectedEdgeIds,
                item.getCongestionLevel()
        );
    }

    public void publishCongestionImageUpdated(UUID sessionId, ObservationItem item) {
        TrainingEventMessage<CongestionImageUpdatedData> message = TrainingEventMessage.of(
                TrainingEventType.CONGESTION_IMAGE_UPDATED,
                sessionId,
                CongestionImageUpdatedData.from(item)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);
        log.debug(
                "혼잡 이벤트 이미지 갱신 발행: sessionId={}, eventId={}",
                sessionId,
                item.getEventId()
        );
    }

    // 즉시 혼잡 이벤트 수신/처리 시 발행한다. CongestionEventService가 발행 성공 여부에 따라
    // DynamoDB 처리 상태(PROCESSED/FAILED)를 직접 갱신해야 해서, 다른 AfterCommit 헬퍼처럼 실패를
    // 로그로 삼키지 않고 그대로 던진다 - 호출자가 afterCommit 트랜잭션 동기화를 직접 관리한다.
    // 즉시 혼잡 상태 전환 1건당 1회만 발행한다 - 영향받는 Edge는 affectedEdgeIds 배열로 한 번에 담는다 (이슈 #192).
    public void publishCongestionEventReceived(UUID sessionId, List<UUID> affectedEdgeIds, CongestionEventItem item) {
        TrainingEventMessage<CongestionEventReceivedData> message = TrainingEventMessage.of(
                TrainingEventType.CONGESTION_EVENT_RECEIVED,
                sessionId,
                CongestionEventReceivedData.from(affectedEdgeIds, item)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "즉시 혼잡 이벤트 발행: sessionId={}, affectedEdgeIds={}, eventId={}, level={}",
                sessionId,
                affectedEdgeIds,
                item.getEventId(),
                item.getCongestionLevel()
        );
    }

    public void publishCongestionEventImageUpdated(UUID sessionId, CongestionEventItem item) {
        TrainingEventMessage<CongestionEventImageUpdatedData> message = TrainingEventMessage.of(
                TrainingEventType.CONGESTION_EVENT_IMAGE_UPDATED,
                sessionId,
                CongestionEventImageUpdatedData.from(item)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);
        log.debug(
                "즉시 혼잡 이벤트 이미지 갱신 발행: sessionId={}, eventId={}",
                sessionId,
                item.getEventId()
        );
    }

    // AI_ANALYSIS_STARTED/ROUTE_DEVIATION_DETECTED가 새로 저장됐을 때 발행한다
    // (CongestionObservationService.tryCreateAiAnalysisStartedEvent,
    // RouteDeviationService.createRouteDeviationDetectedEvent 참고, 이슈 #254).
    public void publishGeneralMonitoringEventReceived(UUID sessionId, GeneralMonitoringEventItem item) {
        TrainingEventMessage<GeneralMonitoringEventReceivedData> message = TrainingEventMessage.of(
                TrainingEventType.GENERAL_MONITORING_EVENT_RECEIVED,
                sessionId,
                GeneralMonitoringEventReceivedData.from(item)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);
        log.debug(
                "일반 모니터링 이벤트 발행: sessionId={}, eventId={}, eventType={}",
                sessionId,
                item.getEventId(),
                item.getEventType()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행한다 (publishTrainingStatusUpdatedAfterCommit 참고).
    public void publishRouteRecalculationRequestedAfterCommit(RouteRecalculation recalculation) {
        publishAfterCommit(
                () -> publishRouteRecalculationRequested(recalculation),
                "커밋 후 재탐색 요청 이벤트 발행 실패: recalculationId=" + recalculation.getId()
        );
    }

    public void publishRouteRecalculationRequested(RouteRecalculation recalculation) {
        UUID sessionId = recalculation.getTrainingSession().getId();
        TrainingEventMessage<RouteRecalculationEventData> message = TrainingEventMessage.of(
                TrainingEventType.ROUTE_RECALCULATION_REQUESTED,
                sessionId,
                RouteRecalculationEventData.from(recalculation)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "재탐색 요청 이벤트 발행: sessionId={}, recalculationId={}",
                sessionId,
                recalculation.getId()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행한다 (publishTrainingStatusUpdatedAfterCommit 참고).
    public void publishEvacuationRouteUpdatedAfterCommit(RouteRecalculation recalculation) {
        publishAfterCommit(
                () -> publishEvacuationRouteUpdated(recalculation),
                "커밋 후 대피 경로 갱신 이벤트 발행 실패: recalculationId=" + recalculation.getId()
        );
    }

    public void publishEvacuationRouteUpdated(RouteRecalculation recalculation) {
        UUID sessionId = recalculation.getTrainingSession().getId();
        TrainingEventMessage<RouteRecalculationEventData> message = TrainingEventMessage.of(
                TrainingEventType.EVACUATION_ROUTE_UPDATED,
                sessionId,
                RouteRecalculationEventData.from(recalculation)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "대피 경로 갱신 이벤트 발행: sessionId={}, recalculationId={}",
                sessionId,
                recalculation.getId()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행한다 (publishTrainingStatusUpdatedAfterCommit 참고).
    public void publishRouteRecalculationRejectedAfterCommit(RouteRecalculation recalculation) {
        publishAfterCommit(
                () -> publishRouteRecalculationRejected(recalculation),
                "커밋 후 재탐색 거절 이벤트 발행 실패: recalculationId=" + recalculation.getId()
        );
    }

    public void publishRouteRecalculationRejected(RouteRecalculation recalculation) {
        UUID sessionId = recalculation.getTrainingSession().getId();
        TrainingEventMessage<RouteRecalculationEventData> message = TrainingEventMessage.of(
                TrainingEventType.ROUTE_RECALCULATION_REJECTED,
                sessionId,
                RouteRecalculationEventData.from(recalculation)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "재탐색 거절 이벤트 발행: sessionId={}, recalculationId={}",
                sessionId,
                recalculation.getId()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행한다 (publishTrainingStatusUpdatedAfterCommit 참고).
    public void publishRouteRecalculationCancelledAfterCommit(RouteRecalculation recalculation) {
        publishAfterCommit(
                () -> publishRouteRecalculationCancelled(recalculation),
                "커밋 후 재탐색 취소 이벤트 발행 실패: recalculationId=" + recalculation.getId()
        );
    }

    public void publishRouteRecalculationCancelled(RouteRecalculation recalculation) {
        UUID sessionId = recalculation.getTrainingSession().getId();
        TrainingEventMessage<RouteRecalculationEventData> message = TrainingEventMessage.of(
                TrainingEventType.ROUTE_RECALCULATION_CANCELLED,
                sessionId,
                RouteRecalculationEventData.from(recalculation)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "재탐색 취소 이벤트 발행: sessionId={}, recalculationId={}",
                sessionId,
                recalculation.getId()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행
    public void publishFireSpreadUpdatedAfterCommit(UUID sessionId, int spreadGeneration, List<FireZone> newlyFired) {
        if (newlyFired.isEmpty()) {
            return;
        }
        publishAfterCommit(
                () -> publishFireSpreadUpdated(sessionId, spreadGeneration, newlyFired),
                "커밋 후 화재 확산 이벤트 발행 실패: sessionId=" + sessionId
        );
    }

    // FireSpreadService가 확산을 1스텝 진행시킬 때마다 호출한다. 새로 옮겨붙은 셀이 없으면 발행하지 않는다.
    public void publishFireSpreadUpdated(UUID sessionId, int spreadGeneration, List<FireZone> newlyFired) {
        if (newlyFired.isEmpty()) {
            return;
        }

        TrainingEventMessage<FireSpreadEventData> message = TrainingEventMessage.of(
                TrainingEventType.FIRE_SPREAD_UPDATED,
                sessionId,
                FireSpreadEventData.from(spreadGeneration, newlyFired)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + sessionId, message);

        log.debug(
                "화재 확산 이벤트 발행: sessionId={}, generation={}, newlyFiredCount={}",
                sessionId,
                spreadGeneration,
                newlyFired.size()
        );
    }

    // DB 트랜잭션이 실제로 커밋된 이후에만 발행 (routeBlockedAt 기록이 롤백되면 알림도 보내지 않는다)
    public void publishEvacuationRouteBlockedAfterCommit(TrainingSession session, UUID floorId) {
        publishAfterCommit(
                () -> publishEvacuationRouteBlocked(session, floorId),
                "커밋 후 대피 경로 소멸 이벤트 발행 실패: sessionId=" + session.getId()
        );
    }

    public void publishEvacuationRouteBlocked(TrainingSession session, UUID floorId) {
        TrainingEventMessage<EvacuationRouteBlockedEventData> message = TrainingEventMessage.of(
                TrainingEventType.EVACUATION_ROUTE_BLOCKED,
                session.getId(),
                EvacuationRouteBlockedEventData.from(session, floorId)
        );

        messagingTemplate.convertAndSend(SESSION_TOPIC_PREFIX + session.getId(), message);

        log.debug("대피 경로 소멸 이벤트 발행: sessionId={}, floorId={}", session.getId(), floorId);
    }

    private void publishAfterCommit(Runnable publish, String failureLogMessage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publish.run();
                        } catch (RuntimeException exception) {
                            log.error(failureLogMessage, exception);
                        }
                    }
                }
        );
    }
}
