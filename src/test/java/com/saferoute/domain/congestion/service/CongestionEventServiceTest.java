package com.saferoute.domain.congestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.congestion.dto.request.ReportCongestionEventRequest;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.EventProcessingStatus;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.CongestionErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class CongestionEventServiceTest {

    @InjectMocks
    private CongestionEventService service;

    @Mock
    private CongestionEventRepository congestionEventRepository;

    @Mock
    private CurrentCongestionEventRepository currentCongestionEventRepository;

    @Mock
    private TrainingSessionRepository trainingSessionRepository;

    @Mock
    private CctvGridCellRepository cctvGridCellRepository;

    @Mock
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;

    @Mock
    private CongestionConfigService congestionConfigService;

    @Mock
    private RouteRecalculationService routeRecalculationService;

    @Mock
    private TrainingEventPublisher trainingEventPublisher;

    private final UUID cctvId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private Cctv cctv;

    @BeforeEach
    void setUp() {
        Building building = mock(Building.class);
        org.mockito.Mockito.lenient().when(building.getId()).thenReturn(buildingId);
        Floor floor = mock(Floor.class);
        org.mockito.Mockito.lenient().when(floor.getBuilding()).thenReturn(building);
        org.mockito.Mockito.lenient().when(floor.getGridCellSizeMeter()).thenReturn(1.0);
        MapNode node = mock(MapNode.class);
        org.mockito.Mockito.lenient().when(node.getFloor()).thenReturn(floor);
        cctv = mock(Cctv.class);
        org.mockito.Mockito.lenient().when(cctv.getId()).thenReturn(cctvId);
        org.mockito.Mockito.lenient().when(cctv.getCode()).thenReturn("CCTV_001");
        org.mockito.Mockito.lenient().when(cctv.getCustomNode()).thenReturn(node);

        org.mockito.Mockito.lenient().when(congestionConfigService.getConfig())
                .thenReturn(CongestionConfig.createDefault());
        // 기본은 GridCell 4개(면적 4.0m2)로, headcount=5 -> density=1.25 (NORMAL)
        org.mockito.Mockito.lenient().when(cctvGridCellRepository.countByCctv_Id(cctvId)).thenReturn(4);
    }

    private ReportCongestionEventRequest request(int headcount) {
        return new ReportCongestionEventRequest(
                UUID.randomUUID(), sessionId, "CCTV_001", CongestionEventType.CONGESTION_STARTED,
                2_000L, headcount, 4.5, CongestionLevel.CROWDED, 1L
        );
    }

    private void givenSaveCreatesItem() {
        given(congestionEventRepository.saveReceivedIfAbsent(any())).willAnswer(invocation ->
                IdempotentSaveResult.created(invocation.getArgument(0, CongestionEventItem.class)));
    }

    private void givenProcessingClaimed() {
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.RECEIVED), eq(EventProcessingStatus.PROCESSING)))
                .willReturn(true);
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.PROCESSED)))
                .willReturn(true);
    }

    private MapEdge edge(UUID id) {
        MapEdge edge = mock(MapEdge.class);
        org.mockito.Mockito.lenient().when(edge.getId()).thenReturn(id);
        return edge;
    }

    private void givenAffectedEdges(MapEdge... edges) {
        UUID gridCellId = UUID.randomUUID();
        FloorGridCell gridCell = mock(FloorGridCell.class);
        given(gridCell.getId()).willReturn(gridCellId);
        CctvGridCell mapping = mock(CctvGridCell.class);
        given(mapping.getGridCell()).willReturn(gridCell);
        given(cctvGridCellRepository.findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctvId))
                .willReturn(List.of(mapping));

        List<MapEdgeGridCell> edgeMappings = Arrays.stream(edges)
                .map(e -> {
                    MapEdgeGridCell edgeGridCell = mock(MapEdgeGridCell.class);
                    given(edgeGridCell.getMapEdge()).willReturn(e);
                    return edgeGridCell;
                })
                .toList();
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(gridCellId))).willReturn(edgeMappings);
    }

    @Test
    @DisplayName("대상 건물에 RUNNING 세션이 없으면 전용 에러를 반환하고 아무것도 저장하지 않는다")
    void reportCongestionEvent_throwsWhenNoRunningSession() {
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportCongestionEvent(cctv, request(9)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", TrainingErrorCode.RUNNING_TRAINING_SESSION_NOT_FOUND);

        verify(congestionEventRepository, never()).saveReceivedIfAbsent(any());
    }

    @Test
    @DisplayName("감시 면적을 계산할 수 없으면 전용 에러를 반환한다")
    void reportCongestionEvent_throwsWhenMonitoredAreaUnavailable() {
        TrainingSession session = mock(TrainingSession.class);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(cctvGridCellRepository.countByCctv_Id(cctvId)).willReturn(0);

        assertThatThrownBy(() -> service.reportCongestionEvent(cctv, request(9)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", CongestionErrorCode.MONITORED_AREA_NOT_AVAILABLE);

        verify(congestionEventRepository, never()).saveReceivedIfAbsent(any());
    }

    @Test
    @DisplayName("BE가 직접 density와 congestionLevel을 계산해서 저장한다 (Pi의 local 값을 신뢰하지 않음)")
    void reportCongestionEvent_computesDensityAndLevelItself() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        givenAffectedEdges();

        // headcount=9, 면적=4.0 -> density=2.25 -> CAUTION (기본 임계값: CAUTION>=2.0, CROWDED>=3.0)
        service.reportCongestionEvent(cctv, request(9));

        verify(congestionEventRepository).saveReceivedIfAbsent(argThat(item ->
                item.getDensity().equals(2.25) && item.getCongestionLevel() == CongestionLevel.CAUTION));
    }

    @Test
    @DisplayName("NORMAL/CAUTION 수준이면 재탐색을 트리거하지 않는다")
    void reportCongestionEvent_doesNotTriggerRecalculationForLowLevel() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        givenAffectedEdges(edge(UUID.randomUUID()));

        // headcount=5 -> density=1.25 -> NORMAL
        service.reportCongestionEvent(cctv, request(5));

        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
    }

    @Test
    @DisplayName("영향받는 Edge가 하나면 그 Edge 하나만 담아 한 번 발행한다")
    void reportCongestionEvent_singleAffectedEdge_publishesOnceWithThatEdge() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        UUID edgeAId = UUID.randomUUID();
        givenAffectedEdges(edge(edgeAId));

        service.reportCongestionEvent(cctv, request(13));

        verify(trainingEventPublisher, times(1)).publishCongestionEventReceived(
                eq(sessionId), eq(List.of(edgeAId)), any());
    }

    @Test
    @DisplayName("재탐색을 트리거하기 전에 CCTV별 최신 즉시 이벤트를 먼저 기록한다")
    void reportCongestionEvent_recordsCurrentEventBeforeTriggering() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        givenAffectedEdges(edge(UUID.randomUUID()));

        // headcount=13 -> density=3.25 -> CROWDED
        service.reportCongestionEvent(cctv, request(13));

        InOrder order = inOrder(currentCongestionEventRepository, routeRecalculationService);
        order.verify(currentCongestionEventRepository).updateIfLatest(argThat(item ->
                item.getCctvCode().equals("CCTV_001")
                        && item.getEventType() == CongestionEventType.CONGESTION_STARTED
                        && item.getCongestionLevel() == CongestionLevel.CROWDED
                        && item.getDetectedAt() == 2_000L));
        order.verify(routeRecalculationService).triggerAsync(
                any(), any(), any(), any(), any(), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("CCTV가 여러 Edge를 감시하면 영향 Edge를 묶어 재탐색을 한 번만 트리거한다")
    void reportCongestionEvent_triggersOneRecalculationForAffectedEdges() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        UUID edgeAId = UUID.randomUUID();
        UUID edgeBId = UUID.randomUUID();
        MapEdge edgeA = edge(edgeAId);
        MapEdge edgeB = edge(edgeBId);
        givenAffectedEdges(edgeA, edgeB);
        List<UUID> expectedEdgeIds = List.of(edgeAId, edgeBId);

        // headcount=13 -> density=3.25 -> CROWDED
        service.reportCongestionEvent(cctv, request(13));

        verify(routeRecalculationService).triggerAsync(eq(session), eq(expectedEdgeIds),
                eq(CongestionLevel.CROWDED),
                eq(RecalculationTriggerType.STARTED), eq("CCTV_001"), anyDouble(), anyLong());
        verify(trainingEventPublisher, times(1))
                .publishCongestionEventReceived(eq(sessionId), eq(expectedEdgeIds), any());
    }

    @Test
    @DisplayName("매핑된 Edge가 없으면 빈 affectedEdgeIds로 한 번만 발행하고 재탐색은 트리거하지 않는다")
    void reportCongestionEvent_noMappedEdges_publishesOnceWithEmptyEdgeIds() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        given(cctvGridCellRepository.findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctvId))
                .willReturn(List.of());

        // headcount=13 -> density=3.25 -> CROWDED, 그래도 Edge가 없으니 트리거는 없음
        service.reportCongestionEvent(cctv, request(13));

        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
        verify(trainingEventPublisher).publishCongestionEventReceived(eq(sessionId), eq(List.of()), any());
    }

    @Test
    @DisplayName("중복 eventId가 PROCESSED이면 다시 처리하지 않고 그대로 반환한다")
    void reportCongestionEvent_doesNotReprocessAlreadyProcessedDuplicate() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(congestionEventRepository.saveReceivedIfAbsent(any())).willAnswer(invocation -> {
            CongestionEventItem existing = invocation.getArgument(0, CongestionEventItem.class);
            existing.setEventStatus(EventProcessingStatus.PROCESSED);
            return IdempotentSaveResult.existing(existing);
        });

        var result = service.reportCongestionEvent(cctv, request(13));

        assertThat(result.created()).isFalse();
        verify(trainingEventPublisher, never()).publishCongestionEventReceived(any(), any(), any());
        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
        verify(congestionEventRepository, never()).updateEventStatus(anyString(), any(), any());
    }

    @Test
    @DisplayName("동일한 eventId가 다른 세션·CCTV를 가리키면 CONGESTION002를 반환한다")
    void reportCongestionEvent_rejectsMismatchedEventIdentity() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(congestionEventRepository.saveReceivedIfAbsent(any())).willAnswer(invocation -> {
            CongestionEventItem existing = invocation.getArgument(0, CongestionEventItem.class);
            existing.setCctvCode("CCTV_999");
            return IdempotentSaveResult.existing(existing);
        });

        assertThatThrownBy(() -> service.reportCongestionEvent(cctv, request(5)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", CongestionErrorCode.EVENT_IDENTITY_MISMATCH);

        verify(congestionEventRepository, never()).updateEventStatus(anyString(), any(), any());
    }

    @Test
    @DisplayName("중복 eventId가 RECEIVED이면 후속 처리를 재개한다")
    void reportCongestionEvent_resumesReceivedDuplicate() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(congestionEventRepository.saveReceivedIfAbsent(any())).willAnswer(invocation ->
                IdempotentSaveResult.existing(invocation.getArgument(0, CongestionEventItem.class)));
        givenProcessingClaimed();
        givenAffectedEdges(edge(UUID.randomUUID()));

        var result = service.reportCongestionEvent(cctv, request(13));

        assertThat(result.created()).isFalse();
        verify(trainingEventPublisher).publishCongestionEventReceived(any(), any(), any());
        verify(routeRecalculationService).triggerAsync(eq(session), any(), eq(CongestionLevel.CROWDED),
                eq(RecalculationTriggerType.STARTED), eq("CCTV_001"), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("중복 eventId가 PROCESSING이면 재처리하지 않고 그대로 반환한다")
    void reportCongestionEvent_doesNotReprocessInFlightDuplicate() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(congestionEventRepository.saveReceivedIfAbsent(any())).willAnswer(invocation -> {
            CongestionEventItem existing = invocation.getArgument(0, CongestionEventItem.class);
            existing.setEventStatus(EventProcessingStatus.PROCESSING);
            return IdempotentSaveResult.existing(existing);
        });

        var result = service.reportCongestionEvent(cctv, request(13));

        assertThat(result.created()).isFalse();
        verify(trainingEventPublisher, never()).publishCongestionEventReceived(any(), any(), any());
        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
    }

    @Test
    @DisplayName("CONGESTION_ENDED는 레벨이 NORMAL이어도 복구 후보 탐색을 동기로 트리거한다")
    void reportCongestionEvent_triggersSynchronousRecalculationForEndedEvent() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();
        givenAffectedEdges(edge(UUID.randomUUID()));

        // headcount=5 -> density=1.25 -> NORMAL (requiresRouteRecalculation()은 false지만,
        // triggerType=ENDED라서 재탐색 조건의 OR 쪽으로 여전히 트리거돼야 한다)
        ReportCongestionEventRequest endedRequest = new ReportCongestionEventRequest(
                UUID.randomUUID(), sessionId, "CCTV_001", CongestionEventType.CONGESTION_ENDED,
                2_000L, 5, 1.25, CongestionLevel.NORMAL, 1L);

        service.reportCongestionEvent(cctv, endedRequest);

        verify(routeRecalculationService).trigger(eq(session), any(), eq(CongestionLevel.NORMAL),
                eq(RecalculationTriggerType.ENDED), eq("CCTV_001"), anyDouble());
        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("후속 처리 실패를 FAILED로 기록하고 공통 혼잡 에러를 반환한다")
    void reportCongestionEvent_marksFailedWhenSideEffectFails() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.RECEIVED), eq(EventProcessingStatus.PROCESSING)))
                .willReturn(true);
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.FAILED)))
                .willReturn(true);
        doThrow(new IllegalStateException("websocket unavailable"))
                .when(trainingEventPublisher).publishCongestionEventReceived(any(), any(), any());

        assertThatThrownBy(() -> service.reportCongestionEvent(cctv, request(13)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", CongestionErrorCode.EVENT_PROCESSING_FAILED);

        verify(congestionEventRepository).updateEventStatus(
                anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.FAILED));
    }

    @Test
    @DisplayName("후속 처리 성공 상태는 PostgreSQL 커밋 이후에 기록한다")
    void reportCongestionEvent_marksProcessedAfterCommit() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        givenProcessingClaimed();

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.reportCongestionEvent(cctv, request(5));

            verify(congestionEventRepository, never()).updateEventStatus(
                    anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.PROCESSED));
            verify(trainingEventPublisher, never())
                    .publishCongestionEventReceived(any(), any(), any());

            TransactionSynchronization synchronization = TransactionSynchronizationManager
                    .getSynchronizations().get(0);
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

            verify(congestionEventRepository).updateEventStatus(
                    anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.PROCESSED));
            verify(trainingEventPublisher).publishCongestionEventReceived(any(), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("PostgreSQL 트랜잭션이 롤백되면 처리 상태를 FAILED로 기록한다")
    void reportCongestionEvent_marksFailedAfterRollback() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenSaveCreatesItem();
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.RECEIVED), eq(EventProcessingStatus.PROCESSING)))
                .willReturn(true);
        given(congestionEventRepository.updateEventStatus(
                anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.FAILED)))
                .willReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.reportCongestionEvent(cctv, request(5));

            verify(trainingEventPublisher, never())
                    .publishCongestionEventReceived(any(), any(), any());
            TransactionSynchronization synchronization = TransactionSynchronizationManager
                    .getSynchronizations().get(0);
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(congestionEventRepository).updateEventStatus(
                    anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.FAILED));
            verify(congestionEventRepository, never()).updateEventStatus(
                    anyString(), eq(EventProcessingStatus.PROCESSING), eq(EventProcessingStatus.PROCESSED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
