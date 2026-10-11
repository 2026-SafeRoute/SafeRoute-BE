package com.saferoute.domain.congestion.service;

import com.saferoute.domain.congestion.entity.CongestionConfig;
import com.saferoute.domain.congestion.repository.CongestionConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 관측 주기 기본값을 5초 -> 2초로 줄이면서(#274), CongestionConfig.createDefault()가 닿지 않는
// 기존 싱글턴 행을 기동 시 한 번 맞춰준다 (스키마 마이그레이션 도구가 없어 여기서 처리한다).
// 이전 기본값(5)일 때만 바꾸므로 여러 번 실행돼도 안전하고, 나머지 설정값은 건드리지 않는다.
// updateSettings()가 version을 올려주므로 Pi가 새 주기를 다시 조회한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotIntervalDefaultMigration implements ApplicationRunner {

    static final int PREVIOUS_DEFAULT_SEC = 5;
    static final int NEW_DEFAULT_SEC = 2;

    private final CongestionConfigRepository congestionConfigRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        congestionConfigRepository.findById(CongestionConfig.SINGLETON_ID)
                .filter(config -> Integer.valueOf(PREVIOUS_DEFAULT_SEC).equals(config.getSnapshotIntervalSec()))
                .ifPresent(config -> {
                    config.updateSettings(NEW_DEFAULT_SEC, null, null, null, null, null, null, null, null);
                    log.info("기존 혼잡 설정의 관측 주기를 {}초에서 {}초로 변경: configVersion={}",
                            PREVIOUS_DEFAULT_SEC, NEW_DEFAULT_SEC, config.getVersion());
                });
    }
}
