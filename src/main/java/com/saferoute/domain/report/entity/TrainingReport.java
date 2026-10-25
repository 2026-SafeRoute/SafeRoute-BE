package com.saferoute.domain.report.entity;

import com.saferoute.domain.training.entity.TrainingSession;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Table(name = "training_reports")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TrainingReport {

    // URL에 노출되는 짧은 id. 62^10 조합이라 무작위 생성만으로도 충돌 가능성이 무시할 만큼 낮고,
    // 별도 채번 테이블(CctvCodeAllocation) 없이 컬럼의 UNIQUE 제약을 최종 방어선으로 둔다.
    private static final String SHORT_ID_ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SHORT_ID_LENGTH = 10;
    private static final SecureRandom SHORT_ID_RANDOM = new SecureRandom();

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "short_id", nullable = false, updatable = false, unique = true, length = SHORT_ID_LENGTH)
    private String shortId;

    //훈련 상태 (A, B 등)
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 20)
    private Grade grade;

    // 4개 항목 점수를 가중합(대피시간 35%/대피 성공률 30%/병목 20%/경로준수율 15%)한 최종 점수 (0~100).
    @Column(name = "overall_score", nullable = false)
    private Double overallScore;

    // 훈련 시작~종료까지 걸린 전체 시간(초). 개인별 평균이 아니라 세션 전체 소요 시간이다
    // (개인별 탈출 시각을 추적하는 장치가 없어 낼 수 있는 가장 정확한 값).
    @Column(name = "avg_evacuation_sec")
    private Integer avgEvacuationSec;

    // 위 avgEvacuationSec을 시나리오의 targetEvacuationSec 기준으로 0~100점 환산한 값.
    @Column(name = "evacuation_score", nullable = false)
    private Integer evacuationScore;

    @Column(name="participant_count", nullable = false)
    private Integer participantCount;

    // 훈련 종료 후 관리자가 수동으로 판정해 입력하는 대피 성공 인원 수.
    @Column(name = "survivor_count", nullable = false)
    private Integer survivorCount;

    // survivorCount / participantCount * 100. 그 자체가 대피 성공률 항목 점수다.
    @Column(name = "survival_rate", nullable = false)
    private BigDecimal survivalRate;

    // 세션 기간 중 감지된 병목(혼잡) 이벤트 횟수.
    @Column(name = "bottleneck_count", nullable = false)
    private Integer bottleneckCount;

    @Column(name = "bottleneck_score", nullable = false)
    private Integer bottleneckScore;

    // 유도등 안내 방향과 실제 이동 방향이 어긋난 관측 구간의 비율 (0.0~1.0).
    @Column(name = "deviation_rate", nullable = false)
    private Double deviationRate;

    @Column(name = "deviation_score", nullable = false)
    private Integer deviationScore;

    // 실시간 경고/시각화용 보조 지표 - 등급 가중치에는 포함되지 않는다. 아직 계산 로직 미구현이라 항상 null.
    @Column(name = "risk_index")
    private Double riskIndex;

    // 훈련 시작부터 화재로 대피 경로가 모두 막히기까지 걸린 초. 막힌 적이 없으면 null. 점수에 반영하지 않는
    // 참고 지표다. 다른 지표처럼 생성 시점 값을 복사해 두어, 조회할 때 세션을 다시 읽지 않는다.
    @Column(name = "route_blocked_after_sec")
    private Integer routeBlockedAfterSec;

    // 자동 평가 보고서 서술형 요약. TrainingReportNarrativeGenerator가 점수 기반 고정 템플릿으로 생성
    @Column(name="summary_text", columnDefinition = "TEXT")
    private String summaryText;

    @Column(name="pdf_url", length = 1000)
    private String pdfUrl;

    @ElementCollection
    @CollectionTable(name = "training_report_recommendations",
            joinColumns = @JoinColumn(name = "training_report_id"))
    @OrderColumn(name = "point_order")
    private List<RecommendationPoint> recommendations = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "training_report_evacuation_points",
            joinColumns = @JoinColumn(name = "training_report_id"))
    @OrderColumn(name = "point_order")
    private List<CumulativeEvacuationPoint> cumulativeEvacuationPoints = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "training_report_zone_densities",
            joinColumns = @JoinColumn(name = "training_report_id"))
    @OrderColumn(name = "point_order")
    private List<ZoneDensityPoint> zoneDensityPoints = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "training_report_recent_evacuations",
            joinColumns = @JoinColumn(name = "training_report_id"))
    @OrderColumn(name = "point_order")
    private List<RecentEvacuationPoint> recentEvacuationPoints = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "training_session_id",
            nullable = false,
            unique = true
    )
    private TrainingSession trainingSession;

    private TrainingReport(Grade grade, Double overallScore,
                           Integer avgEvacuationSec, Integer evacuationScore,
                           Integer participantCount, Integer survivorCount, BigDecimal survivalRate,
                           Integer bottleneckCount, Integer bottleneckScore,
                           Double deviationRate, Integer deviationScore,
                           TrainingReportCharts charts,
                           String summaryText, List<RecommendationPoint> recommendations,
                           TrainingSession trainingSession) {
        this.shortId = generateShortId();
        this.grade = grade;
        this.overallScore = overallScore;
        this.avgEvacuationSec = avgEvacuationSec;
        this.evacuationScore = evacuationScore;
        this.participantCount = participantCount;
        this.survivorCount = survivorCount;
        this.survivalRate = survivalRate;
        this.bottleneckCount = bottleneckCount;
        this.bottleneckScore = bottleneckScore;
        this.deviationRate = deviationRate;
        this.deviationScore = deviationScore;
        this.cumulativeEvacuationPoints = new ArrayList<>(charts.cumulativeEvacuation());
        this.zoneDensityPoints = new ArrayList<>(charts.zoneDensities());
        this.recentEvacuationPoints = new ArrayList<>(charts.recentEvacuationTimes());
        this.summaryText = summaryText;
        this.recommendations = new ArrayList<>(recommendations);
        this.trainingSession = trainingSession;
        this.routeBlockedAfterSec = trainingSession.routeBlockedAfterSec();
    }

    // 4개 항목(대피시간/대피 성공률/병목/경로준수율)을 이미 갖고 있는 데이터로부터 계산한 결과로 리포트를 생성한다.
    // riskIndex/pdfUrl은 아직 계산·생성 로직이 없어 null로 시작한다.
    public static TrainingReport create(Grade grade, Double overallScore,
                                        Integer avgEvacuationSec, Integer evacuationScore,
                                        Integer participantCount, Integer survivorCount, BigDecimal survivalRate,
                                        Integer bottleneckCount, Integer bottleneckScore,
                                        Double deviationRate, Integer deviationScore,
                                        TrainingReportCharts charts,
                                        String summaryText, List<RecommendationPoint> recommendations,
                                        TrainingSession trainingSession) {
        return new TrainingReport(grade, overallScore, avgEvacuationSec, evacuationScore,
                participantCount, survivorCount, survivalRate, bottleneckCount, bottleneckScore,
                deviationRate, deviationScore, charts, summaryText, recommendations, trainingSession);
    }

    private static String generateShortId() {
        StringBuilder builder = new StringBuilder(SHORT_ID_LENGTH);
        for (int i = 0; i < SHORT_ID_LENGTH; i++) {
            builder.append(SHORT_ID_ALPHABET.charAt(SHORT_ID_RANDOM.nextInt(SHORT_ID_ALPHABET.length())));
        }
        return builder.toString();
    }
}