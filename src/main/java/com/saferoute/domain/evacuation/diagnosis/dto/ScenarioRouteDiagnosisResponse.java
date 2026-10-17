package com.saferoute.domain.evacuation.diagnosis.dto;

import com.saferoute.domain.training.entity.FireSpreadSpeed;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

// 시나리오 화면의 훈련 미리보기·시작 확인창용 시뮬레이션 결과.
public record ScenarioRouteDiagnosisResponse(
        @Schema(description = "시나리오 ID")
        UUID scenarioId,

        @Schema(description = "시나리오 시작 지점이 있는 층 ID")
        UUID floorId,

        @Schema(description = "시나리오 시작 지점 노드 ID")
        UUID startNodeId,

        @Schema(description = "시나리오 화재 확산 속도")
        FireSpreadSpeed fireSpreadSpeed,

        @Schema(description = "화재가 한 칸 번지는 간격(초)")
        long tickSeconds,

        @Schema(description = "계산한 최대 시간(초). 훈련 제한 시간과 같다")
        long simulatedUntilSeconds,

        @Schema(description = "훈련 시작 시 처음 안내되는 경로의 노드 ID 목록 (출발 -> 도착)")
        List<UUID> initialRouteNodeIds,

        @Schema(description = "예상 재탐색 횟수")
        int recalculationCount,

        @Schema(description = "모든 경로가 막히는 예상 시점(초). 제한 시간 안에 막히지 않으면 null")
        Long blockedAfterSeconds,

        @Schema(description = "시간순 예상 이벤트")
        List<Event> events,

        @Schema(description = "화재가 안내 경로에 처음 닿았을 때 재탐색이 일어나면 true. "
                + "닿자마자 막히면 false (시나리오 시작 확인창 기준)")
        boolean hasAlternativeRoute
) {

    public enum EventType {
        RECALCULATED,
        BLOCKED
    }

    public record Event(
            @Schema(description = "훈련 시작 후 예상 시점(초)")
            long afterSeconds,

            @Schema(description = "RECALCULATED: 재탐색으로 경로가 바뀜 / BLOCKED: 우회 경로가 없어 경로가 막힘")
            EventType type,

            @Schema(description = "RECALCULATED일 때 바뀐 경로의 노드 ID 목록. BLOCKED면 빈 목록")
            List<UUID> routeNodeIds
    ) {
    }
}
