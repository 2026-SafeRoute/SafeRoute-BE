package com.saferoute.global.api.error;

import com.saferoute.global.api.code.BaseErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements BaseErrorCode {

  REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "REPORT001", "훈련 리포트를 찾을 수 없습니다."),
  SESSION_NOT_ENDED(HttpStatus.CONFLICT, "REPORT002", "훈련이 아직 종료되지 않아 리포트를 생성할 수 없습니다."),
  REPORT_ALREADY_EXISTS(HttpStatus.CONFLICT, "REPORT003", "이미 해당 훈련 세션의 리포트가 존재합니다."),
  SURVIVOR_COUNT_EXCEEDS_PARTICIPANTS(HttpStatus.BAD_REQUEST, "REPORT004", "대피 성공 인원이 전체 참여 인원보다 많습니다."),
  TARGET_EVACUATION_SEC_NOT_CONFIGURED(HttpStatus.CONFLICT, "REPORT005", "목표 대피 시간이 설정되지 않아 리포트를 생성할 수 없습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
