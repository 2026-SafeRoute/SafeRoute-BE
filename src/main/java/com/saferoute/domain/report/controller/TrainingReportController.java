package com.saferoute.domain.report.controller;

import com.saferoute.domain.report.dto.GenerateReportRequest;
import com.saferoute.domain.report.dto.ReportResponse;
import com.saferoute.domain.report.service.TrainingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "훈련 리포트", description = "훈련 세션 결과 리포트 생성 API")
@RestController
@RequestMapping("/api/v1/analysis/trainings")
@RequiredArgsConstructor
public class TrainingReportController {
  private final TrainingReportService trainingReportService;

  // 훈련 종료 후 관리자가 참여/대피 성공 인원을 입력하면, 나머지 평가 항목은 BE가 직접 계산해 리포트를 생성한다.
  @Operation(
      summary = "훈련 세션 리포트 생성",
      description = """
          종료된 훈련 세션에 대해 관리자가 참여 인원(participantCount)과 대피 성공 인원
          (survivorCount)만 입력하면, 나머지 평가 항목(대피시간/병목/경로준수율)은 세션 동안
          이미 수집된 데이터로 서버가 직접 계산해 리포트를 생성합니다.

          대피시간은 세션의 시작~종료 시각 차이(개인별이 아닌 세션 전체 소요 시간)를 시나리오의
          목표 대피시간과 비교해 점수화하고, 병목 횟수는 세션 기간 동안 감지된 혼잡 이벤트 수,
          경로준수율은 유도등 안내 방향과 실제 이동 방향이 어긋난 관측 구간의 비율로 계산됩니다.
          최종 종합 점수는 대피시간 35% · 대피 성공률 30% · 병목 20% · 경로준수율 15% 가중합이며,
          이 점수로 등급(Grade)이 정해집니다. 응답에 포함된 reportId(shortId)로 리포트 단건
          조회/PDF 다운로드 API를 호출할 수 있습니다.

          세션이 아직 종료되지 않았거나(endedAt이 null), 이미 해당 세션에 대한 리포트가
          생성되어 있거나, survivorCount가 participantCount보다 크면 오류가 발생하며 리포트는
          세션당 한 번만 생성할 수 있습니다.
          """
  )
  @PostMapping("/{sessionId}")
  public ResponseEntity<ReportResponse> generateReport(
      @PathVariable UUID sessionId,
      @Valid @RequestBody GenerateReportRequest request,
      Authentication authentication) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(trainingReportService.generate(sessionId, request, authentication.getName()));
  }
}
