package com.saferoute.domain.evacuation.service;

import java.util.Map;
import java.util.UUID;

// 아직 불이 붙지 않았지만 곧 번질 구간에 대한 위험 정보 (경로 탐색의 화재 근접 위험 가중치).
// - 불이 이미 붙은 셀의 엣지는 여기가 아니라 excludedEdgeIds로 그래프에서 완전히 제외한다(절대 통과 금지).
//   여기 담기는 건 그 바깥, 확산 규칙(격자 상하좌우, 한 세대에 한 칸)상 1~2세대 안에 불이 붙을 셀을
//   지나는 엣지뿐이고, 제외가 아니라 배율 페널티로만 반영한다 - 우회로가 없으면 여전히 지나갈 수 있어야
//   "불붙기 전에 빠져나갈 수 있는 길"까지 경로 없음(EVAC005)으로 막아버리지 않는다.
// - generationByEdgeId: 엣지가 지나는 셀 중 가장 먼저 불붙을 셀이 몇 세대 뒤에 불붙는지 (1 또는 2).
// - spreadIntervalSec: 시나리오 확산 속도의 한 세대 주기 (FireSpreadSpeed.tickInterval()).
public record FireProximity(Map<UUID, Integer> generationByEdgeId, double spreadIntervalSec) {

    public static final FireProximity NONE = new FireProximity(Map.of(), 0.0);

    // 위험 가중치를 주는 최대 확산 세대. 3세대 이상(보통 속도 기준 30초 이상 뒤)은 그 사이 재탐색이
    // 다시 돌기 때문에 미리 페널티를 주지 않는다.
    public static final int MAX_GENERATION = 2;

    // 보행 속도(m/s). 엣지 도착 예상 시점 = 시작 노드부터 걸은 실제 거리(m) / 이 값.
    static final double WALKING_SPEED_MPS = 1.2;

    // 도착하기 전에 불이 붙을 것으로 예상되는 엣지의 배율 - CROWDED(3)보다 크고 VERY_CROWDED(10)보다는
    // 작게 잡았다. 곧 불이 붙지만 아직 통행 가능한 구간이므로, 사람이 몰려 사실상 막힌 구간보다는 낫다고 본다.
    static final double IGNITES_BEFORE_ARRIVAL_MULTIPLIER = 5.0;
    // 불 옆이지만 도착 시점엔 아직 불이 붙지 않을 것으로 예상되는 엣지의 배율 - 열기·연기 노출만 반영한다.
    static final double NEAR_FIRE_MULTIPLIER = 2.0;

    public FireProximity {
        generationByEdgeId = Map.copyOf(generationByEdgeId);
    }

    public boolean isEmpty() {
        return generationByEdgeId.isEmpty();
    }

    // arrivalSec: 그 엣지를 다 지나는 예상 시점(초). 모르면(층 전체 다음 홉처럼 출발점이 하나가 아닐 때) null.
    // 예상 발화 시점은 (세대 - 1) × 주기로 잡는다 - 마지막 확산 이후 얼마나 지났는지는 반영하지 않는다.
    // 다음 확산이 곧바로 일어난다고 보는 보수적인 값이고, 현재 시각을 쓰지 않으므로 같은 화재 상태면
    // 언제 호출해도 같은 배율이 나온다(PENDING 비교의 결정성 전제, EvacuationRouteService.QUEUE_ORDER 참고).
    // 그래서 1세대 셀은 언제 도착해도 "도착 전 발화"다.
    double multiplier(UUID edgeId, Double arrivalSec) {
        Integer generation = generationByEdgeId.get(edgeId);
        if (generation == null) {
            return 1.0;
        }
        double ignitionSec = (generation - 1) * spreadIntervalSec;
        boolean ignitesBeforeArrival = arrivalSec == null
                ? generation == 1
                : ignitionSec <= arrivalSec;
        return ignitesBeforeArrival ? IGNITES_BEFORE_ARRIVAL_MULTIPLIER : NEAR_FIRE_MULTIPLIER;
    }
}
