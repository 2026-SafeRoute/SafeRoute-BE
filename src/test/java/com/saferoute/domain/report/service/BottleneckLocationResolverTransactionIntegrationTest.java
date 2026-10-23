package com.saferoute.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.saferoute.domain.building.entity.Building;
import com.saferoute.domain.building.entity.BuildingType;
import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.telemetry.dynamo.repository.CongestionEventRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 리포트 생성은 병목 위치 조회 실패를 삼키고 기존 문구로 저장한다(TrainingReportService.resolveBottleneckLocation).
// 그런데 resolver가 리포트 트랜잭션에 그대로 참여(REQUIRED)하면, 예외가 resolver의 트랜잭션 경계를 지나는 순간
// 바깥 트랜잭션이 rollback-only로 표시돼 호출부가 예외를 잡아도 커밋 시점에 리포트 저장이 통째로 실패한다.
// REQUIRES_NEW로 분리돼 있어 바깥 트랜잭션이 정상 커밋되는지 실제 스프링 트랜잭션으로 확인한다.
@SpringBootTest
class BottleneckLocationResolverTransactionIntegrationTest {

    @Autowired
    private BottleneckLocationResolver bottleneckLocationResolver;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private CongestionEventRepository congestionEventRepository;

    @Test
    void resolveFailure_doesNotRollBackCallerTransaction() {
        UUID sessionId = UUID.randomUUID();
        given(congestionEventRepository.countBottlenecksByCctvCode(sessionId.toString()))
                .willThrow(new IllegalStateException("DynamoDB 장애"));

        UUID buildingId = transactionTemplate.execute(status -> {
            Building building = buildingRepository.save(Building.create(
                    "병목 위치 테스트 건물", "서울특별시 안전구 2", BuildingType.CLASSROOM, "SafeRoute School"));
            assertThatThrownBy(() -> bottleneckLocationResolver.resolve(sessionId, building.getId()))
                    .isInstanceOf(IllegalStateException.class);
            return building.getId();
        });

        assertThat(buildingRepository.findById(buildingId)).isPresent();
    }
}
