package com.saferoute.domain.telemetry.dynamo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.CongestionEventType;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCongestionEventItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@ExtendWith(MockitoExtension.class)
class CurrentCongestionEventRepositoryTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DynamoDbEnhancedClient enhancedClient;
    @Mock
    private DynamoDbTable<CurrentCongestionEventItem> table;

    private CurrentCongestionEventRepository repository;

    @BeforeEach
    void setUp() {
        when(enhancedClient.table(eq("saferoute-telemetry"), any(TableSchema.class))).thenReturn(table);
        repository = new CurrentCongestionEventRepository(enhancedClient, "saferoute-telemetry");
    }

    @Test
    void 최신_detectedAt이면_CCTV별_현재_이벤트를_조건부_갱신한다() {
        CurrentCongestionEventItem incoming = item("CCTV_001", 2_000L);
        ArgumentCaptor<PutItemEnhancedRequest<CurrentCongestionEventItem>> captor = putCaptor();

        assertThat(repository.updateIfLatest(incoming)).isTrue();

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().item().getPk()).isEqualTo("CURRENT_CONGESTION_EVENT#" + SESSION_ID);
        assertThat(captor.getValue().item().getSk()).isEqualTo("CCTV#CCTV_001");
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#detectedAt) OR :incomingTimestamp >= #detectedAt");
        assertThat(captor.getValue().conditionExpression().expressionValues().get(":incomingTimestamp").n())
                .isEqualTo("2000");
    }

    @Test
    void 늦게_도착한_오래된_이벤트로_현재_이벤트를_덮어쓸_수_없다() {
        doThrow(ConditionalCheckFailedException.builder().message("stale").build())
                .when(table).putItem(any(PutItemEnhancedRequest.class));

        assertThat(repository.updateIfLatest(item("CCTV_001", 1_000L))).isFalse();
    }

    @Test
    void 세션의_CCTV별_현재_이벤트를_강한_일관성_Query로_조회한다() {
        CurrentCongestionEventItem first = item("CCTV_001", 1_000L);
        CurrentCongestionEventItem second = item("CCTV_002", 2_000L);
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CurrentCongestionEventItem.class)
                        .items(List.of(first, second)).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        List<CurrentCongestionEventItem> result = repository.findAllBySessionId(SESSION_ID.toString());

        verify(table).query(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
        assertThat(captor.getValue().queryConditional()
                .expression(TableSchema.fromBean(CurrentCongestionEventItem.class), TableMetadata.primaryIndexName())
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("CURRENT_CONGESTION_EVENT#" + SESSION_ID);
        assertThat(result).containsExactly(first, second);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<PutItemEnhancedRequest<CurrentCongestionEventItem>> putCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
    }

    private CurrentCongestionEventItem item(String cctvCode, long detectedAt) {
        return CurrentCongestionEventItem.from(CongestionEventItem.received(
                UUID.randomUUID(), SESSION_ID, cctvCode, CongestionEventType.CONGESTION_STARTED, detectedAt,
                9, 4.5, CongestionLevel.CROWDED, 4.5, CongestionLevel.CROWDED, 1L, null));
    }
}
