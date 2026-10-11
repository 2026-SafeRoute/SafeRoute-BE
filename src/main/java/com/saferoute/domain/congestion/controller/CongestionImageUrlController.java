package com.saferoute.domain.congestion.controller;

import com.saferoute.domain.congestion.dto.response.CongestionImageUrlResponse;
import com.saferoute.domain.congestion.service.CongestionImageUrlService;
import com.saferoute.global.api.response.ApiResponse;
import com.saferoute.global.api.response.CongestionSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "혼잡 이미지", description = "관리자 화면의 혼잡 이미지 조회 URL 발급 API")
@RestController
@RequiredArgsConstructor
public class CongestionImageUrlController {

    private final CongestionImageUrlService congestionImageUrlService;

    @Operation(
            summary = "혼잡 이벤트 이미지 조회 URL 발급",
            description = """
                    관리자 화면에서 혼잡 이벤트(CongestionEventItem)에 연결된 이미지를 열람하기 위한
                    S3 presigned GET URL을 발급합니다. S3 버킷이 비공개이므로 object key만으로는
                    브라우저에서 바로 열 수 없어, 열람할 때마다 이 API로 URL을 새로 받아야 합니다.

                    이벤트의 이미지 업로드 상태(imageUploadStatus)가 COMPLETED이고 eventImageKey가
                    존재할 때만 성공합니다. 아직 이미지가 연결되지 않았거나(PENDING) 업로드가
                    실패한(FAILED) 이벤트를 조회하면 이미지를 찾을 수 없다는 오류가 됩니다.

                    응답의 imageUrl은 expiresAt 이후 만료되는 임시 URL이므로 화면에 오래 캐시하지
                    말고, 만료 전에 다시 호출해 새 URL을 받아야 합니다. eventId가 속한 훈련
                    세션이 요청자와 다른 학교 소속이면 조회할 수 없습니다.
                    """
    )
    @GetMapping("/api/v1/congestion-events/{eventId}/image-url")
    public ResponseEntity<ApiResponse<CongestionImageUrlResponse>> getEventImageUrl(
            @PathVariable UUID eventId,
            Authentication authentication) {
        CongestionImageUrlResponse response =
                congestionImageUrlService.getEventImageUrl(eventId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(CongestionSuccessCode.CONGESTION_IMAGE_URL_ISSUED, response));
    }

    @Operation(
            summary = "혼잡 관측값 이미지 조회 URL 발급",
            description = """
                    관리자 화면에서 주기 혼잡 관측값(ObservationItem)에 연결된 모니터링
                    이미지를 열람하기 위한 S3 presigned GET URL을 발급합니다. 동작 방식은 혼잡
                    이벤트 이미지 조회 URL 발급 API와 동일하며, 대상 리소스만 관측값입니다.

                    경로의 {eventId}는 혼잡 이벤트가 아니라 관측값(Observation)의 eventId를
                    가리킵니다. 관측값에 monitoringImageKey가 없으면(Pi가 이미지 없이 관측값만
                    보낸 경우) 이미지를 찾을 수 없다는 오류가 됩니다.

                    응답의 imageUrl은 expiresAt 이후 만료되는 임시 URL이므로 화면에 오래 캐시하지
                    말고, 만료 전에 다시 호출해 새 URL을 받아야 합니다. eventId가 속한 훈련
                    세션이 요청자와 다른 학교 소속이면 조회할 수 없습니다.
                    """
    )
    @GetMapping("/api/v1/congestion-observations/{eventId}/image-url")
    public ResponseEntity<ApiResponse<CongestionImageUrlResponse>> getObservationImageUrl(
            @PathVariable UUID eventId,
            Authentication authentication
    ) {
        CongestionImageUrlResponse response =
                congestionImageUrlService.getObservationImageUrl(eventId, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(CongestionSuccessCode.CONGESTION_IMAGE_URL_ISSUED, response));
    }
}
