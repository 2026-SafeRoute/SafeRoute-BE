package com.saferoute.domain.device.service;

import com.saferoute.domain.device.dto.request.AssignCctvRequest;
import com.saferoute.domain.device.dto.request.ChangeLightDirectionRequest;
import com.saferoute.domain.device.dto.request.ConfigureGuidanceRequest;
import com.saferoute.domain.device.dto.request.CreateIoTLightRequest;
import com.saferoute.domain.device.dto.request.UpdateIoTLightRequest;
import com.saferoute.domain.device.dto.request.UpdatePiEndpointRequest;
import com.saferoute.domain.device.dto.response.IoTLightResponse;
import com.saferoute.domain.device.dto.response.LightDirectionResponse;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.device.entity.LightCommand;
import com.saferoute.domain.device.entity.LightCommandStatus;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.device.repository.LightCommandJpaRepository;
import com.saferoute.global.api.error.CctvErrorCode;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.floor.service.FloorMapChangeGuard;
import com.saferoute.domain.telemetry.dynamo.entity.LightDirectionEventItem;
import com.saferoute.domain.telemetry.dynamo.repository.LightDirectionEventRepository;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.error.IoTLightErrorCode;
import com.saferoute.global.api.exception.ApiException;
import com.saferoute.infrastructure.websocket.service.TrainingEventPublisher;
import com.saferoute.domain.user.service.SchoolContextService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IoTLightService {

    private final IoTLightJpaRepository iotLightJpaRepository;
    private final IoTLightCodeAllocator iotLightCodeAllocator;
    private final CctvJpaRepository cctvJpaRepository;
    private final MapNodeJpaRepository mapNodeJpaRepository;
    private final MapEdgeJpaRepository mapEdgeJpaRepository;
    private final FloorRepository floorRepository;
    private final LightCommandJpaRepository lightCommandJpaRepository;
    private final IoTLightDirectionStore iotLightDirectionStore;
    private final TrainingEventPublisher trainingEventPublisher;
    private final SchoolContextService schoolContextService;
    private final TrainingSessionRepository trainingSessionRepository;
    private final LightDirectionEventRepository lightDirectionEventRepository;
    private final FloorMapChangeGuard floorMapChangeGuard;

    @Transactional
    public IoTLightResponse createLight(CreateIoTLightRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        Floor floor = floorRepository.findByIdAndBuilding_SchoolName(request.floorId(), schoolName)
                .orElseThrow(() -> new ApiException(FloorErrorCode.FLOOR_NOT_FOUND));
        validateNoRunningTraining(floor);

        // 유도등 code는 기기가 보고하는 시리얼이 아니라 서버가 등록 시점에 채번하는 식별자다 (예: LIGHT_001).
        // IoTLightCodeAllocator가 별도 트랜잭션의 DB sequence로 원자적으로 발급한다
        // (CctvCodeAllocator와 동일한 컨벤션 - count()+1 방식은 동시 요청 시 중복 발급됨).
        String code = iotLightCodeAllocator.allocate();

        // customNode의 code/name은 유도등 자체의 code/name을 그대로 사용한다.
        // IoTLight.code는 시스템 전역 유일이 보장되므로 MapNode의 (floor, code) 유니크 제약도 자동 충족된다.
        MapNode customNode = mapNodeJpaRepository.save(
                MapNode.createCustom(floor, code, request.name(), request.x(), request.y())
        );

        IoTLight light = iotLightJpaRepository.save(
                IoTLight.create(code, request.name(), customNode)
        );

        return IoTLightResponse.from(light);
    }

    public List<IoTLightResponse> getLights(UUID floorId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        List<IoTLight> lights = floorId == null
                ? iotLightJpaRepository.findAllByCustomNode_Floor_Building_SchoolName(schoolName)
                : iotLightJpaRepository
                        .findAllByCustomNode_Floor_IdAndCustomNode_Floor_Building_SchoolName(
                                floorId, schoolName);
        return lights.stream().map(IoTLightResponse::from).toList();
    }

    public IoTLightResponse getLight(UUID lightId, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        IoTLight light = iotLightJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(lightId, schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND));
        return IoTLightResponse.from(light);
    }

    @Transactional
    public IoTLightResponse configureGuidance(
            UUID lightId, ConfigureGuidanceRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        IoTLight light = findLightForSchoolNameOrThrow(lightId, schoolName);
        validateNoRunningTraining(light.getCustomNode().getFloor());

        // leftEdge/rightEdge가 같은 엣지면 IoTLight.configureGuidance()가 IllegalArgumentException을
        // 던지는데 GlobalExceptionHandler가 이를 500으로 처리하므로 여기서 먼저 ApiException으로 막는다.
        if (request.leftEdgeId().equals(request.rightEdgeId())) {
            throw new ApiException(IoTLightErrorCode.INVALID_GUIDANCE_EDGE);
        }

        MapNode decisionNode = mapNodeJpaRepository
                .findByIdAndFloor_Building_SchoolName(request.decisionNodeId(), schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.DECISION_NODE_NOT_FOUND));
        MapEdge leftEdge = mapEdgeJpaRepository
                .findByIdAndFloor_Building_SchoolName(request.leftEdgeId(), schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.GUIDANCE_EDGE_NOT_FOUND));
        MapEdge rightEdge = mapEdgeJpaRepository
                .findByIdAndFloor_Building_SchoolName(request.rightEdgeId(), schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.GUIDANCE_EDGE_NOT_FOUND));

        validateConnectedToDecisionNode(decisionNode, leftEdge);
        validateConnectedToDecisionNode(decisionNode, rightEdge);

        light.configureGuidance(decisionNode, leftEdge, rightEdge);
        return IoTLightResponse.from(light);
    }

    @Transactional
    public IoTLightResponse updateLight(UUID lightId, UpdateIoTLightRequest request, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        validateNoRunningTraining(light.getCustomNode().getFloor());
        light.rename(request.name());
        light.getCustomNode().moveTo(request.x(), request.y());
        return IoTLightResponse.from(light);
    }

    @Transactional
    public IoTLightResponse updatePiEndpoint(
            UUID lightId, UpdatePiEndpointRequest request, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        light.updatePiEndpoint(request.piEndpoint());
        return IoTLightResponse.from(light);
    }

    // 하드웨어 배치 시 관리자가 이 유도등의 릴레이를 제어하는 Pi(=그 Pi가 감시하는 CCTV)를 지정한다.
    // 유도등 명령 폴링(GET /device/light-commands?cctvCode=...)이 이 연결로 담당 유도등을 찾는다.
    @Transactional
    public IoTLightResponse assignCctv(UUID lightId, AssignCctvRequest request, String email) {
        String schoolName = schoolContextService.getSchoolName(email);
        IoTLight light = findLightForSchoolNameOrThrow(lightId, schoolName);
        validateNoRunningTraining(light.getCustomNode().getFloor());
        Cctv cctv = cctvJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(request.cctvId(), schoolName)
                .orElseThrow(() -> new ApiException(CctvErrorCode.CCTV_NOT_FOUND));
        light.assignCctv(cctv);
        return IoTLightResponse.from(light);
    }

    @Transactional
    public IoTLightResponse enableLight(UUID lightId, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        light.enable();
        return IoTLightResponse.from(light);
    }

    @Transactional
    public IoTLightResponse disableLight(UUID lightId, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        light.disable();
        return IoTLightResponse.from(light);
    }

    // 유도등의 도면 위치 노드(customNode)를 지우면 iot_lights FK의 ON DELETE CASCADE로 유도등 자체도 함께 삭제된다.
    // 노드 삭제 전 연결된 엣지(from/to 어느 쪽이든)를 먼저 지우는 것은 MapGraphRepositoryImpl.deleteNode와 동일한 처리다.
    @Transactional
    public void deleteLight(UUID lightId, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        MapNode customNode = light.getCustomNode();
        validateNoRunningTraining(customNode.getFloor());
        mapEdgeJpaRepository.deleteByFromNode_IdOrToNode_Id(customNode.getId(), customNode.getId());
        mapNodeJpaRepository.delete(customNode);
    }

    // 검증(enabled/guidance) -> 명령 큐 적재 -> 상태 저장 -> 이력 기록 -> 이벤트 발행 순서로 조립한다.
    // EC2->사설 Pi 직접 호출은 금지라, 여기서 Pi를 직접 부르지 않고 LightCommand를
    // PENDING으로 적재만 한다. 실제 실행은 그 유도등을 담당하는 CCTV(Pi)가 GET
    // /device/light-commands로 폴링해가서 하고, 결과는 ACK로 비동기 보고된다.
    // 현재 방향은 훈련별 동적 상태라 DB에 저장하지 않고 IoTLightDirectionStore(서버 메모리)에 저장한다 (IoTLight 참고).
    // 전환 이력만 경로 이탈률 계산을 위해 DynamoDB(LightDirectionEventItem)에 별도로 남긴다.
    // 주의: 아래 상태 저장/이력 기록/이벤트 발행은 ACK(실제 실행 확인)를 기다리지 않고
    // 명령을 적재하는 시점에 낙관적으로 수행한다 - Pi가 실행에 실패해도 이 시점에는 알 수 없다.
    public LightDirectionResponse changeDirection(
            UUID lightId, ChangeLightDirectionRequest request, String email) {
        IoTLight light = findLightForSchoolOrThrow(lightId, email);
        return changeDirection(light, request);
    }

    private LightDirectionResponse changeDirection(
            IoTLight light, ChangeLightDirectionRequest request) {
        IoTLightDirection direction = request.direction();

        if (!light.isEnabled()) {
            throw new ApiException(IoTLightErrorCode.LIGHT_DISABLED);
        }
        if (direction != IoTLightDirection.OFF && !light.isGuidanceConfigured()) {
            throw new ApiException(IoTLightErrorCode.GUIDANCE_NOT_CONFIGURED);
        }
        if (light.getCctv() == null) {
            throw new ApiException(IoTLightErrorCode.CCTV_NOT_ASSIGNED);
        }

        enqueueCommand(light, direction);
        iotLightDirectionStore.update(light.getId(), direction);
        Instant changedAt = Instant.now();
        logDirectionChange(light, direction, changedAt);
        trainingEventPublisher.publishIoTLightStatusUpdatedAfterCommit(light, direction);

        return new LightDirectionResponse(light.getId(), direction, changedAt);
    }

    // 기존에 아직 실행 안 된(PENDING) 명령은 이번 명령으로 의미가 없어지므로 SUPERSEDED
    // 처리하고, 새 명령을 PENDING으로 적재한다. Pi는 폴링할 때 유도등당 최신 PENDING
    // 하나만 가져가므로, 밀린 명령이 뒤늦게 실행되는 일은 없다.
    private void enqueueCommand(IoTLight light, IoTLightDirection direction) {
        lightCommandJpaRepository.findAllByLight_IdAndStatus(light.getId(), LightCommandStatus.PENDING)
                .forEach(LightCommand::supersede);
        lightCommandJpaRepository.save(LightCommand.createPending(light, direction));
    }

    // 경로 이탈률 계산의 원천 데이터로 쓰기 위해 방향 전환 시점을 훈련 세션 기준으로 DynamoDB에 남긴다.
    // 실행 중인 훈련 세션이 없으면(설정/점검 중 방향 전환) 남길 이력이 없으므로 조용히 넘어가고,
    // 로깅 실패가 유도등 명령 자체의 성공 여부에 영향을 주지 않도록 예외를 흡수한다.
    private void logDirectionChange(IoTLight light, IoTLightDirection direction, Instant changedAt) {
        try {
            UUID buildingId = light.getCustomNode().getFloor().getBuilding().getId();
            Optional<TrainingSession> runningSession = trainingSessionRepository
                    .findFirstByStatusAndScenario_Building_IdOrderByStartedAtAsc(TrainingStatus.RUNNING, buildingId);
            if (runningSession.isEmpty()) {
                return;
            }
            LightDirectionEventItem item = LightDirectionEventItem.create(
                    runningSession.get().getId(),
                    light.getId(),
                    light.getCode(),
                    direction,
                    light.getDecisionNode() != null ? light.getDecisionNode().getId() : null,
                    light.getLeftEdge() != null ? light.getLeftEdge().getId() : null,
                    light.getRightEdge() != null ? light.getRightEdge().getId() : null,
                    changedAt.toEpochMilli()
            );
            lightDirectionEventRepository.save(item);
        } catch (RuntimeException exception) {
            log.warn("유도등 방향 전환 이력 기록 실패: lightId={}, direction={}", light.getId(), direction, exception);
        }
    }

    // RouteRecalculation 승인/훈련 시작 시 호출한다. 해당 층의 안내 가능한 유도등 전체를 "출구로 가는
    // 다음 노드" 방향으로 갱신한다. 승인된 경로 위 유도등만 바꾸면, 이전 경로에만 있던 유도등이
    // 혼잡/화재 구간 쪽 옛 방향을 그대로 가리킨 채 남기 때문이다(실제 대피자는 층 전체에 흩어져 있다).
    // - nextHops: EvacuationRouteService.computeNextHops()의 층 전체 "다음 홉" 트리.
    // - pathNodeIds: 승인된(또는 최초) 경로. 연속한 두 노드 쌍이 nextHops보다 우선한다 - 관리자가
    //   승인한 경로를 유도등이 정확히 따라야 하기 때문이다.
    // - 다음 홉이 없거나(도달 불가, EXIT 노드 자체) 이 유도등의 좌우 엣지와 맞지 않으면 평상시(BOTH)로
    //   둔다. 옛 방향을 남기지 않는 것이 핵심이다.
    // 개별 유도등이 비활성/미설정/기기 unreachable이어도 다른 유도등 전환을 막지 않도록 여기서 흡수한다
    // - 유도등 반영은 승인/훈련 시작 자체의 성공 여부에 영향을 주지 않는다.
    public void applyFloorGuidance(UUID floorId, List<UUID> pathNodeIds, Map<UUID, UUID> nextHops) {
        Map<UUID, UUID> guidance = new HashMap<>(nextHops);
        for (int i = 0; i < pathNodeIds.size() - 1; i++) {
            guidance.put(pathNodeIds.get(i), pathNodeIds.get(i + 1));
        }

        for (IoTLight light : iotLightJpaRepository.findAllByCustomNode_Floor_Id(floorId)) {
            if (!light.isEnabled() || !light.isGuidanceConfigured()) {
                continue;
            }
            UUID decisionNodeId = light.getDecisionNode().getId();
            UUID nextNodeId = guidance.get(decisionNodeId);
            IoTLightDirection direction = nextNodeId == null
                    ? null
                    : resolveDirection(light, decisionNodeId, nextNodeId);
            if (direction == null) {
                direction = IoTLightDirection.BOTH;
            }
            // changeDirection은 호출될 때마다 LightCommand를 적재하고 WebSocket을 발행하므로, 층 전체를
            // 돌 때 이미 같은 방향인 유도등까지 건드리면 Pi 명령 큐에 의미 없는 명령이 쌓인다.
            if (iotLightDirectionStore.get(light.getId()) == direction) {
                continue;
            }
            try {
                changeDirection(light, new ChangeLightDirectionRequest(direction));
            } catch (ApiException exception) {
                log.warn("경로 반영에 따른 유도등 자동 전환 실패: lightId={}, direction={}",
                        light.getId(), direction, exception);
            }
        }
    }

    // 훈련 종료(정상/강제/타임아웃) 시 호출한다. 건물 내 안내 설정이 끝난 유도등을 모두 평상시(BOTH)
    // 상태로 되돌린다. applyFloorGuidance와 동일하게 개별 유도등의 실패는 흡수한다.
    public void resetToNormal(UUID buildingId) {
        for (IoTLight light : iotLightJpaRepository.findAllByCustomNode_Floor_Building_Id(buildingId)) {
            if (!light.isEnabled() || !light.isGuidanceConfigured()) {
                continue;
            }
            try {
                changeDirection(light, new ChangeLightDirectionRequest(IoTLightDirection.BOTH));
            } catch (ApiException exception) {
                log.warn("훈련 종료에 따른 유도등 평상시 복귀 실패: lightId={}", light.getId(), exception);
            }
        }
    }

    // leftEdge/rightEdge 중 다음 노드로 이어지는 쪽을 찾는다. 둘 다 아니면(이 유도등은 이 경로와 무관) null.
    private IoTLightDirection resolveDirection(IoTLight light, UUID decisionNodeId, UUID nextNodeId) {
        if (!light.isGuidanceConfigured()) {
            return null;
        }
        if (otherEndpoint(light.getLeftEdge(), decisionNodeId).equals(nextNodeId)) {
            return IoTLightDirection.LEFT;
        }
        if (otherEndpoint(light.getRightEdge(), decisionNodeId).equals(nextNodeId)) {
            return IoTLightDirection.RIGHT;
        }
        return null;
    }

    private UUID otherEndpoint(MapEdge edge, UUID nodeId) {
        return edge.getFromNode().getId().equals(nodeId) ? edge.getToNode().getId() : edge.getFromNode().getId();
    }

    private IoTLight findLightForSchoolOrThrow(UUID lightId, String email) {
        return findLightForSchoolNameOrThrow(lightId, schoolContextService.getSchoolName(email));
    }

    private IoTLight findLightForSchoolNameOrThrow(UUID lightId, String schoolName) {
        return iotLightJpaRepository
                .findByIdAndCustomNode_Floor_Building_SchoolName(lightId, schoolName)
                .orElseThrow(() -> new ApiException(IoTLightErrorCode.IOT_LIGHT_NOT_FOUND));
    }

    // leftEdge/rightEdge가 실제로 decisionNode에 연결된 엣지인지 검증 (엔티티가 아닌 Service 책임)
    private void validateConnectedToDecisionNode(MapNode decisionNode, MapEdge edge) {
        boolean connected = edge.getFromNode().getId().equals(decisionNode.getId())
                || edge.getToNode().getId().equals(decisionNode.getId());
        if (!connected) {
            throw new ApiException(IoTLightErrorCode.INVALID_GUIDANCE_EDGE);
        }
    }

    // 훈련 중 기기 구성(노드 위치, 안내 설정, 명령을 받아 갈 Pi)이 바뀌면 안내·경로 이탈 판정이 어긋난다.
    // 도면 변경과 같은 기준(FloorMapChangeGuard)으로 막는다. 활성화/비활성화·Pi 주소·방향 변경은 훈련 중
    // 고장 대응이나 운영 자체에 필요하므로 막지 않는다.
    private void validateNoRunningTraining(Floor floor) {
        floorMapChangeGuard.validateNoRunningTraining(floor);
    }
}
