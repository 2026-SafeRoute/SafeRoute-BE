package com.saferoute.domain.telemetry.dynamo.repository;

import com.saferoute.domain.telemetry.dynamo.entity.LatestMonitoringCaptureItem;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class LatestMonitoringCaptureRepository {

    private final DynamoDbTable<LatestMonitoringCaptureItem> table;

    public LatestMonitoringCaptureRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${aws.dynamodb.table-name}") String tableName
    ) {
        this.table = enhancedClient.table(
                tableName,
                TableSchema.fromBean(LatestMonitoringCaptureItem.class)
        );
    }

    // "더 최신 캡처인지"는 서버 수신 시각(receivedAt)으로 비교한다(CurrentCctvStateRepository와 같은 이유 -
    // Pi 시계가 뒤로 보정되면 화면이 그 시간차만큼 멈춘다). receivedAt이 없는 예전 호출/데이터는 capturedAt으로 비교한다.
    public boolean updateIfLatest(LatestMonitoringCaptureItem incoming) {
        boolean byReceivedAt = incoming.getReceivedAt() != null;
        String attribute = byReceivedAt ? "receivedAt" : "capturedAt";
        long incomingTimestamp = byReceivedAt ? incoming.getReceivedAt() : incoming.getCapturedAt();
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp")
                .putExpressionName("#timestamp", attribute)
                .putExpressionValue(":incomingTimestamp", AttributeValue.fromN(Long.toString(incomingTimestamp)))
                .build();
        PutItemEnhancedRequest<LatestMonitoringCaptureItem> request =
                PutItemEnhancedRequest.builder(LatestMonitoringCaptureItem.class)
                        .item(incoming)
                        .conditionExpression(condition)
                        .build();

        try {
            table.putItem(request);
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    public List<LatestMonitoringCaptureItem> findAllBySessionId(String trainingSessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(LatestMonitoringCaptureItem.buildPk(trainingSessionId))
                        .build()))
                .scanIndexForward(true)
                .build();
        return table.query(request).items().stream().toList();
    }
}
