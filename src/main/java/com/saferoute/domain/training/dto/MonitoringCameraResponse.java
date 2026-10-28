package com.saferoute.domain.training.dto;

import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.infrastructure.s3.dto.PresignedGetUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "훈련 모니터링 화면의 카메라 카드")
public record MonitoringCameraResponse(
        @Schema(description = "CCTV ID", example = "67b86e33-7874-494c-855f-e591e7847c09")
        UUID cctvId,

        @Schema(description = "CCTV 고유 코드", example = "CCTV_001")
        String code,

        @Schema(description = "관리자가 지정한 CCTV 이름", example = "CAM-1")
        String name,

        @Schema(description = "CCTV가 설치된 건물명", example = "A동")
        String buildingName,

        @Schema(description = "화면 표시용 층 이름", example = "3층")
        String floorName,

        @Schema(description = "건물명과 층 이름을 조합한 표시 위치", example = "A동 3층")
        String location,

        @Schema(
                description = "최신 캡처 이미지의 S3 presigned GET URL. 캡처가 없으면 null",
                example = "https://example-bucket.s3.amazonaws.com/training/session/monitoring/CCTV_001/frame.jpg",
                nullable = true
        )
        String thumbnailUrl,

        @Schema(
                description = "최신 프레임 캡처 시각(Unix epoch milliseconds). 캡처가 없으면 null",
                example = "1787722095000",
                nullable = true
        )
        Long capturedAt,

        @Schema(
                description = "thumbnailUrl 만료 시각(Unix epoch milliseconds). 캡처가 없으면 null",
                example = "1787725695000",
                nullable = true
        )
        Long urlExpiresAt,

        @Schema(
                description = "마지막 캡처를 서버가 받은 뒤 stateStaleAfterSec가 지났거나 캡처가 아직 없으면 true. "
                        + "true면 thumbnailUrl을 라이브 화면이 아니라 '끊김/오래된 이미지'로 표시해야 한다",
                example = "false"
        )
        boolean stale,

        @Schema(
                description = "최신 프레임 관측값을 서버가 받은 시각(Unix epoch milliseconds). capturedAt은 Pi 시계라 "
                        + "지연 측정은 이 값을 기준으로 한다. 캡처가 없거나 예전 데이터면 null",
                example = "1787722096200",
                nullable = true
        )
        Long receivedAt
) {

    public static MonitoringCameraResponse withoutCapture(Cctv cctv) {
        Location location = Location.from(cctv);
        return new MonitoringCameraResponse(
                cctv.getId(),
                cctv.getCode(),
                cctv.getName(),
                location.buildingName(),
                location.floorName(),
                location.displayName(),
                null,
                null,
                null,
                true,
                null
        );
    }

    public static MonitoringCameraResponse withCapture(
            Cctv cctv,
            long capturedAt,
            Long receivedAt,
            PresignedGetUrl presignedGetUrl,
            boolean stale
    ) {
        Location location = Location.from(cctv);
        return new MonitoringCameraResponse(
                cctv.getId(),
                cctv.getCode(),
                cctv.getName(),
                location.buildingName(),
                location.floorName(),
                location.displayName(),
                presignedGetUrl.viewUrl(),
                capturedAt,
                presignedGetUrl.expiresAt().toEpochMilli(),
                stale,
                receivedAt
        );
    }

    private record Location(String buildingName, String floorName, String displayName) {

        private static Location from(Cctv cctv) {
            Floor floor = cctv.getCustomNode().getFloor();
            String buildingName = floor.getBuilding().getName();
            String floorName = formatFloorName(floor.getFloorNum());
            return new Location(buildingName, floorName, buildingName + " " + floorName);
        }

        private static String formatFloorName(int floorNum) {
            if (floorNum < 0) {
                return "지하 " + Math.abs((long) floorNum) + "층";
            }
            return floorNum + "층";
        }
    }
}
