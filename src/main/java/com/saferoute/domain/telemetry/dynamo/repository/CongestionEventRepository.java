package com.saferoute.domain.telemetry.dynamo.repository;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.EventProcessingStatus;
import com.saferoute.domain.telemetry.dynamo.entity.ImageUploadStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class CongestionEventRepository {

    static final int DEFAULT_QUERY_LIMIT = 100;

    // CongestionEventItem과 GeneralMonitoringEventItem은 같은 물리 테이블의 같은 GSI1을
    // 공유하고, 둘 다 GSI1_PK를 "SESSION#{trainingSessionId}"로 만든다(단일 테이블 설계).
    // GSI1로 세션 단위 조회를 할 때 이 접두사로 걸러내지 않으면 다른 아이템 타입까지 같이
    // 반환되어, 그 아이템의 eventType 값을 CongestionEventType enum으로 역직렬화하려다
    // IllegalArgumentException이 터진다(반대 방향도 마찬가지 - GeneralMonitoringEventRepository 참고).
    private static final String PK_PREFIX = "CONGESTION_EVENT#";

    private static final Expression ITEM_NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(#pk)")
            .putExpressionName("#pk", "pk")
            .build();

    // 리포트의 병목 횟수(bottleneckCount) 집계 기준: CONGESTION_STARTED이면서 BE가 최종 판정한
    // congestionLevel이 CROWDED/VERY_CROWDED인 이벤트만 "병목 구간이 시작된 횟수"로 센다.
    // CONGESTION_LEVEL_UP/CONGESTION_ENDED는 같은 병목 구간의 상태 변화일 뿐이라 제외한다.
    private static final Expression BOTTLENECK_FILTER = Expression.builder()
            .expression("#eventType = :started AND (#congestionLevel = :crowded OR #congestionLevel = :veryCrowded)")
            .putExpressionName("#eventType", "eventType")
            .putExpressionName("#congestionLevel", "congestionLevel")
            .putExpressionValue(":started", AttributeValue.fromS(CongestionEventType.CONGESTION_STARTED.name()))
            .putExpressionValue(":crowded", AttributeValue.fromS(CongestionLevel.CROWDED.name()))
            .putExpressionValue(":veryCrowded", AttributeValue.fromS(CongestionLevel.VERY_CROWDED.name()))
            .build();

    private final DynamoDbTable<CongestionEventItem> table;
    private final DynamoDbIndex<CongestionEventItem> gsi1;

    public CongestionEventRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${aws.dynamodb.table-name}") String tableName,
            @Value("${aws.dynamodb.gsi1-name:GSI1}") String gsi1Name
    ) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CongestionEventItem.class));
        this.gsi1 = table.index(gsi1Name);
    }

    public IdempotentSaveResult<CongestionEventItem> saveReceivedIfAbsent(CongestionEventItem item) {
        PutItemEnhancedRequest<CongestionEventItem> request =
                PutItemEnhancedRequest.builder(CongestionEventItem.class)
                        .item(item)
                        .conditionExpression(ITEM_NOT_EXISTS)
                        .build();

        try {
            table.putItem(request);
            return IdempotentSaveResult.created(item);
        } catch (ConditionalCheckFailedException exception) {
            return findByEventId(item.getEventId())
                    .map(IdempotentSaveResult::existing)
                    .orElseThrow(() -> exception);
        }
    }

    public Optional<CongestionEventItem> findByEventId(String eventId) {
        GetItemEnhancedRequest request = GetItemEnhancedRequest.builder()
                .key(Key.builder()
                        .partitionValue(CongestionEventItem.buildPk(eventId))
                        .sortValue("META")
                        .build())
                .consistentRead(true)
                .build();
        return Optional.ofNullable(table.getItem(request));
    }

    public List<CongestionEventItem> findAllBySessionId(String trainingSessionId) {
        return findAllBySessionId(trainingSessionId, DEFAULT_QUERY_LIMIT);
    }

    public List<CongestionEventItem> findAllBySessionId(String trainingSessionId, int limit) {
        validateLimit(limit);
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(CongestionEventItem.buildGsi1Pk(trainingSessionId))
                        .build()))
                .filterExpression(pkPrefixFilter())
                .scanIndexForward(true)
                .limit(limit)
                .build();

        return gsi1.query(request).stream()
                .flatMap(page -> page.items().stream())
                .limit(limit)
                .toList();
    }

    // 리포트 전용 병목 횟수 집계: 임의의 상한 없이 세션 파티션 전체를 순회하며 서버 사이드
    // FilterExpression(BOTTLENECK_FILTER)을 통과한 아이템만 센다. Limit을 걸지 않으므로 SDK가
    // 필터로 줄어든 만큼 다음 물리 페이지를 자동으로 더 읽어와, 5,000건 같은 고정 상한 때문에
    // 일부 병목이 집계에서 누락되는 일이 없다.
    public int countBottlenecksBySessionId(String trainingSessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(CongestionEventItem.buildGsi1Pk(trainingSessionId))
                        .build()))
                .filterExpression(BOTTLENECK_FILTER)
                .build();

        return (int) gsi1.query(request).stream()
                .flatMap(page -> page.items().stream())
                .count();
    }

    // 이벤트 타임라인 조회 전용: 최신순으로 커서 페이지네이션하며, cctvCode 필터를 DynamoDB
    // FilterExpression으로 적용한다(애플리케이션 레벨 필터 대신) - 필터로 줄어든 만큼 SDK가
    // LastEvaluatedKey를 따라 다음 물리 페이지를 자동으로 더 읽어오므로, 특정 CCTV로 필터링해도
    // 다른 CCTV 이벤트에 밀려 결과가 누락되지 않는다.
    public List<CongestionEventItem> findPageBySessionId(
            String trainingSessionId,
            String cctvCode,
            int limit,
            Long beforeDetectedAt,
            String beforeEventId
    ) {
        validateLimit(limit);
        QueryConditional queryConditional = beforeDetectedAt == null
                ? QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(CongestionEventItem.buildGsi1Pk(trainingSessionId))
                        .build())
                : QueryConditional.sortLessThan(Key.builder()
                        .partitionValue(CongestionEventItem.buildGsi1Pk(trainingSessionId))
                        .sortValue(CongestionEventItem.buildGsi1Sk(beforeDetectedAt, beforeEventId))
                        .build());
        Expression filterExpression = cctvCode == null
                ? pkPrefixFilter()
                : Expression.builder()
                        .expression("begins_with(#pk, :pkPrefix) AND #cctvCode = :cctvCode")
                        .putExpressionName("#pk", "pk")
                        .putExpressionName("#cctvCode", "cctvCode")
                        .putExpressionValue(":pkPrefix", AttributeValue.fromS(PK_PREFIX))
                        .putExpressionValue(":cctvCode", AttributeValue.fromS(cctvCode))
                        .build();
        QueryEnhancedRequest.Builder requestBuilder = QueryEnhancedRequest.builder()
                .queryConditional(queryConditional)
                .filterExpression(filterExpression)
                .scanIndexForward(false)
                .limit(limit);

        return gsi1.query(requestBuilder.build()).stream()
                .flatMap(page -> page.items().stream())
                .limit(limit)
                .toList();
    }

    // GSI1을 공유하는 다른 아이템 타입(GeneralMonitoringEventItem)이 결과에 섞여 들어와
    // 역직렬화에 실패하지 않도록, 이 리포지토리가 다루는 타입의 pk 접두사만 통과시킨다.
    private Expression pkPrefixFilter() {
        return Expression.builder()
                .expression("begins_with(#pk, :pkPrefix)")
                .putExpressionName("#pk", "pk")
                .putExpressionValue(":pkPrefix", AttributeValue.fromS(PK_PREFIX))
                .build();
    }

    public boolean updateEventStatus(
            String eventId,
            EventProcessingStatus expectedStatus,
            EventProcessingStatus newStatus
    ) {
        validateEventStatusTransition(expectedStatus, newStatus);
        return updateItem(eventId, "eventStatus", expectedStatus.name(), item -> item.setEventStatus(newStatus));
    }

    public boolean updateImageUploadStatus(
            String eventId,
            ImageUploadStatus expectedStatus,
            ImageUploadStatus newStatus
    ) {
        validateImageStatusTransition(expectedStatus, newStatus);
        return updateItem(
                eventId,
                "imageUploadStatus",
                expectedStatus.name(),
                item -> item.setImageUploadStatus(newStatus)
        );
    }

    // 이벤트가 PROCESSED이고 이미지가 아직 PENDING/FAILED일 때만 이미지 연결을 완료 처리한다.
    public boolean completeImageUpload(String eventId, String eventImageKey, long uploadedAt) {
        CongestionEventItem item = new CongestionEventItem();
        item.setPk(CongestionEventItem.buildPk(eventId));
        item.setSk("META");
        item.setEventImageKey(eventImageKey);
        item.setImageUploadedAt(uploadedAt);
        item.setImageUploadStatus(ImageUploadStatus.COMPLETED);

        Expression condition = Expression.builder()
                .expression("attribute_exists(#pk) AND #eventStatus = :processed"
                        + " AND (attribute_not_exists(#imageStatus)"
                        + " OR #imageStatus = :pending OR #imageStatus = :failed)")
                .putExpressionName("#pk", "pk")
                .putExpressionName("#eventStatus", "eventStatus")
                .putExpressionName("#imageStatus", "imageUploadStatus")
                .putExpressionValue(":processed", AttributeValue.fromS(EventProcessingStatus.PROCESSED.name()))
                .putExpressionValue(":pending", AttributeValue.fromS(ImageUploadStatus.PENDING.name()))
                .putExpressionValue(":failed", AttributeValue.fromS(ImageUploadStatus.FAILED.name()))
                .build();
        UpdateItemEnhancedRequest<CongestionEventItem> request =
                UpdateItemEnhancedRequest.builder(CongestionEventItem.class)
                        .item(item)
                        .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                        .conditionExpression(condition)
                        .build();
        try {
            table.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    private boolean updateItem(
            String eventId,
            String statusAttribute,
            String expectedStatus,
            java.util.function.Consumer<CongestionEventItem> update
    ) {
        CongestionEventItem item = new CongestionEventItem();
        item.setPk(CongestionEventItem.buildPk(eventId));
        item.setSk("META");
        update.accept(item);
        Expression condition = Expression.builder()
                .expression("#status = :expectedStatus")
                .putExpressionName("#status", statusAttribute)
                .putExpressionValue(":expectedStatus", AttributeValue.fromS(expectedStatus))
                .build();
        UpdateItemEnhancedRequest<CongestionEventItem> request =
                UpdateItemEnhancedRequest.builder(CongestionEventItem.class)
                        .item(item)
                        .ignoreNullsMode(IgnoreNullsMode.SCALAR_ONLY)
                        .conditionExpression(condition)
                        .build();

        try {
            table.updateItem(request);
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    private void validateEventStatusTransition(
            EventProcessingStatus expectedStatus,
            EventProcessingStatus newStatus
    ) {
        boolean retryOrStart = (expectedStatus == EventProcessingStatus.RECEIVED
                || expectedStatus == EventProcessingStatus.FAILED)
                && newStatus == EventProcessingStatus.PROCESSING;
        boolean finish = expectedStatus == EventProcessingStatus.PROCESSING
                && (newStatus == EventProcessingStatus.PROCESSED
                || newStatus == EventProcessingStatus.FAILED);
        if (!retryOrStart && !finish) {
            throw new IllegalArgumentException(
                    "허용되지 않은 이벤트 상태 변경입니다: " + expectedStatus + " -> " + newStatus
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void validateImageStatusTransition(
            ImageUploadStatus expectedStatus,
            ImageUploadStatus newStatus
    ) {
        boolean complete = expectedStatus == ImageUploadStatus.PENDING
                && (newStatus == ImageUploadStatus.COMPLETED
                || newStatus == ImageUploadStatus.UPLOADED
                || newStatus == ImageUploadStatus.FAILED);
        boolean retry = expectedStatus == ImageUploadStatus.FAILED
                && newStatus == ImageUploadStatus.PENDING;
        if (!complete && !retry) {
            throw new IllegalArgumentException(
                    "허용되지 않은 이미지 상태 변경입니다: " + expectedStatus + " -> " + newStatus
            );
        }
    }

    private void validateLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit은 0보다 커야합니다.");
        }
    }
}
