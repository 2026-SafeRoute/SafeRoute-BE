package com.saferoute.domain.telemetry.dynamo.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saferoute.domain.telemetry.dynamo.entity.LatestMonitoringCaptureItem;
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
class LatestMonitoringCaptureRepositoryTest {

    private static final UUID SESSION_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DynamoDbEnhancedClient enhancedClient;
    @Mock
    private DynamoDbTable<LatestMonitoringCaptureItem> table;

    private LatestMonitoringCaptureRepository repository;

    @BeforeEach
    void setUp() {
        when(enhancedClient.table(eq("saferoute-telemetry"), any(TableSchema.class)))
                .thenReturn(table);
        repository = new LatestMonitoringCaptureRepository(enhancedClient, "saferoute-telemetry");
    }

    @Test
    void 서버_수신_시각이_있으면_receivedAt_기준으로_조건부_갱신한다() {
        LatestMonitoringCaptureItem incoming = LatestMonitoringCaptureItem.create(
                SESSION_ID, "CCTV_001", 2_000L, 7_000L, "training/session/monitoring/CCTV_001/frame.jpg");
        ArgumentCaptor<PutItemEnhancedRequest<LatestMonitoringCaptureItem>> captor = putCaptor();

        assertThat(repository.updateIfLatest(incoming)).isTrue();

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp");
        assertThat(captor.getValue().conditionExpression().expressionNames())
                .containsEntry("#timestamp", "receivedAt");
        assertThat(captor.getValue().conditionExpression().expressionValues()
                .get(":incomingTimestamp").n()).isEqualTo("7000");
    }

    @Test
    void 서버_수신_시각이_없는_예전_계약은_capturedAt_기준으로_조건부_갱신한다() {
        LatestMonitoringCaptureItem incoming = item("CCTV_001", 2_000L);
        ArgumentCaptor<PutItemEnhancedRequest<LatestMonitoringCaptureItem>> captor = putCaptor();

        assertThat(repository.updateIfLatest(incoming)).isTrue();

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#timestamp) OR :incomingTimestamp >= #timestamp");
        assertThat(captor.getValue().conditionExpression().expressionNames())
                .containsEntry("#timestamp", "capturedAt");
        assertThat(captor.getValue().conditionExpression().expressionValues()
                .get(":incomingTimestamp").n()).isEqualTo("2000");
    }

    @Test
    void 과거_캡처는_최신_포인터를_덮어쓸_수_없다() {
        doThrow(ConditionalCheckFailedException.builder().message("stale").build())
                .when(table).putItem(any(PutItemEnhancedRequest.class));

        assertThat(repository.updateIfLatest(item("CCTV_001", 1_000L))).isFalse();
    }

    @Test
    void 세션의_CCTV별_최신_캡처를_한번의_Query로_조회한다() {
        LatestMonitoringCaptureItem first = item("CCTV_001", 1_000L);
        LatestMonitoringCaptureItem second = item("CCTV_002", 2_000L);
        when(table.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(LatestMonitoringCaptureItem.class)
                        .items(List.of(first, second)).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor =
                ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        List<LatestMonitoringCaptureItem> result =
                repository.findAllBySessionId(SESSION_ID.toString());

        verify(table).query(captor.capture());
        assertThat(captor.getValue().queryConditional()
                .expression(
                        TableSchema.fromBean(LatestMonitoringCaptureItem.class),
                        TableMetadata.primaryIndexName()
                )
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("LATEST_MONITORING#" + SESSION_ID);
        assertThat(result).containsExactly(first, second);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<PutItemEnhancedRequest<LatestMonitoringCaptureItem>> putCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
    }

    private LatestMonitoringCaptureItem item(String cctvCode, long capturedAt) {
        return LatestMonitoringCaptureItem.create(
                SESSION_ID,
                cctvCode,
                capturedAt,
                "training/session/monitoring/" + cctvCode + "/frame.jpg"
        );
    }
}
