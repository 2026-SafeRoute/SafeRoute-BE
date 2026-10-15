package com.saferoute.domain.device.service;

import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saferoute.domain.device.dto.request.CreateCctvRequest;
import com.saferoute.domain.device.dto.response.CctvRegistrationResponse;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.security.DeviceTokenService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CctvRegistrationServiceTest {

    @Mock CctvJpaRepository cctvJpaRepository;
    @Mock CctvGridCellRepository cctvGridCellRepository;
    @Mock FloorGridCellRepository floorGridCellRepository;
    @Mock MapNodeJpaRepository mapNodeJpaRepository;
    @Mock FloorRepository floorRepository;
    @Mock DeviceTokenService deviceTokenService;
    @Mock CongestionConfigService congestionConfigService;
    @Mock FloorMapChangeGuard floorMapChangeGuard;

    private CctvRegistrationService service;
    private Floor floor;
    private UUID floorId;

    @BeforeEach
    void setUp() {
        service = new CctvRegistrationService(
                cctvJpaRepository,
                cctvGridCellRepository,
                floorGridCellRepository,
                mapNodeJpaRepository,
                floorRepository,
                deviceTokenService,
                congestionConfigService,
                floorMapChangeGuard
        );
        floor = org.mockito.Mockito.mock(Floor.class);
        floorId = UUID.randomUUID();
        // 훈련 중 등록 거부 테스트는 이 값들을 읽기 전에 끝나므로 lenient로 둔다.
        org.mockito.Mockito.lenient().when(floor.getId()).thenReturn(floorId);
        org.mockito.Mockito.lenient().when(floor.getGridCellSizeMeter()).thenReturn(0.5);
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 CCTV를 등록하지 않고 FLOOR004를 던진다")
    void register_whileTrainingRunning_throws() {
        CreateCctvRequest request = request(List.of(UUID.randomUUID()));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        org.mockito.BDDMockito.willThrow(new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard)
                .validateNoRunningTraining(org.mockito.ArgumentMatchers.<Floor>any());

        assertThatThrownBy(() -> service.register(request, "CCTV_099"))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        verify(mapNodeJpaRepository, never()).save(any());
        verify(cctvJpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("CCTV 위치 노드와 감시 셀을 하나의 등록 작업으로 저장한다")
    void register_success() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        FloorGridCell first = cell(firstId, floor, 0, 0, true);
        FloorGridCell second = cell(secondId, floor, 0, 1, true);
        CreateCctvRequest request = request(List.of(firstId, secondId));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(floorGridCellRepository.findAllById(request.gridCellIds()))
                .willReturn(List.of(second, first));
        given(mapNodeJpaRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(cctvJpaRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(deviceTokenService.issue()).willReturn(
                new DeviceTokenService.IssuedDeviceToken("raw-token", "hashed-token")
        );

        CctvRegistrationResponse response = service.register(request, "CCTV_001");

        assertThat(response.cctv().code()).isEqualTo("CCTV_001");
        assertThat(response.cctv().monitoredGridCellCount()).isEqualTo(2);
        assertThat(response.cctv().gridCells()).extracting("id").containsExactly(firstId, secondId);
        assertThat(response.deviceToken()).isEqualTo("raw-token");
        ArgumentCaptor<List<CctvGridCell>> mappings = ArgumentCaptor.forClass(List.class);
        verify(cctvGridCellRepository).saveAllAndFlush(mappings.capture());
        assertThat(mappings.getValue()).hasSize(2);
        verify(congestionConfigService).incrementVersionForGridChange();
    }

    @Test
    @DisplayName("검증 이후 실제 저장 사이에 그리드가 재생성돼 셀이 사라지면 GRID_CELL_NOT_FOUND로 응답한다")
    void register_staleGridCellOnSave_throwsGridCellNotFound() {
        UUID cellId = UUID.randomUUID();
        FloorGridCell cell = cell(cellId, floor, 0, 0, true);
        CreateCctvRequest request = request(List.of(cellId));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(floorGridCellRepository.findAllById(request.gridCellIds())).willReturn(List.of(cell));
        given(mapNodeJpaRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(cctvJpaRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(deviceTokenService.issue()).willReturn(
                new DeviceTokenService.IssuedDeviceToken("raw-token", "hashed-token")
        );
        given(cctvGridCellRepository.saveAllAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("FK violation"));

        assertThatThrownBy(() -> service.register(request, "CCTV_001"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(CctvErrorCode.GRID_CELL_NOT_FOUND);

        verify(congestionConfigService, never()).incrementVersionForGridChange();
    }

    @Test
    @DisplayName("중복 GridCell 요청은 저장 전에 거부한다")
    void register_duplicateGridCells() {
        UUID cellId = UUID.randomUUID();
        CreateCctvRequest request = request(List.of(cellId, cellId));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));

        assertThatThrownBy(() -> service.register(request, "CCTV_001"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(CctvErrorCode.DUPLICATE_GRID_CELL);
        verify(mapNodeJpaRepository, never()).save(any());
        verify(cctvJpaRepository, never()).save(any());
    }

    @Test
    @DisplayName("다른 층의 GridCell은 CCTV 감시 영역으로 등록할 수 없다")
    void register_gridCellFloorMismatch() {
        UUID cellId = UUID.randomUUID();
        Floor otherFloor = org.mockito.Mockito.mock(Floor.class);
        given(otherFloor.getId()).willReturn(UUID.randomUUID());
        FloorGridCell cell = cell(cellId, otherFloor, 0, 0, true);
        CreateCctvRequest request = request(List.of(cellId));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(floorGridCellRepository.findAllById(request.gridCellIds())).willReturn(List.of(cell));

        assertThatThrownBy(() -> service.register(request, "CCTV_001"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(CctvErrorCode.GRID_CELL_FLOOR_MISMATCH);
    }

    @Test
    @DisplayName("보행 불가능한 GridCell은 CCTV 감시 영역으로 등록할 수 없다")
    void register_nonWalkableGridCell() {
        UUID cellId = UUID.randomUUID();
        FloorGridCell cell = cell(cellId, floor, 0, 0, false);
        CreateCctvRequest request = request(List.of(cellId));
        given(floorRepository.findById(floorId)).willReturn(Optional.of(floor));
        given(floorGridCellRepository.findAllById(request.gridCellIds())).willReturn(List.of(cell));

        assertThatThrownBy(() -> service.register(request, "CCTV_001"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(CctvErrorCode.NON_WALKABLE_GRID_CELL);
    }

    private CreateCctvRequest request(List<UUID> gridCellIds) {
        return new CreateCctvRequest("동쪽 복도 CCTV", floorId, 0.4, 0.3, gridCellIds);
    }

    private FloorGridCell cell(
            UUID id,
            Floor owningFloor,
            int row,
            int column,
            boolean walkable
    ) {
        FloorGridCell cell = org.mockito.Mockito.mock(FloorGridCell.class);
        org.mockito.Mockito.lenient().when(cell.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(cell.getFloor()).thenReturn(owningFloor);
        org.mockito.Mockito.lenient().when(cell.getRowIndex()).thenReturn(row);
        org.mockito.Mockito.lenient().when(cell.getColumnIndex()).thenReturn(column);
        org.mockito.Mockito.lenient().when(cell.isWalkable()).thenReturn(walkable);
        return cell;
    }
}
