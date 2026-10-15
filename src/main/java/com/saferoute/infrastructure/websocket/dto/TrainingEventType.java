package com.saferoute.infrastructure.websocket.dto;

public enum TrainingEventType {

    // TrainingSession.complete()/stop()/fail() 호출 시점에 발행할 이벤트.
    // 현재는 위 도메인 메서드를 호출하는 시작/종료 API가 없어 실제로 발행되지는 않는다.
    TRAINING_STATUS_UPDATED,

    // CongestionObservationService.reportObservation() 호출 시 발행된다. (이슈 #93)
    CONGESTION_UPDATED,

    // 혼잡 이벤트 이미지가 S3 업로드 완료 후 연결되었을 때 발행된다. (이슈 #83)
    CONGESTION_IMAGE_UPDATED,

    // CongestionEventService.reportCongestionEvent() 호출 시 발행된다 (이슈 #93).
    CONGESTION_EVENT_RECEIVED,

    // 즉시 혼잡 이벤트의 이미지가 S3 업로드 완료 후 연결되었을 때 발행된다 (이슈 #93).
    CONGESTION_EVENT_IMAGE_UPDATED,

    // CongestionObservationService.tryCreateAiAnalysisStartedEvent()/RouteDeviationService가
    // GeneralMonitoringEventItem(AI_ANALYSIS_STARTED/ROUTE_DEVIATION_DETECTED)을 새로 저장했을 때
    // 발행된다. 저장만 하고 알림이 없으면 화면이 새로고침 전까지 못 보는 문제가 있었다 (이슈 #254).
    GENERAL_MONITORING_EVENT_RECEIVED,

    // RouteRecalculationService.trigger()가 재탐색 결과를 PENDING으로 저장했을 때 발행된다. (이슈 #48)
    ROUTE_RECALCULATION_REQUESTED,

    // RouteRecalculationService.approve()가 재탐색 결과를 승인했을 때 발행된다.
    EVACUATION_ROUTE_UPDATED,

    // RouteRecalculationService.reject()가 재탐색 결과를 거절했을 때 발행된다.
    ROUTE_RECALCULATION_REJECTED,

    // RouteRecalculationService가 PENDING 후보를 시스템적으로 무효화했을 때
    // (혼잡 레벨 변경, 훈련 종료, 혼잡 종료) 발행된다.
    ROUTE_RECALCULATION_CANCELLED,

    // IoTLightService.changeDirection() 호출 시 TrainingEventPublisher.publishIoTLightStatusUpdated()가 발행한다.
    IOT_LIGHT_STATUS_UPDATED,

    // FireSpreadService가 화재 확산을 1스텝 진행시켜 새로 옮겨붙은 셀이 생겼을 때 발행된다.
    FIRE_SPREAD_UPDATED,

    // 화재 확산으로 시작 지점에서 출구로 가는 우회 경로가 처음 사라졌을 때 세션당 한 번 발행된다.
    // 훈련은 계속되며, 종료 여부는 관리자가 판단한다.
    EVACUATION_ROUTE_BLOCKED
}
