package com.saferoute.infrastructure.websocket.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.infrastructure.websocket.dto.EvacuationRouteBlockedEventData;
import com.saferoute.infrastructure.websocket.dto.IoTLightEventMessage;
import com.saferoute.infrastructure.websocket.dto.IoTLightStatusEventData;
import com.saferoute.infrastructure.websocket.dto.TrainingEventMessage;
import com.saferoute.infrastructure.websocket.dto.TrainingEventType;
import com.saferoute.infrastructure.websocket.dto.TrainingStatusEventData;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class TrainingEventPublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private TrainingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TrainingEventPublisher(messagingTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("올바른 destination으로, eventType/sessionId/occurredAt/data가 누락되지 않은 메시지를 발행한다")
    void publishesMessageWithAllRequiredFieldsToCorrectDestination() {
        TrainingSession session = mockSession(TrainingStatus.COMPLETED);

        publisher.publishTrainingStatusUpdated(session);

        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue())
                .isEqualTo("/topic/training-sessions/" + session.getId());

        TrainingEventMessage<TrainingStatusEventData> message =
                (TrainingEventMessage<TrainingStatusEventData>) payloadCaptor.getValue();

        assertThat(message.eventType()).isEqualTo(TrainingEventType.TRAINING_STATUS_UPDATED);
        assertThat(message.sessionId()).isEqualTo(session.getId());
        assertThat(message.occurredAt()).isNotNull();
        assertThat(message.data()).isNotNull();
        assertThat(message.data().status()).isEqualTo(TrainingStatus.COMPLETED);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("대피 경로 소멸 알림을 세션 토픽으로 층과 소멸 시각을 담아 발행한다")
    void publishEvacuationRouteBlocked_sendsToSessionTopicWithBlockedInfo() {
        UUID sessionId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Instant blockedAt = Instant.parse("2026-01-01T00:01:35Z");
        TrainingSession session = mock(TrainingSession.class);
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.getRouteBlockedAt()).thenReturn(blockedAt);
        lenient().when(session.routeBlockedAfterSec()).thenReturn(95);

        publisher.publishEvacuationRouteBlocked(session, floorId);

        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());
        assertThat(destinationCaptor.getValue()).isEqualTo("/topic/training-sessions/" + sessionId);

        TrainingEventMessage<EvacuationRouteBlockedEventData> message =
                (TrainingEventMessage<EvacuationRouteBlockedEventData>) payloadCaptor.getValue();
        assertThat(message.eventType()).isEqualTo(TrainingEventType.EVACUATION_ROUTE_BLOCKED);
        assertThat(message.sessionId()).isEqualTo(sessionId);
        assertThat(message.data().floorId()).isEqualTo(floorId);
        assertThat(message.data().blockedAt()).isEqualTo(blockedAt);
        assertThat(message.data().blockedAfterSec()).isEqualTo(95);
    }

    @Test
    @DisplayName("트랜잭션 동기화가 활성화되어 있지 않으면 즉시 발행한다")
    void publishesImmediatelyWithoutActiveTransaction() {
        TrainingSession session = mockSession(TrainingStatus.RUNNING);

        publisher.publishTrainingStatusUpdatedAfterCommit(session);

        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("트랜잭션 커밋 전에는 발행하지 않고, 커밋 이후에만 발행한다")
    void publishesOnlyAfterCommit() {
        TrainingSession session = mockSession(TrainingStatus.RUNNING);

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishTrainingStatusUpdatedAfterCommit(session);

            // 커밋 전에는 아직 발행되지 않아야 한다.
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));

            // 등록된 동기화 콜백을 커밋 시점처럼 수동으로 실행한다.
            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션이 롤백되어 afterCommit이 호출되지 않으면 메시지도 발행되지 않는다")
    void doesNotPublishWhenTransactionRollsBack() {
        TrainingSession session = mockSession(TrainingStatus.FAILED);

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishTrainingStatusUpdatedAfterCommit(session);

            // afterCommit()을 호출하지 않고 그대로 동기화를 정리한다 (롤백 시나리오 = afterCommit 미호출).
            TransactionSynchronizationManager.clearSynchronization();

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("올바른 층 topic으로, eventType/floorId/occurredAt/data가 누락되지 않은 유도등 메시지를 발행한다")
    void publishesIoTLightMessageWithAllRequiredFieldsToCorrectDestination() {
        IoTLight light = mockLight();
        UUID floorId = light.getCustomNode().getFloor().getId();

        publisher.publishIoTLightStatusUpdated(light, IoTLightDirection.LEFT);

        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue()).isEqualTo("/topic/floors/" + floorId + "/lights");

        IoTLightEventMessage<IoTLightStatusEventData> message =
                (IoTLightEventMessage<IoTLightStatusEventData>) payloadCaptor.getValue();

        assertThat(message.eventType()).isEqualTo(TrainingEventType.IOT_LIGHT_STATUS_UPDATED);
        assertThat(message.floorId()).isEqualTo(floorId);
        assertThat(message.occurredAt()).isNotNull();
        assertThat(message.data().lightId()).isEqualTo(light.getId());
        assertThat(message.data().direction()).isEqualTo(IoTLightDirection.LEFT);
    }

    @Test
    @DisplayName("유도등 이벤트도 트랜잭션 커밋 이후에만 발행한다")
    void publishesIoTLightEventOnlyAfterCommit() {
        IoTLight light = mockLight();

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.publishIoTLightStatusUpdatedAfterCommit(light, IoTLightDirection.RIGHT);

            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));

            for (TransactionSynchronization synchronization :
                    TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private IoTLight mockLight() {
        Floor floor = mock(Floor.class);
        lenient().when(floor.getId()).thenReturn(UUID.randomUUID());
        MapNode customNode = MapNode.createCustom(floor, "LIGHT_001", "복도 유도등", 0.3, 0.4);
        IoTLight light = IoTLight.create("LIGHT_001", "복도 유도등", customNode);
        org.springframework.test.util.ReflectionTestUtils.setField(light, "id", UUID.randomUUID());
        return light;
    }

    // 테스트별로 publish가 실제로 호출되지 않아 일부 스텁이 쓰이지 않을 수 있으므로 lenient로 등록한다.
    private TrainingSession mockSession(TrainingStatus status) {
        TrainingSession session = mock(TrainingSession.class);
        lenient().when(session.getId()).thenReturn(UUID.randomUUID());
        lenient().when(session.getStatus()).thenReturn(status);
        lenient().when(session.getStartedAt()).thenReturn(Instant.now());
        lenient().when(session.getEndedAt()).thenReturn(Instant.now());
        return session;
    }
}