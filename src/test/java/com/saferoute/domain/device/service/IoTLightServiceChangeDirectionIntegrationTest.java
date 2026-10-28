package com.saferoute.domain.device.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.device.dto.request.ChangeLightDirectionRequest;
import com.saferoute.domain.device.entity.Cctv;
import com.saferoute.domain.device.entity.IoTLight;
import com.saferoute.domain.device.entity.IoTLightDirection;
import com.saferoute.domain.device.entity.LightCommand;
import com.saferoute.domain.device.entity.LightCommandStatus;
import com.saferoute.domain.device.repository.CctvJpaRepository;
import com.saferoute.domain.device.repository.IoTLightJpaRepository;
import com.saferoute.domain.device.repository.LightCommandJpaRepository;
import com.saferoute.domain.evacuation.graph.entity.MapEdge;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapEdgeJpaRepository;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.telemetry.dynamo.repository.LightDirectionEventRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 관리자 수동 방향 전환(PATCH /lights/{id}/direction)이 실제 DB 트랜잭션 위에서 LightCommand를 적재하는지 검증한다.
// IoTLightService는 클래스 기본이 @Transactional(readOnly = true)라, changeDirection에 쓰기 트랜잭션을 빠뜨리면
// Hibernate flush가 MANUAL이 돼 save()가 예외 없이 조용히 버려진다 - 메모리 store와 WebSocket만 바뀌고 Pi는
// 명령을 영영 받지 못한다. 단위 테스트(Mockito)는 save() 호출만 확인하므로 이 회귀는 여기서만 잡힌다.
@SpringBootTest
class IoTLightServiceChangeDirectionIntegrationTest {

    private static final String SCHOOL_NAME = "수동 방향 전환 테스트교";

    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MapNodeJpaRepository mapNodeRepository;
    @Autowired
    private MapEdgeJpaRepository mapEdgeRepository;
    @Autowired
    private CctvJpaRepository cctvRepository;
    @Autowired
    private IoTLightJpaRepository iotLightRepository;
    @Autowired
    private LightCommandJpaRepository lightCommandRepository;
    @Autowired
    private IoTLightService iotLightService;
    @Autowired
    private IoTLightDirectionStore directionStore;
    @Autowired
    private TransactionTemplate transactionTemplate;
    // 유도등 방향 전환 이력(DynamoDB) 기록은 이 테스트의 관심사가 아니다.
    @MockitoBean
    private LightDirectionEventRepository lightDirectionEventRepository;

    @Test
    void changeDirection_persistsPendingCommandAndSupersedesPreviousOne() {
        Fixture fixture = transactionTemplate.execute(status -> createFixture());

        iotLightService.changeDirection(
                fixture.lightId(), new ChangeLightDirectionRequest(IoTLightDirection.LEFT), fixture.email());
        iotLightService.changeDirection(
                fixture.lightId(), new ChangeLightDirectionRequest(IoTLightDirection.RIGHT), fixture.email());

        assertThat(directionStore.get(fixture.lightId())).isEqualTo(IoTLightDirection.RIGHT);
        List<LightCommand> pending = lightCommandRepository
                .findAllByLight_IdAndStatus(fixture.lightId(), LightCommandStatus.PENDING);
        assertThat(pending).singleElement()
                .extracting(LightCommand::getDirection).isEqualTo(IoTLightDirection.RIGHT);
        assertThat(lightCommandRepository
                .findAllByLight_IdAndStatus(fixture.lightId(), LightCommandStatus.SUPERSEDED))
                .singleElement()
                .extracting(LightCommand::getDirection).isEqualTo(IoTLightDirection.LEFT);
    }

    private Fixture createFixture() {
        // 테스트 메서드마다 같은 인메모리 DB에 픽스처를 만들므로 유니크 제약이 걸린 값에 접미사를 붙인다.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "manual-direction-" + suffix + "@saferoute.com";

        Building building = buildingRepository.save(Building.create(
                "수동방향 " + suffix, "부산광역시 해운대구 우동 1-1", BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(3.0, 4.0, "floors/manual-direction-" + suffix + ".png");
        floor = floorRepository.save(floor);

        MapNode decision = mapNodeRepository.save(MapNode.create(floor, "D", NodeType.HALLWAY, "분기", 0.5, 0.5, false));
        MapNode left = mapNodeRepository.save(MapNode.create(floor, "L", NodeType.HALLWAY, "왼쪽", 0.1, 0.5, false));
        MapNode right = mapNodeRepository.save(MapNode.create(floor, "R", NodeType.EXIT, "출구", 0.9, 0.5, true));
        MapEdge leftEdge = mapEdgeRepository.save(MapEdge.create(floor, decision, left, 1.0, true));
        MapEdge rightEdge = mapEdgeRepository.save(MapEdge.create(floor, decision, right, 1.0, true));

        Cctv cctv = cctvRepository.save(Cctv.create("CCTV_" + suffix, "수동방향 CCTV",
                mapNodeRepository.save(MapNode.createCustom(floor, "CCTV_" + suffix, "CCTV", 0.5, 0.5))));
        IoTLight light = IoTLight.create("LIGHT_" + suffix, "수동방향 유도등",
                mapNodeRepository.save(MapNode.createCustom(floor, "LIGHT_" + suffix, "유도등", 0.5, 0.5)));
        light.assignCctv(cctv);
        light.configureGuidance(decision, leftEdge, rightEdge);
        light = iotLightRepository.save(light);

        userRepository.save(User.create("md-" + suffix, "password", email, UserRole.MANAGER, SCHOOL_NAME));
        return new Fixture(light.getId(), email);
    }

    private record Fixture(UUID lightId, String email) {
    }
}
