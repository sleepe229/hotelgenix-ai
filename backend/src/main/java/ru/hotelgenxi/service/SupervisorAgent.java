package ru.hotelgenxi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.hotelgenxi.dto.ChatMessage;

/**
 * 🧠 СУПЕРВИЗОР АГЕНТ — мозг системы!
 * <p>
 * Анализирует входящий запрос и решает:
 * - Это поиск отеля? → RAGAnalystAgent
 * - Это поиск информации (погода, цены, курсы)? → ResearchAgent (с function calling)
 * - Это анализ фото? → VisionAgent (позже)
 * - Это просто диалог? → GigaChatService
 * <p>
 * МАРШРУТИЗАЦИЯ (в порядке приоритета):
 * 1️⃣  Hotel Search: "отель", "бронь", "забронировать", "найди куда поехать"
 * 2️⃣  Research: "погода", "цена на рейс", "курс валюты", "как добраться" (с tools!)
 * 3️⃣  Vision: загрузка изображения
 * 4️⃣  General Chat: всё остальное
 */
@Service
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final RAGAnalystAgent ragAnalystAgent;
    private final ResearchAgent researchAgent;
    private final GigaChatService gigaChatService;
    private final SimpMessagingTemplate messagingTemplate;

    public SupervisorAgent(
            RAGAnalystAgent ragAnalystAgent,
            ResearchAgent researchAgent,
            GigaChatService gigaChatService,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.ragAnalystAgent = ragAnalystAgent;
        this.researchAgent = researchAgent;
        this.gigaChatService = gigaChatService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 🎯 Главный метод маршрутизации
     *
     * @param userMessage — сообщение от пользователя
     * @param sessionId   — ID сессии WebSocket (для отправки ответов)
     */
    public void handleUserQuery(String userMessage, String sessionId) {
        log.info("[SUPERVISOR] Analyzing query: {}", userMessage);

        if (userMessage == null || userMessage.trim().isEmpty()) {
            log.warn("[SUPERVISOR] Empty message received");
            return;
        }

        try {
            // 🔄 ПРИОРИТЕТ 1: Hotel Search (проверяем ПЕРВЫМ!)
            if (isHotelSearchQuery(userMessage)) {
                log.info("[SUPERVISOR] → Routing to RAG Analyst Agent");
                ragAnalystAgent.handleHotelSearch(userMessage);
                return;
            }

            // 🔄 ПРИОРИТЕТ 2: Research (информационные запросы с function calling)
            if (isResearchQuery(userMessage)) {
                log.info("[SUPERVISOR] → Routing to Research Agent (with function calling)");
                researchAgent.processResearchQuery(userMessage, sessionId);
                return;
            }

            // 🔄 ПРИОРИТЕТ 3: Общий диалог
            log.info("[SUPERVISOR] → Routing to GigaChat (General Chat)");
            gigaChatService.streamResponse(userMessage);

        } catch (Exception e) {
            log.error("[SUPERVISOR] Error routing query", e);
            sendErrorMessage(sessionId, "❌ Произошла ошибка при обработке вашего запроса: " + e.getMessage());
        }
    }

    /**
     * 🏨 Определяем, это запрос поиска отеля?
     * ✅ РАСШИРЕННЫЕ ТРИГГЕРЫ
     */
    private boolean isHotelSearchQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }

        String lower = query.toLowerCase();

        // ============ ОСНОВНЫЕ ТРИГГЕРЫ ==============
        String[] mainTriggers = {
                "отель", "отели", "гостинец", "гостиница",
                "бронь", "забронировать", "хочу остановиться",
                "где остановиться", "жилье", "апартамент",
                "буклет", "каталог отелей",
                "поиск отеля", "подберите отель", "рекомендуй отель"
        };

        for (String trigger : mainTriggers) {
            if (lower.contains(trigger)) {
                log.debug("[SUPERVISOR] Hotel trigger found: '{}'", trigger);
                return true;
            }
        }

        // ============ ТРИГГЕРЫ ПО УСЛУГАМ ==============
        String[] amenityTriggers = {
                "детский клуб", "kids club",
                "all inclusive", "all-inclusive", "олл инклюзив",
                "аквапарк", "aquapark",
                "спа", "spa", "массаж",
                "бассейн", "pool", "пляж",
                "ресторан", "кафе", "бар"
        };

        for (String trigger : amenityTriggers) {
            if (lower.contains(trigger)) {
                log.debug("[SUPERVISOR] Amenity trigger found: '{}'", trigger);
                return true;
            }
        }

        // ============ ТРИГГЕРЫ ПО ТИПАМ ОТЕЛЕЙ ==============
        String[] hotelTypes = {
                "курорт", "пансионат", "санаторий",
                "5 звёзд", "4 звёзд", "3 звёзд",
                "люкс", "premium", "эконом"
        };

        for (String trigger : hotelTypes) {
            if (lower.contains(trigger)) {
                log.debug("[SUPERVISOR] Hotel type trigger found: '{}'", trigger);
                return true;
            }
        }

        // ============ ТРИГГЕРЫ ПО ЛОКАЦИЯМ (ГОРОДА) ==============
        String[] cities = {
                "сочи", "анапа", "ялта", "крым",
                "турция", "анталья", "кемер", "мармарис",
                "египет", "хургада", "шарм-эль-шейх", "асуан",
                "таиланд", "пхукет", "патайя", "бангкок",
                "оаэ", "дубай", "абу-даби",
                "мальдив", "мале",
                "греция", "крит", "афины",
                "испания", "барселона", "мадрид"
        };

        for (String city : cities) {
            if (lower.contains(city)) {
                log.debug("[SUPERVISOR] City trigger found: '{}'", city);
                return true;
            }
        }

        // ============ ТРИГГЕРЫ ПО ФИЛЬТРАМ ==============
        String[] filterTriggers = {
                "до ", // "до 5000" (цена)
                "от ", // "от 3000" (цена)
                "рублей", "₽", "руб",
                "звёзд", "звезд", "звезды",
                "с детьми", "для семьи", "с ребенком",
                "с пляжем", "с бассейном",
                "недорог", "дешев", "бюджет"
        };

        for (String trigger : filterTriggers) {
            if (lower.contains(trigger)) {
                log.debug("[SUPERVISOR] Filter trigger found: '{}'", trigger);
                return true;
            }
        }

        return false;
    }

    /**
     * 🔍 Определяем, это информационный запрос?
     * ✅ РАСШИРЕННЫЙ СПИСОК ТРИГГЕРОВ (60+) для Research Agent
     */
    private boolean isResearchQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            log.warn("[SUPERVISOR] Query is null or empty");
            return false;
        }

        String lower = query.toLowerCase().trim();
        log.debug("[SUPERVISOR] Checking if research query: '{}'", lower);

        // === ВСЕ ТРИГГЕРЫ ДЛЯ RESEARCH (60+) ===
        String[] researchTriggers = {
                // === ПОГОДА ===
                "погода", "weather", "температура", "temp", "климат", "climate",
                "тепло", "холодно", "дождь", "снег", "облака", "солнечно",
                "ветер", "влажность", "прогноз", "forecast",

                // === АВИАБИЛЕТЫ ===
                "авиабилет", "рейс", "перелет", "flight", "цена на рейс",
                "сколько стоит билет", "цены на авиа", "билет",

                // === ВАЛЮТА ===
                "курс", "валюта", "доллар", "евро", "рубль", "фунт", "грн",
                "exchange rate", "currency", "usd", "eur", "gbp", "jpy",

                // === ТРАНСПОРТ ===
                "как добраться", "транспорт", "машина", "такси", "метро",
                "автобус", "поезд", "маршрут", "route", "transportation",

                // === ВИЗА И ДОКУМЕНТЫ ===
                "виза", "страховка", "документы", "паспорт", "visa",
                "insurance", "requirements",

                // === ЛУЧШЕЕ ВРЕМЯ ===
                "когда лучше", "сезон", "когда ехать", "best time",
                "когда дешевле", "high season", "low season",

                // === ДОСТОПРИМЕЧАТЕЛЬНОСТИ ===
                "что посмотреть", "достопримечательность", "музей",
                "культура", "история", "monument", "museum", "attractions",

                // === ИНФОРМАЦИЯ ===
                "информация о", "расскажи о", "узнать о", "tell me about",
                "информация", "как там", "что там",

                // === РЕЖИМ РАБОТЫ ===
                "когда открыто", "режим работы", "часы работы", "opening",
                "hours", "расписание",

                // === ЕДА ===
                "местная кухня", "еда", "блюдо", "ресторан рекомендуй",
                "пища", "dish", "cuisine", "food", "restaurant",

                // === УПАКОВКА ===
                "как одеться", "одежда", "чемодан", "что брать",
                "what to pack", "clothing", "luggage",

                // === ОБЩИЕ ПОИСКИ ===
                "поиск", "найди", "ищу", "ищем", "цена", "стоимость", "сколько стоит"
        };

        for (String trigger : researchTriggers) {
            if (lower.contains(trigger)) {
                log.info("[SUPERVISOR] ✅ Research trigger MATCHED: '{}' in query: '{}'",
                        trigger, lower);
                return true;
            }
        }

        log.debug("[SUPERVISOR] ❌ No research trigger matched for: '{}'", lower);
        return false;
    }

    /**
     * ❌ Отправляет сообщение об ошибке пользователю (без sessionId)
     */
    public void sendErrorMessage(String errorMessage) {
        sendErrorMessage(null, errorMessage);
    }

    /**
     * ❌ Отправляет сообщение об ошибке пользователю (с sessionId)
     */
    private void sendErrorMessage(String sessionId, String errorMessage) {
        ChatMessage error = new ChatMessage();
        error.setContent(errorMessage);
        error.setSender("assistant");
        error.setType("error");
        error.setTimestamp(System.currentTimeMillis());

        if (sessionId != null && !sessionId.isEmpty()) {
            messagingTemplate.convertAndSendToUser(sessionId, "/topic/messages", error);
        } else {
            messagingTemplate.convertAndSend("/topic/messages", error);
        }

        log.error("[SUPERVISOR] Error message sent: {}", errorMessage);
    }
}