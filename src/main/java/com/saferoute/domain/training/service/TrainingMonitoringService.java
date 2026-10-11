package com.saferoute.domain.training.service;

import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.service.CongestionConfigService;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.evacuation.recalculation.entity.RouteRecalculation;
import com.saferoute.domain.evacuation.recalculation.repository.RouteRecalculationRepository;
import com.saferoute.domain.telemetry.dynamo.entity.CurrentCctvStateItem;
import com.saferoute.domain.telemetry.dynamo.entity.LatestMonitoringCaptureItem;
import com.saferoute.domain.telemetry.dynamo.entity.ObservationItem;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CurrentCctvStateRepository;
import com.saferoute.domain.telemetry.dynamo.repository.GeneralMonitoringEventRepository;
import com.saferoute.domain.telemetry.dynamo.repository.LatestMonitoringCaptureRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationCountRepository;
import com.saferoute.domain.telemetry.dynamo.repository.ObservationRepository;
import com.saferoute.domain.training.dto.CurrentCctvStateListResponse;
import com.saferoute.domain.training.dto.CurrentCctvStateResponse;
import com.saferoute.domain.training.dto.MonitoringCameraListResponse;
import com.saferoute.domain.training.dto.MonitoringCameraResponse;
import com.saferoute.domain.training.dto.MonitoringContextResponse;
import com.saferoute.domain.training.dto.MonitoringEventListResponse;
import com.saferoute.domain.training.dto.MonitoringEventResponse;
import com.saferoute.domain.training.dto.MonitoringFrameListResponse;
import com.saferoute.domain.training.dto.MonitoringFrameResponse;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.s3.dto.PresignedGetUrl;
import com.saferoute.infrastructure.s3.service.S3PresignedUrlService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainingMonitoringService {

    private final TrainingSessionRepository trainingSessionRepository;
    private final CctvJpaRepository cctvJpaRepository;
    private final LatestMonitoringCaptureRepository latestMonitoringCaptureRepository;
    private final ObservationRepository observationRepository;
    private final ObservationCountRepository observationCountRepository;
    private final CongestionEventRepository congestionEventRepository;
    private final GeneralMonitoringEventRepository generalMonitoringEventRepository;
    private final CurrentCctvStateRepository currentCctvStateRepository;
    private final RouteRecalculationRepository routeRecalculationRepository;
    private final S3PresignedUrlService s3PresignedUrlService;
    private final SchoolContextService schoolContextService;
    private final CongestionConfigService congestionConfigService;

    public MonitoringCameraListResponse getCameras(UUID sessionId, String email) {
        TrainingSession session = findSessionForSchool(sessionId, email);
        UUID buildingId = session.getScenario().getBuilding().getId();
        List<Cctv> cctvs = cctvJpaRepository
                .findAllByEnabledTrueAndCustomNode_Floor_Building_IdOrderByCustomNode_Floor_FloorNumAscCodeAsc(
                        buildingId);
        if (cctvs.isEmpty()) {
            return new MonitoringCameraListResponse(sessionId, List.of());
        }
        Map<String, LatestMonitoringCaptureItem> capturesByCctvCode =
                latestMonitoringCaptureRepository.findAllBySessionId(sessionId.toString()).stream()
                        .collect(Collectors.toMap(
                                LatestMonitoringCaptureItem::getCctvCode,
                                Function.identity()
                        ));

        List<MonitoringCameraResponse> cameras = cctvs.stream()
                .map(cctv -> toResponse(cctv, capturesByCctvCode.get(cctv.getCode())))
                .toList();
        return new MonitoringCameraListResponse(sessionId, cameras);
    }

    // 모니터링 상세 화면 헤더에 필요한 세션 기본 정보 + 전역 설정값(저장 간격, stale 기준)을
    // 한 번에 제공한다. 다른 모니터링 조회 API와 동일하게 세션 상태와 무관하게 조회 가능하다.
    // 아직 시작 전(SCHEDULED)인 세션은 startedAt/elapsedSeconds가 모두 null이다.
    public MonitoringContextResponse getContext(UUID sessionId, String email) {
        TrainingSession session = findSessionForSchool(sessionId, email);
        CongestionConfig config = congestionConfigService.getConfig();
        Instant startedAt = session.getStartedAt();
        Instant endedAt = session.getEndedAt();
        Long elapsedSeconds = startedAt == null
                ? null
                : Duration.between(startedAt, endedAt != null ? endedAt : Instant.now()).getSeconds();

        return new MonitoringContextResponse(
                session.getId(),
                session.getScenario().getName(),
                session.getScenario().getBuilding().getName(),
                session.getStatus(),
                startedAt != null ? startedAt.toEpochMilli() : null,
                endedAt != null ? endedAt.toEpochMilli() : null,
                elapsedSeconds,
                config.getSnapshotIntervalSec(),
                config.getStateStaleAfterSec()
        );
    }

    // 상태가 아직 없는 CCTV도 목록에서 누락하지 않고 stale=true인 null 상태로 반환한다.
    // 기준 데이터가 주기 Observation이므로, 마지막 관측 이후 stateStaleAfterSec가 지났으면
    // congestionLevel 등이 남아있어도 stale=true로 표시해 "오래된 정보를 NORMAL로 오인"하는 것을 막는다.
    public CurrentCctvStateListResponse getCurrentStates(UUID sessionId, String email) {
        TrainingSession session = findSessionForSchool(sessionId, email);
        UUID buildingId = session.getScenario().getBuilding().getId();
        List<Cctv> cctvs = cctvJpaRepository
                .findAllByEnabledTrueAndCustomNode_Floor_Building_IdOrderByCustomNode_Floor_FloorNumAscCodeAsc(
                        buildingId);
        long observedAt = Instant.now().toEpochMilli();
        if (cctvs.isEmpty()) {
            return new CurrentCctvStateListResponse(sessionId, observedAt, List.of());
        }

        int stateStaleAfterSec = congestionConfigService.getConfig().getStateStaleAfterSec();
        Map<String, CurrentCctvStateItem> statesByCctvCode =
                currentCctvStateRepository.findAllBySessionId(sessionId.toString()).stream()
                        .collect(Collectors.toMap(
                                CurrentCctvStateItem::getCctvCode,
                                Function.identity()
                        ));

        List<CurrentCctvStateResponse> states = cctvs.stream()
                .map(cctv -> toStateResponse(cctv, statesByCctvCode.get(cctv.getCode()), observedAt, stateStaleAfterSec))
                .toList();
        return new CurrentCctvStateListResponse(sessionId, observedAt, states);
    }

    private CurrentCctvStateResponse toStateResponse(
            Cctv cctv, CurrentCctvStateItem item, long observedAt, int stateStaleAfterSec
    ) {
        if (item == null) {
            return CurrentCctvStateResponse.withoutState(cctv);
        }
        boolean stale = observedAt - item.getLastDetectedAt() > stateStaleAfterSec * 1_000L;
        return CurrentCctvStateResponse.withState(cctv, item, stale);
    }

    public MonitoringFrameListResponse getFrames(
            UUID sessionId,
            UUID cctvId,
            int limit,
            String cursor,
            String email
    ) {
        TrainingSession session = findSessionForSchool(sessionId, email);
        Cctv cctv = findCctvInSessionBuilding(cctvId, session);
        PageCursor.Position position = PageCursor.decode(cursor);
        Long beforeCapturedAt = position != null ? position.timestamp() : null;
        String beforeEventId = position != null ? position.id() : null;

        // hasNext 판단을 위해 요청한 개수보다 하나 더 조회한다.
        List<ObservationItem> page = observationRepository.findPageBySessionIdAndCctvCode(
                sessionId.toString(), cctv.getCode(), limit + 1, beforeCapturedAt, beforeEventId);

        boolean hasNext = page.size() > limit;
        List<ObservationItem> items = hasNext ? page.subList(0, limit) : page;

        List<MonitoringFrameResponse> frames = items.stream()
                .map(this::toFrameResponse)
                .toList();
        String nextCursor = hasNext
                ? PageCursor.encode(
                        items.get(items.size() - 1).getCapturedAt(),
                        items.get(items.size() - 1).getEventId())
                : null;

        long totalCount = observationCountRepository.find(sessionId.toString(), cctv.getCode())
                .orElseGet(() -> observationCountRepository
                        .countAllBySessionIdAndCctvCode(sessionId.toString(), cctv.getCode()));

        return new MonitoringFrameListResponse(sessionId, cctvId, frames, nextCursor, hasNext, totalCount);
    }

    // 혼잡 감지 이벤트(CongestionEventItem), 경로 재탐색 이벤트(RouteRecalculation), 일반 모니터링
    // 이벤트(GeneralMonitoringEventItem - AI 분석 시작/경로 이탈 감지)를 발생 시각 최신순으로 합쳐
    // 커서 페이지네이션한다. 관측값(ObservationItem)은 짧은 주기 스냅샷이라 타임라인에 넣으면 너무
    // 촘촘해져서 제외한다 - "이벤트"로 부를 만한 것들만 대상으로 한다. 재탐색 한 건은 요청 시점과
    // (있다면) 해소 시점 두 항목으로 나뉠 수 있다.
    //
    // 혼잡 이벤트/일반 모니터링 이벤트(둘 다 DynamoDB)는 세션당 수백 건까지도 쌓일 수 있어 커서
    // 페이지네이션 + cctvCode DB 필터가 필요하다. 재탐색 이벤트(PostgreSQL)는 쿨다운으로 발생
    // 빈도가 낮아 매 요청마다 전량 조회해도 무리가 없으므로 별도 페이지네이션 없이 가져온 뒤,
    // 다른 두 소스와 동일한 커서 경계로 걸러 중복 없이 병합한다.
    public MonitoringEventListResponse getEvents(
            UUID sessionId, String cctvCode, int limit, String cursor, String email
    ) {
        findSessionForSchool(sessionId, email);

        PageCursor.Position position = PageCursor.decode(cursor);
        Long beforeOccurredAt = position != null ? position.timestamp() : null;
        String beforeEventId = position != null ? position.id() : null;

        // hasNext 판단을 위해 요청한 개수보다 하나 더 조회한다.
        List<MonitoringEventResponse> congestionEvents = congestionEventRepository
                .findPageBySessionId(sessionId.toString(), cctvCode, limit + 1, beforeOccurredAt, beforeEventId)
                .stream()
                .map(MonitoringEventResponse::fromCongestionEvent)
                .toList();

        List<MonitoringEventResponse> generalEvents = generalMonitoringEventRepository
                .findPageBySessionId(sessionId.toString(), cctvCode, limit + 1, beforeOccurredAt, beforeEventId)
                .stream()
                .map(MonitoringEventResponse::fromGeneralEvent)
                .toList();

        List<MonitoringEventResponse> allRecalculationEvents = new ArrayList<>();
        routeRecalculationRepository.findAllByTrainingSession_IdOrderByRequestedAtDesc(sessionId).stream()
                .filter(recalculation -> matchesCctv(recalculation.getCctvCode(), cctvCode))
                .forEach(recalculation -> addRecalculationEvents(allRecalculationEvents, recalculation));
        List<MonitoringEventResponse> recalculationEvents = beforeOccurredAt == null
                ? allRecalculationEvents
                : allRecalculationEvents.stream()
                        .filter(event -> isBeforeCursor(event, beforeOccurredAt, beforeEventId))
                        .toList();

        List<MonitoringEventResponse> merged = new ArrayList<>(
                congestionEvents.size() + generalEvents.size() + recalculationEvents.size());
        merged.addAll(congestionEvents);
        merged.addAll(generalEvents);
        merged.addAll(recalculationEvents);
        merged.sort(EVENT_ORDER);

        boolean hasNext = merged.size() > limit;
        List<MonitoringEventResponse> page = hasNext ? merged.subList(0, limit) : merged;
        String nextCursor = hasNext
                ? PageCursor.encode(page.get(page.size() - 1).occurredAt(), page.get(page.size() - 1).eventId())
                : null;

        return new MonitoringEventListResponse(sessionId, page, nextCursor, hasNext);
    }

    // 발생 시각 내림차순(최신 먼저), 같은 시각이면 eventId 내림차순. isBeforeCursor()와 방향을
    // 맞춰야 페이지 경계에서 이벤트가 중복되거나 누락되지 않는다.
    private static final Comparator<MonitoringEventResponse> EVENT_ORDER = Comparator
            .comparingLong(MonitoringEventResponse::occurredAt).reversed()
            .thenComparing(Comparator.comparing(MonitoringEventResponse::eventId).reversed());

    // 커서보다 "이후 페이지"에 속하는(=더 오래된) 이벤트인지 판단한다. CongestionEventRepository의
    // sortLessThan 커서 조건과 동일한 (timestamp, id) 비교 규칙을 써야 두 소스를 병합했을 때 경계가
    // 어긋나지 않는다.
    private boolean isBeforeCursor(MonitoringEventResponse event, long beforeOccurredAt, String beforeEventId) {
        if (event.occurredAt() != beforeOccurredAt) {
            return event.occurredAt() < beforeOccurredAt;
        }
        return event.eventId().compareTo(beforeEventId) < 0;
    }

    private void addRecalculationEvents(List<MonitoringEventResponse> events, RouteRecalculation recalculation) {
        events.add(MonitoringEventResponse.requestedFrom(recalculation));
        if (recalculation.getResolvedAt() != null) {
            events.add(MonitoringEventResponse.resolvedFrom(recalculation));
        }
    }

    private boolean matchesCctv(String eventCctvCode, String filterCctvCode) {
        return filterCctvCode == null || Objects.equals(eventCctvCode, filterCctvCode);
    }

    private Cctv findCctvInSessionBuilding(UUID cctvId, TrainingSession session) {
        UUID buildingId = session.getScenario().getBuilding().getId();
        return cctvJpaRepository.findByIdAndCustomNode_Floor_Building_Id(cctvId, buildingId)
                .orElseThrow(() -> new ApiException(CctvErrorCode.CCTV_NOT_FOUND));
    }

    private MonitoringFrameResponse toFrameResponse(ObservationItem item) {
        if (item.getMonitoringImageKey() == null || item.getMonitoringImageKey().isBlank()) {
            return MonitoringFrameResponse.withoutImage(item);
        }
        PresignedGetUrl presignedGetUrl = s3PresignedUrlService.createGetUrl(item.getMonitoringImageKey());
        return MonitoringFrameResponse.withImage(item, presignedGetUrl);
    }

    // 읽기 전용 조회이므로 세션 상태는 검증하지 않는다 - 존재 여부와 요청자 학교 소속만 확인한다.
    // 종료된(COMPLETED/STOPPED/FAILED) 세션도 마지막 저장된 데이터를 그대로 조회할 수 있어야
    // 훈련 종료 직후나 새로고침 후에도 화면이 깨지지 않는다. Pi가 데이터를 보내는 쓰기 경로
    // (CongestionObservationService/CongestionEventService)는 이 정책과 무관하게 RUNNING만 계속 허용한다.
    private TrainingSession findSessionForSchool(UUID sessionId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        return trainingSessionRepository
                .findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
                .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));
    }

    private MonitoringCameraResponse toResponse(
            Cctv cctv,
            LatestMonitoringCaptureItem capture
    ) {
        if (!hasMonitoringImage(capture)) {
            return MonitoringCameraResponse.withoutCapture(cctv);
        }
        return withCapture(cctv, capture);
    }

    private boolean hasMonitoringImage(LatestMonitoringCaptureItem capture) {
        return capture != null
                && capture.getMonitoringImageKey() != null
                && !capture.getMonitoringImageKey().isBlank();
    }

    private MonitoringCameraResponse withCapture(Cctv cctv, LatestMonitoringCaptureItem capture) {
        PresignedGetUrl presignedGetUrl =
                s3PresignedUrlService.createGetUrl(capture.getMonitoringImageKey());
        return MonitoringCameraResponse.withCapture(
                cctv,
                capture.getCapturedAt(),
                presignedGetUrl
        );
    }
}
