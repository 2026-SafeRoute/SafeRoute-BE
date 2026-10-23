package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BottleneckLocationResolverTest {

    @InjectMocks
    private BottleneckLocationResolver resolver;

    @Mock
    private CongestionEventRepository congestionEventRepository;
    @Mock
    private CctvJpaRepository cctvJpaRepository;
    @Mock
    private CctvGridCellRepository cctvGridCellRepository;
    @Mock
    private MapEdgeGridCellRepository mapEdgeGridCellRepository;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();

    @Test
    @DisplayName("병목이 가장 많은 CCTV와 그 CCTV 감시 셀과 가장 많이 겹치는 구간을 적는다")
    void resolve_topCctvWithMostOverlappingEdge() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_122", 2L));
        Cctv cctv = cctvInBuilding(buildingId);
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.of(cctv));
        FloorGridCell cellA = gridCell();
        FloorGridCell cellB = gridCell();
        givenMonitoredCells(cctv, cellA, cellB);
        MapEdge minorEdge = edge("복도 5", "복도 6");
        MapEdge mainEdge = edge("복도 4", "복도 5");
        List<MapEdgeGridCell> edgeMappings =
                List.of(mapping(minorEdge, cellA), mapping(mainEdge, cellA), mapping(mainEdge, cellB));
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(cellA.getId(), cellB.getId())))
                .willReturn(edgeMappings);

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122(복도 4 – 복도 5 구간)");
    }

    @Test
    @DisplayName("겹침 수가 같으면 조회 순서와 무관하게 격자 순서상 먼저인 구간을 적는다")
    void resolve_overlapTie_picksEdgeEarlierInGridOrderRegardlessOfQueryOrder() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_122", 2L));
        Cctv cctv = cctvInBuilding(buildingId);
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.of(cctv));
        FloorGridCell firstCell = gridCell();
        FloorGridCell secondCell = gridCell();
        givenMonitoredCells(cctv, firstCell, secondCell);
        MapEdge firstEdge = edge("복도 1", "복도 2");
        MapEdge secondEdge = edge("복도 2", "복도 3");
        // DB가 격자 순서와 반대로 돌려줘도 결과가 바뀌면 안 된다.
        List<MapEdgeGridCell> edgeMappings = List.of(mapping(secondEdge, secondCell), mapping(firstEdge, firstCell));
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(firstCell.getId(), secondCell.getId())))
                .willReturn(edgeMappings);

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122(복도 1 – 복도 2 구간)");
    }

    @Test
    @DisplayName("병목이 여러 CCTV에서 났으면 가장 많은 CCTV를 적고 나머지는 '외 N곳'으로 줄인다")
    void resolve_multipleCctvs_picksMostFrequentAndSummarizesRest() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_001", 1L, "CCTV_122", 3L, "CCTV_200", 1L));
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.empty());

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122 외 2곳");
    }

    @Test
    @DisplayName("횟수가 같으면 코드 순으로 골라 항상 같은 결과가 나온다")
    void resolve_tie_picksSmallestCode() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_200", 2L, "CCTV_001", 2L));
        given(cctvJpaRepository.findByCode("CCTV_001")).willReturn(Optional.empty());

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_001 외 1곳");
    }

    @Test
    @DisplayName("CCTV가 삭제돼 찾을 수 없으면 코드만 적는다")
    void resolve_cctvNotFound_usesCodeOnly() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_122", 2L));
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.empty());

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122");
    }

    @Test
    @DisplayName("다른 건물의 CCTV면 구간을 찾지 않고 코드만 적는다")
    void resolve_cctvInOtherBuilding_usesCodeOnly() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_122", 2L));
        Cctv cctv = cctvInBuilding(UUID.randomUUID());
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.of(cctv));

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122");
        verify(cctvGridCellRepository, never())
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(Mockito.any());
    }

    @Test
    @DisplayName("감시 셀이나 겹치는 구간이 없으면(도면 변경 등) 코드만 적는다")
    void resolve_noMonitoredEdge_usesCodeOnly() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willReturn(Map.of("CCTV_122", 2L));
        Cctv cctv = cctvInBuilding(buildingId);
        given(cctvJpaRepository.findByCode("CCTV_122")).willReturn(Optional.of(cctv));
        FloorGridCell cell = gridCell();
        givenMonitoredCells(cctv, cell);
        given(mapEdgeGridCellRepository.findAllByGridCell_IdIn(List.of(cell.getId()))).willReturn(List.of());

        assertThat(resolver.resolve(sessionId, buildingId)).contains("CCTV_122");
    }

    @Test
    @DisplayName("CCTV 코드가 있는 병목이 없으면 위치를 비운다")
    void resolve_noBottleneckWithCctvCode_returnsEmpty() {
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString())).willReturn(Map.of());

        assertThat(resolver.resolve(sessionId, buildingId)).isEmpty();
    }

    private Cctv cctvInBuilding(UUID cctvBuildingId) {
        Building building = mock(Building.class);
        given(building.getId()).willReturn(cctvBuildingId);
        Floor floor = mock(Floor.class);
        given(floor.getBuilding()).willReturn(building);
        MapNode customNode = mock(MapNode.class);
        given(customNode.getFloor()).willReturn(floor);
        Cctv cctv = mock(Cctv.class);
        given(cctv.getCustomNode()).willReturn(customNode);
        Mockito.lenient().when(cctv.getId()).thenReturn(UUID.randomUUID());
        return cctv;
    }

    private void givenMonitoredCells(Cctv cctv, FloorGridCell... cells) {
        List<CctvGridCell> mappings = java.util.Arrays.stream(cells)
                .map(cell -> {
                    CctvGridCell mapping = mock(CctvGridCell.class);
                    given(mapping.getGridCell()).willReturn(cell);
                    return mapping;
                })
                .toList();
        given(cctvGridCellRepository.findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctv.getId()))
                .willReturn(mappings);
    }

    private FloorGridCell gridCell() {
        FloorGridCell cell = mock(FloorGridCell.class);
        Mockito.lenient().when(cell.getId()).thenReturn(UUID.randomUUID());
        return cell;
    }

    private MapEdge edge(String fromName, String toName) {
        MapNode from = mock(MapNode.class);
        Mockito.lenient().when(from.getName()).thenReturn(fromName);
        MapNode to = mock(MapNode.class);
        Mockito.lenient().when(to.getName()).thenReturn(toName);
        MapEdge edge = mock(MapEdge.class);
        Mockito.lenient().when(edge.getId()).thenReturn(UUID.randomUUID());
        Mockito.lenient().when(edge.getFromNode()).thenReturn(from);
        Mockito.lenient().when(edge.getToNode()).thenReturn(to);
        return edge;
    }

    private MapEdgeGridCell mapping(MapEdge edge, FloorGridCell cell) {
        MapEdgeGridCell mapping = mock(MapEdgeGridCell.class);
        Mockito.lenient().when(mapping.getMapEdge()).thenReturn(edge);
        Mockito.lenient().when(mapping.getGridCell()).thenReturn(cell);
        return mapping;
    }
}
