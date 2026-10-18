package com.saferoute.domain.training.dto;

import com.saferoute.domain.training.entity.FireSpreadSpeed;
import com.saferoute.domain.training.entity.ScenarioStatus;
import com.saferoute.domain.training.entity.TrainingScenario;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScenarioResponse {

    private UUID id;
    private String name;
    private UUID buildingId;
    private UUID adminId;
    private UUID startNodeId;
    private Integer expectedParticipants;
    private Integer targetEvacuationSec;
    private Instant scheduledAt;
    private Boolean isTemplate;
    private FireSpreadSpeed fireSpreadSpeed;
    private ScenarioStatus status;
    // 훈련이 시작된 세션이 하나도 없는(없거나 전부 SCHEDULED) 시나리오만 삭제 가능. 단건 조회에선 항상 null (목록 조회 전용 필드).
    private Boolean deletable;
    // 이 시나리오의 훈련 리포트 id(TrainingReport.shortId). 리포트가 아직 없으면 null (목록 조회 전용 필드).
    private String reportId;
    private Instant createdAt;
    private Instant updatedAt;

    public static ScenarioResponse from(TrainingScenario scenario) {
        return from(scenario, null, null);
    }

    public static ScenarioResponse from(TrainingScenario scenario, Boolean deletable, String reportId) {
        return ScenarioResponse.builder()
                .id(scenario.getId())
                .name(scenario.getName())
                .buildingId(scenario.getBuildingId())
                .adminId(scenario.getAdminId())
                .startNodeId(scenario.getStartNodeId())
                .expectedParticipants(scenario.getExpectedParticipants())
                .targetEvacuationSec(scenario.getTargetEvacuationSec())
                .scheduledAt(scenario.getScheduledAt())
                .isTemplate(scenario.getIsTemplate())
                .fireSpreadSpeed(scenario.getFireSpreadSpeed())
                .status(scenario.getStatus())
                .deletable(deletable)
                .reportId(reportId)
                .createdAt(scenario.getCreatedAt())
                .updatedAt(scenario.getUpdatedAt())
                .build();
    }
}
