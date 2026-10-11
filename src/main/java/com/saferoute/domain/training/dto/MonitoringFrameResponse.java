package com.saferoute.domain.training.dto;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.infrastructure.s3.dto.PresignedGetUrl;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상세 모니터링 화면의 프레임 한 장")
public record MonitoringFrameResponse(
        @Schema(description = "프레임(Observation) ID", example = "3c9f7e2a-3b39-4f0a-9f0a-6a2b6b1f5a11")
        String frameId,

        @Schema(description = "프레임 캡처 시각(Unix epoch milliseconds)", example = "1787722095000")
        long capturedAt,

        @Schema(
                description = "이 프레임이 속한 분석 구간의 시작 시각(Unix epoch milliseconds)",
                example = "1787722094000"
        )
        long windowStart,

        @Schema(
                description = "이 프레임이 속한 분석 구간의 종료 시각(Unix epoch milliseconds)",
                example = "1787722095000"
        )
        long windowEnd,

        @Schema(
                description = "프레임 이미지의 S3 presigned GET URL. 이미지 업로드가 아직 끝나지 않았으면 null",
                example = "https://example-bucket.s3.amazonaws.com/training/session/monitoring/CCTV_001/frame.jpg",
                nullable = true
        )
        String imageUrl,

        @Schema(
                description = "imageUrl 만료 시각(Unix epoch milliseconds). imageUrl이 없으면 null",
                example = "1787725695000",
                nullable = true
        )
        Long urlExpiresAt,

        @Schema(description = "이미지 Snapshot의 인원수", example = "12", nullable = true)
        Integer headcount,

        @Schema(description = "이미지 Snapshot 인원수 기준 밀집도", example = "0.42", nullable = true)
        Double density,

        @Schema(description = "이미지 Snapshot 밀집도 기준 혼잡 단계", example = "CROWDED", nullable = true)
        CongestionLevel congestionLevel
) {

    public static MonitoringFrameResponse withoutImage(ObservationItem item) {
        return new MonitoringFrameResponse(
                item.getEventId(),
                item.getCapturedAt(),
                item.getWindowStart(),
                item.getWindowEnd(),
                null,
                null,
                frameHeadcount(item),
                frameDensity(item),
                frameCongestionLevel(item)
        );
    }

    public static MonitoringFrameResponse withImage(ObservationItem item, PresignedGetUrl presignedGetUrl) {
        return new MonitoringFrameResponse(
                item.getEventId(),
                item.getCapturedAt(),
                item.getWindowStart(),
                item.getWindowEnd(),
                presignedGetUrl.viewUrl(),
                presignedGetUrl.expiresAt().toEpochMilli(),
                frameHeadcount(item),
                frameDensity(item),
                frameCongestionLevel(item)
        );
    }

    private static Integer frameHeadcount(ObservationItem item) {
        return item.getFrameHeadcount() != null ? item.getFrameHeadcount() : item.getPeakHeadcount();
    }

    private static Double frameDensity(ObservationItem item) {
        return item.getFrameDensity() != null ? item.getFrameDensity() : item.getDensity();
    }

    private static CongestionLevel frameCongestionLevel(ObservationItem item) {
        return item.getFrameCongestionLevel() != null
                ? item.getFrameCongestionLevel()
                : item.getCongestionLevel();
    }
}
