package com.saferoute.domain.device.service;

import com.saferoute.domain.device.dto.request.ConfigureCctvGridCellsRequest;
import com.saferoute.domain.device.dto.request.CreateCctvRequest;
import com.saferoute.domain.device.dto.request.UpdateCctvRequest;
import com.saferoute.domain.device.dto.response.CctvResponse;
import com.saferoute.domain.device.dto.response.CctvRegistrationResponse;
import com.saferoute.domain.device.dto.response.DeviceTokenIssueResponse;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.DeviceErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.security.DeviceTokenService;
import com.saferoute.domain.user.service.SchoolContextService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CctvService {

    private final CctvJpaRepository cctvJpaRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final FloorGridCellRepository floorGridCellRepository;
    private final CctvCodeAllocator cctvCodeAllocator;
    private final CctvRegistrationService cctvRegistrationService;
    private final DeviceTokenService deviceTokenService;
    private final CongestionConfigService congestionConfigService;
    private final SchoolContextService schoolContextService;
    private final FloorMapChangeGuard floorMapChangeGuard;

    public CctvRegistrationResponse createCctv(CreateCctvRequest request) {
        return cctvRegistrationService.register(request, cctvCodeAllocator.allocate());
    }

    @Transactional
    public DeviceTokenIssueResponse issueDeviceToken(UUID cctvId) {
        Cctv cctv = cctvJpaRepository.findByIdForDeviceTokenIssue(cctvId)
                .orElseThrow(() -> new ApiException(CctvErrorCode.CCTV_NOT_FOUND));
        if (cctv.getDeviceTokenHash() != null) {
            throw new ApiException(DeviceErrorCode.DEVICE_TOKEN_ALREADY_ISSUED);
        }

        DeviceTokenService.IssuedDeviceToken issuedToken = deviceTokenService.issue();
        cctv.issueDeviceToken(issuedToken.hash());
        return new DeviceTokenIssueResponse(issuedToken.rawToken());
    }

    public List<CctvResponse> getCctvs(UUID floorId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        List<Cctv> cctvs = floorId == null
                ? cctvJpaRepository.findAllByCustomNode_Floor_Building_SchoolName(schoolName)
                : cctvJpaRepository
                        .findAllByCustomNode_Floor_IdAndCustomNode_Floor_Building_SchoolName(
                                floorId, schoolName);
        return toResponses(cctvs);
    }

    private List<CctvResponse> toResponses(List<Cctv> cctvs) {
        if (cctvs.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<FloorGridCell>> gridCellsByCctvId = cctvGridCellRepository
                .findAllByCctvIdsWithGridCell(cctvs.stream().map(Cctv::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        mapping -> mapping.getCctv().getId(),
                        Collectors.mapping(CctvGridCell::getGridCell, Collectors.toList())
                ));

        return cctvs.stream()
                .map(cctv -> CctvResponse.of(
                        cctv,
                        gridCellsByCctvId.getOrDefault(cctv.getId(), List.of())
                ))
                .toList();
    }

    public CctvResponse getCctv(UUID cctvId, String email) {
        return toResponse(findCctvForSchoolOrThrow(cctvId, email));
    }

    public CctvResponse getGridCells(UUID cctvId, String email) {
        return getCctv(cctvId, email);
    }

    @Transactional
    public CctvResponse configureGridCells(
            UUID cctvId,
            ConfigureCctvGridCellsRequest request,
            String email
    ) {
        Cctv cctv = findCctvForSchoolOrThrow(cctvId, email);
        validateNoRunningTraining(cctv);
        Floor floor = cctv.getCustomNode().getFloor();
        validateGridConfigured(floor);
        List<FloorGridCell> gridCells = findAndValidateGridCells(
                floor.getId(), request.gridCellIds());

        cctvGridCellRepository.deleteAllByCctvId(cctvId);
        try {
            saveMappings(cctv, gridCells);
        } catch (DataIntegrityViolationException exception) {
            // 검증(findAndValidateGridCells) 이후 저장 사이에 그리드가 재생성되면 그 셀들이
            // 이미 삭제된 상태라 FK 위반으로 터진다 - 클라이언트가 오래된 셀 목록을 들고 있었던
            // 것과 같은 상황이므로 동일한 에러로 응답해 다시 최신 목록을 불러오게 한다.
            throw new ApiException(CctvErrorCode.GRID_CELL_NOT_FOUND, exception);
        }
        congestionConfigService.incrementVersionForGridChange();
        return CctvResponse.of(cctv, gridCells);
    }

    @Transactional
    public CctvResponse updateCctv(UUID cctvId, UpdateCctvRequest request, String email) {
        Cctv cctv = findCctvForSchoolOrThrow(cctvId, email);
        validateNoRunningTraining(cctv);
        cctv.rename(request.name());
        cctv.getCustomNode().moveTo(request.x(), request.y());
        return toResponse(cctv);
    }

    @Transactional
    public CctvResponse enableCctv(UUID cctvId, String email) {
        Cctv cctv = findCctvForSchoolOrThrow(cctvId, email);
        cctv.enable();
        return toResponse(cctv);
    }

    @Transactional
    public CctvResponse disableCctv(UUID cctvId, String email) {
        Cctv cctv = findCctvForSchoolOrThrow(cctvId, email);
        cctv.disable();
        return toResponse(cctv);
    }

    @Transactional
    public void deleteCctv(UUID cctvId, String email) {
        Cctv cctv = findCctvForSchoolOrThrow(cctvId, email);
        validateNoRunningTraining(cctv);
        cctv.delete();
    }

    private CctvResponse toResponse(Cctv cctv) {
        List<FloorGridCell> gridCells = cctvGridCellRepository
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctv.getId())
                .stream()
                .map(CctvGridCell::getGridCell)
                .toList();
        return CctvResponse.of(cctv, gridCells);
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

    private void saveMappings(Cctv cctv, List<FloorGridCell> gridCells) {
        // saveAll()만으로는 실제 INSERT가 트랜잭션 커밋 시점까지 미뤄져 configureGridCells의
        // try/catch 밖(JpaTransactionManager.doCommit)에서 예외가 터진다 - saveAllAndFlush로
        // 즉시 flush해서 검증 이후 그리드 재생성으로 인한 FK 위반을 여기서 잡는다.
        cctvGridCellRepository.saveAllAndFlush(
                gridCells.stream()
                        .map(cell -> CctvGridCell.create(cctv, cell))
                        .toList()
        );
    }

    private void validateGridConfigured(Floor floor) {
        Double cellSizeMeter = floor.getGridCellSizeMeter();
        if (cellSizeMeter == null || cellSizeMeter <= 0) {
            throw new ApiException(CctvErrorCode.GRID_NOT_CONFIGURED);
        }
    }

    private Cctv findCctvForSchoolOrThrow(UUID cctvId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        return cctvJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, schoolName)
                .orElseThrow(() -> new ApiException(CctvErrorCode.CCTV_NOT_FOUND));
    }

    // 훈련 중 CCTV 위치·감시 구역이 바뀌거나 CCTV가 사라지면 혼잡이 반영되는 엣지와 모니터링 대상이 훈련 도중
    // 달라진다. 도면 변경과 같은 기준(FloorMapChangeGuard)으로 막는다. 활성화/비활성화·토큰 재발급은 훈련 중
    // 고장 대응에 필요하므로 막지 않는다.
    private void validateNoRunningTraining(Cctv cctv) {
        floorMapChangeGuard.validateNoRunningTraining(cctv.getCustomNode().getFloor());
    }
}
