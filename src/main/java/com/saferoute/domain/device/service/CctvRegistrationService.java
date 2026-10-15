package com.saferoute.domain.device.service;

import com.saferoute.domain.device.dto.request.CreateCctvRequest;
import com.saferoute.domain.device.dto.response.CctvResponse;
import com.saferoute.domain.device.dto.response.CctvRegistrationResponse;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.evacuation.graph.entity.CustomDeviceType;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.security.DeviceTokenService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CctvRegistrationService {

    private final CctvJpaRepository cctvJpaRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final FloorGridCellRepository floorGridCellRepository;
    private final MapNodeJpaRepository mapNodeJpaRepository;
    private final FloorRepository floorRepository;
    private final DeviceTokenService deviceTokenService;
    private final CongestionConfigService congestionConfigService;
    private final FloorMapChangeGuard floorMapChangeGuard;

    // MapNode/Cctv/매핑 저장은 하나의 트랜잭션으로 처리한다.
    // CCTV 코드는 호출 전에 독립 트랜잭션의 DB sequence로 이미 발급된다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CctvRegistrationResponse register(CreateCctvRequest request, String code) {
        Floor floor = floorRepository.findById(request.floorId())
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
        // 훈련 중에는 새 CCTV(도면 노드·감시 구역)를 추가하지 않는다 (CctvService의 수정·삭제와 같은 기준).
        floorMapChangeGuard.validateNoRunningTraining(floor);
        validateGridConfigured(floor);
        List<FloorGridCell> gridCells = findAndValidateGridCells(
                floor.getId(), request.gridCellIds());

        MapNode customNode = mapNodeJpaRepository.save(
                MapNode.createCustom(
                        floor,
                        code,
                        request.name(),
                        request.x(),
                        request.y(),
                        CustomDeviceType.CCTV
                )
        );
        DeviceTokenService.IssuedDeviceToken issuedToken = deviceTokenService.issue();
        Cctv cctv = Cctv.create(code, request.name(), customNode);
        cctv.issueDeviceToken(issuedToken.hash());
        Cctv savedCctv = cctvJpaRepository.save(cctv);
        try {
            // saveAll()만으로는 실제 INSERT가 트랜잭션 커밋 시점까지 미뤄져 이 try/catch
            // 밖(JpaTransactionManager.doCommit)에서 예외가 터진다 - saveAllAndFlush로
            // 즉시 flush해서 검증(findAndValidateGridCells) 이후 그리드가 재생성돼 그 셀들이
            // 이미 삭제된 경우의 FK 위반을 여기서 잡는다.
            cctvGridCellRepository.saveAllAndFlush(
                    gridCells.stream()
                            .map(cell -> CctvGridCell.create(savedCctv, cell))
                            .toList()
            );
        } catch (DataIntegrityViolationException exception) {
            // CctvService.configureGridCells와 동일한 상황이므로 같은 에러로 응답해
            // 최신 목록을 다시 불러오게 한다.
            throw new ApiException(CctvErrorCode.GRID_CELL_NOT_FOUND, exception);
        }
        congestionConfigService.incrementVersionForGridChange();

        return new CctvRegistrationResponse(
                CctvResponse.of(savedCctv, gridCells),
                issuedToken.rawToken()
        );
    }

    private List<FloorGridCell> findAndValidateGridCells(
            UUID floorId,
            List<UUID> gridCellIds
    ) {
        if (new HashSet<>(gridCellIds).size() != gridCellIds.size()) {
            throw new ApiException(CctvErrorCode.DUPLICATE_GRID_CELL);
        }

        List<FloorGridCell> gridCells = floorGridCellRepository.findAllById(gridCellIds);
        if (gridCells.size() != gridCellIds.size()) {
            throw new ApiException(CctvErrorCode.GRID_CELL_NOT_FOUND);
        }
        if (gridCells.stream().anyMatch(cell -> !cell.getFloor().getId().equals(floorId))) {
            throw new ApiException(CctvErrorCode.GRID_CELL_FLOOR_MISMATCH);
        }
        if (gridCells.stream().anyMatch(cell -> !cell.isWalkable())) {
            throw new ApiException(CctvErrorCode.NON_WALKABLE_GRID_CELL);
        }

        return gridCells.stream()
                .sorted(Comparator.comparingInt(FloorGridCell::getRowIndex)
                        .thenComparingInt(FloorGridCell::getColumnIndex))
                .toList();
    }

    private void validateGridConfigured(Floor floor) {
        Double cellSizeMeter = floor.getGridCellSizeMeter();
        if (cellSizeMeter == null || cellSizeMeter <= 0) {
            throw new ApiException(CctvErrorCode.GRID_NOT_CONFIGURED);
        }
    }
}
