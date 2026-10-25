package com.saferoute.domain.report.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.saferoute.domain.report.entity.Grade;
import com.saferoute.domain.report.entity.RecommendationPoint;
import com.saferoute.domain.report.entity.TrainingReport;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TrainingReportPdfGenerator {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.systemDefault());

    private static final Color BAR_FILLED_COLOR = new Color(0x2F, 0x6F, 0xED);
    private static final Color BAR_EMPTY_COLOR = new Color(0xE5, 0xE7, 0xEB);
    private static final Color HEADER_BG_COLOR = new Color(0xF3, 0xF4, 0xF6);

    public byte[] generate(TrainingReport report) {
        Document document = new Document(PageSize.A4, 40, 40, 50, 50);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            BaseFont baseFont = BaseFont.createFont("HYSMyeongJo-Medium", "UniKS-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(baseFont, 20, Font.BOLD);
            Font headingFont = new Font(baseFont, 13, Font.BOLD);
            Font bodyFont = new Font(baseFont, 10, Font.NORMAL);
            Font smallFont = new Font(baseFont, 9, Font.NORMAL);
            Font gradeFont = new Font(baseFont, 40, Font.BOLD, gradeColor(report.getGrade()));

            addTitle(document, titleFont, report);
            addGradeSection(document, gradeFont, bodyFont, report);
            addScoreTable(document, headingFont, smallFont, report);
            addSummary(document, headingFont, bodyFont, report);
            addRecommendations(document, headingFont, smallFont, report.getRecommendations());
            addRawDataAppendix(document, headingFont, smallFont, report);

            document.close();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("PDF 생성에 실패했습니다.", e);
        }
        return out.toByteArray();
    }

    private void addTitle(Document document, Font titleFont, TrainingReport report) throws DocumentException {
        Paragraph title = new Paragraph("화재 대피 훈련 분석 보고서", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Font metaFont = new Font(titleFont.getBaseFont(), 9, Font.NORMAL, Color.GRAY);
        Paragraph meta = new Paragraph("리포트 ID: " + report.getShortId(), metaFont);
        meta.setAlignment(Element.ALIGN_CENTER);
        meta.setSpacingAfter(20f);
        document.add(meta);
    }

    private void addGradeSection(Document document, Font gradeFont, Font bodyFont, TrainingReport report)
            throws DocumentException {
        Paragraph grade = new Paragraph(report.getGrade().name() + "  " + report.getOverallScore() + "점", gradeFont);
        grade.setAlignment(Element.ALIGN_CENTER);
        grade.setSpacingAfter(20f);
        document.add(grade);
    }

    private void addScoreTable(Document document, Font headingFont, Font smallFont, TrainingReport report)
            throws DocumentException {
        document.add(sectionHeading("평가 항목별 점수", headingFont));

        PdfPTable table = new PdfPTable(new float[]{3f, 1.2f, 4f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);

        addHeaderCell(table, "항목", smallFont);
        addHeaderCell(table, "점수", smallFont);
        addHeaderCell(table, "", smallFont);

        addScoreRow(table, smallFont, "대피 시간 (35%)", report.getEvacuationScore());
        addScoreRow(table, smallFont, "대피 성공률 (30%)", report.getSurvivalRate().intValue());
        addScoreRow(table, smallFont, "병목 회피 (20%)", report.getBottleneckScore());
        addScoreRow(table, smallFont, "경로 준수율 (15%)", report.getDeviationScore());

        document.add(table);
    }

    private void addScoreRow(PdfPTable table, Font font, String label, int score) {
        table.addCell(plainCell(label, font));
        table.addCell(plainCell(score + "/100", font));
        table.addCell(scoreBarCell(score));
    }

    // 점수만큼 채워진 파란 칸 + 나머지 회색 칸으로 이루어진 중첩 표 하나를 막대그래프처럼 보이게 만든다.
    // 0점/100점처럼 한쪽이 꽉 찬 경우엔 반대쪽 색이 1%라도 슬리버로 비치지 않도록 단일 색 칸으로 만든다.
    private PdfPCell scoreBarCell(int score) {
        int clamped = Math.max(0, Math.min(100, score));
        PdfPTable bar;
        if (clamped <= 0) {
            bar = new PdfPTable(1);
            bar.setWidthPercentage(100);
            bar.addCell(barSegment(BAR_EMPTY_COLOR));
        } else if (clamped >= 100) {
            bar = new PdfPTable(1);
            bar.setWidthPercentage(100);
            bar.addCell(barSegment(BAR_FILLED_COLOR));
        } else {
            bar = new PdfPTable(new float[]{clamped, 100 - clamped});
            bar.setWidthPercentage(100);
            bar.addCell(barSegment(BAR_FILLED_COLOR));
            bar.addCell(barSegment(BAR_EMPTY_COLOR));
        }

        PdfPCell wrapper = new PdfPCell(bar);
        wrapper.setPadding(6f);
        wrapper.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return wrapper;
    }

    private PdfPCell barSegment(Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(color);
        cell.setFixedHeight(10f);
        cell.setBorder(0);
        return cell;
    }

    private void addSummary(Document document, Font headingFont, Font bodyFont, TrainingReport report)
            throws DocumentException {
        document.add(sectionHeading("자동 평가 보고서", headingFont));
        Paragraph summary = new Paragraph(
                report.getSummaryText() != null ? report.getSummaryText() : "-", bodyFont);
        summary.setSpacingBefore(6f);
        summary.setSpacingAfter(16f);
        summary.setLeading(15f);
        document.add(summary);
    }

    private void addRecommendations(Document document, Font headingFont, Font smallFont,
                                     List<RecommendationPoint> recommendations) throws DocumentException {
        document.add(sectionHeading("개선 권고사항", headingFont));

        if (recommendations.isEmpty()) {
            Paragraph none = new Paragraph("특이사항이 없습니다.", smallFont);
            none.setSpacingBefore(6f);
            none.setSpacingAfter(16f);
            document.add(none);
            return;
        }

        PdfPTable table = new PdfPTable(new float[]{1.2f, 2.5f, 4f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);
        table.setSpacingAfter(16f);

        addHeaderCell(table, "우선순위", smallFont);
        addHeaderCell(table, "제목", smallFont);
        addHeaderCell(table, "설명", smallFont);

        for (RecommendationPoint point : recommendations) {
            table.addCell(plainCell(priorityLabel(point.getPriority()), smallFont));
            table.addCell(plainCell(point.getTitle(), smallFont));
            table.addCell(plainCell(point.getDescription(), smallFont));
        }

        document.add(table);
    }

    private void addRawDataAppendix(Document document, Font headingFont, Font smallFont, TrainingReport report)
            throws DocumentException {
        document.add(sectionHeading("세부 데이터", headingFont));

        PdfPTable table = new PdfPTable(new float[]{3f, 3f});
        table.setWidthPercentage(70);
        table.setSpacingBefore(6f);

        addDataRow(table, smallFont, "대피 소요 시간", formatDuration(report.getAvgEvacuationSec()));
        addDataRow(table, smallFont, "참여 인원 / 대피 성공 인원",
                report.getParticipantCount() + "명 / " + report.getSurvivorCount() + "명");
        addDataRow(table, smallFont, "병목(혼잡) 발생 횟수", report.getBottleneckCount() + "회");
        addDataRow(table, smallFont, "경로 이탈률",
                String.format("%.1f%%", report.getDeviationRate() * 100.0));
        addDataRow(table, smallFont, "생성 일시",
                report.getCreatedAt() != null ? DATE_FORMAT.format(report.getCreatedAt()) : "-");

        document.add(table);
    }

    private void addDataRow(PdfPTable table, Font font, String label, String value) {
        PdfPCell labelCell = plainCell(label, font);
        labelCell.setBackgroundColor(HEADER_BG_COLOR);
        table.addCell(labelCell);
        table.addCell(plainCell(value, font));
    }

    private Paragraph sectionHeading(String text, Font font) {
        Paragraph heading = new Paragraph(text, font);
        heading.setSpacingBefore(14f);
        return heading;
    }

    private PdfPCell plainCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setPadding(5f);
        return cell;
    }

    private PdfPCell addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = plainCell(text, font);
        cell.setBackgroundColor(HEADER_BG_COLOR);
        table.addCell(cell);
        return cell;
    }

    private Color gradeColor(Grade grade) {
        return switch (grade) {
            case A -> new Color(0x16, 0x8A, 0x3D);
            case B -> new Color(0x2F, 0x6F, 0xED);
            case C -> new Color(0xE0, 0x8E, 0x0B);
            case D -> new Color(0xE0, 0x5A, 0x0B);
            case F -> new Color(0xD9, 0x2D, 0x2D);
        };
    }

    private String priorityLabel(com.saferoute.domain.report.entity.RecommendationPriority priority) {
        return switch (priority) {
            case HIGH -> "높음";
            case MEDIUM -> "중간";
            case LOW -> "낮음";
        };
    }

    private String formatDuration(Integer totalSeconds) {
        if (totalSeconds == null) {
            return "-";
        }
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes == 0 ? seconds + "초" : minutes + "분 " + seconds + "초";
    }
}
