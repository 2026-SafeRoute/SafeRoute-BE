package com.saferoute.domain.evacuation.recalculation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CurrentCongestionWeightProviderTest {

    private final UUID sessionId = UUID.randomUUID();
    private final UUID floorId = UUID.randomUUID();

    @InjectMocks
    private CurrentCongestionWeightProvider provider;

    @Mock
    private CurrentCctvStateRepository currentCctvStateRepository;

    @Mock
    private CctvJpaRepository cctvJpaRepository;

    @Mock
    private CctvGridCellRepository cctvGridCellRepository;

    @Mock
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;

    @Mock
    private CongestionConfigService congestionConfigService;

    @Mock
    private CurrentCongestionEventRepository currentCongestionEventRepository;

    // 기본 설정(stateStaleAfterSec = 15초)
    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(congestionConfigService.getConfig())
                .thenReturn(CongestionConfig.createDefault());
    }

    // 방금 관측된 상태. 마지막 관측이 오래된 CCTV는 경로 가중치에서 빠지므로(stale), 시각을 따로 지정하지
    // 않는 기존 테스트는 "지금 막 관측된" 상태여야 한다.
    private CurrentCctvStateItem state(String cctvCode, CongestionLevel level) {
        return state(cctvCode, level, Instant.now().toEpochMilli());
    }

    private CurrentCctvStateItem state(String cctvCode, CongestionLevel level, long lastDetectedAt) {
        return CurrentCctvStateItem.create(sessionId, cctvCode, 1.0, 1, 1.0, level, lastDetectedAt, 1L);
    }

    private CurrentCongestionEventItem event(String cctvCode, CongestionEventType type, CongestionLevel level,
            long detectedAt) {
        return CurrentCongestionEventItem.from(CongestionEventItem.received(UUID.randomUUID(), sessionId,
                cctvCode, type, detectedAt, 2, 3.1, level, 3.1, level, 1L, null));
    }

    private void latestEvents(CurrentCongestionEventItem... events) {
        given(currentCongestionEventRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(events));
    }

    private long secondsAgo(int seconds) {
        return Instant.now().minusSeconds(seconds).toEpochMilli();
    }

    private Cctv cctv(UUID id, String code) {
        Cctv cctv = mock(Cctv.class);
        given(cctv.getId()).willReturn(id);
        given(cctv.getCode()).willReturn(code);
        return cctv;
    }

    private FloorGridCell cell(UUID id) {
        FloorGridCell cell = mock(FloorGridCell.class);
        given(cell.getId()).willReturn(id);
        return cell;
    }

    private CctvGridCell watching(Cctv cctv, FloorGridCell cell) {
        CctvGridCell mapping = mock(CctvGridCell.class);
        given(mapping.getCctv()).willReturn(cctv);
        given(mapping.getGridCell()).willReturn(cell);
        return mapping;
    }

    private MapEdgeGridCell crossing(UUID edgeId, FloorGridCell cell) {
        MapEdge edge = mock(MapEdge.class);
        given(edge.getId()).willReturn(edgeId);
        MapEdgeGridCell mapping = mock(MapEdgeGridCell.class);
        given(mapping.getMapEdge()).willReturn(edge);
        given(mapping.getGridCell()).willReturn(cell);
        return mapping;
    }

    @Test
    @DisplayName("혼잡한 CCTV가 감시하는 엣지에만 레벨별 배율을 부여한다")
    void currentMultipliers_mapsCongestedCctvToItsEdges() {
        UUID crowdedCctvId = UUID.randomUUID();
        UUID cautionCctvId = UUID.randomUUID();
        UUID crowdedEdgeId = UUID.randomUUID();
        UUID cautionEdgeId = UUID.randomUUID();
        FloorGridCell crowdedCell = cell(UUID.randomUUID());
        FloorGridCell cautionCell = cell(UUID.randomUUID());
        Cctv crowdedCctv = cctv(crowdedCctvId, "CCTV-1");
        Cctv cautionCctv = cctv(cautionCctvId, "CCTV-2");
        Cctv normalCctv = mock(Cctv.class);
        given(normalCctv.getCode()).willReturn("CCTV-3");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-1", CongestionLevel.CROWDED),
                state("CCTV-2", CongestionLevel.CAUTION),
                state("CCTV-3", CongestionLevel.NORMAL)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(crowdedCctv, cautionCctv, normalCctv));
        List<CctvGridCell> cctvCells = List.of(watching(crowdedCctv, crowdedCell), watching(cautionCctv, cautionCell));
        List<MapEdgeGridCell> edgeCells =
                List.of(crossing(crowdedEdgeId, crowdedCell), crossing(cautionEdgeId, cautionCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        Map<UUID, Double> multipliers = provider.currentMultipliers(sessionId, floorId);

        assertThat(multipliers).containsOnly(
                Map.entry(crowdedEdgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER),
                Map.entry(cautionEdgeId, RouteRecalculationService.CAUTION_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("VERY_CROWDED도 제외가 아니라 큰 배율로만 반영한다")
    void currentMultipliers_veryCrowdedIsSoftPenalty() {
        UUID cctvId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cell = cell(UUID.randomUUID());
        Cctv cctv = cctv(cctvId, "CCTV-1");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.VERY_CROWDED)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(cctv));
        List<CctvGridCell> cctvCells = List.of(watching(cctv, cell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, CurrentCongestionWeightProvider.VERY_CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("여러 CCTV가 같은 엣지를 감시하면 가장 큰 배율을 쓴다")
    void currentMultipliers_sharedEdgeTakesMaxMultiplier() {
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cautionCell = cell(UUID.randomUUID());
        FloorGridCell crowdedCell = cell(UUID.randomUUID());
        Cctv cautionCctv = cctv(UUID.randomUUID(), "CCTV-1");
        Cctv crowdedCctv = cctv(UUID.randomUUID(), "CCTV-2");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-1", CongestionLevel.CAUTION), state("CCTV-2", CongestionLevel.CROWDED)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(cautionCctv, crowdedCctv));
        List<CctvGridCell> cctvCells = List.of(watching(cautionCctv, cautionCell), watching(crowdedCctv, crowdedCell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cautionCell), crossing(edgeId, crowdedCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("제외 지정한 CCTV의 혼잡은 집계에서 빼고 나머지 CCTV의 혼잡은 그대로 반영한다")
    void currentMultipliers_excludedCctvIsIgnoredOthersKept() {
        UUID keptEdgeId = UUID.randomUUID();
        FloorGridCell keptCell = cell(UUID.randomUUID());
        Cctv keptCctv = cctv(UUID.randomUUID(), "CCTV-KEPT");
        Cctv endedCctv = mock(Cctv.class);
        given(endedCctv.getCode()).willReturn("CCTV-ENDED");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-KEPT", CongestionLevel.CROWDED), state("CCTV-ENDED", CongestionLevel.VERY_CROWDED)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(keptCctv, endedCctv));
        List<CctvGridCell> cctvCells = List.of(watching(keptCctv, keptCell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(keptEdgeId, keptCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId, java.util.Set.of("CCTV-ENDED")))
                .containsOnly(Map.entry(keptEdgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("제외한 CCTV 말고는 혼잡한 곳이 없으면 이후 조회 없이 빈 맵을 반환한다")
    void currentMultipliers_onlyExcludedCctvCongested_returnsEmpty() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-ENDED", CongestionLevel.CROWDED)));

        assertThat(provider.currentMultipliers(sessionId, floorId, java.util.Set.of("CCTV-ENDED"))).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("혼잡한 CCTV가 없으면 이후 조회 없이 빈 맵을 반환한다")
    void currentMultipliers_noCongestion_returnsEmptyWithoutFurtherQueries() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.NORMAL)));

        assertThat(provider.currentMultipliers(sessionId, floorId)).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("혼잡한 CCTV가 다른 층에 있으면 이 층에는 영향이 없다")
    void currentMultipliers_congestedCctvOnOtherFloor_returnsEmpty() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-OTHER", CongestionLevel.CROWDED)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of());

        assertThat(provider.currentMultipliers(sessionId, floorId)).isEmpty();
        verifyNoInteractions(cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    // === 관측이 끊긴 CCTV(stale) ===

    @Test
    @DisplayName("마지막 관측이 stale 기준보다 오래된 CCTV의 혼잡은 무시하고 최근 관측된 CCTV만 반영한다")
    void currentMultipliers_staleCctvIsIgnored() {
        UUID freshEdgeId = UUID.randomUUID();
        FloorGridCell freshCell = cell(UUID.randomUUID());
        Cctv freshCctv = cctv(UUID.randomUUID(), "CCTV-FRESH");
        Cctv staleCctv = mock(Cctv.class);
        given(staleCctv.getCode()).willReturn("CCTV-STALE");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-FRESH", CongestionLevel.CROWDED),
                state("CCTV-STALE", CongestionLevel.VERY_CROWDED, secondsAgo(20))));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(freshCctv, staleCctv));
        List<CctvGridCell> cctvCells = List.of(watching(freshCctv, freshCell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(freshEdgeId, freshCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(freshEdgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("stale 기준(15초) 안에서 관측된 CCTV의 혼잡은 그대로 반영한다")
    void currentMultipliers_justWithinStaleWindow_isKept() {
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cell = cell(UUID.randomUUID());
        Cctv cctv = cctv(UUID.randomUUID(), "CCTV-1");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.CROWDED, secondsAgo(10))));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(cctv));
        List<CctvGridCell> cctvCells = List.of(watching(cctv, cell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("마지막 관측 시각을 알 수 없는(null) 상태는 무시한다")
    void currentMultipliers_nullLastDetectedAt_isIgnored() {
        CurrentCctvStateItem unknownTime = state("CCTV-1", CongestionLevel.VERY_CROWDED);
        ReflectionTestUtils.setField(unknownTime, "lastDetectedAt", null);
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(unknownTime));

        assertThat(provider.currentMultipliers(sessionId, floorId)).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("혼잡한 CCTV가 모두 stale이면 이후 조회 없이 빈 맵을 반환한다")
    void currentMultipliers_allStale_returnsEmptyWithoutFurtherQueries() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-1", CongestionLevel.CROWDED, secondsAgo(60)),
                state("CCTV-2", CongestionLevel.VERY_CROWDED, secondsAgo(300))));

        assertThat(provider.currentMultipliers(sessionId, floorId)).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("제외 지정한 CCTV와 stale CCTV를 함께 빼고 나머지만 반영한다")
    void currentMultipliers_excludedAndStaleCombined() {
        UUID keptEdgeId = UUID.randomUUID();
        FloorGridCell keptCell = cell(UUID.randomUUID());
        Cctv keptCctv = cctv(UUID.randomUUID(), "CCTV-KEPT");
        Cctv endedCctv = mock(Cctv.class);
        given(endedCctv.getCode()).willReturn("CCTV-ENDED");
        Cctv staleCctv = mock(Cctv.class);
        given(staleCctv.getCode()).willReturn("CCTV-STALE");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-KEPT", CongestionLevel.CROWDED),
                state("CCTV-ENDED", CongestionLevel.VERY_CROWDED),
                state("CCTV-STALE", CongestionLevel.VERY_CROWDED, secondsAgo(40))));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId))
                .willReturn(List.of(keptCctv, endedCctv, staleCctv));
        List<CctvGridCell> cctvCells = List.of(watching(keptCctv, keptCell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(keptEdgeId, keptCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId, java.util.Set.of("CCTV-ENDED")))
                .containsOnly(Map.entry(keptEdgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("stale 기준 설정이 없으면 필터를 적용하지 않고 기존처럼 반영한다")
    void currentMultipliers_noStaleSetting_keepsOldObservation() {
        CongestionConfig config = mock(CongestionConfig.class);
        given(config.getStateStaleAfterSec()).willReturn(null);
        given(congestionConfigService.getConfig()).willReturn(config);
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cell = cell(UUID.randomUUID());
        Cctv cctv = cctv(UUID.randomUUID(), "CCTV-1");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.CROWDED, secondsAgo(3600))));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(cctv));
        List<CctvGridCell> cctvCells = List.of(watching(cctv, cell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    // === 즉시 혼잡 이벤트 (#293) ===

    @Test
    @DisplayName("관측값이 NORMAL이어도 최신 즉시 이벤트가 끝나지 않은 혼잡이면 그 레벨을 반영한다")
    void currentMultipliers_activeImmediateEventOverridesNormalObservation() {
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cell = cell(UUID.randomUUID());
        Cctv cctv = cctv(UUID.randomUUID(), "CCTV_122");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV_122", CongestionLevel.NORMAL)));
        latestEvents(event("CCTV_122", CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED,
                secondsAgo(60)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(cctv));
        List<CctvGridCell> cctvCells = List.of(watching(cctv, cell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("관측값과 즉시 이벤트가 둘 다 혼잡이면 더 큰 배율을 쓴다")
    void currentMultipliers_usesLargerOfObservationAndEvent() {
        UUID edgeId = UUID.randomUUID();
        FloorGridCell cell = cell(UUID.randomUUID());
        Cctv cctv = cctv(UUID.randomUUID(), "CCTV-1");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.CROWDED)));
        latestEvents(event("CCTV-1", CongestionEventType.CONGESTION_LEVEL_UP, CongestionLevel.VERY_CROWDED,
                secondsAgo(5)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(cctv));
        List<CctvGridCell> cctvCells = List.of(watching(cctv, cell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(edgeId, cell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(edgeId, CurrentCongestionWeightProvider.VERY_CROWDED_WEIGHT_MULTIPLIER));
    }

    @Test
    @DisplayName("최신 즉시 이벤트가 ENDED면 그 CCTV의 이벤트 혼잡은 반영하지 않는다")
    void currentMultipliers_endedEventIsIgnored() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString()))
                .willReturn(List.of(state("CCTV-1", CongestionLevel.NORMAL)));
        latestEvents(event("CCTV-1", CongestionEventType.CONGESTION_ENDED, CongestionLevel.NORMAL,
                secondsAgo(1)));

        assertThat(provider.currentMultipliers(sessionId, floorId)).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("제외 지정한 CCTV는 즉시 이벤트가 혼잡이어도 반영하지 않는다")
    void currentMultipliers_excludedCctvEventIsIgnored() {
        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of());
        latestEvents(event("CCTV-ENDED", CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED,
                secondsAgo(1)));

        assertThat(provider.currentMultipliers(sessionId, floorId, java.util.Set.of("CCTV-ENDED"))).isEmpty();
        verifyNoInteractions(cctvJpaRepository, cctvGridCellRepository, mapEdgeGridCellRepository);
    }

    @Test
    @DisplayName("관측이 끊긴 CCTV의 즉시 이벤트는 방금 온 것만 반영하고 오래된 것은 무시한다")
    void currentMultipliers_eventOfDisconnectedCctvNeedsFreshEvent() {
        UUID freshEdgeId = UUID.randomUUID();
        FloorGridCell freshCell = cell(UUID.randomUUID());
        Cctv freshCctv = cctv(UUID.randomUUID(), "CCTV-FRESH-EVENT");
        Cctv oldCctv = mock(Cctv.class);
        given(oldCctv.getCode()).willReturn("CCTV-OLD-EVENT");

        given(currentCctvStateRepository.findAllBySessionId(sessionId.toString())).willReturn(List.of(
                state("CCTV-FRESH-EVENT", CongestionLevel.NORMAL, secondsAgo(60)),
                state("CCTV-OLD-EVENT", CongestionLevel.NORMAL, secondsAgo(60))));
        latestEvents(
                event("CCTV-FRESH-EVENT", CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED,
                        secondsAgo(2)),
                event("CCTV-OLD-EVENT", CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED,
                        secondsAgo(60)));
        given(cctvJpaRepository.findAllByCustomNode_Floor_Id(floorId)).willReturn(List.of(freshCctv, oldCctv));
        List<CctvGridCell> cctvCells = List.of(watching(freshCctv, freshCell));
        List<MapEdgeGridCell> edgeCells = List.of(crossing(freshEdgeId, freshCell));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(any())).willReturn(cctvCells);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(any())).willReturn(edgeCells);

        assertThat(provider.currentMultipliers(sessionId, floorId))
                .containsOnly(Map.entry(freshEdgeId, RouteRecalculationService.CROWDED_WEIGHT_MULTIPLIER));
    }
}
