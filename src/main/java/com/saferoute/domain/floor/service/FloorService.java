package com.saferoute.domain.floor.service;

import com.saferoute.domain.analysis.service.FloorAnalysisService;
import com.saferoute.domain.analysis.service.FloorAnalysisStatusService;
import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.floor.dto.request.CreateFloorRequest;
import com.saferoute.domain.floor.dto.request.UpdateFloorRequest;
import com.saferoute.domain.floor.dto.request.UploadFloorRequest;
import com.saferoute.domain.floor.dto.response.FloorImageUrlResponse;
import com.saferoute.domain.floor.dto.response.FloorResponse;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.entity.SegmentationStatus;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.global.api.error.AnalysisErrorCode;
import java.util.List;
import java.util.UUID;

import com.saferoute.global.api.error.BuildingErrorCode;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.dto.S3UploadResponse;
import com.saferoute.infrastructure.s3.service.S3PresignedUrlService;
import com.saferoute.infrastructure.s3.service.S3Service;
import com.saferoute.domain.user.service.SchoolContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FloorService {

    private final FloorRepository floorRepository;
    private final BuildingRepository buildingRepository;
    private final FloorAnalysisService floorAnalysisService;
    private final FloorAnalysisStatusService floorAnalysisStatusService;
    private final S3Service s3Service;
    private final S3PresignedUrlService s3PresignedUrlService;
    private final SchoolContextService schoolContextService;
    private final MapNodeJpaRepository mapNodeRepository;
    private final MapEdgeJpaRepository mapEdgeRepository;
    private final FloorMapChangeGuard floorMapChangeGuard;

    public List<FloorResponse> getFloors(UUID buildingId, String email) {
        findBuilding(buildingId, email);
        return floorRepository.findByBuilding_IdOrderByFloorNumAsc(buildingId).stream()
                .map(FloorResponse::from)
                .toList();
    }

    @Transactional
    public FloorResponse createFloor(
            UUID buildingId,
            CreateFloorRequest request,
            String email
    ) {
        Building building = findBuilding(buildingId, email);

        validateDuplicateFloorNum(buildingId, request.floorNum());

        Floor floor = Floor.create(
                building,
                request.floorNum()
        );
        building.addFloor(request.floorNum());

        return FloorResponse.from(floorRepository.save(floor));
    }

    @Transactional
    public FloorResponse uploadFloor(UUID buildingId, UploadFloorRequest request, String email){
        Building building = findBuilding(buildingId, email);
        floorMapChangeGuard.validateNoRunningTraining(building.getId());
        Floor floor = floorRepository.findByBuilding_IdAndFloorNum(building.getId(), request.floorNum())
            .orElseThrow(() ->
                new ApiException(FloorErrorCode.FLOOR_NOT_FOUND)
            );

        // 재업로드는 기존 도면에 딸린 그래프를 무효화한다 (프론트 재업로드 확인 문구와 동일한 동작 보장)
        mapEdgeRepository.deleteAllByFloor(floor);
        mapNodeRepository.deleteAllByFloor(floor);

        S3UploadResponse uploadResult =
            s3Service.upload(request.file());

        floor.upload(request.realHeight(), request.realWidth(), uploadResult.key());

        return FloorResponse.from(floor);
    }

    @Transactional
    public FloorResponse updateFloor(
            UUID buildingId, UUID floorId, UpdateFloorRequest request, String email) {
        Floor floor = findFloor(buildingId, floorId, email);

        if (!floor.getFloorNum().equals(request.floorNum())) {
            validateDuplicateFloorNum(buildingId, request.floorNum());
            floor.getBuilding().removeFloor(floor.getFloorNum());
            floor.getBuilding().addFloor(request.floorNum());
        }

        floor.updateFloorNum(request.floorNum());

        return FloorResponse.from(floor);
    }

    public void requestAnalysis(UUID floorId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        // 분석 결과 반영이 기존 그래프를 지우고 새로 만들므로, 비동기 분석을 시작하기 전에 막는다.
        // 층이 없거나 다른 기관이면 아래 markAsProcessing이 기존대로 FLOOR_NOT_FOUND를 던진다.
        floorRepository.findByIdAndBuilding_SchoolName(floorId, schoolName)
                .ifPresent(floor -> floorMapChangeGuard.validateNoRunningTraining(floor.getBuilding().getId()));
        floorAnalysisStatusService.markAsProcessing(floorId, schoolName);
        floorAnalysisService.analyzeFloor(floorId);
    }


    @Transactional(readOnly = true)
    public FloorResponse getFloor(UUID buildingId, UUID floorId, String email) {
        return FloorResponse.from(findFloor(buildingId, floorId, email));
    }

    @Transactional(readOnly = true)
    public FloorImageUrlResponse getFloorImageUrl(UUID buildingId, UUID floorId, String email) {
        Floor floor = findFloor(buildingId, floorId, email);

        if (floor.getMapImageKey() == null) {
            throw new ApiException(FloorErrorCode.MAP_IMAGE_NOT_FOUND);
        }

        return FloorImageUrlResponse.from(s3PresignedUrlService.createGetUrl(floor.getMapImageKey()));
    }

    @Transactional
    public void deleteFloor(UUID buildingId, UUID floorId, String email) {
        Floor floor = findFloor(buildingId, floorId, email);
        floor.getBuilding().removeFloor(floor.getFloorNum());
        floorRepository.delete(floor);
    }

    // 도면 초기화: 이미지·세그멘테이션·그리드·노드·엣지만 지우고 층(Floor row)과 층수 카운트는 유지
    @Transactional
    public FloorResponse clearFloorMap(UUID buildingId, UUID floorId, String email) {
        Floor floor = findFloor(buildingId, floorId, email);
        floorMapChangeGuard.validateNoRunningTraining(floor.getBuilding().getId());

        if (floor.getSegmentationStatus() == SegmentationStatus.PROCESSING) {
            throw new ApiException(AnalysisErrorCode.ANALYSIS_ALREADY_IN_PROGRESS);
        }

        mapEdgeRepository.deleteAllByFloor(floor);
        mapNodeRepository.deleteAllByFloor(floor);
        floor.clearMap();

        return FloorResponse.from(floor);
    }

    private Building findBuilding(UUID buildingId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        return buildingRepository.findByIdAndSchoolName(buildingId, schoolName)
                .orElseThrow(() -> new ApiException(BuildingErrorCode.BUILDING_NOT_FOUND));
    }

    private void validateDuplicateFloorNum(UUID buildingId, Integer floorNum) {
        if (floorRepository.existsByBuilding_IdAndFloorNum(buildingId, floorNum)) {
            throw new ApiException(FloorErrorCode.DUPLICATE_FLOOR_NUM);
        }
    }

    private Floor findFloor(UUID buildingId, UUID floorId, String email) {
        Building building = findBuilding(buildingId, email);
        return floorRepository.findByIdAndBuilding_Id(floorId, building.getId())
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
    }
}
