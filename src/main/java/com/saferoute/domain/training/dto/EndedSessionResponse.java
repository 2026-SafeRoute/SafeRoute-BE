package com.saferoute.domain.training.dto;

import com.saferoute.domain.training.entity.TrainingStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

// COMPLETED/FAILED/CANCELLED/STOPPED 등 더 이상 RUNNING이 아닌 세션의 상태 조회 응답.
// 종료/강제종료/타임아웃 직후 화면이 상태 API를 다시 호출해도 더 이상 UNSUPPORTED_STATUS를
// 받지 않도록 한다 (이슈 #254).
@Getter
@AllArgsConstructor
public class EndedSessionResponse implements TrainingStatusResponse {
  private String buildingName;
  private TrainingStatus status;
  private Instant endedAt;
  private Long elapsedSeconds;
  private Integer actualParticipants;
  private BigDecimal currentSurvivalRate;
  // 훈련 중 화재로 대피 경로가 처음 막힌 시각과 경과 초(RunningSessionResponse와 같음). 막힌 적이 없으면 null.
  private Instant routeBlockedAt;
  private Integer routeBlockedAfterSec;
}
