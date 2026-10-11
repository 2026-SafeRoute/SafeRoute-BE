package com.saferoute.domain.training.dto;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "CCTV 한 대의 현재 혼잡 상태")
public record CurrentCctvStateResponse(
        @Schema(description = "CCTV ID", example = "67b86e33-7874-494c-855f-e591e7847c09")
        UUID cctvId,

        @Schema(description = "CCTV 고유 코드", example = "CCTV_001")
        String cctvCode,

        @Schema(description = "관리자가 지정한 CCTV 이름", example = "CAM-1")
        String cctvName,

        @Schema(description = "CCTV가 설치된 건물명", example = "A동")
        String buildingName,

        @Schema(description = "화면 표시용 층 이름", example = "3층")
        String floorName,

        @Schema(description = "건물명과 층 이름을 조합한 표시 위치", example = "A동 3층")
        String location,

        @Schema(
                description = "최근 관측 구간의 평균 인원. 상태가 없으면 null",
                example = "8.6",
                nullable = true
        )
        Double avgHeadcount,

        @Schema(
                description = "최근 관측 구간의 순간 최대 인원. 상태가 없으면 null",
                example = "12",
                nullable = true
        )
        Integer peakHeadcount,

        @Schema(description = "밀집도(㎡당 인원). 상태가 없으면 null", example = "0.42", nullable = true)
        Double density,

        @Schema(description = "혼잡 단계. 상태가 없으면 null", example = "CROWDED", nullable = true)
        CongestionLevel congestionLevel,

        @Schema(
                description = "상태를 마지막으로 관측한 시각(Unix epoch milliseconds). 상태가 없으면 null",
                example = "1787722095000",
                nullable = true
        )
        Long lastDetectedAt,

        @Schema(
                description = "stateStaleAfterSec를 초과해 오래됐거나 상태가 아직 없으면 true. "
                        + "true일 때는 congestionLevel 등을 NORMAL로 오인하지 말고 '정보 없음/오래됨'으로 표시해야 한다",
                example = "false"
        )
        boolean stale,

        @Schema(
                description = "상태가 저장될 때 적용된 혼잡 설정 버전. 상태가 없으면 null",
                example = "3",
                nullable = true
        )
        Long configVersion
) {

    public static CurrentCctvStateResponse withoutState(Cctv cctv) {
        Location location = Location.from(cctv);
        return new CurrentCctvStateResponse(
                cctv.getId(),
                cctv.getCode(),
                cctv.getName(),
                location.buildingName(),
                location.floorName(),
                location.displayName(),
                null,
                null,
                null,
                null,
                null,
                true,
                null
        );
    }

    public static CurrentCctvStateResponse withState(Cctv cctv, CurrentCctvStateItem item, boolean stale) {
        Location location = Location.from(cctv);
        return new CurrentCctvStateResponse(
                cctv.getId(),
                cctv.getCode(),
                cctv.getName(),
                location.buildingName(),
                location.floorName(),
                location.displayName(),
                item.getAvgHeadcount(),
                item.getPeakHeadcount(),
                item.getDensity(),
                item.getCongestionLevel(),
                item.getLastDetectedAt(),
                stale,
                item.getConfigVersion()
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
