package com.saferoute.domain.telemetry.dynamo.entity;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

// 세션+CCTV별로 가장 최근에 받은 즉시 혼잡 이벤트(CongestionEventItem) 하나만 들고 있는 아이템.
// 경로 가중치 계산(CurrentCongestionWeightProvider)이 관측 주기마다 "지금 끝나지 않은 혼잡"을 읽어야 하는데,
// 이벤트 원본은 GSI1(최종 일관성)으로만 세션 단위 조회가 되고 다른 아이템 타입과 파티션을 공유해 매번
// 파티션 전체를 읽게 된다(#293). 그래서 CurrentCctvStateItem처럼 기본 테이블에 CCTV당 한 건을 두고
// 강한 일관성 Query로 읽는다.
@DynamoDbBean
public class CurrentCongestionEventItem {

    private String pk;
    private String sk;
    private String trainingSessionId;
    private String cctvCode;
    private String eventId;
    private CongestionEventType eventType;
    private CongestionLevel congestionLevel;
    private Long detectedAt;
    // BE가 이 이벤트를 받은 서버 시각(detectedAt은 Pi 시계). stale 판정은 이 값을 기준으로 한다.
    private Long receivedAt;

    public CurrentCongestionEventItem() {
    }

    public static CurrentCongestionEventItem from(CongestionEventItem event) {
        CurrentCongestionEventItem item = new CurrentCongestionEventItem();
        item.trainingSessionId = event.getTrainingSessionId();
        item.cctvCode = event.getCctvCode();
        item.eventId = event.getEventId();
        item.eventType = event.getEventType();
        item.congestionLevel = event.getCongestionLevel();
        item.detectedAt = event.getDetectedAt();
        item.receivedAt = event.getReceivedAt();
        item.pk = buildPk(item.trainingSessionId);
        item.sk = buildSk(item.cctvCode);
        return item;
    }

    public static String buildPk(String trainingSessionId) {
        return "CURRENT_CONGESTION_EVENT#" + trainingSessionId;
    }

    public static String buildSk(String cctvCode) {
        return "CCTV#" + cctvCode;
    }

    @DynamoDbPartitionKey
    public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }

    @DynamoDbSortKey
    public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }

    public String getTrainingSessionId() { return trainingSessionId; }
    public void setTrainingSessionId(String trainingSessionId) { this.trainingSessionId = trainingSessionId; }

    public String getCctvCode() { return cctvCode; }
    public void setCctvCode(String cctvCode) { this.cctvCode = cctvCode; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public CongestionEventType getEventType() { return eventType; }
    public void setEventType(CongestionEventType eventType) { this.eventType = eventType; }

    public CongestionLevel getCongestionLevel() { return congestionLevel; }
    public void setCongestionLevel(CongestionLevel congestionLevel) { this.congestionLevel = congestionLevel; }

    public Long getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Long detectedAt) { this.detectedAt = detectedAt; }

    public Long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Long receivedAt) { this.receivedAt = receivedAt; }

    // 서버 시각 기준 판정에 쓸 시각. receivedAt이 없는 예전 데이터는 detectedAt으로 대신한다.
    public Long effectiveReceivedAt() {
        return receivedAt != null ? receivedAt : detectedAt;
    }
}
