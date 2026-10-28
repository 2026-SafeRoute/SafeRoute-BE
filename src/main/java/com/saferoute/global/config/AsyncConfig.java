package com.saferoute.global.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// 혼잡 재탐색(RouteRecalculationService.triggerAsync)을 디바이스 요청 스레드에서 떼어내기 위한
// 전용 풀 (#250). 큐까지 꽉 차면 CallerRunsPolicy로 호출 스레드가 직접 실행하게 해, 극단적
// 과부하에서도 재탐색을 몰래 버리는 대신 지금처럼 동기 실행으로 자연히 degrade 되게 한다.
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String CONGESTION_RECALCULATION_EXECUTOR = "congestionRecalculationExecutor";
    public static final String ROUTE_DEVIATION_EXECUTOR = "routeDeviationExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 50;

    @Override
    @Bean(CONGESTION_RECALCULATION_EXECUTOR)
    public Executor getAsyncExecutor() {
        return newExecutor("congestion-recalc-");
    }

    // 경로 이탈 실시간 판정(RouteDeviationService.evaluateObservationAsync)용 전용 풀. 건물 내 유도등 수만큼
    // DynamoDB/JPA 조회를 반복하는 작업이라 Pi의 1초 관측 요청 스레드(=DB 커넥션을 쥔 트랜잭션) 안에서 돌리면
    // CCTV 수가 늘수록 Pi 응답이 늦어지고 커넥션 풀이 고갈된다. 재탐색 풀과 분리해 서로 큐를 밀어내지 않게 한다.
    @Bean(ROUTE_DEVIATION_EXECUTOR)
    public Executor routeDeviationExecutor() {
        return newExecutor("route-deviation-");
    }

    private Executor newExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    // void @Async 메서드의 예외는 호출자에게 전파되지 않으므로, 여기서 놓치면 조용히
    // 사라진다 - 컨텍스트(메서드/인자)를 남겨 최소한 로그로는 추적 가능하게 한다.
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return this::logUncaughtAsyncException;
    }

    private void logUncaughtAsyncException(Throwable throwable, Method method, Object... params) {
        log.error("비동기 메서드 실행 중 예외 발생: method={}, params={}",
                method.getName(), params, throwable);
    }
}
