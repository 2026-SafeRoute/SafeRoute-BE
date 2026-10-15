package com.saferoute.domain.training.repository;

import com.saferoute.domain.training.entity.FireZone;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FireZoneRepository extends JpaRepository<FireZone, UUID> {
    void deleteAllByFloor_Id(UUID floorId);

    // 현재 세대(frontier)에 속한 FireZone 조회 - 다음 확산의 시작점.
    List<FireZone> findByScenario_IdAndSpreadGeneration(UUID scenarioId, int spreadGeneration);

    // 관리자가 수동 지정한 최초 발화점만 조회 (확산 시뮬레이션이 생성한 FireZone은 제외)
    List<FireZone> findByScenario_IdAndIsManualAddTrue(UUID scenarioId);

    boolean existsByScenario_IdAndIsManualAddTrue(UUID scenarioId);

    // 대피 설정이 반쪽만 남았을 때(시작 노드가 지워짐) 남은 수동 발화점을 지우고 다시 등록하기 위해 쓴다.
    void deleteAllByScenario_IdAndIsManualAddTrue(UUID scenarioId);

    // 시나리오의 전체 FireZone(수동 발화점 + 확산으로 옮겨붙은 셀) 조회 - 세대 오름차순, 같은 세대는 등록 시각 오름차순
    List<FireZone> findByScenario_IdOrderBySpreadGenerationAscAddedAtAsc(UUID scenarioId);

    // 같은 층을 여러 시나리오가 동시에(RUNNING) 쓸 수 있어, FloorGridCell.isFired만으로는 그
    // 화재가 "이 시나리오"의 것인지 알 수 없다 - 특정 시나리오가 그 층에 낸 화재 셀만 조회한다.
    List<FireZone> findByScenario_IdAndFloor_Id(UUID scenarioId, UUID floorId);

    // 세션 종료 시 해당 시나리오의 화재 셀들 isFired = false로 일괄 초기화
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE FloorGridCell c SET c.isFired = false
        WHERE c.id IN (
            SELECT fz.gridCell.id FROM FireZone fz WHERE fz.scenario.id = :scenarioId
        )
        AND c.id NOT IN (
            SELECT fz2.gridCell.id FROM FireZone fz2
            WHERE fz2.scenario.id <> :scenarioId
              AND fz2.scenario.id IN (
                  SELECT ts.scenario.id FROM TrainingSession ts
                  WHERE ts.status = com.saferoute.domain.training.entity.TrainingStatus.RUNNING
              )
        )
        """)
    void resetFiredCellsByScenarioId(@Param("scenarioId") UUID scenarioId);
}
