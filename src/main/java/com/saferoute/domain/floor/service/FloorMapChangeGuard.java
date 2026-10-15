package com.saferoute.domain.floor.service;

import com.saferoute.domain.building.repository.BuildingRepository;
import com.saferoute.domain.training.entity.TrainingStatus;
import com.saferoute.domain.training.repository.TrainingSessionRepository;
import com.saferoute.global.api.error.FloorErrorCode;
import com.saferoute.global.api.exception.ApiException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 도면 재업로드·초기화·그리드 재생성·AI 분석 결과 반영은 층의 노드/엣지/그리드 셀을 지우고 새로 만든다. 훈련이
// 진행 중인 건물에서 이걸 허용하면 안내 중인 경로·유도등 설정·화재 확산 셀이 훈련 도중에 사라져 안내와 화재
// 확산이 깨지므로, 그 건물에 RUNNING 세션이 있으면 409로 막는다.
@Component
@RequiredArgsConstructor
public class FloorMapChangeGuard {

    private final BuildingRepository buildingRepository;
    private final TrainingSessionRepository trainingSessionRepository;

    // 훈련 시작(TrainingSessionService.start)과 같은 건물 행 잠금을 먼저 잡은 뒤 RUNNING 여부를 본다. 잠금 없이
    // 검사하면 "검사 통과 -> 그 사이 훈련 시작 커밋 -> 그래프 삭제" 순서로 끼어들 수 있다. 잠금은 호출부
    // 트랜잭션이 끝날 때까지 유지되므로, 그래프 교체가 끝나기 전에는 훈련을 시작할 수 없다.
    // 호출부에 트랜잭션이 없으면(분석 요청의 사전 검사) 이 메서드만의 짧은 트랜잭션으로 검사한다.
    @Transactional
    public void validateNoRunningTraining(UUID buildingId) {
        buildingRepository.findByIdForUpdate(buildingId);
        if (trainingSessionRepository.existsByStatusAndScenario_Building_Id(TrainingStatus.RUNNING, buildingId)) {
            throw new ApiException(FloorErrorCode.FLOOR_LOCKED_BY_RUNNING_TRAINING);
        }
    }
}
