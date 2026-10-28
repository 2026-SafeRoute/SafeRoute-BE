package com.saferoute.infrastructure.websocket.config;

import com.saferoute.global.config.CorsProperties;
import com.saferoute.infrastructure.websocket.security.StompAuthChannelInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// 관리자 대시보드로 훈련 현황을 실시간 전달하기 위한 STOMP WebSocket 설정.
// 관리자 웹 -> 서버 명령은 기존 REST API를 그대로 사용하므로 application destination prefix(/app)는
// 현재 실제로 사용하는 @MessageMapping이 없다. 향후 클라이언트->서버 STOMP 메시지가 필요해지면 사용한다.
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // STOMP 하트비트 주기(ms). 하트비트가 없으면 ALB/NAT idle timeout으로 TCP가 조용히 끊겨도 브라우저는
    // 여전히 구독 중이라 믿고 이벤트를 영영 못 받는다("Pi는 보내는데 대시보드만 멈춤"). 서버->클라이언트,
    // 클라이언트->서버 모두 10초로 두면 어느 쪽이든 끊김을 30초 안에 알아채고 재연결할 수 있다.
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final CorsProperties corsProperties;
    private TaskScheduler messageBrokerTaskScheduler;

    public WebSocketConfig(
            StompAuthChannelInterceptor stompAuthChannelInterceptor,
            CorsProperties corsProperties
    ) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.corsProperties = corsProperties;
    }

    // 하트비트에는 TaskScheduler가 필요하다. 메시지 브로커 설정이 만드는 messageBrokerTaskScheduler를 그대로
    // 쓰되, 그 빈은 이 설정(WebSocketMessageBrokerConfigurer)을 소비하는 쪽에서 만들어지므로 생성자 주입이
    // 아니라 @Lazy 세터 주입으로 순환 의존을 피한다(Spring 공식 문서의 simple broker 하트비트 예제와 같은 방식).
    @Autowired
    public void setMessageBrokerTaskScheduler(
            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 브라우저 STOMP 클라이언트에서 Authorization 헤더는 CONNECT 프레임으로 전달되므로
        // 핸드셰이크 자체는 인증 없이 열어두고(SecurityConfig에서 permitAll), 인증은
        // StompAuthChannelInterceptor에서 CONNECT 프레임 단계에 수행한다.
        //
        // REST API와 동일한 프론트엔드 Origin만 WebSocket handshake에 허용한다.
        // 정확한 Origin 목록을 사용하며 wildcard 패턴은 허용하지 않는다.
        // 프론트가 SockJS fallback을 요구하면 이후 .withSockJS()를 추가한다.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}