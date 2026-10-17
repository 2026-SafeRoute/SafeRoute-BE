package com.saferoute.domain.telemetry.dynamo.repository;

import com.saferoute.domain.telemetry.dynamo.entity.CurrentCongestionEventItem;
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
public class CurrentCongestionEventRepository {

    private final DynamoDbTable<CurrentCongestionEventItem> table;

    public CurrentCongestionEventRepository(
            DynamoDbEnhancedClient enhancedClient,
            @Value("${aws.dynamodb.table-name}") String tableName
    ) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(CurrentCongestionEventItem.class));
    }

    // 늦게 도착한 오래된 이벤트가 최신 이벤트를 덮어쓰지 않도록 detectedAt 기준으로 조건부 저장한다.
    // 같은 이벤트의 재전송(같은 detectedAt)은 같은 값으로 다시 쓰는 것이라 허용한다.
    public boolean updateIfLatest(CurrentCongestionEventItem incoming) {
        Expression condition = Expression.builder()
                .expression("attribute_not_exists(#detectedAt) OR :incomingTimestamp >= #detectedAt")
                .putExpressionName("#detectedAt", "detectedAt")
                .putExpressionValue(
                        ":incomingTimestamp",
                        AttributeValue.fromN(Long.toString(incoming.getDetectedAt()))
                )
                .build();
        PutItemEnhancedRequest<CurrentCongestionEventItem> request =
                PutItemEnhancedRequest.builder(CurrentCongestionEventItem.class)
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

    // 재탐색 취소 판단에 쓰이므로 방금 저장된 이벤트가 반드시 보여야 한다 - 강한 일관성으로 읽는다.
    // 파티션에는 CCTV당 한 건만 있어 관측 주기마다 읽어도 조회량이 CCTV 수로 고정된다.
    public List<CurrentCongestionEventItem> findAllBySessionId(String trainingSessionId) {
        QueryEnhancedRequest request = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder()
                        .partitionValue(CurrentCongestionEventItem.buildPk(trainingSessionId))
                        .build()))
                .consistentRead(true)
                .build();
        return table.query(request).items().stream().toList();
    }
}
