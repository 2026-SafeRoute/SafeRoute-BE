package com.saferoute.domain.telemetry.dynamo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
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
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@ExtendWith(MockitoExtension.class)
class CurrentCctvStateRepositoryTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DynamoDbEnhancedClient enhancedClient;
    @Mock
    private DynamoDbTable<CurrentCctvStateItem> table;

    private CurrentCctvStateRepository repository;

    @BeforeEach
    void setUp() {
        when(enhancedClient.table(eq("saferoute-telemetry"), any(TableSchema.class))).thenReturn(table);
        repository = new CurrentCctvStateRepository(enhancedClient, "saferoute-telemetry");
    }

    @Test
    void 서버_수신_시각이_있으면_lastReceivedAt_기준으로_현재_상태를_조건부_갱신한다() {
        CurrentCctvStateItem incoming = CurrentCctvStateItem.create(
                SESSION_ID, "CCTV_001", 8.6, 12, 4.5, CongestionLevel.CROWDED, 2_000L, 7_000L, 1L);
        ArgumentCaptor<PutItemEnhancedRequest<CurrentCctvStateItem>> captor = putCaptor();

        assertThat(repository.updateIfLatest(incoming)).isTrue();

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp");
        assertThat(captor.getValue().conditionExpression().expressionNames())
                .containsEntry("#timestamp", "lastReceivedAt");
        assertThat(captor.getValue().conditionExpression().expressionValues().get(":incomingTimestamp").n())
                .isEqualTo("7000");
    }

    @Test
    void 서버_수신_시각이_없는_예전_계약은_lastDetectedAt_기준으로_현재_상태를_조건부_갱신한다() {
        CurrentCctvStateItem incoming = item("CCTV_001", 2_000L);
        ArgumentCaptor<PutItemEnhancedRequest<CurrentCctvStateItem>> captor = putCaptor();

        assertThat(repository.updateIfLatest(incoming)).isTrue();

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp");
        assertThat(captor.getValue().conditionExpression().expressionNames())
                .containsEntry("#timestamp", "lastDetectedAt");
        assertThat(captor.getValue().conditionExpression().expressionValues().get(":incomingTimestamp").n())
                .isEqualTo("2000");
    }

    @Test
    void 오래된_timestamp로_현재_상태를_덮어쓸_수_없다() {
        doThrow(ConditionalCheckFailedException.builder().message("stale").build())
                .when(table).putItem(any(PutItemEnhancedRequest.class));

        assertThat(repository.updateIfLatest(item("CCTV_001", 1_000L))).isFalse();
    }

    @Test
    void 세션의_모든_CCTV_현재_상태를_Query로_조회한다() {
        CurrentCctvStateItem first = item("CCTV_001", 1_000L);
        CurrentCctvStateItem second = item("CCTV_002", 2_000L);
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CurrentCctvStateItem.class)
                        .items(List.of(first, second)).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        List<CurrentCctvStateItem> result = repository.findAllBySessionId(SESSION_ID.toString());

        verify(table).query(captor.capture());
        assertThat(captor.getValue().queryConditional()
                .expression(TableSchema.fromBean(CurrentCctvStateItem.class), TableMetadata.primaryIndexName())
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("CURRENT_STATE#" + SESSION_ID);
        assertThat(result).containsExactly(first, second);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<PutItemEnhancedRequest<CurrentCctvStateItem>> putCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
    }

    private CurrentCctvStateItem item(String cctvCode, long timestamp) {
        return CurrentCctvStateItem.create(
                SESSION_ID, cctvCode, 8.6, 12, 4.5, CongestionLevel.CROWDED, timestamp, 1L
        );
    }
}
