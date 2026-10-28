package com.saferoute.domain.telemetry.dynamo.entity;

import java.util.UUID;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class LatestMonitoringCaptureItem {

    private String pk;
    private String sk;
    private String trainingSessionId;
    private String cctvCode;
    private Long capturedAt;
    // BE가 이 캡처 관측값을 받은 서버 시각. "더 최신 캡처인지" 비교는 Pi 시계(capturedAt)가 아니라 이 값으로
    // 한다 - Pi 시계가 뒤로 보정되면 이후 캡처가 전부 "오래됨"으로 거부돼 화면이 멈추기 때문이다.
    private Long receivedAt;
    private String monitoringImageKey;
    private Long expiresAt;

    public LatestMonitoringCaptureItem() {
    }

    // receivedAt 없이 만드는 예전 계약(테스트/기존 데이터 호환용).
    public static LatestMonitoringCaptureItem create(
            UUID trainingSessionId,
            String cctvCode,
            long capturedAt,
            String monitoringImageKey
    ) {
        return create(trainingSessionId, cctvCode, capturedAt, null, monitoringImageKey);
    }

    public static LatestMonitoringCaptureItem create(
            UUID trainingSessionId,
            String cctvCode,
            long capturedAt,
            Long receivedAt,
            String monitoringImageKey
    ) {
        LatestMonitoringCaptureItem item = new LatestMonitoringCaptureItem();
        item.receivedAt = receivedAt;
        item.trainingSessionId = trainingSessionId.toString();
        item.cctvCode = cctvCode;
        item.capturedAt = capturedAt;
        item.monitoringImageKey = monitoringImageKey;
        item.expiresAt = Math.floorDiv(capturedAt, 1_000L) + ObservationItem.TTL_SECONDS;
        item.pk = buildPk(item.trainingSessionId);
        item.sk = buildSk(cctvCode);
        return item;
    }

    public static String buildPk(String trainingSessionId) {
        return "LATEST_MONITORING#" + trainingSessionId;
    }

    public static String buildSk(String cctvCode) {
        return "CCTV#" + cctvCode;
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

    public String getTrainingSessionId() {
        return trainingSessionId;
    }

    public void setTrainingSessionId(String trainingSessionId) {
        this.trainingSessionId = trainingSessionId;
    }

    public String getCctvCode() {
        return cctvCode;
    }

    public void setCctvCode(String cctvCode) {
        this.cctvCode = cctvCode;
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

    public Long getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Long receivedAt) {
        this.receivedAt = receivedAt;
    }

    // 서버 시각 기준 판정에 쓸 시각. receivedAt이 없는 예전 데이터는 capturedAt으로 대신한다.
    public Long effectiveReceivedAt() {
        return receivedAt != null ? receivedAt : capturedAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
