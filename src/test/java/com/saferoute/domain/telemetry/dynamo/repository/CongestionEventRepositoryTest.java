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
import com.saferoute.domain.telemetry.dynamo.entity.EventProcessingStatus;
import com.saferoute.domain.telemetry.dynamo.entity.ImageUploadStatus;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

@ExtendWith(MockitoExtension.class)
class CongestionEventRepositoryTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DynamoDbEnhancedClient enhancedClient;
    @Mock
    private DynamoDbTable<CongestionEventItem> table;
    @Mock
    private DynamoDbIndex<CongestionEventItem> gsi1;

    private CongestionEventRepository repository;

    @BeforeEach
    void setUp() {
        when(enhancedClient.table(eq("saferoute-telemetry"), any(TableSchema.class))).thenReturn(table);
        when(table.index("GSI1")).thenReturn(gsi1);
        repository = new CongestionEventRepository(enhancedClient, "saferoute-telemetry", "GSI1");
    }

    @Test
    void eventId가_처음이면_RECEIVED_이벤트를_조건부로_저장한다() {
        CongestionEventItem item = item("event-1", 1_000L);
        ArgumentCaptor<PutItemEnhancedRequest<CongestionEventItem>> captor = requestCaptor();

        IdempotentSaveResult<CongestionEventItem> result = repository.saveReceivedIfAbsent(item);

        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("attribute_not_exists(#pk)");
        assertThat(result.created()).isTrue();
    }

    @Test
    void 같은_eventId면_기존_이벤트를_반환한다() {
        CongestionEventItem incoming = item("event-1", 1_000L);
        CongestionEventItem existing = item("event-1", 900L);
        doThrow(ConditionalCheckFailedException.builder().message("duplicate").build())
                .when(table).putItem(any(PutItemEnhancedRequest.class));
        when(table.getItem(any(software.amazon.awssdk.enhanced.dynamodb.model.GetItemEnhancedRequest.class)))
                .thenReturn(existing);

        IdempotentSaveResult<CongestionEventItem> result = repository.saveReceivedIfAbsent(incoming);

        assertThat(result.created()).isFalse();
        assertThat(result.item()).isSameAs(existing);
    }

    @Test
    void 세션의_이벤트를_GSI에서_시간순으로_조회한다() {
        CongestionEventItem first = item("event-1", 1_000L);
        CongestionEventItem second = item("event-2", 2_000L);
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of(first, second)).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        List<CongestionEventItem> result = repository.findAllBySessionId(SESSION_ID.toString(), 10);

        verify(gsi1).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isTrue();
        assertThat(captor.getValue().queryConditional()
                .expression(TableSchema.fromBean(CongestionEventItem.class), CongestionEventItem.GSI1_NAME)
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("SESSION#" + SESSION_ID);
        assertThat(captor.getValue().filterExpression().expression())
                .isEqualTo("begins_with(#pk, :pkPrefix)");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":pkPrefix").s())
                .isEqualTo("CONGESTION_EVENT#");
        assertThat(result).containsExactly(first, second);
    }

    @Test
    void 이벤트_타임라인은_최신순으로_조회한다() {
        CongestionEventItem newest = item("event-1", 2_000L);
        CongestionEventItem oldest = item("event-2", 1_000L);
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of(newest, oldest)).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        List<CongestionEventItem> result = repository.findPageBySessionId(
                SESSION_ID.toString(), null, 10, null, null);

        verify(gsi1).query(captor.capture());
        assertThat(captor.getValue().scanIndexForward()).isFalse();
        assertThat(captor.getValue().limit()).isEqualTo(10);
        // GSI1을 공유하는 GeneralMonitoringEventItem이 섞여 들어와 역직렬화가 실패하지 않도록,
        // cctvCode 필터가 없어도 pk 접두사 필터는 항상 적용돼야 한다.
        assertThat(captor.getValue().filterExpression().expression())
                .isEqualTo("begins_with(#pk, :pkPrefix)");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":pkPrefix").s())
                .isEqualTo("CONGESTION_EVENT#");
        assertThat(captor.getValue().queryConditional()
                .expression(TableSchema.fromBean(CongestionEventItem.class), CongestionEventItem.GSI1_NAME)
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("SESSION#" + SESSION_ID);
        assertThat(result).containsExactly(newest, oldest);
    }

    @Test
    void cctvCode가_있으면_pk_접두사_필터와_함께_적용한다() {
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of()).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        repository.findPageBySessionId(SESSION_ID.toString(), "CCTV_001", 10, null, null);

        verify(gsi1).query(captor.capture());
        assertThat(captor.getValue().filterExpression()).isNotNull();
        assertThat(captor.getValue().filterExpression().expression())
                .isEqualTo("begins_with(#pk, :pkPrefix) AND #cctvCode = :cctvCode");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":pkPrefix").s())
                .isEqualTo("CONGESTION_EVENT#");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":cctvCode").s())
                .isEqualTo("CCTV_001");
    }

    @Test
    void cursor가_있으면_해당_시각_이전_이벤트만_조회한다() {
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of()).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        repository.findPageBySessionId(SESSION_ID.toString(), null, 10, 2_000L, "event-1");

        verify(gsi1).query(captor.capture());
        assertThat(captor.getValue().queryConditional()
                .expression(TableSchema.fromBean(CongestionEventItem.class), CongestionEventItem.GSI1_NAME)
                .expressionValues().values())
                .extracting(value -> value.s())
                .contains("EVENT#2000#event-1");
    }

    @Test
    void 병목_집계는_CROWDED_VERY_CROWDED_CONGESTION_STARTED만_센다() {
        CongestionEventItem crowdedStarted = item(
                "started-crowded", 1_000L, CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED);
        CongestionEventItem veryCrowdedStarted = item(
                "started-very-crowded", 2_000L, CongestionEventType.CONGESTION_STARTED, CongestionLevel.VERY_CROWDED);
        // FilterExpression은 이미 통과한 아이템만 넘어온다고 가정하고 스텁한다 - 실제 필터 자체는
        // 아래 필터_익스프레션_검증 테스트에서 별도로 확인한다.
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of(crowdedStarted, veryCrowdedStarted)).build()).iterator())
        );

        int count = repository.countBottlenecksBySessionId(SESSION_ID.toString());

        assertThat(count).isEqualTo(2);
    }

    @Test
    void 병목_집계는_필터_익스프레션으로_STARTED와_CROWDED_이상만_요청한다() {
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(Page.builder(CongestionEventItem.class)
                        .items(List.of()).build()).iterator())
        );
        ArgumentCaptor<QueryEnhancedRequest> captor = ArgumentCaptor.forClass(QueryEnhancedRequest.class);

        repository.countBottlenecksBySessionId(SESSION_ID.toString());

        verify(gsi1).query(captor.capture());
        assertThat(captor.getValue().limit()).isNull();
        assertThat(captor.getValue().filterExpression().expression())
                .isEqualTo("#eventType = :started AND (#congestionLevel = :crowded OR #congestionLevel = :veryCrowded)");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":started").s())
                .isEqualTo("CONGESTION_STARTED");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":crowded").s())
                .isEqualTo("CROWDED");
        assertThat(captor.getValue().filterExpression().expressionValues().get(":veryCrowded").s())
                .isEqualTo("VERY_CROWDED");
    }

    @Test
    void 병목_집계는_고정_상한_없이_여러_물리_페이지를_모두_순회한다() {
        List<CongestionEventItem> firstPageItems = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> item("started-" + i, i, CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED))
                .toList();
        List<CongestionEventItem> secondPageItems = java.util.stream.IntStream.range(3, 5)
                .mapToObj(i -> item("started-" + i, i, CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED))
                .toList();
        when(gsi1.query(any(QueryEnhancedRequest.class))).thenReturn(
                PageIterable.create(() -> List.of(
                        Page.builder(CongestionEventItem.class).items(firstPageItems).build(),
                        Page.builder(CongestionEventItem.class).items(secondPageItems).build()
                ).iterator())
        );

        int count = repository.countBottlenecksBySessionId(SESSION_ID.toString());

        assertThat(count).isEqualTo(5);
    }

    @Test
    void 이벤트를_RECEIVED_PROCESSING_PROCESSED_순서로_변경한다() {
        ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> captor = updateCaptor();

        assertThat(repository.updateEventStatus(
                "event-1", EventProcessingStatus.RECEIVED, EventProcessingStatus.PROCESSING
        )).isTrue();
        assertThat(repository.updateEventStatus(
                "event-1", EventProcessingStatus.PROCESSING, EventProcessingStatus.PROCESSED
        )).isTrue();

        verify(table, org.mockito.Mockito.times(2)).updateItem(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(request -> request.conditionExpression()
                        .expressionValues().get(":expectedStatus").s())
                .containsExactly("RECEIVED", "PROCESSING");
        assertThat(captor.getAllValues())
                .extracting(request -> request.item().getEventStatus())
                .containsExactly(EventProcessingStatus.PROCESSING, EventProcessingStatus.PROCESSED);
        assertThat(captor.getAllValues())
                .allSatisfy(request -> {
                    assertThat(request.ignoreNullsMode()).isEqualTo(
                            software.amazon.awssdk.enhanced.dynamodb.model.IgnoreNullsMode.SCALAR_ONLY
                    );
                    assertThat(request.item().getImageUploadStatus()).isNull();
                });
    }

    @Test
    void RECEIVED_FAILED_또는_만료된_PROCESSING만_lease로_선점한다() {
        ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> captor = updateCaptor();

        boolean claimed = repository.claimProcessing("event-1", "worker-1", 10_000L, 40_000L);

        assertThat(claimed).isTrue();
        verify(table).updateItem(captor.capture());
        UpdateItemEnhancedRequest<CongestionEventItem> request = captor.getValue();
        assertThat(request.item().getEventStatus()).isEqualTo(EventProcessingStatus.PROCESSING);
        assertThat(request.item().getProcessingOwner()).isEqualTo("worker-1");
        assertThat(request.item().getProcessingStartedAt()).isEqualTo(10_000L);
        assertThat(request.item().getProcessingExpiresAt()).isEqualTo(40_000L);
        assertThat(request.conditionExpression().expression())
                .startsWith("attribute_exists(#pk) AND (")
                .contains("#status = :received", "#status = :failed", "#processingExpiresAt <= :now")
                .endsWith(")");
        assertThat(request.conditionExpression().expressionValues().get(":now").n()).isEqualTo("10000");
    }

    @Test
    void 다른_요청의_lease가_살아_있으면_선점에_실패한다() {
        doThrow(ConditionalCheckFailedException.builder().message("processing").build())
                .when(table).updateItem(any(UpdateItemEnhancedRequest.class));

        assertThat(repository.claimProcessing("event-1", "worker-2", 10_000L, 40_000L)).isFalse();
    }

    @Test
    void 선점한_요청만_PROCESSED로_완료할_수_있다() {
        ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> captor = updateCaptor();

        assertThat(repository.completeProcessing("event-1", "worker-1")).isTrue();

        verify(table).updateItem(captor.capture());
        assertThat(captor.getValue().item().getEventStatus()).isEqualTo(EventProcessingStatus.PROCESSED);
        assertThat(captor.getValue().conditionExpression().expression())
                .isEqualTo("#status = :processing AND #processingOwner = :processingOwner");
        assertThat(captor.getValue().conditionExpression().expressionValues().get(":processingOwner").s())
                .isEqualTo("worker-1");
    }

    @Test
    void 선점한_요청의_처리_실패를_FAILED로_기록한다() {
        ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> captor = updateCaptor();

        assertThat(repository.failProcessing("event-1", "worker-1")).isTrue();

        verify(table).updateItem(captor.capture());
        assertThat(captor.getValue().item().getEventStatus()).isEqualTo(EventProcessingStatus.FAILED);
    }

    @Test
    void FAILED_이벤트를_PROCESSING으로_재처리할_수_있다() {
        boolean updated = repository.updateEventStatus(
                "event-1", EventProcessingStatus.FAILED, EventProcessingStatus.PROCESSING
        );

        assertThat(updated).isTrue();
    }

    @Test
    void 이미지_업로드_실패_후_PENDING으로_재시도할_수_있다() {
        boolean updated = repository.updateImageUploadStatus(
                "event-1", ImageUploadStatus.FAILED, ImageUploadStatus.PENDING
        );

        assertThat(updated).isTrue();
    }

    @Test
    void PROCESSED_이벤트에_이미지를_연결하면_COMPLETED로_갱신한다() {
        ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> captor = updateCaptor();

        boolean updated = repository.completeImageUpload("event-1", "training/s/events/CCTV_001/event-1.jpg", 1_000L);

        assertThat(updated).isTrue();
        verify(table).updateItem(captor.capture());
        CongestionEventItem item = captor.getValue().item();
        assertThat(item.getEventImageKey()).isEqualTo("training/s/events/CCTV_001/event-1.jpg");
        assertThat(item.getImageUploadedAt()).isEqualTo(1_000L);
        assertThat(item.getImageUploadStatus()).isEqualTo(ImageUploadStatus.COMPLETED);
    }

    @Test
    void 이미지_연결_조건이_맞지_않으면_false를_반환한다() {
        doThrow(ConditionalCheckFailedException.builder().message("conflict").build())
                .when(table).updateItem(any(UpdateItemEnhancedRequest.class));

        boolean updated = repository.completeImageUpload("event-1", "training/s/events/CCTV_001/event-1.jpg", 1_000L);

        assertThat(updated).isFalse();
    }

    @Test
    void 허용되지_않은_이벤트_상태_변경은_거부한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.updateEventStatus(
                        "event-1", EventProcessingStatus.RECEIVED, EventProcessingStatus.PROCESSED
                ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<PutItemEnhancedRequest<CongestionEventItem>> requestCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(PutItemEnhancedRequest.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<UpdateItemEnhancedRequest<CongestionEventItem>> updateCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(UpdateItemEnhancedRequest.class);
    }

    private CongestionEventItem item(String eventId, long detectedAt) {
        return item(eventId, detectedAt, CongestionEventType.CONGESTION_STARTED, CongestionLevel.CROWDED);
    }

    private CongestionEventItem item(
            String eventId, long detectedAt, CongestionEventType eventType, CongestionLevel congestionLevel) {
        return CongestionEventItem.received(
                UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8)), SESSION_ID,
                "CCTV_001", eventType,
                detectedAt, 9, 4.5, CongestionLevel.CROWDED, 4.5,
                congestionLevel, 1L, null
        );
    }
}
