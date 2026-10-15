package com.saferoute.domain.training.service;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.device.service.IoTLightService;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.recalculation.dto.response.CurrentRouteResponse;
import com.saferoute.domain.evacuation.service.EvacuationRoute;
import com.saferoute.domain.evacuation.service.EvacuationRouteService;
import com.saferoute.domain.training.dto.CreateSessionRequest;
import com.saferoute.domain.training.dto.EndedSessionResponse;
import com.saferoute.domain.training.dto.RunningSessionResponse;
import com.saferoute.domain.training.dto.ScheduledSessionResponse;
import com.saferoute.domain.training.dto.TrainingSessionListResponse;
import com.saferoute.domain.training.dto.TrainingSessionResponse;
import com.saferoute.domain.training.dto.TrainingSessionSummaryResponse;
import com.saferoute.domain.training.dto.TrainingStatusResponse;
import com.saferoute.domain.training.entity.ScenarioStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.evacuation.recalculation.service.RouteRecalculationService;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.domain.user.service.SchoolContextService;
import com.saferoute.global.api.code.ErrorCode;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingSessionService {

  // 텔레메트리(personCount) 수신 파이프라인이 아직 없어, 3분 공백 기반 정밀 타임아웃 판정 대신
  // 1단계로 RUNNING 세션에 대한 단순 하드 타임아웃만 적용한다. 카메라 연동 완료 후 별도 이슈에서 대체 예정.
  public static final Duration TRAINING_TIMEOUT = Duration.ofMinutes(10);

  private final UserRepository userRepository;
  private final TrainingSessionRepository trainingSessionRepository;
  private final TrainingScenarioRepository trainingScenarioRepository;
  private final FireZoneRepository fireZoneRepository;
  private final RouteRecalculationService routeRecalculationService;
  private final EvacuationRouteService evacuationRouteService;
  private final IoTLightService ioTLightService;
  private final TrainingEventPublisher trainingEventPublisher;
  private final SchoolContextService schoolContextService;
  private final BuildingRepository buildingRepository;

  @Transactional
  public TrainingSessionResponse create(CreateSessionRequest request, UUID scenarioId, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    User user = userRepository.findByIdAndSchoolName(request.getAdminId(), schoolName)
        .orElseThrow(() -> new ApiException(TrainingErrorCode.ADMIN_NOT_FOUND));
    if (user.getRole() != UserRole.MANAGER) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    TrainingScenario scenario = trainingScenarioRepository.findByIdAndAdmin_SchoolName(scenarioId, schoolName)
        .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SCENARIO_NOT_FOUND));

    // 시나리오 작성이 아직 완료되지 않은(DRAFT) 상태에서는 발화점·START 설정 자체가
    // 불가능하므로, 세션을 먼저 막아 이후의 evacuation-setup/훈련 시작 흐름도 함께 차단한다.
    if (scenario.getStatus() == ScenarioStatus.DRAFT) {
      throw new ApiException(TrainingErrorCode.SCENARIO_DRAFT_NOT_ALLOWED);
    }

    if (trainingSessionRepository.existsByScenario_Id(scenarioId)) {
      throw new ApiException(TrainingErrorCode.SESSION_ALREADY_EXISTS);
    }

    TrainingSession trainingSession = TrainingSession.schedule(user, scenario);

    // existsByScenario_Id 체크와 save는 원자적이지 않아 동시 요청이 둘 다 통과할 수 있다.
    // 이 경우 뒤늦게 실패하는 쪽은 UNIQUE 제약 위반으로 걸러지므로, saveAndFlush로 INSERT를
    // 즉시 실행시켜 그 예외를 여기서 도메인 에러로 변환한다.
    try {
      return TrainingSessionResponse.from(trainingSessionRepository.saveAndFlush(trainingSession));
    } catch (DataIntegrityViolationException e) {
      throw new ApiException(TrainingErrorCode.SESSION_ALREADY_EXISTS);
    }
  }

  // 모니터링 화면 진입점: 프론트가 이 목록에서 sessionId를 얻어 모니터링 화면으로 이동한다.
  @Transactional(readOnly = true)
  public TrainingSessionListResponse getSessions(TrainingStatus status, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    List<TrainingSession> trainingSessions = status == TrainingStatus.SCHEDULED
        ? trainingSessionRepository
            .findAllByStatusAndScenario_Building_SchoolNameOrderByCreatedAtDesc(status, schoolName)
        : trainingSessionRepository
            .findAllByStatusAndScenario_Building_SchoolNameOrderByStartedAtDesc(status, schoolName);
    List<TrainingSessionSummaryResponse> sessions = trainingSessions
        .stream()
        .map(TrainingSessionSummaryResponse::from)
        .toList();
    return new TrainingSessionListResponse(sessions);
  }

  @Transactional(readOnly = true)
  public TrainingStatusResponse getTrainingStatus(UUID sessionId, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    TrainingSession session = trainingSessionRepository
        .findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
        .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));

    TrainingScenario scenario = session.getScenario();
    Building building = scenario.getBuilding();

    if (session.getStatus() == TrainingStatus.SCHEDULED) {
      return new ScheduledSessionResponse(
          building.getName(),
          building.getTotalFloors(),
          scenario.getScheduledAt(),
          scenario.getExpectedParticipants()
      );
    } else if (session.getStatus() == TrainingStatus.RUNNING) {
      long elapsed = Instant.now().getEpochSecond() - session.getStartedAt().getEpochSecond();
      return new RunningSessionResponse(
          building.getName(),
          elapsed,
          session.getActualParticipants() != null ? session.getActualParticipants() : 0,
          session.getCurrentSurvivalRate() != null ? session.getCurrentSurvivalRate() : BigDecimal.ZERO,
          session.getRouteBlockedAt(),
          session.routeBlockedAfterSec()
      );
    }
    // COMPLETED/FAILED/CANCELLED/STOPPED - 종료/강제종료/타임아웃 직후 화면이 상태를
    // 다시 조회해도 오류 없이 마지막 상태를 받을 수 있게 한다 (이슈 #254).
    Long elapsedSeconds = (session.getStartedAt() != null && session.getEndedAt() != null)
        ? session.getEndedAt().getEpochSecond() - session.getStartedAt().getEpochSecond()
        : null;
    return new EndedSessionResponse(
        building.getName(),
        session.getStatus(),
        session.getEndedAt(),
        elapsedSeconds,
        session.getActualParticipants() != null ? session.getActualParticipants() : 0,
        session.getCurrentSurvivalRate() != null ? session.getCurrentSurvivalRate() : BigDecimal.ZERO,
        session.getRouteBlockedAt(),
        session.routeBlockedAfterSec()
    );
  }

  @Transactional
  public TrainingSessionResponse start(UUID sessionId, String email) {
    TrainingSession session = findSession(sessionId, email);

    if (session.getStatus() != TrainingStatus.SCHEDULED) {
      throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
    }

    if (hasRunningSession(session.getScenario().getBuildingId())) {
      throw new ApiException(TrainingErrorCode.RUNNING_SESSION_ALREADY_EXISTS);
    }

    // 유도등 반영 전 최초 경로부터 계산해, EXIT 미지정/도달 불가 시 세션 상태를 바꾸지 않고 막는다.
    MapNode startNode = session.getScenario().getStartNode();
    if (startNode == null) {
      throw new ApiException(TrainingErrorCode.START_NODE_NOT_CONFIGURED);
    }
    boolean validStartType = startNode.getType() == NodeType.START
        || (startNode.getType() == NodeType.DOOR && startNode.isStartCandidate());
    if (!validStartType) {
      throw new ApiException(TrainingErrorCode.START_NODE_TYPE_INVALID);
    }
    List<FireZone> fireOrigins = fireZoneRepository
        .findByScenario_IdAndIsManualAddTrue(session.getScenario().getId());
    if (fireOrigins.isEmpty()) {
      throw new ApiException(TrainingErrorCode.FIRE_ORIGIN_NOT_CONFIGURED);
    }
    UUID startFloorId = startNode.getFloor().getId();
    if (fireOrigins.stream().anyMatch(origin -> !startFloorId.equals(origin.getFloorId()))) {
      throw new ApiException(TrainingErrorCode.FIRE_ORIGIN_START_FLOOR_MISMATCH);
    }

    // 발화점 설정(POST .../evacuation-setup)은 시나리오별 정적 데이터만 저장하고 셀을 실제
    // 화재 상태로 바꾸지 않는다. isFired는 훈련 중에만 의미 있는 동적 상태이므로 여기,
    // 훈련이 실제로 시작되는 시점에 최초 발화점 셀을 활성화한다.
    fireOrigins.forEach(origin -> origin.getGridCell().markFired());

    // 방금 활성화한 발화점 구간은 최초 안내 경로 계산에서도 제외해야 한다. 그러지 않으면
    // 훈련이 시작되자마자 발화점을 지나는 경로가 유도등에 그대로 반영될 수 있다.
    Set<UUID> firedEdgeIds =
        routeRecalculationService.firedEdgeIds(session.getScenario().getId(), startFloorId);
    EvacuationRoute initialRoute =
        evacuationRouteService.findShortestRoute(startFloorId, startNode.getId(), firedEdgeIds);

    session.start(Instant.now());
    session.getScenario().markInProgress();
    // 경로 위 유도등만 지정하면 그 밖의 유도등은 방향이 정해지지 않은 채(직전 상태 그대로) 남으므로,
    // 층 전체를 "출구로 가는 다음 노드" 방향으로 안내한다(최초 경로가 우선). 시작 시점에는 혼잡
    // 데이터가 없어 배율은 비운다. 다음 홉은 부가 정보라 계산이 실패해도 훈련 시작을 막지 않는다.
    Map<UUID, UUID> nextHops;
    try {
      nextHops = evacuationRouteService.computeNextHops(startFloorId, firedEdgeIds, Map.of());
    } catch (RuntimeException exception) {
      log.warn("층 전체 다음 홉 계산에 실패해 최초 경로 위 유도등만 반영: sessionId={}, floorId={}",
          session.getId(), startFloorId, exception);
      nextHops = Map.of();
    }
    ioTLightService.applyFloorGuidance(
        startFloorId, initialRoute.path().stream().map(MapNode::getId).toList(), nextHops);
    trainingEventPublisher.publishTrainingStatusUpdatedAfterCommit(session);

    return TrainingSessionResponse.from(session);
  }

  @Transactional
  public TrainingSessionResponse end(UUID sessionId, String email) {
    TrainingSession session = findSession(sessionId, email);

    if (session.getStatus() != TrainingStatus.RUNNING) {
      throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
    }

    session.complete(Instant.now());
    session.getScenario().markCompleted();
    fireZoneRepository.resetFiredCellsByScenarioId(session.getScenario().getId());
    routeRecalculationService.cancelAllPendingForSession(session.getId(), "훈련 종료로 무효화됨");
    ioTLightService.resetToNormal(session.getScenario().getBuildingId());
    trainingEventPublisher.publishTrainingStatusUpdatedAfterCommit(session);

    return TrainingSessionResponse.from(session);
  }

  // 관리자가 훈련을 중간에 강제로 끊는 경우로, 정상 종료(COMPLETED)와 구분해 시나리오도 ERROR로 표시한다.
  @Transactional
  public TrainingSessionResponse forceEnd(UUID sessionId, String email) {
    TrainingSession session = findSession(sessionId, email);

    if (session.getStatus() != TrainingStatus.RUNNING) {
      throw new ApiException(TrainingErrorCode.INVALID_STATUS_TRANSITION);
    }

    session.stop(Instant.now());
    session.getScenario().markError();
    fireZoneRepository.resetFiredCellsByScenarioId(session.getScenario().getId());
    routeRecalculationService.cancelAllPendingForSession(session.getId(), "훈련 강제 종료로 무효화됨");
    ioTLightService.resetToNormal(session.getScenario().getBuildingId());
    trainingEventPublisher.publishTrainingStatusUpdatedAfterCommit(session);

    return TrainingSessionResponse.from(session);
  }

  // 10분 하드 타임아웃을 넘긴 RUNNING 세션을 스케줄러가 주기적으로 호출해 FAILED 처리한다.
  @Transactional
  public void failTimedOutSessions() {
    Instant threshold = Instant.now().minus(TRAINING_TIMEOUT);
    List<TrainingSession> timedOutSessions =
        trainingSessionRepository.findByStatusAndStartedAtBefore(TrainingStatus.RUNNING, threshold);

    for (TrainingSession session : timedOutSessions) {
      session.fail(Instant.now());
      session.getScenario().markTimeoutFailed();
    }

    timedOutSessions.stream()
        .map(session -> session.getScenario().getId())
        .distinct()
        .forEach(fireZoneRepository::resetFiredCellsByScenarioId);

    for (TrainingSession session : timedOutSessions) {
      routeRecalculationService.cancelAllPendingForSession(session.getId(), "훈련 타임아웃으로 무효화됨");
      ioTLightService.resetToNormal(session.getScenario().getBuildingId());
      trainingEventPublisher.publishTrainingStatusUpdatedAfterCommit(session);
      log.info("훈련 세션 타임아웃 처리: sessionId={}", session.getId());
    }
  }

  // "지금 안내되고 있는 경로" 조회는 재탐색 승인 이력까지 함께 봐야 해서 실제 로직은
  // RouteRecalculationService가 갖고 있다 - 이 메서드는 /api/v1/sessions 하위 URL 컨벤션에
  // 맞추기 위한 위임일 뿐이다.
  @Transactional(readOnly = true)
  public CurrentRouteResponse getCurrentRoute(UUID sessionId, String email) {
    return routeRecalculationService.getCurrentRoute(sessionId, email);
  }

  private TrainingSession findSession(UUID sessionId, String email) {
    String schoolName = schoolContextService.getSchoolName(email);
    return trainingSessionRepository.findByIdAndScenario_Building_SchoolName(sessionId, schoolName)
        .orElseThrow(() -> new ApiException(TrainingErrorCode.TRAINING_SESSION_NOT_FOUND));
  }

  private boolean hasRunningSession(UUID buildingId) {
    // 건물 행 잠금 이후 RUNNING 여부를 검사해야 동시 시작 요청도 하나만 성공한다.
    buildingRepository.findByIdForUpdate(buildingId);
    return trainingSessionRepository.existsByStatusAndScenario_Building_Id(
        TrainingStatus.RUNNING, buildingId);
  }
}
