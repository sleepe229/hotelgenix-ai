package ru.hotelgenxi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 🔌 WebSocket конфиг с перехватом sessionId
 * ✅ Устанавливает sessionId в ThreadLocal для каждого сообщения
 * ✅ Поддерживает /topic (публичные) и /queue (личные) сообщения
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // ✅ /topic — для публичных сообщений
        // ✅ /queue — для личных сообщений конкретному пользователю
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws-chat")
                .setAllowedOriginPatterns("*")
                .withSockJS();  // SockJS fallback для старых браузеров
    }

    /**
     * ✅ КРИТИЧНО: Перехватываем все WebSocket сообщения
     * Устанавливаем sessionId в ThreadLocal для доступа из любого места
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null) {
                    String sessionId = accessor.getSessionId();

                    // ✅ При подключении логируем
                    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                        log.info("[WEBSOCKET] Client connected. SessionId: {}", sessionId);
                    }

                    // ✅ Для КАЖДОГО сообщения устанавливаем sessionId в ThreadLocal
                    if (sessionId != null) {
                        SessionContext.setSessionId(sessionId);
                        log.debug("[WEBSOCKET] Set sessionId in ThreadLocal: {}", sessionId);
                    } else {
                        log.warn("[WEBSOCKET] SessionId is null for command: {}", accessor.getCommand());
                    }
                }

                return message;
            }
        });
    }
}