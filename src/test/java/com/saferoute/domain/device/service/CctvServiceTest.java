package com.saferoute.domain.device.service;

import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.global.api.error.DeviceErrorCode;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.global.security.DeviceTokenService;
import com.saferoute.domain.user.service.SchoolContextService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

@ExtendWith(MockitoExtension.class)
class CctvServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @Mock CctvJpaRepository cctvJpaRepository;
    @Mock CctvGridCellRepository cctvGridCellRepository;
    @Mock FloorGridCellRepository floorGridCellRepository;
    @Mock CctvCodeAllocator cctvCodeAllocator;
    @Mock CctvRegistrationService cctvRegistrationService;
    @Mock DeviceTokenService deviceTokenService;
    @Mock CongestionConfigService congestionConfigService;
    @Mock SchoolContextService schoolContextService;
    @Mock FloorMapChangeGuard floorMapChangeGuard;

    private CctvService cctvService;

    @BeforeEach
    void setUp() {
        cctvService = new CctvService(
                cctvJpaRepository,
                cctvGridCellRepository,
                floorGridCellRepository,
                cctvCodeAllocator,
                cctvRegistrationService,
                deviceTokenService,
                congestionConfigService,
                schoolContextService,
                floorMapChangeGuard
        );
        org.mockito.Mockito.lenient()
                .when(schoolContextService.getSchoolName(EMAIL))
                .thenReturn(SCHOOL_NAME);
    }

    @Test
    @DisplayName("CCTV 등록 시 생성한 코드를 별도 트랜잭션 등록 서비스에 전달한다")
    void createCctv_success() {
        CreateCctvRequest request = request();
        CctvRegistrationResponse expected = org.mockito.Mockito.mock(CctvRegistrationResponse.class);
        given(cctvCodeAllocator.allocate()).willReturn("CCTV_001");
        given(cctvRegistrationService.register(any(), any())).willReturn(expected);

        CctvRegistrationResponse response = cctvService.createCctv(request);

        assertThat(response).isSameAs(expected);
        verify(cctvRegistrationService).register(
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq("CCTV_001")
        );
    }

    @Test
    @DisplayName("토큰이 없는 기존 CCTV에는 디바이스 토큰을 최초 1회 발급한다")
    void issueDeviceToken_success() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctvJpaRepository.findByIdForDeviceTokenIssue(cctvId))
                .willReturn(Optional.of(cctv));
        given(deviceTokenService.issue()).willReturn(
                new DeviceTokenService.IssuedDeviceToken("raw-token", "hashed-token")
        );

        DeviceTokenIssueResponse response = cctvService.issueDeviceToken(cctvId);

        assertThat(response.deviceToken()).isEqualTo("raw-token");
        verify(cctv).issueDeviceToken("hashed-token");
    }

    @Test
    @DisplayName("이미 토큰이 있는 CCTV에는 토큰을 다시 발급하지 않는다")
    void issueDeviceToken_rejectsAlreadyIssuedToken() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctv.getDeviceTokenHash()).willReturn("existing-hash");
        given(cctvJpaRepository.findByIdForDeviceTokenIssue(cctvId))
                .willReturn(Optional.of(cctv));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cctvService.issueDeviceToken(cctvId)
                )
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        DeviceErrorCode.DEVICE_TOKEN_ALREADY_ISSUED
                );
        verify(deviceTokenService, never()).issue();
    }

    @Test
    @DisplayName("CCTV 목록의 감시 셀 매핑을 CCTV ID 목록으로 한 번에 조회한다")
    void getCctvs_loadsMappingsInBatch() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Cctv firstCctv = cctv(firstId);
        Cctv secondCctv = cctv(secondId);
        CctvGridCell firstMapping = mapping(firstCctv, cell(0, 0));
        CctvGridCell secondMapping = mapping(secondCctv, cell(0, 1));
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(cctvJpaRepository.findAllByCustomNode_Floor_Building_SchoolName(SCHOOL_NAME))
                .willReturn(List.of(firstCctv, secondCctv));
        given(cctvGridCellRepository.findAllByCctvIdsWithGridCell(anyList()))
                .willReturn(List.of(firstMapping, secondMapping));

        List<CctvResponse> responses = cctvService.getCctvs(null, EMAIL);

        assertThat(responses).hasSize(2);
        verify(cctvGridCellRepository).findAllByCctvIdsWithGridCell(List.of(firstId, secondId));
        verify(cctvGridCellRepository, never())
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(any());
    }

    @Test
    @DisplayName("감시 영역 수정 시 검증을 마친 뒤 기존 매핑을 교체한다")
    void configureGridCells_success() {
        UUID cctvId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        Floor floor = org.mockito.Mockito.mock(Floor.class);
        MapNode node = org.mockito.Mockito.mock(MapNode.class);
        Cctv cctv = cctv(cctvId);
        FloorGridCell cell = cell(1, 2);
        given(floor.getId()).willReturn(floorId);
        given(floor.getGridCellSizeMeter()).willReturn(0.5);
        given(node.getFloor()).willReturn(floor);
        given(cctv.getCustomNode()).willReturn(node);
        given(cell.getId()).willReturn(cellId);
        given(cell.getFloor()).willReturn(floor);
        given(cell.isWalkable()).willReturn(true);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));
        given(floorGridCellRepository.findAllById(List.of(cellId))).willReturn(List.of(cell));

        cctvService.configureGridCells(
                cctvId,
                new ConfigureCctvGridCellsRequest(List.of(cellId)),
                EMAIL
        );

        verify(cctvGridCellRepository).deleteAllByCctvId(cctvId);
        verify(cctvGridCellRepository).saveAllAndFlush(any());
        verify(congestionConfigService).incrementVersionForGridChange();
    }

    @Test
    @DisplayName("검증 이후 저장 사이에 그리드가 재생성돼 셀이 사라지면 GRID_CELL_NOT_FOUND로 응답한다")
    void configureGridCells_staleGridCellOnSave_throwsGridCellNotFound() {
        UUID cctvId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID cellId = UUID.randomUUID();
        Floor floor = org.mockito.Mockito.mock(Floor.class);
        MapNode node = org.mockito.Mockito.mock(MapNode.class);
        Cctv cctv = cctv(cctvId);
        FloorGridCell cell = cell(1, 2);
        given(floor.getId()).willReturn(floorId);
        given(floor.getGridCellSizeMeter()).willReturn(0.5);
        given(node.getFloor()).willReturn(floor);
        given(cctv.getCustomNode()).willReturn(node);
        given(cell.getFloor()).willReturn(floor);
        given(cell.isWalkable()).willReturn(true);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));
        given(floorGridCellRepository.findAllById(List.of(cellId))).willReturn(List.of(cell));
        given(cctvGridCellRepository.saveAllAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("FK violation"));

        assertThatThrownBy(() -> cctvService.configureGridCells(
                cctvId,
                new ConfigureCctvGridCellsRequest(List.of(cellId)),
                EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(CctvErrorCode.GRID_CELL_NOT_FOUND);

        verify(congestionConfigService, never()).incrementVersionForGridChange();
    }

    @Test
    @DisplayName("CCTV 정보 수정 시 이름과 연결 노드 위치를 함께 변경한다")
    void updateCctv_success() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        MapNode node = cctv.getCustomNode();
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));

        cctvService.updateCctv(
                cctvId,
                new UpdateCctvRequest("1층 출입구 CCTV", 0.4, 0.6),
                EMAIL
        );
        verify(cctv).rename("1층 출입구 CCTV");
        verify(node).moveTo(0.4, 0.6);
    }

    @Test
    @DisplayName("CCTV 삭제 시 소프트 삭제 처리만 하고 별도 응답은 없다")
    void deleteCctv_success() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));

        cctvService.deleteCctv(cctvId, EMAIL);

        verify(cctv).delete();
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 CCTV 이름·위치를 수정하지 않고 FLOOR004를 던진다")
    void updateCctv_whileTrainingRunning_throws() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));
        org.mockito.BDDMockito.willThrow(new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard)
                .validateNoRunningTraining(org.mockito.ArgumentMatchers.<Floor>any());

        assertThatThrownBy(() -> cctvService.updateCctv(
                cctvId, new UpdateCctvRequest("1층 출입구 CCTV", 0.4, 0.6), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        verify(cctv, never()).rename(any());
        verify(cctv.getCustomNode(), never()).moveTo(org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 CCTV 감시 구역을 바꾸지 않고 FLOOR004를 던진다")
    void configureGridCells_whileTrainingRunning_throws() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));
        org.mockito.BDDMockito.willThrow(new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard)
                .validateNoRunningTraining(org.mockito.ArgumentMatchers.<Floor>any());

        assertThatThrownBy(() -> cctvService.configureGridCells(
                cctvId, new ConfigureCctvGridCellsRequest(List.of(UUID.randomUUID())), EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        verify(cctvGridCellRepository, never()).deleteAllByCctvId(any());
    }

    @Test
    @DisplayName("훈련이 진행 중인 건물이면 CCTV를 삭제하지 않고 FLOOR004를 던진다")
    void deleteCctv_whileTrainingRunning_throwsAndKeepsCctv() {
        UUID cctvId = UUID.randomUUID();
        Cctv cctv = cctv(cctvId);
        given(cctvJpaRepository.findByIdAndCustomNode_Floor_Building_SchoolName(cctvId, SCHOOL_NAME))
                .willReturn(Optional.of(cctv));
        org.mockito.BDDMockito.willThrow(new ApiException(
                        com.saferoute.global.api.error.FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING))
                .given(floorMapChangeGuard)
                .validateNoRunningTraining(org.mockito.ArgumentMatchers.<com.saferoute.domain.floor.entity.Floor>any());

        assertThatThrownBy(() -> cctvService.deleteCctv(cctvId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(com.saferoute.global.api.error.FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        verify(cctv, org.mockito.Mockito.never()).delete();
    }

    @Test
    @DisplayName("다른 기관 CCTV의 모든 변경 요청은 not-found로 거부한다")
    void mutations_otherSchool_throwNotFound() {
        UUID cctvId = UUID.randomUUID();
        List<ThrowingCallable> operations = List.of(
                () -> cctvService.configureGridCells(
                        cctvId,
                        new ConfigureCctvGridCellsRequest(List.of(UUID.randomUUID())),
                        EMAIL),
                () -> cctvService.updateCctv(
                        cctvId,
                        new UpdateCctvRequest("변경 이름", 0.4, 0.6),
                        EMAIL),
                () -> cctvService.enableCctv(cctvId, EMAIL),
                () -> cctvService.disableCctv(cctvId, EMAIL),
                () -> cctvService.deleteCctv(cctvId, EMAIL)
        );

        for (ThrowingCallable operation : operations) {
            assertThatThrownBy(operation)
                    .isInstanceOf(ApiException.class)
                    .extracting(exception -> ((ApiException) exception).getErrorCode())
                    .isEqualTo(CctvErrorCode.CCTV_NOT_FOUND);
        }

        verify(cctvGridCellRepository, never()).deleteAllByCctvId(any());
        verify(congestionConfigService, never()).incrementVersionForGridChange();
    }

    private CreateCctvRequest request() {
        return new CreateCctvRequest(
                "동쪽 복도 CCTV",
                UUID.randomUUID(),
                0.4,
                0.3,
                List.of(UUID.randomUUID())
        );
    }

    private Cctv cctv(UUID id) {
        Cctv cctv = org.mockito.Mockito.mock(Cctv.class);
        MapNode node = org.mockito.Mockito.mock(MapNode.class);
        Floor floor = org.mockito.Mockito.mock(Floor.class);
        org.mockito.Mockito.lenient().when(cctv.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(cctv.getCustomNode()).thenReturn(node);
        org.mockito.Mockito.lenient().when(node.getFloor()).thenReturn(floor);
        org.mockito.Mockito.lenient().when(floor.getGridCellSizeMeter()).thenReturn(0.5);
        return cctv;
    }

    private FloorGridCell cell(int row, int column) {
        FloorGridCell cell = org.mockito.Mockito.mock(FloorGridCell.class);
        org.mockito.Mockito.lenient().when(cell.getRowIndex()).thenReturn(row);
        org.mockito.Mockito.lenient().when(cell.getColumnIndex()).thenReturn(column);
        return cell;
    }

    private CctvGridCell mapping(Cctv cctv, FloorGridCell cell) {
        CctvGridCell mapping = org.mockito.Mockito.mock(CctvGridCell.class);
        given(mapping.getCctv()).willReturn(cctv);
        given(mapping.getGridCell()).willReturn(cell);
        return mapping;
    }
}
