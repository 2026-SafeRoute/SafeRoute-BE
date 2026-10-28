package com.saferoute.domain.congestion.service;

import com.saferoute.domain.congestion.dto.request.ReportObservationRequest;
import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.entity.CongestionLevel;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.CctvGridCell;
import com.saferoute.domain.device.repository.CctvGridCellRepository;
import com.saferoute.domain.device.util.MonitoredAreaCalculator;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.grid.entity.MapEdgeGridCell;
import com.saferoute.domain.evacuation.grid.repository.MapEdgeGridCellRepository;
import com.saferoute.domain.evacuation.deviation.service.RouteDeviationService;
import com.saferoute.domain.evacuation.recalculation.entity.RecalculationTriggerType;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventItem;
import com.saferoute.domain.telemetry.dynamo.entity.GeneralMonitoringEventType;
import com.saferoute.domain.telemetry.dynamo.entity.LatestMonitoringCaptureItem;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import com.saferoute.domain.telemetry.dynamo.repository.GeneralMonitoringEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.IdempotentSaveResult;
import com.saferoute.domain.telemetry.dynamo.repository.LatestMonitoringCaptureRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationCountRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.CongestionErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.service.S3Service;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Pi가 snapshotIntervalSec(기본 1초)마다 보내는 관측값을 받아 BE가 직접 밀도·혼잡 단계를 계산하고 저장한다.
// 멱등 저장/처리 lease/after-commit 발행 구조는 CongestionEventService와 유사한 패턴을 쓴다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CongestionObservationService {

    static final Duration PROCESSING_LEASE = Duration.ofMinutes(1);
    // Pi 시계(capturedAt)와 서버 시각 차이가 이보다 크면 경고한다. 최신 여부/stale 판정은 서버 수신 시각으로
    // 하므로 동작은 계속되지만, 리포트의 구간 시각 등 Pi 시계를 그대로 쓰는 곳이 남아 있어 운영자가 알아야 한다.
    static final long CLOCK_SKEW_WARN_THRESHOLD_MS = 10_000L;
    // 관측값이 1초마다 오므로 같은 CCTV의 시계 경고는 이 간격으로만 남긴다.
    private static final long CLOCK_SKEW_WARN_INTERVAL_MS = 60_000L;
    // 관측값 1건의 서버 처리(수신~WebSocket 발행)가 이보다 오래 걸리면 단계별 소요를 WARN으로 남긴다.
    // Pi가 1초마다 보내므로 이 값을 넘기 시작하면 Pi 응답 지연 -> 재전송으로 번진다.
    static final long SLOW_OBSERVATION_WARN_MS = 500L;

    private static final String TRAINING_PREFIX = "training";
    private static final String MONITORING_DIRECTORY = "monitoring";
    private static final String JPEG_SUFFIX = ".jpg";

    private final Map<String, Long> clockSkewLastWarnedAt = new ConcurrentHashMap<>();

    private final ObservationRepository observationRepository;
    private final ObservationCountRepository observationCountRepository;
    private final GeneralMonitoringEventRepository generalMonitoringEventRepository;
    private final LatestMonitoringCaptureRepository latestMonitoringCaptureRepository;
    private final CurrentCctvStateRepository currentCctvStateRepository;
    private final TrainingSessionRepository trainingSessionRepository;
    private final CctvGridCellRepository cctvGridCellRepository;
    private final MapEdgeGridCellRepository mapEdgeGridCellRepository;
    private final CongestionConfigService congestionConfigService;
    private final RouteRecalculationService routeRecalculationService;
    private final RouteDeviationService routeDeviationService;
    private final TrainingEventPublisher trainingEventPublisher;
    private final S3Service s3Service;

    @Transactional
    public IdempotentSaveResult<ObservationItem> reportObservation(Cctv cctv, ReportObservationRequest request) {
        // 서버 수신 시각. Pi 시계(capturedAt)는 저장만 하고, 최신 여부/stale/지연 판정은 전부 이 값으로 한다.
        long receivedAt = Instant.now().toEpochMilli();
        StageTimer timer = new StageTimer(cctv.getCode());
        warnIfClockSkewed(cctv.getCode(), request.capturedAt(), receivedAt);

        Floor floor = cctv.getCustomNode().getFloor();
        UUID buildingId = floor.getBuilding().getId();
        TrainingSession session = trainingSessionRepository
                .findByIdAndStatusAndScenario_Building_Id(
                        request.trainingSessionId(), TrainingStatus.RUNNING, buildingId)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.RUNNING_TRAINING_SESSION_NOT_FOUND));

        CongestionConfig config = congestionConfigService.getConfig();
        Double monitoredAreaM2 = MonitoredAreaCalculator.calculate(
                cctvGridCellRepository.countByCctv_Id(cctv.getId()), floor.getGridCellSizeMeter());
        // GridCell이 하나도 없으면 0.0㎡로 계산되는데, 그대로 나누면 density가 무한대가 되므로 여기서도 막는다.
        if (monitoredAreaM2 == null || monitoredAreaM2 <= 0) {
            throw new ApiException(CongestionErrorCode.MONITORED_AREA_NOT_AVAILABLE);
        }

        String monitoringImageKey = validateMonitoringImageKey(
                session.getId(), cctv.getCode(), request.capturedAt(), request.monitoringImageKey());

        double density = request.avgHeadcount() / monitoredAreaM2;
        CongestionLevel level = config.classify(density);
        Double frameDensity = request.frameHeadcount() == null
                ? null
                : request.frameHeadcount() / monitoredAreaM2;
        CongestionLevel frameLevel = frameDensity == null ? null : config.classify(frameDensity);

        ObservationItem item = ObservationItem.create(
                request.eventId(),
                session.getId(),
                null,
                cctv.getCode(),
                request.avgHeadcount(),
                request.peakHeadcount(),
                request.frameHeadcount(),
                request.sampleCount(),
                density,
                level,
                frameDensity,
                frameLevel,
                request.windowStart(),
                request.windowEnd(),
                request.capturedAt(),
                monitoringImageKey,
                request.configVersion()
        );
        item.setReceivedAt(receivedAt);
        timer.mark("validate");

        IdempotentSaveResult<ObservationItem> saveResult = observationRepository.saveIfAbsent(item);
        validateEventIdentity(saveResult.item(), request, session.getId());
        timer.mark("save");
        // 이미지가 없는 관측값(Pi가 그 초의 S3 업로드를 건너뛴 경우 등)은 카메라 화면의 최신 캡처 포인터를 건드리지
        // 않는다 - 덮어쓰면 그 1초 때문에 마지막 정상 이미지가 사라져 다음 업로드 성공까지 빈 화면이 된다.
        if (monitoringImageKey != null) {
            updateLatestMonitoringCapture(
                    session.getId(), cctv.getCode(), request.capturedAt(), receivedAt, monitoringImageKey);
        }
        timer.mark("capture");
        tryCreateAiAnalysisStartedEvent(session.getId(), cctv.getCode(), request.capturedAt());
        // 경로 이탈 판정은 건물 내 유도등 수만큼 조회를 반복하므로 Pi 응답 경로에서 떼어낸다(전용 풀).
        routeDeviationService.evaluateObservationAsync(buildingId, saveResult.item());
        tryIncrementObservationCount(saveResult, session.getId(), cctv.getCode());
        timer.mark("count");

        String processingOwner = UUID.randomUUID().toString();
        long processingStartedAt = Instant.now().toEpochMilli();
        boolean claimed = observationRepository.claimProcessing(
                saveResult.item().getEventId(),
                processingOwner,
                processingStartedAt,
                processingStartedAt + PROCESSING_LEASE.toMillis()
        );
        timer.mark("claim");
        if (!claimed) {
            timer.finish(saveResult.item().getEventId());
            return saveResult;
        }

        try {
            List<MapEdge> affectedEdges = resolveAffectedEdges(cctv.getId());
            timer.mark("edges");
            updateCurrentState(session.getId(), cctv.getCode(), request, saveResult.item(), receivedAt);
            timer.mark("state");

            CongestionLevel savedLevel = saveResult.item().getCongestionLevel();
            // 관측값은 주기적 스냅샷이라 STARTED/ENDED 같은 이벤트 구분이 없다 - 항상 LEVEL_UP으로 취급한다
            // (즉시 이벤트의 CONGESTION_ENDED 기반 복구 트리거는 CongestionEventService가 전담한다).
            if (savedLevel.requiresRouteRecalculation()) {
                if (affectedEdges.isEmpty()) {
                    log.warn(
                            "혼잡 레벨이 재계산 기준을 충족했지만 감시 중인 MapEdge가 없어 재계산을 건너뜀: "
                                    + "sessionId={}, cctvCode={}, level={}",
                            session.getId(), cctv.getCode(), savedLevel
                    );
                } else {
                    // 관측값은 항상 LEVEL_UP이라 혼잡이 지속되면 관측 주기마다 재시도되므로 비동기로
                    // 떼어내도 안전하다 (#250) - ENDED는 이 서비스가 다루지 않는다
                    // (CongestionEventService 참고). MapEdge 엔티티가 아니라 id만 넘기고,
                    // 적재 시각(서버 시각)을 함께 넘겨 실행기 큐에 너무 오래 밀린 낡은 판단은
                    // triggerAsync가 스스로 건너뛰게 한다.
                    routeRecalculationService.triggerAsync(
                            session, affectedEdges.stream().map(MapEdge::getId).toList(), savedLevel,
                            RecalculationTriggerType.LEVEL_UP, cctv.getCode(), density, receivedAt);
                }
            }
            // 큐가 꽉 차면 CallerRunsPolicy로 위 triggerAsync가 이 스레드에서 동기 실행되므로 그 시간도 여기 잡힌다.
            timer.mark("trigger");
            publishAndCompleteAfterCommit(session.getId(), affectedEdges, saveResult.item(), processingOwner, timer);
        } catch (ApiException exception) {
            failProcessing(saveResult.item().getEventId(), processingOwner, exception);
            throw exception;
        } catch (RuntimeException exception) {
            failProcessing(saveResult.item().getEventId(), processingOwner, exception);
            throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
        }
        return saveResult;
    }

    // CCTV -> CctvGridCell -> FloorGridCell -> MapEdgeGridCell -> MapEdge 연쇄 조회.
    // CCTV 한 대가 여러 Edge를 감시할 수 있어 목록으로 반환한다 (Cctv.java 클래스 주석 참고).
    private List<MapEdge> resolveAffectedEdges(UUID cctvId) {
        List<UUID> gridCellIds = cctvGridCellRepository
                .findAllByCctv_IdOrderByGridCell_RowIndexAscGridCell_ColumnIndexAsc(cctvId)
                .stream()
                .map(mapping -> mapping.getGridCell().getId())
                .toList();
        if (gridCellIds.isEmpty()) {
            return List.of();
        }
        return mapEdgeGridCellRepository.findAllByGridCell_IdIn(gridCellIds).stream()
                .map(MapEdgeGridCell::getMapEdge)
                .distinct()
                .toList();
    }

    private void updateCurrentState(
            UUID sessionId, String cctvCode, ReportObservationRequest request, ObservationItem item, long receivedAt
    ) {
        CurrentCctvStateItem stateItem = CurrentCctvStateItem.create(
                sessionId,
                cctvCode,
                item.getAvgHeadcount(),
                item.getPeakHeadcount(),
                item.getDensity(),
                item.getCongestionLevel(),
                request.capturedAt(),
                receivedAt,
                request.configVersion()
        );
        // 오래된 관측값이 최신 상태를 덮어쓰지 않도록 조건부 갱신 (서버 수신 시각 기준). 실패해도 관측값 저장 자체는 유지한다.
        if (!currentCctvStateRepository.updateIfLatest(stateItem)) {
            log.debug("더 최신 상태가 있어 CCTV 현재 상태 갱신을 건너뜀: cctvCode={}", cctvCode);
        }
    }

    private void updateLatestMonitoringCapture(
            UUID sessionId,
            String cctvCode,
            long capturedAt,
            long receivedAt,
            String monitoringImageKey
    ) {
        LatestMonitoringCaptureItem capture = LatestMonitoringCaptureItem.create(
                sessionId,
                cctvCode,
                capturedAt,
                receivedAt,
                monitoringImageKey
        );
        if (!latestMonitoringCaptureRepository.updateIfLatest(capture)) {
            log.debug("더 최신 캡처가 있어 모니터링 포인터 갱신을 건너뜀: cctvCode={}", cctvCode);
        }
    }

    // Pi 시계가 서버와 많이 어긋나면 운영자가 알 수 있게 CCTV당 1분에 한 번만 경고하고 메트릭을 올린다.
    private void warnIfClockSkewed(String cctvCode, long capturedAt, long receivedAt) {
        long skewMs = receivedAt - capturedAt;
        if (Math.abs(skewMs) <= CLOCK_SKEW_WARN_THRESHOLD_MS) {
            return;
        }
        Metrics.counter("saferoute.observation.clock_skew", "cctvCode", cctvCode).increment();
        Long lastWarnedAt = clockSkewLastWarnedAt.get(cctvCode);
        if (lastWarnedAt != null && receivedAt - lastWarnedAt < CLOCK_SKEW_WARN_INTERVAL_MS) {
            return;
        }
        clockSkewLastWarnedAt.put(cctvCode, receivedAt);
        log.warn("Pi 시계가 서버와 어긋남(NTP 확인 필요): cctvCode={}, skewMs={} (양수면 Pi가 느림)", cctvCode, skewMs);
    }

    // 세션+CCTV 조합의 첫 유효 Observation 저장 시점에 AI_ANALYSIS_STARTED 일반 모니터링 이벤트를 생성한다.
    // eventId를 세션+CCTV+이벤트타입으로부터 결정적으로 만들어 attribute_not_exists 조건부 put에 태우면,
    // 이 저장 시도 하나만으로 "세션+CCTV당 정확히 한 번" 생성을 보장할 수 있다 (별도 마커/플래그 불필요).
    // 실패해도 Observation 저장 자체(reportObservation 전체)는 실패시키지 않는다.
    private void tryCreateAiAnalysisStartedEvent(UUID sessionId, String cctvCode, long capturedAt) {
        try {
            String eventId = UUID.nameUUIDFromBytes(
                    ("AI_ANALYSIS_STARTED:" + sessionId + ":" + cctvCode).getBytes(StandardCharsets.UTF_8)
            ).toString();
            GeneralMonitoringEventItem item = GeneralMonitoringEventItem.create(
                    eventId,
                    sessionId.toString(),
                    cctvCode,
                    GeneralMonitoringEventType.AI_ANALYSIS_STARTED,
                    capturedAt,
                    null
            );
            IdempotentSaveResult<GeneralMonitoringEventItem> result =
                    generalMonitoringEventRepository.saveIfAbsent(item);
            if (result.created()) {
                trainingEventPublisher.publishGeneralMonitoringEventReceived(sessionId, result.item());
            }
        } catch (RuntimeException exception) {
            log.error(
                    "AI_ANALYSIS_STARTED 이벤트 생성 중 오류: sessionId={}, cctvCode={}",
                    sessionId, cctvCode, exception
            );
        }
    }

    // Observation이 이번에 새로 저장된 경우(중복 재시도가 아닌 경우)에만 세션+CCTV별 저장 개수 카운터를
    // 1 증가시킨다. 실패해도 Observation 저장 자체(reportObservation() 전체)는 실패시키지 않는다.
    private void tryIncrementObservationCount(
            IdempotentSaveResult<ObservationItem> saveResult, UUID sessionId, String cctvCode
    ) {
        if (!saveResult.created()) {
            return;
        }
        try {
            observationCountRepository.increment(sessionId.toString(), cctvCode);
        } catch (RuntimeException exception) {
            log.error(
                    "Observation 카운터 증가 중 오류: sessionId={}, cctvCode={}",
                    sessionId, cctvCode, exception
            );
        }
    }

    // monitoringImageKey는 Pi가 임의로 채워 보내는 문자열이라, canonical 경로 형식과 요청 신원(세션/CCTV/캡처시각)이
    // 일치하고 S3에 실제 객체가 있을 때만 저장한다. 다른 세션/CCTV의 S3 객체를 가리키는 변조된 key가 저장되는
    // 것과, 업로드가 실패/지연된 객체를 가리켜 화면에 깨진 이미지가 뜨는 것을 막기 위함.
    // 어느 검사든 실패하면 예외 대신 null(이미지 없음)로 처리한다 - 이미지 key 하나 때문에 그 초의 인원/밀도
    // 관측값까지 400으로 버리면 혼잡 데이터가 통째로 유실되기 때문이다(검증 실패는 WARN으로 남긴다).
    // 빈 문자열도 null로 정규화한다 - CongestionImageUrlService는 null만 이미지 없음으로 취급한다.
    private String validateMonitoringImageKey(
            UUID sessionId, String cctvCode, long capturedAt, String monitoringImageKey
    ) {
        if (monitoringImageKey == null || monitoringImageKey.isBlank()) {
            return null;
        }

        try {
            validateMonitoringImageKeyFormatAndIdentity(sessionId, cctvCode, capturedAt, monitoringImageKey);
        } catch (ApiException exception) {
            log.warn("monitoringImageKey 검증 실패로 이미지 없이 관측값만 저장: cctvCode={}, key={}, reason={}",
                    cctvCode, monitoringImageKey, exception.getErrorCode());
            Metrics.counter("saferoute.observation.image_rejected", "cctvCode", cctvCode).increment();
            return null;
        }
        return verifyMonitoringImageExists(cctvCode, monitoringImageKey);
    }

    // S3에 객체가 없으면(Pi의 PUT 실패/지연, presigned URL 만료) 이미지 없음으로 저장한다. 존재 확인 자체가
    // 실패(S3 장애 등)하면 key를 그대로 믿는다 - S3가 잠깐 흔들린다고 멀쩡한 이미지까지 화면에서 지우지 않기 위함.
    private String verifyMonitoringImageExists(String cctvCode, String monitoringImageKey) {
        try {
            if (s3Service.objectExists(monitoringImageKey)) {
                return monitoringImageKey;
            }
        } catch (RuntimeException exception) {
            log.warn("S3 객체 존재 확인 실패, 이미지 key를 그대로 저장: cctvCode={}, key={}",
                    cctvCode, monitoringImageKey, exception);
            return monitoringImageKey;
        }
        log.warn("S3에 모니터링 이미지가 없어 이미지 없이 관측값만 저장(Pi 업로드 실패/지연 의심): cctvCode={}, key={}",
                cctvCode, monitoringImageKey);
        Metrics.counter("saferoute.observation.image_missing", "cctvCode", cctvCode).increment();
        return null;
    }

    private void validateMonitoringImageKeyFormatAndIdentity(
            UUID sessionId, String cctvCode, long capturedAt, String monitoringImageKey
    ) {
        String[] segments = monitoringImageKey.split("/", -1);
        if (segments.length != 5
                || !TRAINING_PREFIX.equals(segments[0])
                || !MONITORING_DIRECTORY.equals(segments[2])
                || !segments[4].endsWith(JPEG_SUFFIX)) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID);
        }

        UUID keySessionId = parseCanonicalUuid(segments[1]);
        String capturedAtSegment = segments[4].substring(0, segments[4].length() - JPEG_SUFFIX.length());
        long keyCapturedAt = parseCapturedAt(capturedAtSegment);

        boolean sameIdentity = sessionId.equals(keySessionId)
                && Objects.equals(cctvCode, segments[3])
                && capturedAt == keyCapturedAt;
        if (!sameIdentity) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_IDENTITY_MISMATCH);
        }
    }

    private UUID parseCanonicalUuid(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID, exception);
        }
    }

    private long parseCapturedAt(String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID, exception);
        }
        // Long.parseLong은 "+2000", "02000" 같은 비canonical 표기도 허용하므로,
        // 다시 문자열로 되돌렸을 때 원본과 같은지 확인해 canonical decimal만 통과시킨다.
        if (!Long.toString(parsed).equals(value)) {
            throw new ApiException(CongestionErrorCode.MONITORING_IMAGE_KEY_INVALID);
        }
        return parsed;
    }

    private void validateEventIdentity(ObservationItem item, ReportObservationRequest request, UUID sessionId) {
        boolean sameIdentity = Objects.equals(item.getTrainingSessionId(), sessionId.toString())
                && Objects.equals(item.getCctvCode(), request.cctvCode());
        if (!sameIdentity) {
            throw new ApiException(CongestionErrorCode.EVENT_IDENTITY_MISMATCH);
        }
    }

    private void publishAndCompleteAfterCommit(
            UUID sessionId, List<MapEdge> affectedEdges, ObservationItem item, String processingOwner,
            StageTimer timer
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishCongestionUpdated(sessionId, affectedEdges, item);
            timer.mark("publish");
            completeProcessing(item.getEventId(), processingOwner);
            timer.mark("complete");
            timer.finish(item.getEventId());
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // JPA 커밋 자체에 걸린 시간은 직전 mark 이후 ~ 여기까지로 잡힌다.
                    timer.mark("commit");
                    publishCongestionUpdated(sessionId, affectedEdges, item);
                    timer.mark("publish");
                    completeProcessing(item.getEventId(), processingOwner);
                    timer.mark("complete");
                    timer.finish(item.getEventId());
                } catch (RuntimeException exception) {
                    failProcessing(item.getEventId(), processingOwner, exception);
                    throw new ApiException(CongestionErrorCode.EVENT_PROCESSING_FAILED, exception);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    failProcessing(item.getEventId(), processingOwner, null);
                }
            }
        });
    }

    // CCTV당 1 Observation = 1 WebSocket 이벤트 원칙을 지키기 위해 영향받는 Edge 전체를 배열로 담아
    // 한 번만 발행한다 (이슈 #192). Edge가 없으면(감시 영역에 아직 GridCell/Edge 매핑이 안 된 CCTV)
    // 빈 목록으로 발행해 대시보드가 최소한 CCTV 단위 관측값은 볼 수 있게 한다.
    private void publishCongestionUpdated(UUID sessionId, List<MapEdge> affectedEdges, ObservationItem item) {
        List<UUID> affectedEdgeIds = affectedEdges.stream().map(MapEdge::getId).distinct().toList();
        trainingEventPublisher.publishCongestionUpdated(sessionId, affectedEdgeIds, item);
    }

    private void completeProcessing(String eventId, String processingOwner) {
        try {
            if (!observationRepository.completeProcessing(eventId, processingOwner)) {
                log.warn("혼잡 관측 처리 완료 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException exception) {
            log.error("혼잡 관측 처리 완료 상태 저장 중 오류: eventId={}", eventId, exception);
        }
    }

    private void failProcessing(String eventId, String processingOwner, RuntimeException cause) {
        try {
            if (!observationRepository.failProcessing(eventId, processingOwner)) {
                log.warn("혼잡 관측 처리 실패 상태 갱신 실패: eventId={}", eventId);
            }
        } catch (RuntimeException statusException) {
            if (cause != null) {
                cause.addSuppressed(statusException);
            }
            log.error("혼잡 관측 처리 실패 상태 저장 중 오류: eventId={}", eventId, statusException);
        }
    }

    // 관측값 1건의 서버 처리 단계별 소요 시간. 프론트가 보는 "capturedAt 대비 지연"이 Pi/S3 쪽(수신 전)인지
    // BE 쪽(수신~발행)인지, BE 안에서는 어느 단계(DynamoDB 저장, 재탐색 동기 degrade, 커밋, 발행 등)인지
    // 구분할 수 있게 단계별 ms를 메트릭(saferoute.observation.stage{stage=...})과 로그로 남긴다.
    private static final class StageTimer {

        private final String cctvCode;
        private final long startedAtNanos = System.nanoTime();
        private final StringBuilder breakdown = new StringBuilder();
        private long lastMarkNanos = startedAtNanos;

        private StageTimer(String cctvCode) {
            this.cctvCode = cctvCode;
        }

        void mark(String stage) {
            long now = System.nanoTime();
            long elapsedMs = (now - lastMarkNanos) / 1_000_000L;
            lastMarkNanos = now;
            Metrics.timer("saferoute.observation.stage", "stage", stage).record(Duration.ofMillis(elapsedMs));
            breakdown.append(stage).append('=').append(elapsedMs).append("ms ");
        }

        void finish(String eventId) {
            long totalMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
            Metrics.timer("saferoute.observation.total").record(Duration.ofMillis(totalMs));
            if (totalMs > SLOW_OBSERVATION_WARN_MS) {
                log.warn("관측값 처리가 느림: cctvCode={}, eventId={}, totalMs={}, stages=[{}]",
                        cctvCode, eventId, totalMs, breakdown.toString().trim());
            } else if (log.isDebugEnabled()) {
                log.debug("관측값 처리 소요: cctvCode={}, eventId={}, totalMs={}, stages=[{}]",
                        cctvCode, eventId, totalMs, breakdown.toString().trim());
            }
        }
    }
}
