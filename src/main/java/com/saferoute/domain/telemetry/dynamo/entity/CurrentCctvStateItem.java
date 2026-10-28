package com.saferoute.domain.telemetry.dynamo.entity;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import java.util.UUID;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class CurrentCctvStateItem {

    private String pk;
    private String sk;
    private String trainingSessionId;
    private String cctvCode;
    private Double avgHeadcount;
    private Integer peakHeadcount;
    private Double density;
    private CongestionLevel congestionLevel;
    private Long lastDetectedAt;
    // 마지막 관측값을 BE가 받은 서버 시각. lastDetectedAt(Pi 시계)과 달리 stale 판정/최신 여부 비교의
    // 기준으로 쓴다. 예전 데이터에는 없을 수 있다(null) - 그때는 lastDetectedAt으로 대신한다.
    private Long lastReceivedAt;
    private Long configVersion;

    public CurrentCctvStateItem() {
    }

    // lastReceivedAt 없이 만드는 예전 계약(테스트/기존 데이터 호환용).
    public static CurrentCctvStateItem create(
            UUID trainingSessionId,
            String cctvCode,
            Double avgHeadcount,
            Integer peakHeadcount,
            Double density,
            CongestionLevel congestionLevel,
            long lastDetectedAt,
            long configVersion
    ) {
        return create(trainingSessionId, cctvCode, avgHeadcount, peakHeadcount, density, congestionLevel,
                lastDetectedAt, null, configVersion);
    }

    // 기준 데이터는 주기 Observation으로 통일한다 - 즉시 혼잡 이벤트(CongestionEventService)는
    // 더 이상 이 아이템을 직접 갱신하지 않는다 (이벤트 타임라인/재탐색 트리거/WebSocket 알림 용도로만 쓰임).
    public static CurrentCctvStateItem create(
            UUID trainingSessionId,
            String cctvCode,
            Double avgHeadcount,
            Integer peakHeadcount,
            Double density,
            CongestionLevel congestionLevel,
            long lastDetectedAt,
            Long lastReceivedAt,
            long configVersion
    ) {
        CurrentCctvStateItem item = new CurrentCctvStateItem();
        item.lastReceivedAt = lastReceivedAt;
        item.trainingSessionId = trainingSessionId.toString();
        item.cctvCode = cctvCode;
        item.avgHeadcount = avgHeadcount;
        item.peakHeadcount = peakHeadcount;
        item.density = density;
        item.congestionLevel = congestionLevel;
        item.lastDetectedAt = lastDetectedAt;
        item.configVersion = configVersion;
        item.pk = buildPk(item.trainingSessionId);
        item.sk = buildSk(cctvCode);
        return item;
    }

    public static String buildPk(String trainingSessionId) {
        return "CURRENT_STATE#" + trainingSessionId;
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

    public Double getAvgHeadcount() { return avgHeadcount; }
    public void setAvgHeadcount(Double avgHeadcount) { this.avgHeadcount = avgHeadcount; }

    public Integer getPeakHeadcount() { return peakHeadcount; }
    public void setPeakHeadcount(Integer peakHeadcount) { this.peakHeadcount = peakHeadcount; }

    public Double getDensity() { return density; }
    public void setDensity(Double density) { this.density = density; }

    public CongestionLevel getCongestionLevel() { return congestionLevel; }
    public void setCongestionLevel(CongestionLevel congestionLevel) { this.congestionLevel = congestionLevel; }

    public Long getLastDetectedAt() { return lastDetectedAt; }
    public void setLastDetectedAt(Long lastDetectedAt) { this.lastDetectedAt = lastDetectedAt; }

    public Long getLastReceivedAt() { return lastReceivedAt; }
    public void setLastReceivedAt(Long lastReceivedAt) { this.lastReceivedAt = lastReceivedAt; }

    // 서버 시각 기준 stale 판정에 쓸 시각. lastReceivedAt이 없는 예전 데이터는 lastDetectedAt으로 대신한다.
    // (get으로 시작하면 DynamoDB Enhanced Client가 속성으로 오인하므로 이름을 달리 둔다.)
    public Long effectiveReceivedAt() {
        return lastReceivedAt != null ? lastReceivedAt : lastDetectedAt;
    }

    public Long getConfigVersion() { return configVersion; }
    public void setConfigVersion(Long configVersion) { this.configVersion = configVersion; }
}
