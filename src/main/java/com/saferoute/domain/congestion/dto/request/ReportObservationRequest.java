package com.saferoute.domain.congestion.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

// Pi가 snapshotIntervalSec(기본 1초)마다 보내는 관측값. frameHeadcount는 이미지 Snapshot의 인원이며,
// edgeId/density/congestionLevel은 Pi가 보내지 않는다 - BE가 직접 계산한다.
public record ReportObservationRequest(
        @NotNull UUID eventId,
        @NotNull UUID trainingSessionId,
        @NotBlank String cctvCode,
        @NotNull @PositiveOrZero Double avgHeadcount,
        @NotNull @PositiveOrZero Integer peakHeadcount,
        @PositiveOrZero Integer frameHeadcount,
        @NotNull @Positive Integer sampleCount,
        @NotNull @PositiveOrZero Long windowStart,
        @NotNull @PositiveOrZero Long windowEnd,
        @NotNull @PositiveOrZero Long capturedAt,
        @NotNull @Positive Long configVersion,
        String monitoringImageKey
) {
    @AssertTrue(message = "avgHeadcount must not exceed peakHeadcount")
    public boolean isHeadcountValid() {
        if (avgHeadcount == null || peakHeadcount == null) {
            return true;
        }
        return avgHeadcount <= peakHeadcount.doubleValue();
    }

    @AssertTrue(message = "frameHeadcount must not exceed peakHeadcount")
    public boolean isFrameHeadcountValid() {
        if (frameHeadcount == null || peakHeadcount == null) {
            return true;
        }
        return frameHeadcount <= peakHeadcount;
    }

    @AssertTrue(message = "windowEnd must not be before windowStart")
    public boolean isWindowValid() {
        if (windowStart == null || windowEnd == null) {
            return true;
        }
        return windowEnd >= windowStart;
    }

    @AssertTrue(message = "capturedAt must not be before windowStart")
    public boolean isCapturedAtValid() {
        if (capturedAt == null || windowStart == null) {
            return true;
        }
        return capturedAt >= windowStart;
    }
}
