package com.saferoute.global.api.error;

import com.saferoute.global.api.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum FloorErrorCode implements BaseErrorCode {

    FLOOR_NOT_FOUND(HttpStatus.NOT_FOUND, "FLOOR001", "도면을 찾을 수 없습니다."),
    DUPLICATE_FLOOR_NUM(HttpStatus.CONFLICT, "FLOOR002", "이미 등록된 층 번호입니다."),
    MAP_IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "FLOOR003", "등록된 도면 이미지가 없습니다."),
    FLOOR_LOCKED_BY_RUNNING_TRAINING(HttpStatus.CONFLICT, "FLOOR004", "진행 중인 훈련이 있는 건물의 도면은 변경할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}