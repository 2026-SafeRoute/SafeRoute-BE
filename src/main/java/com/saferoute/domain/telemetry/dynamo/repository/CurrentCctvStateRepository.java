package com.saferoute.domain.telemetry.dynamo.repository;

import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@Repository
public class CurrentCctvStateRepository {

    private final DynamoDbTable<CurrentCctvStateItem> table;

    public CurrentCctvStateRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${aws.dynamodb.table-name}") String tableName
    ) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CurrentCctvStateItem.class));
    }

    // "더 최신인지"는 서버 수신 시각(lastReceivedAt)으로 비교한다. Pi 시계(lastDetectedAt)로 비교하면 Pi의
    // NTP 보정으로 시계가 뒤로 가는 순간부터 그 시간차만큼 모든 관측값이 "오래됨"으로 거부돼 현재 상태가
    // 멈춘다. lastReceivedAt이 없는 예전 호출/데이터는 종전대로 lastDetectedAt으로 비교한다.
    public boolean updateIfLatest(CurrentCctvStateItem incoming) {
        boolean byReceivedAt = incoming.getLastReceivedAt() != null;
        String attribute = byReceivedAt ? "lastReceivedAt" : "lastDetectedAt";
        long incomingTimestamp = byReceivedAt ? incoming.getLastReceivedAt() : incoming.getLastDetectedAt();
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp")
                .putExpressionName("#timestamp", attribute)
                .putExpressionValue(":incomingTimestamp", AttributeValue.fromN(Long.toString(incomingTimestamp)))
                .build();
        PutItemEnhancedRequest<CurrentCctvStateItem> request =
                PutItemEnhancedRequest.builder(CurrentCctvStateItem.class)
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

    public Optional<CurrentCctvStateItem> findBySessionIdAndCctvCode(
            String trainingSessionId,
            String cctvCode
    ) {
        GetItemEnhancedRequest request = GetItemEnhancedRequest.builder()
                .key(Key.builder()
                        .partitionValue(CurrentCctvStateItem.buildPk(trainingSessionId))
                        .sortValue(CurrentCctvStateItem.buildSk(cctvCode))
                        .build())
                .consistentRead(true)
                .build();
        return Optional.ofNullable(table.getItem(request));
    }

    public List<CurrentCctvStateItem> findAllBySessionId(String trainingSessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(CurrentCctvStateItem.buildPk(trainingSessionId))
                        .build()))
                .scanIndexForward(true)
                .build();
        return table.query(request).items().stream().toList();
    }
}
