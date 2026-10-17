package com.saferoute.domain.evacuation.diagnosis.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

// 도면 관리 화면의 "대체 경로" 체크리스트용 층 진단 결과.
// 시작 지점이 정해지지 않은 화면이라 모든 시작 후보를 기준으로 본다.
public record FloorRouteDiagnosisResponse(
        @Schema(description = "진단한 층 ID")
        UUID floorId,

        @Schema(description = "끊긴 노드가 없고 모든 시작 후보가 탈출구 2곳 이상에 닿으면 true")
        boolean ready,

        @Schema(description = "어떤 탈출구에도 도달할 수 없는 노드 ID 목록 (CCTV·유도등 위치 노드는 제외)")
        List<UUID> unreachableNodeIds,

        @Schema(description = "타입이 STAIR·EXIT인데 탈출구로 지정하지 않은 노드 ID 목록. 엣지로 이어져 있으면 경로 "
                + "중간에는 지날 수 있지만 대피 목적지(탈출구)로는 선택되지 않는다. "
                + "일부러 쓰지 않는 계단도 있을 수 있어 참고 정보이며 ready에는 영향이 없다")
        List<UUID> undesignatedStairNodeIds,

        @Schema(description = "시작 후보별 도달 가능한 탈출구 수")
        List<StartCandidate> startCandidates
) {

    public record StartCandidate(
            @Schema(description = "시작 후보 노드 ID")
            UUID nodeId,

            @Schema(description = "이 후보에서 갈 수 있는 탈출구 수")
            int reachableExitCount
    ) {
    }
}
