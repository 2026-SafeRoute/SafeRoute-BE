package com.saferoute.domain.telemetry.dynamo.entity;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import java.util.Locale;
import java.util.UUID;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class ObservationItem {

    public static final String GSI1_NAME = "GSI1";
    public static final long TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final int GSI1_TIMESTAMP_WIDTH = 19;

    private String pk;
    private String sk;
    private String gsi1Pk;
    private String gsi1Sk;
    private String eventId;
    private String trainingSessionId;
    private String edgeId;
    private String cctvCode;
    private Double avgHeadcount;
    private Integer peakHeadcount;
    private Integer frameHeadcount;
    private Integer sampleCount;
    private Double density;
    private CongestionLevel congestionLevel;
    private Double frameDensity;
    private CongestionLevel frameCongestionLevel;
    private Long windowStart;
    private Long windowEnd;
    private Long capturedAt;
    private String monitoringImageKey;
    private String eventImageKey;
    private Long imageUploadedAt;
    private ImageUploadStatus imageUploadStatus;
    private Long configVersion;
    private Long expiresAt;
    private EventProcessingStatus eventStatus;
    private Long processingStartedAt;
    private Long processingExpiresAt;
    private String processingOwner;
    // BE가 이 관측값을 받은 서버 시각. capturedAt은 Pi 시계라 신선도/순서 판정에 그대로 쓰면 Pi 시계가
    // 어긋났을 때 전부 틀어지므로, 서버 쪽 판정은 이 값을 기준으로 한다. 예전 데이터에는 없을 수 있다(null).
    private Long receivedAt;

    public ObservationItem() {
    }

    // frame 필드가 저장되기 전의 기존 데이터/테스트 생성 계약을 유지한다.
    public static ObservationItem create(
            UUID eventId,
            UUID trainingSessionId,
            UUID edgeId,
            String cctvCode,
            Double avgHeadcount,
            Integer peakHeadcount,
            Integer sampleCount,
            Double density,
            CongestionLevel congestionLevel,
            long windowStart,
            long windowEnd,
            long capturedAt,
            String monitoringImageKey,
            long configVersion
    ) {
        return create(
                eventId, trainingSessionId, edgeId, cctvCode,
                avgHeadcount, peakHeadcount, null, sampleCount,
                density, congestionLevel, null, null,
                windowStart, windowEnd, capturedAt, monitoringImageKey, configVersion
        );
    }

    public static ObservationItem create(
            UUID eventId,
            UUID trainingSessionId,
            UUID edgeId,
            String cctvCode,
            Double avgHeadcount,
            Integer peakHeadcount,
            Integer frameHeadcount,
            Integer sampleCount,
            Double density,
            CongestionLevel congestionLevel,
            Double frameDensity,
            CongestionLevel frameCongestionLevel,
            long windowStart,
            long windowEnd,
            long capturedAt,
            String monitoringImageKey,
            long configVersion
    ) {
        ObservationItem item = new ObservationItem();
        item.eventId = eventId.toString();
        item.trainingSessionId = trainingSessionId.toString();
        // 관측값 하나가 여러 MapEdge에 걸칠 수 있어(CCTV 1개가 여러 Edge를 감시) 특정 Edge로 고정할 수 없다.
        // 영향받는 Edge 목록은 저장 시점이 아니라 조회 시점에 CCTV -> GridCell -> MapEdge로 다시 계산한다.
        item.edgeId = edgeId != null ? edgeId.toString() : null;
        item.cctvCode = cctvCode;
        item.avgHeadcount = avgHeadcount;
        item.peakHeadcount = peakHeadcount;
        item.frameHeadcount = frameHeadcount;
        item.sampleCount = sampleCount;
        item.density = density;
        item.congestionLevel = congestionLevel;
        item.frameDensity = frameDensity;
        item.frameCongestionLevel = frameCongestionLevel;
        item.windowStart = windowStart;
        item.windowEnd = windowEnd;
        item.capturedAt = capturedAt;
        item.monitoringImageKey = monitoringImageKey;
        item.imageUploadStatus = ImageUploadStatus.PENDING;
        item.configVersion = configVersion;
        item.expiresAt = Math.floorDiv(capturedAt, 1_000L) + TTL_SECONDS;
        item.eventStatus = EventProcessingStatus.RECEIVED;
        item.pk = buildPk(item.eventId);
        item.sk = "META";
        item.gsi1Pk = buildGsi1Pk(item.trainingSessionId, cctvCode);
        item.gsi1Sk = buildGsi1Sk(capturedAt, item.eventId);
        return item;
    }

    public static String buildPk(String eventId) {
        return "OBSERVATION#" + eventId;
    }

    public static String buildGsi1Pk(String trainingSessionId, String cctvCode) {
        return "SESSION#" + trainingSessionId + "#CCTV#" + cctvCode;
    }

    // capturedAt만으로는 같은 캡처 시각을 가진 프레임이 같은 정렬 키를 갖게 되어
    // 페이지 경계에서 sortLessThan 커서 조회 시 프레임이 누락될 수 있다.
    // eventId를 tie-breaker로 덧붙여 정렬 키를 항상 고유하게 만든다.
    public static String buildGsi1Sk(long capturedAt, String eventId) {
        return "TIME#" + String.format(
                Locale.ROOT,
                "%0" + GSI1_TIMESTAMP_WIDTH + "d",
                capturedAt
        ) + "#" + eventId;
    }

    @DynamoDbPartitionKey
    public String getPk() {
        return pk;
    }

    public void setPk(String pk) {
        this.pk = pk;
    }

    @DynamoDbSortKey
    public String getSk() {
        return sk;
    }

    public void setSk(String sk) {
        this.sk = sk;
    }

    @DynamoDbAttribute("GSI1_PK")
    @DynamoDbSecondaryPartitionKey(indexNames = GSI1_NAME)
    public String getGsi1Pk() {
        return gsi1Pk;
    }

    public void setGsi1Pk(String gsi1Pk) {
        this.gsi1Pk = gsi1Pk;
    }

    @DynamoDbAttribute("GSI1_SK")
    @DynamoDbSecondarySortKey(indexNames = GSI1_NAME)
    public String getGsi1Sk() {
        return gsi1Sk;
    }

    public void setGsi1Sk(String gsi1Sk) {
        this.gsi1Sk = gsi1Sk;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTrainingSessionId() {
        return trainingSessionId;
    }

    public void setTrainingSessionId(String trainingSessionId) {
        this.trainingSessionId = trainingSessionId;
    }

    public String getEdgeId() {
        return edgeId;
    }

    public void setEdgeId(String edgeId) {
        this.edgeId = edgeId;
    }

    public String getCctvCode() {
        return cctvCode;
    }

    public void setCctvCode(String cctvCode) {
        this.cctvCode = cctvCode;
    }

    public Double getAvgHeadcount() {
        return avgHeadcount;
    }

    public void setAvgHeadcount(Double avgHeadcount) {
        this.avgHeadcount = avgHeadcount;
    }

    public Integer getPeakHeadcount() {
        return peakHeadcount;
    }

    public void setPeakHeadcount(Integer peakHeadcount) {
        this.peakHeadcount = peakHeadcount;
    }

    public Integer getFrameHeadcount() {
        return frameHeadcount;
    }

    public void setFrameHeadcount(Integer frameHeadcount) {
        this.frameHeadcount = frameHeadcount;
    }

    public Integer getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(Integer sampleCount) {
        this.sampleCount = sampleCount;
    }

    public Double getDensity() {
        return density;
    }

    public void setDensity(Double density) {
        this.density = density;
    }

    public CongestionLevel getCongestionLevel() {
        return congestionLevel;
    }

    public void setCongestionLevel(CongestionLevel congestionLevel) {
        this.congestionLevel = congestionLevel;
    }

    public Double getFrameDensity() {
        return frameDensity;
    }

    public void setFrameDensity(Double frameDensity) {
        this.frameDensity = frameDensity;
    }

    public CongestionLevel getFrameCongestionLevel() {
        return frameCongestionLevel;
    }

    public void setFrameCongestionLevel(CongestionLevel frameCongestionLevel) {
        this.frameCongestionLevel = frameCongestionLevel;
    }

    public Long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Long windowStart) {
        this.windowStart = windowStart;
    }

    public Long getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Long windowEnd) {
        this.windowEnd = windowEnd;
    }

    public Long getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Long capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getMonitoringImageKey() {
        return monitoringImageKey;
    }

    public void setMonitoringImageKey(String monitoringImageKey) {
        this.monitoringImageKey = monitoringImageKey;
    }

    public String getEventImageKey() {
        return eventImageKey;
    }

    public void setEventImageKey(String eventImageKey) {
        this.eventImageKey = eventImageKey;
    }

    public Long getImageUploadedAt() {
        return imageUploadedAt;
    }

    public void setImageUploadedAt(Long imageUploadedAt) {
        this.imageUploadedAt = imageUploadedAt;
    }

    public ImageUploadStatus getImageUploadStatus() {
        return imageUploadStatus;
    }

    public void setImageUploadStatus(ImageUploadStatus imageUploadStatus) {
        this.imageUploadStatus = imageUploadStatus;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public EventProcessingStatus getEventStatus() {
        return eventStatus;
    }

    public void setEventStatus(EventProcessingStatus eventStatus) {
        this.eventStatus = eventStatus;
    }

    public Long getProcessingStartedAt() {
        return processingStartedAt;
    }

    public void setProcessingStartedAt(Long processingStartedAt) {
        this.processingStartedAt = processingStartedAt;
    }

    public Long getProcessingExpiresAt() {
        return processingExpiresAt;
    }

    public void setProcessingExpiresAt(Long processingExpiresAt) {
        this.processingExpiresAt = processingExpiresAt;
    }

    public String getProcessingOwner() {
        return processingOwner;
    }

    public void setProcessingOwner(String processingOwner) {
        this.processingOwner = processingOwner;
    }

    public Long getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Long receivedAt) {
        this.receivedAt = receivedAt;
    }

    // 서버 시각 기준 판정에 쓸 시각. receivedAt이 없는 예전 데이터는 capturedAt으로 대신한다.
    // (get으로 시작하면 DynamoDB Enhanced Client가 속성으로 오인하므로 이름을 달리 둔다.)
    public long effectiveReceivedAt() {
        return receivedAt != null ? receivedAt : capturedAt;
    }
}
