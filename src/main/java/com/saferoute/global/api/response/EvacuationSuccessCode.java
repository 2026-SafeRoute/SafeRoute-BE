package com.saferoute.global.api.response;

import com.saferoute.global.api.code.BaseCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum EvacuationSuccessCode implements BaseCode {

    MAP_NODE_CREATED(HttpStatus.CREATED, "EVAC_SUCCESS_001", "노드가 생성되었습니다."),
    MAP_NODE_DELETED(HttpStatus.OK, "EVAC_SUCCESS_002", "노드가 삭제되었습니다."),
    MAP_EDGE_CREATED(HttpStatus.CREATED, "EVAC_SUCCESS_003", "엣지가 생성되었습니다."),
    MAP_EDGE_DELETED(HttpStatus.OK, "EVAC_SUCCESS_004", "엣지가 삭제되었습니다."),
    ROUTE_RECALCULATION_APPROVED(HttpStatus.OK, "EVAC_SUCCESS_005", "재탐색 결과가 승인되었습니다."),
    ROUTE_RECALCULATION_REJECTED(HttpStatus.OK, "EVAC_SUCCESS_006", "재탐색 결과가 거절되었습니다."),
    ROUTE_RECALCULATION_LIST_FOUND(HttpStatus.OK, "EVAC_SUCCESS_007", "재탐색 목록을 조회했습니다."),
    ROUTE_RECALCULATION_DETAIL_FOUND(HttpStatus.OK, "EVAC_SUCCESS_008", "재탐색 상세를 조회했습니다."),
    FLOOR_ROUTE_DIAGNOSIS_FOUND(HttpStatus.OK, "EVAC_SUCCESS_009", "층 경로 연결 진단을 조회했습니다."),
    SCENARIO_ROUTE_DIAGNOSIS_FOUND(HttpStatus.OK, "EVAC_SUCCESS_010", "시나리오 경로 진단을 조회했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
