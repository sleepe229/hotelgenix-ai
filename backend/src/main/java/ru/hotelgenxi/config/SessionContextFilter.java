package ru.hotelgenxi.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 🔐 Фильтр для установки sessionId в ThreadLocal
 * ✅ Работает для HTTP и WebSocket запросов
 */
@Component
public class SessionContextFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(SessionContextFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            if (request instanceof HttpServletRequest httpRequest) {
                // ✅ Получаем сессию (создаём если нужно)
                HttpSession session = httpRequest.getSession(true);
                String sessionId = session.getId();

                log.debug("[SESSION] Setting sessionId: {}", sessionId);
                SessionContext.setSessionId(sessionId);
            }

            chain.doFilter(request, response);

        } finally {
            SessionContext.clear();
        }
    }
}