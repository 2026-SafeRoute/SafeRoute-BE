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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.congestion.dto.request.ReportObservationRequest;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventType;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import com.saferoute.domain.telemetry.dynamo.repository.GeneralMonitoringEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.telemetry.dynamo.repository.LatestMonitoringCaptureRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationCountRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.CongestionErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.service.S3Service;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CongestionObservationServiceTest {

    @InjectMocks
    private CongestionObservationService service;

    @Mock
    private ObservationRepository observationRepository;

    @Mock
    private ObservationCountRepository observationCountRepository;

    @Mock
    private GeneralMonitoringEventRepository generalMonitoringEventRepository;

    @Mock
    private LatestMonitoringCaptureRepository latestMonitoringCaptureRepository;

    @Mock
    private CurrentCctvStateRepository currentCctvStateRepository;

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
    private RouteDeviationService routeDeviationService;

    @Mock
    private TrainingEventPublisher trainingEventPublisher;

    @Mock
    private S3Service s3Service;

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
        // 기본은 GridCell 4개(면적 4.0m2)로, avgHeadcount=5 -> density=1.25 (NORMAL)
        org.mockito.Mockito.lenient().when(cctvGridCellRepository.countByCctv_Id(cctvId)).thenReturn(4);
        // 실제 리포지토리는 항상 non-null IdempotentSaveResult를 돌려주므로, 그 전제로 동작하는
        // tryCreateAiAnalysisStartedEvent()의 .created() 체크가 목에서도 NPE 없이 테스트되게 한다.
        org.mockito.Mockito.lenient().when(generalMonitoringEventRepository.saveIfAbsent(any()))
                .thenAnswer(invocation -> IdempotentSaveResult.created(invocation.getArgument(0)));
        // 기본은 Pi가 S3 업로드를 끝낸 상태로 본다 - 객체가 없는 경우는 개별 테스트에서 덮어쓴다.
        org.mockito.Mockito.lenient().when(s3Service.objectExists(anyString())).thenReturn(true);
    }

    private ReportObservationRequest request(double avgHeadcount) {
        return request(avgHeadcount, null);
    }

    private ReportObservationRequest request(double avgHeadcount, String monitoringImageKey) {
        return request(avgHeadcount, 7, monitoringImageKey);
    }

    private ReportObservationRequest request(
            double avgHeadcount,
            Integer frameHeadcount,
            String monitoringImageKey
    ) {
        return new ReportObservationRequest(
                UUID.randomUUID(), sessionId, "CCTV_001", avgHeadcount, 8, frameHeadcount, 25,
                1_000L, 2_000L, 2_000L, 1L, monitoringImageKey
        );
    }

    private void givenProcessingClaimed() {
        given(observationRepository.claimProcessing(anyString(), anyString(), anyLong(), anyLong()))
                .willReturn(true);
        given(observationRepository.completeProcessing(anyString(), anyString())).willReturn(true);
        given(observationRepository.saveIfAbsent(any())).willAnswer(invocation ->
                IdempotentSaveResult.created(invocation.getArgument(0, ObservationItem.class)));
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

        List<MapEdgeGridCell> edgeMappings = java.util.Arrays.stream(edges)
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
    void reportObservation_throwsWhenNoRunningSession() {
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reportObservation(cctv, request(5.0)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", TrainingErrorCode.RUNNING_TRAINING_SESSION_NOT_FOUND);

        verify(observationRepository, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("감시 면적을 계산할 수 없으면 전용 에러를 반환한다")
    void reportObservation_throwsWhenMonitoredAreaUnavailable() {
        TrainingSession session = mock(TrainingSession.class);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(cctvGridCellRepository.countByCctv_Id(cctvId)).willReturn(0);

        assertThatThrownBy(() -> service.reportObservation(cctv, request(5.0)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", CongestionErrorCode.MONITORED_AREA_NOT_AVAILABLE);

        verify(observationRepository, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("BE가 구간 평균과 이미지 프레임의 밀집도 및 단계를 각각 계산해서 저장한다")
    void reportObservation_computesDensityAndLevelItself() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        // avgHeadcount=9, 면적=4.0 -> density=2.25 -> CAUTION (기본 임계값: CAUTION>=2.0, CROWDED>=3.0)
        service.reportObservation(cctv, request(9.0));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                item.getDensity().equals(2.25)
                        && item.getCongestionLevel() == CongestionLevel.CAUTION
                        && item.getFrameHeadcount().equals(7)
                        && item.getFrameDensity().equals(1.75)
                        && item.getFrameCongestionLevel() == CongestionLevel.NORMAL));
        verify(currentCctvStateRepository).updateIfLatest(argThat(state ->
                state.getDensity().equals(2.25)
                        && state.getCongestionLevel() == CongestionLevel.CAUTION));
    }

    @Test
    @DisplayName("구버전 요청의 frameHeadcount가 없으면 프레임 지표도 null로 저장한다")
    void reportObservation_preservesNullFrameMetricsForLegacyRequest() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(9.0, null, null));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                item.getFrameHeadcount() == null
                        && item.getFrameDensity() == null
                        && item.getFrameCongestionLevel() == null));
    }

    @Test
    @DisplayName("NORMAL/CAUTION 수준이면 재탐색을 트리거하지 않는다")
    void reportObservation_doesNotTriggerRecalculationForLowLevel() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges(edge(UUID.randomUUID()));

        // avgHeadcount=5 -> density=1.25 -> NORMAL
        service.reportObservation(cctv, request(5.0));

        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
        // 이미지 없는 관측값은 카메라 화면의 최신 캡처 포인터를 건드리지 않는다(마지막 정상 이미지가 지워지지 않게).
        verify(latestMonitoringCaptureRepository, never()).updateIfLatest(any());
        verify(currentCctvStateRepository).updateIfLatest(any());
    }

    @Test
    @DisplayName("관측값·현재 상태·캡처 포인터에 서버 수신 시각(receivedAt)을 함께 저장한다")
    void reportObservation_storesServerReceivedAt() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        String key = "training/" + sessionId + "/monitoring/CCTV_001/2000.jpg";
        long before = System.currentTimeMillis();

        service.reportObservation(cctv, request(5.0, key));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                item.getReceivedAt() != null && item.getReceivedAt() >= before));
        verify(currentCctvStateRepository).updateIfLatest(argThat(state ->
                state.getLastReceivedAt() != null && state.getLastReceivedAt() >= before
                        && state.getLastDetectedAt().equals(2_000L)));
        verify(latestMonitoringCaptureRepository).updateIfLatest(argThat(capture ->
                capture.getReceivedAt() != null && capture.getReceivedAt() >= before
                        && key.equals(capture.getMonitoringImageKey())));
    }

    @Test
    @DisplayName("영향받는 Edge가 하나면 그 Edge 하나만 담아 한 번 발행한다")
    void reportObservation_singleAffectedEdge_publishesOnceWithThatEdge() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        UUID edgeAId = UUID.randomUUID();
        givenAffectedEdges(edge(edgeAId));

        service.reportObservation(cctv, request(5.0));

        verify(trainingEventPublisher, times(1)).publishCongestionUpdated(
                eq(sessionId), eq(List.of(edgeAId)), any());
    }

    @Test
    @DisplayName("CCTV가 여러 Edge를 감시하면 영향 Edge를 묶어 재탐색을 한 번만 트리거한다")
    void reportObservation_triggersOneRecalculationForAffectedEdges() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        UUID edgeAId = UUID.randomUUID();
        UUID edgeBId = UUID.randomUUID();
        MapEdge edgeA = edge(edgeAId);
        MapEdge edgeB = edge(edgeBId);
        givenAffectedEdges(edgeA, edgeB);
        List<UUID> expectedEdgeIds = List.of(edgeAId, edgeBId);

        // avgHeadcount=13 -> density=3.25 -> CROWDED
        service.reportObservation(cctv, request(13.0));

        verify(routeRecalculationService).triggerAsync(eq(session), eq(expectedEdgeIds),
                eq(CongestionLevel.CROWDED),
                eq(RecalculationTriggerType.LEVEL_UP), eq("CCTV_001"), anyDouble(), anyLong());
        verify(trainingEventPublisher, times(1)).publishCongestionUpdated(
                eq(sessionId), eq(expectedEdgeIds), any());
    }

    @Test
    @DisplayName("같은 Edge가 여러 GridCell 매핑을 통해 중복으로 조회돼도 발행 payload에는 한 번만 담는다")
    void reportObservation_dedupesAffectedEdgeIds() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        UUID edgeAId = UUID.randomUUID();
        MapEdge edgeA = edge(edgeAId);
        givenAffectedEdges(edgeA, edgeA);

        service.reportObservation(cctv, request(5.0));

        verify(trainingEventPublisher).publishCongestionUpdated(
                eq(sessionId), eq(List.of(edgeAId)), any());
    }

    @Test
    @DisplayName("매핑된 Edge가 없으면 빈 edgeIds로 한 번만 발행하고 재탐색은 트리거하지 않는다")
    void reportObservation_noMappedEdges_publishesOnceWithEmptyEdgeIds() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        given(cctvGridCellRepository.findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctvId))
                .willReturn(List.of());

        // avgHeadcount=13 -> density=3.25 -> CROWDED, 그래도 Edge가 없으니 트리거는 없음
        service.reportObservation(cctv, request(13.0));

        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
        verify(trainingEventPublisher).publishCongestionUpdated(eq(sessionId), eq(List.of()), any());
    }

    @Test
    @DisplayName("중복 eventId이면 발행과 재탐색을 다시 수행하지 않는다")
    void reportObservation_doesNotRepeatSideEffectsForDuplicateEvent() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(observationRepository.saveIfAbsent(any())).willAnswer(invocation ->
                IdempotentSaveResult.existing(invocation.getArgument(0, ObservationItem.class)));

        var result = service.reportObservation(cctv, request(5.0));

        assertThat(result.created()).isFalse();
        verify(trainingEventPublisher, never()).publishCongestionUpdated(any(), any(), any());
        verify(routeRecalculationService, never())
                .triggerAsync(any(), any(), any(), any(), any(), anyDouble(), anyLong());
        verify(routeRecalculationService, never()).trigger(any(), any(), any(), any(), any(), anyDouble());
        verify(observationCountRepository, never()).increment(anyString(), anyString());
    }

    @Test
    @DisplayName("동일한 eventId가 다른 세션·CCTV를 가리키면 CONGESTION002를 반환한다")
    void reportObservation_rejectsMismatchedEventIdentity() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(observationRepository.saveIfAbsent(any())).willAnswer(invocation -> {
            ObservationItem existing = invocation.getArgument(0, ObservationItem.class);
            existing.setCctvCode("CCTV_999");
            return IdempotentSaveResult.existing(existing);
        });

        assertThatThrownBy(() -> service.reportObservation(cctv, request(5.0)))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", CongestionErrorCode.EVENT_IDENTITY_MISMATCH);

        verify(observationRepository, never()).claimProcessing(anyString(), anyString(), anyLong(), anyLong());
        verify(generalMonitoringEventRepository, never()).saveIfAbsent(any());
        verify(routeDeviationService, never()).evaluateObservationAsync(any(), any());
    }

    @Test
    @DisplayName("Observation 저장 자체가 실패하면 AI_ANALYSIS_STARTED 이벤트 생성을 시도하지 않는다")
    void reportObservation_doesNotTryToCreateAiAnalysisStartedEventWhenObservationSaveFails() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        given(observationRepository.saveIfAbsent(any())).willThrow(new RuntimeException("dynamo unavailable"));

        assertThatThrownBy(() -> service.reportObservation(cctv, request(5.0)))
                .isInstanceOf(RuntimeException.class);

        verify(generalMonitoringEventRepository, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("monitoringImageKey가 canonical 형식(training/{sessionId}/monitoring/{cctvCode}/{capturedAt}.jpg)이면 저장된다")
    void reportObservation_acceptsValidMonitoringImageKey() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        String key = "training/" + sessionId + "/monitoring/CCTV_001/2000.jpg";

        service.reportObservation(cctv, request(5.0, key));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                key.equals(item.getMonitoringImageKey())));
        verify(s3Service).objectExists(key);
    }

    @Test
    @DisplayName("빈 monitoringImageKey는 검증을 통과시키되 null로 정규화해서 저장하고 캡처 포인터는 건드리지 않는다")
    void reportObservation_normalizesBlankMonitoringImageKeyToNull() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0, ""));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                item.getMonitoringImageKey() == null));
        verify(latestMonitoringCaptureRepository, never()).updateIfLatest(any());
        verify(s3Service, never()).objectExists(anyString());
    }

    @Test
    @DisplayName("S3에 모니터링 이미지 객체가 없으면 관측값은 저장하되 이미지는 없음(null)으로 처리한다")
    void reportObservation_dropsMonitoringImageWhenObjectMissingInS3() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        String key = "training/" + sessionId + "/monitoring/CCTV_001/2000.jpg";
        given(s3Service.objectExists(key)).willReturn(false);

        service.reportObservation(cctv, request(5.0, key));

        verify(observationRepository).saveIfAbsent(argThat(item -> item.getMonitoringImageKey() == null));
        verify(latestMonitoringCaptureRepository, never()).updateIfLatest(any());
    }

    @Test
    @DisplayName("S3 존재 확인 자체가 실패하면(S3 장애) 이미지 key를 그대로 믿고 저장한다")
    void reportObservation_keepsMonitoringImageWhenS3CheckFails() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        String key = "training/" + sessionId + "/monitoring/CCTV_001/2000.jpg";
        given(s3Service.objectExists(key)).willThrow(new IllegalStateException("s3 unavailable"));

        service.reportObservation(cctv, request(5.0, key));

        verify(observationRepository).saveIfAbsent(argThat(item -> key.equals(item.getMonitoringImageKey())));
        verify(latestMonitoringCaptureRepository).updateIfLatest(argThat(capture ->
                key.equals(capture.getMonitoringImageKey())));
    }

    // 이미지 key가 잘못돼도 관측값(인원/밀도)까지 버리지 않는다 - 이미지만 없음(null)으로 저장하고
    // S3 존재 확인도 하지 않는다(다른 세션/CCTV 객체를 들여다보지 않게).
    private void assertObservationSavedWithoutImage(String monitoringImageKey) {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0, monitoringImageKey));

        verify(observationRepository).saveIfAbsent(argThat(item ->
                item.getMonitoringImageKey() == null && item.getAvgHeadcount().equals(5.0)));
        verify(latestMonitoringCaptureRepository, never()).updateIfLatest(any());
        verify(s3Service, never()).objectExists(anyString());
    }

    @Test
    @DisplayName("monitoringImageKey의 capturedAt 세그먼트가 canonical decimal이 아니면 이미지 없이 관측값만 저장한다")
    void reportObservation_nonCanonicalCapturedAtSegment_savesWithoutImage() {
        assertObservationSavedWithoutImage("training/" + sessionId + "/monitoring/CCTV_001/+2000.jpg");
    }

    @Test
    @DisplayName("monitoringImageKey의 capturedAt 세그먼트에 선행 0이 있으면 이미지 없이 관측값만 저장한다")
    void reportObservation_leadingZeroCapturedAtSegment_savesWithoutImage() {
        assertObservationSavedWithoutImage("training/" + sessionId + "/monitoring/CCTV_001/02000.jpg");
    }

    @Test
    @DisplayName("monitoringImageKey 형식이 canonical 경로가 아니면 이미지 없이 관측값만 저장한다")
    void reportObservation_malformedMonitoringImageKey_savesWithoutImage() {
        assertObservationSavedWithoutImage("training/" + sessionId + "/monitoring/CCTV_001/2000.png");
    }

    @Test
    @DisplayName("monitoringImageKey의 세션 ID가 요청 세션과 다르면 이미지 없이 관측값만 저장한다")
    void reportObservation_differentSessionInKey_savesWithoutImage() {
        assertObservationSavedWithoutImage(
                "training/" + UUID.randomUUID() + "/monitoring/CCTV_001/2000.jpg");
    }

    @Test
    @DisplayName("monitoringImageKey의 CCTV 코드가 요청 CCTV와 다르면 이미지 없이 관측값만 저장한다")
    void reportObservation_differentCctvInKey_savesWithoutImage() {
        assertObservationSavedWithoutImage("training/" + sessionId + "/monitoring/CCTV_999/2000.jpg");
    }

    @Test
    @DisplayName("monitoringImageKey의 capturedAt이 요청 capturedAt과 다르면 이미지 없이 관측값만 저장한다")
    void reportObservation_differentCapturedAtInKey_savesWithoutImage() {
        assertObservationSavedWithoutImage("training/" + sessionId + "/monitoring/CCTV_001/9999.jpg");
    }

    @Test
    @DisplayName("유효한 Observation을 저장하면 경로 이탈 판정을 비동기로 시도한다")
    void reportObservation_triesToEvaluateRouteDeviation() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0));

        verify(routeDeviationService).evaluateObservationAsync(eq(buildingId), argThat(item ->
                item.getTrainingSessionId().equals(sessionId.toString())
                        && item.getCctvCode().equals("CCTV_001")
        ));
    }

    @Test
    @DisplayName("유효한 Observation을 저장하면 AI_ANALYSIS_STARTED 이벤트 생성을 시도한다")
    void reportObservation_triesToCreateAiAnalysisStartedEvent() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0));

        verify(generalMonitoringEventRepository).saveIfAbsent(argThat(item ->
                item.getTrainingSessionId().equals(sessionId.toString())
                        && item.getCctvCode().equals("CCTV_001")
                        && item.getEventType() == GeneralMonitoringEventType.AI_ANALYSIS_STARTED
                        && item.getOccurredAt() == 2_000L
                        && item.getCongestionLevel() == null
        ));
        verify(trainingEventPublisher).publishGeneralMonitoringEventReceived(eq(sessionId), any());
    }

    @Test
    @DisplayName("같은 세션+CCTV로 여러 번 호출해도 AI_ANALYSIS_STARTED 이벤트 eventId는 항상 같다 (결정적 생성)")
    void reportObservation_generatesDeterministicEventIdForAiAnalysisStartedEvent() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0));
        service.reportObservation(cctv, request(7.0));

        org.mockito.ArgumentCaptor<GeneralMonitoringEventItem> captor =
                org.mockito.ArgumentCaptor.forClass(GeneralMonitoringEventItem.class);
        verify(generalMonitoringEventRepository, times(2)).saveIfAbsent(captor.capture());
        List<GeneralMonitoringEventItem> saved = captor.getAllValues();
        assertThat(saved.get(0).getEventId()).isEqualTo(saved.get(1).getEventId());
    }

    @Test
    @DisplayName("AI_ANALYSIS_STARTED 이벤트 저장이 실패해도 Observation 저장 자체는 실패하지 않는다")
    void reportObservation_swallowsExceptionFromGeneralMonitoringEventRepository() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        given(generalMonitoringEventRepository.saveIfAbsent(any()))
                .willThrow(new RuntimeException("dynamo unavailable"));

        var result = service.reportObservation(cctv, request(5.0));

        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("Observation이 새로 저장되면 세션+CCTV별 카운터를 증가시킨다")
    void reportObservation_incrementsObservationCountWhenNewlyCreated() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();

        service.reportObservation(cctv, request(5.0));

        verify(observationCountRepository).increment(sessionId.toString(), "CCTV_001");
    }

    @Test
    @DisplayName("카운터 증가가 실패해도 Observation 저장 자체는 실패하지 않는다")
    void reportObservation_swallowsExceptionFromObservationCountRepository() {
        TrainingSession session = mock(TrainingSession.class);
        given(session.getId()).willReturn(sessionId);
        given(trainingSessionRepository.findByIdAndStatusAndScenario_Building_Id(
                sessionId, TrainingStatus.RUNNING, buildingId)).willReturn(Optional.of(session));
        givenProcessingClaimed();
        givenAffectedEdges();
        org.mockito.Mockito.doThrow(new RuntimeException("dynamo unavailable"))
                .when(observationCountRepository).increment(anyString(), anyString());

        var result = service.reportObservation(cctv, request(5.0));

        assertThat(result.created()).isTrue();
    }
}
