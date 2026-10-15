package com.saferoute.domain.floor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.saferoute.domain.analysis.service.FloorAnalysisService;
import com.saferoute.domain.analysis.service.FloorAnalysisStatusService;
import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.floor.dto.request.UploadFloorRequest;
import com.saferoute.domain.floor.dto.response.FloorImageUrlResponse;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.entity.SegmentationStatus;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.AnalysisErrorCode;
import com.saferoute.global.api.error.BuildingErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.dto.PresignedGetUrl;
import com.saferoute.infrastructure.s3.dto.S3UploadResponse;
import com.saferoute.infrastructure.s3.service.S3PresignedUrlService;
import com.saferoute.infrastructure.s3.service.S3Service;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FloorServiceTest {

    private static final String EMAIL = "manager@saferoute.com";
    private static final String SCHOOL_NAME = "SafeRoute School";

    @InjectMocks
    private FloorService floorService;

    @Mock
    private FloorRepository floorRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private FloorAnalysisService floorAnalysisService;

    @Mock
    private FloorAnalysisStatusService floorAnalysisStatusService;

    @Mock
    private S3Service s3Service;

    @Mock
    private S3PresignedUrlService s3PresignedUrlService;

    @Mock
    private SchoolContextService schoolContextService;

    @Mock
    private MapNodeJpaRepository mapNodeRepository;

    @Mock
    private MapEdgeJpaRepository mapEdgeRepository;

    @Mock
    private FloorMapChangeGuard floorMapChangeGuard;

    @Test
    void hidesFloorsOfBuildingFromAnotherSchool() {
        UUID buildingId = UUID.randomUUID();
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> floorService.getFloors(buildingId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(BuildingErrorCode.BUILDING_NOT_FOUND);
    }

    @Test
    void hidesFloorAnalysisTargetFromAnotherSchool() {
        UUID floorId = UUID.randomUUID();
        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        willThrow(new ApiException(FloorErrorCode.FLOOR_NOT_FOUND))
                .given(floorAnalysisStatusService).markAsProcessing(floorId, SCHOOL_NAME);

        assertThatThrownBy(() -> floorService.requestAnalysis(floorId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.FLOOR_NOT_FOUND);
    }

    @Test
    void issuesFloorImageUrlWhenMapImageExists() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = mock(Building.class);
        Floor floor = mock(Floor.class);
        Instant expiresAt = Instant.now().plusSeconds(3600);

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(floorRepository.findByIdAndBuilding_Id(floorId, building.getId()))
                .willReturn(Optional.of(floor));
        given(floor.getMapImageKey()).willReturn("floors/map.png");
        given(s3PresignedUrlService.createGetUrl("floors/map.png"))
                .willReturn(new PresignedGetUrl("https://example.com/floors/map.png", expiresAt));

        FloorImageUrlResponse response = floorService.getFloorImageUrl(buildingId, floorId, EMAIL);

        assertThat(response.imageUrl()).isEqualTo("https://example.com/floors/map.png");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsFloorImageUrlWhenMapImageMissing() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = mock(Building.class);
        Floor floor = mock(Floor.class);

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(floorRepository.findByIdAndBuilding_Id(floorId, building.getId()))
                .willReturn(Optional.of(floor));
        given(floor.getMapImageKey()).willReturn(null);

        assertThatThrownBy(() -> floorService.getFloorImageUrl(buildingId, floorId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(FloorErrorCode.MAP_IMAGE_NOT_FOUND);
    }

    @Test
    void hidesFloorImageUrlTargetFromAnotherSchool() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> floorService.getFloorImageUrl(buildingId, floorId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(BuildingErrorCode.BUILDING_NOT_FOUND);
    }

    @Test
    void clearsFloorMapAndCascadesGraphDeletion() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = mock(Building.class);
        Floor floor = Floor.create(building, 1);
        floor.upload(3.0, 4.0, "floors/map.png");

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(floorRepository.findByIdAndBuilding_Id(floorId, building.getId()))
                .willReturn(Optional.of(floor));

        floorService.clearFloorMap(buildingId, floorId, EMAIL);

        then(mapEdgeRepository).should().deleteAllByFloor(floor);
        then(mapNodeRepository).should().deleteAllByFloor(floor);
        assertThat(floor.getMapImageKey()).isNull();
        assertThat(floor.getSegmentationStatus()).isEqualTo(SegmentationStatus.PENDING);
    }

    @Test
    void rejectsClearingFloorMapWhileAnalysisInProgress() {
        UUID buildingId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        Building building = mock(Building.class);
        Floor floor = Floor.create(building, 1);
        floor.updateSegmentationStatus(SegmentationStatus.PROCESSING);

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(floorRepository.findByIdAndBuilding_Id(floorId, building.getId()))
                .willReturn(Optional.of(floor));

        assertThatThrownBy(() -> floorService.clearFloorMap(buildingId, floorId, EMAIL))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(AnalysisErrorCode.ANALYSIS_ALREADY_IN_PROGRESS);

        then(mapEdgeRepository).should(never()).deleteAllByFloor(floor);
        then(mapNodeRepository).should(never()).deleteAllByFloor(floor);
    }

    @Test
    void cascadesExistingGraphDeletionOnReupload() {
        UUID buildingId = UUID.randomUUID();
        Building building = mock(Building.class);
        Floor floor = Floor.create(building, 1);
        UploadFloorRequest request = new UploadFloorRequest(
                1, 4.0, 3.0,
                new MockMultipartFile("file", "plan.png", "image/png", new byte[]{1, 2, 3}));

        given(schoolContextService.getSchoolName(EMAIL)).willReturn(SCHOOL_NAME);
        given(buildingRepository.findByIdAndSchoolName(buildingId, SCHOOL_NAME))
                .willReturn(Optional.of(building));
        given(building.getId()).willReturn(buildingId);
        given(floorRepository.findByBuilding_IdAndFloorNum(buildingId, 1))
                .willReturn(Optional.of(floor));
        given(s3Service.upload(request.file()))
                .willReturn(new S3UploadResponse("bucket", "floors/new-plan.png", "s3://bucket/floors/new-plan.png",
                        3L, "image/png"));

        floorService.uploadFloor(buildingId, request, EMAIL);

        then(mapEdgeRepository).should().deleteAllByFloor(floor);
        then(mapNodeRepository).should().deleteAllByFloor(floor);
        assertThat(floor.getMapImageKey()).isEqualTo("floors/new-plan.png");
        assertThat(floor.getRealWidth()).isEqualTo(4.0);
        assertThat(floor.getRealHeight()).isEqualTo(3.0);
    }
}
