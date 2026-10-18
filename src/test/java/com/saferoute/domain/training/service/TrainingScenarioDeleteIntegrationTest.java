package com.saferoute.domain.training.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.evacuation.graph.entity.MapNode;
import com.saferoute.domain.evacuation.graph.entity.NodeType;
import com.saferoute.domain.evacuation.graph.repository.MapNodeJpaRepository;
import com.saferoute.domain.evacuation.grid.entity.FloorGridCell;
import com.saferoute.domain.evacuation.grid.repository.FloorGridCellRepository;
import com.saferoute.domain.floor.entity.Floor;
import com.saferoute.domain.floor.repository.FloorRepository;
import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.FireZone;
import com.saferoute.domain.training.entity.TrainingScenario;
import com.saferoute.domain.training.entity.TrainingSession;
import com.saferoute.domain.training.repository.FireZoneRepository;
import com.saferoute.domain.training.repository.TrainingScenarioRepository;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.domain.user.entity.User;
import com.saferoute.domain.user.entity.UserRole;
import com.saferoute.domain.user.repository.UserRepository;
import com.saferoute.global.api.error.TrainingErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// 대피 설정(발화점·시작점)을 등록한 시나리오가 세션이 없는데도 fire_zones FK 때문에 삭제되지 않던 문제를
// 실제 DB로 재현·검증한다 (이슈 #282). 훈련이 시작된 세션이 있으면 훈련 기록 보존을 위해 막히고,
// 시작 전(SCHEDULED) 세션만 있으면 세션과 함께 삭제된다 (이슈 #295).
@SpringBootTest
class TrainingScenarioDeleteIntegrationTest {

    private static final String SCHOOL_NAME = "시나리오삭제 테스트교";

    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MapNodeJpaRepository mapNodeRepository;
    @Autowired
    private FloorGridCellRepository floorGridCellRepository;
    @Autowired
    private TrainingScenarioRepository trainingScenarioRepository;
    @Autowired
    private TrainingSessionRepository trainingSessionRepository;
    @Autowired
    private FireZoneRepository fireZoneRepository;
    @Autowired
    private TrainingScenarioService trainingScenarioService;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private enum SessionKind {
        NONE, SCHEDULED, RUNNING
    }

    private record Fixture(UUID scenarioId, String email) {
    }

    @Test
    void deleteScenario_withEvacuationSetupAndNoSession_deletesScenarioAndFireOrigin() {
        Fixture fixture = transactionTemplate.execute(status -> createScenarioWithEvacuationSetup("nosession", SessionKind.NONE));

        assertThatCode(() -> trainingScenarioService.deleteScenario(fixture.scenarioId(), fixture.email()))
                .doesNotThrowAnyException();

        assertThat(trainingScenarioRepository.findById(fixture.scenarioId())).isEmpty();
        assertThat(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(fixture.scenarioId())).isEmpty();
    }

    @Test
    void deleteScenario_withScheduledSessionOnly_deletesScenarioAndSession() {
        Fixture fixture = transactionTemplate.execute(
                status -> createScenarioWithEvacuationSetup("scheduled", SessionKind.SCHEDULED));

        assertThatCode(() -> trainingScenarioService.deleteScenario(fixture.scenarioId(), fixture.email()))
                .doesNotThrowAnyException();

        assertThat(trainingScenarioRepository.findById(fixture.scenarioId())).isEmpty();
        assertThat(trainingSessionRepository.existsByScenario_Id(fixture.scenarioId())).isFalse();
        assertThat(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(fixture.scenarioId())).isEmpty();
    }

    @Test
    void deleteScenario_withStartedSession_isRejectedAndFireOriginKept() {
        Fixture fixture = transactionTemplate.execute(
                status -> createScenarioWithEvacuationSetup("started", SessionKind.RUNNING));

        assertThatThrownBy(() -> trainingScenarioService.deleteScenario(fixture.scenarioId(), fixture.email()))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(TrainingErrorCode.SCENARIO_DELETE_NOT_ALLOWED);

        assertThat(trainingScenarioRepository.findById(fixture.scenarioId())).isPresent();
        assertThat(trainingSessionRepository.existsByScenario_Id(fixture.scenarioId())).isTrue();
        assertThat(fireZoneRepository.findByScenario_IdAndIsManualAddTrue(fixture.scenarioId())).isNotEmpty();
    }

    private Fixture createScenarioWithEvacuationSetup(String suffix, SessionKind sessionKind) {
        Building building = buildingRepository.save(Building.create(
                "시나리오삭제 건물 " + suffix, "부산광역시 해운대구 우동 2-" + suffix, BuildingType.CLASSROOM, SCHOOL_NAME));
        Floor floor = Floor.create(building, 1);
        floor.upload(10.0, 20.0, "floors/scenario-delete-" + suffix + ".png");
        floorRepository.save(floor);

        String email = "sd-" + suffix + "@saferoute.com";
        User admin = userRepository.save(User.create(
                "sd-" + suffix, "password", email, UserRole.MANAGER, SCHOOL_NAME));

        MapNode start = mapNodeRepository.save(MapNode.create(floor, "START", NodeType.START, "출발", 0.5, 0.5, false));
        FloorGridCell originCell = floorGridCellRepository.save(FloorGridCell.create(floor, 0, 0, true, 0.1, 0.1));

        TrainingScenario scenario = trainingScenarioRepository.save(TrainingScenario.create(
                "삭제 시나리오 " + suffix, 10, Instant.now(), false, FireSpreadSpeed.FAST, building, admin, start));
        fireZoneRepository.save(FireZone.createOrigin(scenario, floor, originCell));
        if (sessionKind != SessionKind.NONE) {
            TrainingSession session = TrainingSession.schedule(admin, scenario);
            if (sessionKind == SessionKind.RUNNING) {
                session.start(Instant.now());
            }
            trainingSessionRepository.save(session);
        }
        return new Fixture(scenario.getId(), email);
    }
}
