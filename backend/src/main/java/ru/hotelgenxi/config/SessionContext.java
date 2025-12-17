package ru.hotelgenxi.config;

/**
 * 🔧 Контекст текущей WebSocket сессии
 * Используется для передачи sessionId без параметров в ThreadLocal
 */
public class SessionContext {
    private static final ThreadLocal<String> sessionIdHolder = new ThreadLocal<>();

    public static void setSessionId(String sessionId) {
        sessionIdHolder.set(sessionId);
    }

    public static String getSessionId() {
        return sessionIdHolder.get();
    }

    public static void clear() {
        sessionIdHolder.remove();
    }
}
