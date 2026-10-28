package com.saferoute.infrastructure.websocket.dto;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// 프론트가 카드 단위로 갱신 대상을 식별할 수 있도록 eventId/cctvCode/capturedAt/hasMonitoringImage를 포함한다 (이슈 #142).
// CCTV당 1 Observation = 1 이벤트 발행 원칙을 지키기 위해 영향받는 Edge 전체를 affectedEdgeIds 배열로 담는다
// (Edge가 없으면 빈 배열이며 null을 섞어 쓰지 않는다). 배열 안 Edge ID는 중복 없이 담는다 (이슈 #192).
// REST current-states 응답과 필드명을 맞춰 density/configVersion을 함께 포함한다. capturedAt은 REST의
// lastDetectedAt에 대응한다 - REST 호환성을 위해 필드명 자체는 맞추지 않고 대응 관계만 문서화한다 (이슈 #192).
public record CongestionEventData(
        UUID eventId,
        List<UUID> affectedEdgeIds,
        String cctvCode,
        Double avgHeadcount,
        Integer peakHeadcount,
        Double density,
        CongestionLevel congestionLevel,
        Long windowStart,
        Long windowEnd,
        Long capturedAt,
        Long configVersion,
        // 이미지는 presigned URL 만료 문제로 WebSocket payload에 직접 포함하지 않는다.
        // true면 프론트가 카메라/프레임 조회 REST API를 재호출해서 새 presigned URL을 받아야 한다.
        // REST의 frameId와 이 payload의 eventId를 비교하면 같은 프레임인지 확인할 수 있다 (이슈 #192).
        // BE가 S3에 객체가 있는지 확인한 뒤 세우므로(CongestionObservationService) true면 바로 조회해도 된다.
        boolean hasMonitoringImage,
        // 지연 구간 분리용 서버 시각(Unix epoch ms). capturedAt(Pi 시계)->receivedAt은 Pi 추론+S3 업로드+전송,
        // receivedAt->publishedAt은 BE 처리, publishedAt->프론트 수신은 WebSocket 구간이다.
        Long receivedAt,
        Long publishedAt
) {

    public static CongestionEventData from(List<UUID> affectedEdgeIds, ObservationItem item) {
        return new CongestionEventData(
                UUID.fromString(item.getEventId()),
                affectedEdgeIds.stream().distinct().toList(),
                item.getCctvCode(),
                item.getAvgHeadcount(),
                item.getPeakHeadcount(),
                item.getDensity(),
                item.getCongestionLevel(),
                item.getWindowStart(),
                item.getWindowEnd(),
                item.getCapturedAt(),
                item.getConfigVersion(),
                item.getMonitoringImageKey() != null && !item.getMonitoringImageKey().isBlank(),
                item.getReceivedAt(),
                Instant.now().toEpochMilli()
        );
    }
}
