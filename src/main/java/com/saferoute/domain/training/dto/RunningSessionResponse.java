package com.saferoute.domain.training.dto;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RunningSessionResponse implements TrainingStatusResponse {
  private String buildingName;
  private Long elapsedSeconds;
  private Integer actualParticipants;
  private BigDecimal currentSurvivalRate;
  // EVACUATION_ROUTE_BLOCKED 웹소켓 알림은 세션당 한 번뿐이라, 그 순간을 놓친 화면(새로고침, 재연결)이
  // 경고 상태를 복원할 수 있도록 상태 조회에도 담는다. 막힌 적이 없으면 둘 다 null.
  private Instant routeBlockedAt;
  private Integer routeBlockedAfterSec;
}
