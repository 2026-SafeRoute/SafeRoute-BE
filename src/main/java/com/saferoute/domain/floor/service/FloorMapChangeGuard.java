package com.saferoute.domain.floor.service;

import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 도면 재업로드·초기화·그리드 재생성·AI 분석은 층의 노드/엣지/그리드 셀을 지우고 새로 만든다. 훈련이 진행
// 중인 건물에서 이걸 허용하면 안내 중인 경로·유도등 설정·화재 확산 셀이 훈련 도중에 사라져 안내와 화재
// 확산이 깨지므로, 그 건물에 RUNNING 세션이 있으면 409로 막는다.
@Component
@RequiredArgsConstructor
public class FloorMapChangeGuard {

    private final TrainingSessionRepository trainingSessionRepository;

    public void validateNoRunningTraining(UUID buildingId) {
        if (trainingSessionRepository.existsByStatusAndScenario_Building_Id(TrainingStatus.RUNNING, buildingId)) {
            throw new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        }
    }
}
