package com.saferoute.domain.report.service;

import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 리포트 개선 권고에 적을 병목 위치 문구를 만든다. 예: "CCTV_122(복도 4 – 복도 5 구간) 외 1곳".
// 병목이 가장 많이 감지된 CCTV 하나를 대표로 고르고, 그 CCTV가 감시하는 엣지 중 감시 셀과 가장 많이
// 겹치는 엣지를 대표 구간으로 쓴다. CCTV가 삭제됐거나 도면이 바뀌어 구간을 못 찾으면 코드만 쓴다.
// 리포트 생성(TrainingReportService.generate) 트랜잭션과 분리된 REQUIRES_NEW로 실행한다 - 여기서 조회
// 예외가 나도 리포트 트랜잭션이 rollback-only로 오염되지 않아야 호출부가 예외를 삼키고 기존 문구로
// 리포트를 정상 저장할 수 있다.
@Component
@RequiredArgsConstructor
public class BottleneckLocationResolver {

    private static final String EDGE_SEPARATOR = " – ";

    private final CongestionEventRepository congestionEventRepository;
    private final CctvJpaRepository cctvJpaRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> resolve(UUID sessionId, UUID buildingId) {
        Map<String, Long> countsByCctvCode = congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString());
        if (countsByCctvCode.isEmpty()) {
            return Optional.empty();
        }

        // 횟수가 같으면 코드 순으로 골라, 같은 데이터면 항상 같은 CCTV가 나오게 한다.
        String topCctvCode = countsByCctvCode.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        String location = cctvJpaRepository.findByCode(topCctvCode)
                .filter(cctv -> buildingId.equals(cctv.getCustomNode().getFloor().getBuilding().getId()))
                .flatMap(this::representativeEdgeName)
                .map(edgeName -> topCctvCode + "(" + edgeName + " 구간)")
                .orElse(topCctvCode);

        int otherCctvCount = countsByCctvCode.size() - 1;
        if (otherCctvCount > 0) {
            location += " 외 " + otherCctvCount + "곳";
        }
        return Optional.of(location);
    }

    private Optional<String> representativeEdgeName(Cctv cctv) {
        List<UUID> gridCellIds = cctvGridCellRepository
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctv.getId())
                .stream()
                .map(mapping -> mapping.getGridCell().getId())
                .toList();
        if (gridCellIds.isEmpty()) {
            return Optional.empty();
        }

        // 감시 셀과 겹치는 셀 수가 가장 많은 엣지. 동률이면 격자 순서상 먼저 나오는 엣지를 쓴다.
        // IN 조회는 순서를 보장하지 않으므로, 감시 셀의 격자 순서(gridCellIds 순서)로 다시 정렬한 뒤 집계한다.
        // 같은 셀에 걸친 엣지끼리는 id 순으로 정해, 같은 데이터면 항상 같은 구간이 나오게 한다.
        Map<UUID, Integer> cellOrder = new HashMap<>();
        for (int i = 0; i < gridCellIds.size(); i++) {
            cellOrder.putIfAbsent(gridCellIds.get(i), i);
        }
        List<MapEdgeGridCell> mappings = new ArrayList<>(mapEdgeGridCellRepository.findAllByGridCell_IdIn(gridCellIds));
        mappings.sort(Comparator
                .comparingInt((MapEdgeGridCell mapping) ->
                        cellOrder.getOrDefault(mapping.getGridCell().getId(), Integer.MAX_VALUE))
                .thenComparing(mapping -> mapping.getMapEdge().getId()));

        Map<MapEdge, Long> overlapByEdge = new LinkedHashMap<>();
        for (MapEdgeGridCell mapping : mappings) {
            overlapByEdge.merge(mapping.getMapEdge(), 1L, Long::sum);
        }
        MapEdge representative = null;
        long maxOverlap = 0;
        for (Map.Entry<MapEdge, Long> entry : overlapByEdge.entrySet()) {
            if (entry.getValue() > maxOverlap) {
                representative = entry.getKey();
                maxOverlap = entry.getValue();
            }
        }
        return Optional.ofNullable(representative)
                .map(edge -> edge.getFromNode().getName() + EDGE_SEPARATOR + edge.getToNode().getName());
    }
}
