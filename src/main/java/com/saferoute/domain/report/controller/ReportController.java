package com.saferoute.domain.report.controller;

import com.saferoute.domain.report.dto.ReportResponse;
import com.saferoute.domain.report.service.TrainingReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "훈련 리포트", description = "훈련 리포트 단건 조회 API")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

  private final TrainingReportService trainingReportService;

  // reportId는 TrainingReport.shortId (URL 노출용 짧은 id)
  @Operation(
      summary = "훈련 리포트 단건 조회",
      description = """
          리포트 생성 시점에 계산·저장된 훈련 리포트 상세를 조회합니다. 등급, 종합 점수와
          함께 대피시간/대피 성공률/병목/경로준수율 4개 항목의 개별 값·점수, 서술형 요약, 개선
          권고 목록, 그리고 누적 대피 인원 추이·구역별 밀집도·최근 대피 시간 차트 데이터를
          함께 반환합니다.

          reportId는 리포트의 UUID가 아니라 URL 노출용으로 별도 발급되는 10자리 shortId입니다.

          riskIndex와 pdfUrl은 아직 계산·생성 로직이 없어 항상 null로 반환되므로, 프론트엔드는
          해당 필드가 채워지지 않은 것을 정상 상태로 처리해야 합니다.

          요청자가 속한 학교의 리포트만 조회할 수 있으며, 다른 학교 소속이거나 존재하지 않는
          reportId는 404로 처리됩니다.
          """
  )
  @GetMapping("/{reportId}")
  public ResponseEntity<ReportResponse> getReport(
      @PathVariable String reportId,
      Authentication authentication) {
    return ResponseEntity.ok(trainingReportService.getReport(reportId, authentication.getName()));
  }

  @Operation(
      summary = "훈련 리포트 PDF 다운로드",
      description = """
          지정한 훈련 리포트를 PDF 파일로 변환해 반환합니다. 리포트는 생성된 이후 값이
          바뀌지 않으므로 PDF를 S3 등에 미리 저장해두지 않고, 매 요청마다 DB에 저장된 리포트
          값으로 그 자리에서 새로 생성합니다.

          응답은 Content-Type: application/pdf이며, Content-Disposition 헤더로
          "report-{reportId}.pdf" 파일명이 함께 내려가므로 프론트엔드는 별도 파일명 지정 없이
          그대로 다운로드에 사용할 수 있습니다.

          요청자가 속한 학교의 리포트만 다운로드할 수 있으며, 다른 학교 소속이거나 존재하지
          않는 reportId는 404로 처리됩니다.
          """
  )
  @GetMapping("/{reportId}/pdf")
  public ResponseEntity<byte[]> downloadReportPdf(
      @PathVariable String reportId,
      Authentication authentication) {
    byte[] pdf = trainingReportService.generatePdf(reportId, authentication.getName());
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename("report-" + reportId + ".pdf").build().toString())
        .body(pdf);
  }
}
