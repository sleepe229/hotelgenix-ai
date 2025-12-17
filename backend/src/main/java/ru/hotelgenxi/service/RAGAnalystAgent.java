package ru.hotelgenxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.hotelgenxi.dto.ChatMessage;
import ru.hotelgenxi.dto.HotelFilters;
import ru.hotelgenxi.dto.HotelSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class RAGAnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(RAGAnalystAgent.class);

    private final QdrantService qdrantService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RAGAnalystAgent(QdrantService qdrantService,
                           SimpMessagingTemplate messagingTemplate) {
        this.qdrantService = qdrantService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 🔧 Проверка, нужна ли обработка RAG поиска
     * Supervisor Agent вызывает этот метод, если запрос содержит слова про отели
     */
    public boolean shouldProcessWithRAG(String query) {
        String lower = query.toLowerCase();

        // Ключевые слова для активации RAG
        String[] ragTriggers = {
                "отель", "отели", "гостинец", "гостиница",
                "буклет", "забронировать", "бронь", "комната", "номер",
                "пляж", "море", "аквапарк", "спа", "курорт",
                "поиск", "ищу", "найди", "подберите", "рекомендуй",
                "турция", "египет", "таиланд", "оаэ", "мальдив",
                "сочи", "анапа", "крым", "ялта",
                "сколько стоит", "цена", "стоимость",
                "5 звёзд", "4 звёзд", "3 звёзд",
                "all inclusive", "детский клуб", "аквапарк"
        };

        for (String trigger : ragTriggers) {
            if (lower.contains(trigger)) {
                log.info("[RAG] RAG триггер найден: '{}'", trigger);
                return true;
            }
        }

        return false;
    }

    /**
     * 🔧 Основной метод поиска отелей
     */
    public void handleHotelSearch(String query) {
        try {
            log.info("[RAG] Обработка запроса: {}", query);

            // 1. Парсим ТОЛЬКО явные фильтры
            HotelFilters filters = parseFiltersFromQuery(query);
            log.info("[RAG] Фильтры: {}", filters);

            // 2. Делаем semantic search (весь запрос как вектор)
            List<HotelSearchResult> results = qdrantService.searchHotels(query, filters, 5);
            log.info("[RAG] Найдено {} отелей", results.size());

            // 3. Отправляем результаты
            if (results.isEmpty()) {
                String notFoundMessage = "😢 К сожалению, я не нашёл отелей, соответствующих вашим критериям.\n\n" +
                        "Попробуйте изменить:\n" +
                        "• Диапазон цен\n" +
                        "• Количество звёзд\n" +
                        "• Страну или город\n\n" +
                        "Я всегда готов помочь! 🏨";
                sendMessage(notFoundMessage);
            } else {
                String header = "🎉 Я нашёл для вас " + results.size() + " отелей:\n\n";
                sendMessage(header);

                for (int i = 0; i < results.size(); i++) {
                    HotelSearchResult hotel = results.get(i);
                    sendHotelCard(hotel);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {}
                }

                String footer = "\n\n💡 Хотите узнать больше об одном из этих отелей? " +
                        "Спросите меня подробнее! 🌟";
                sendMessage(footer);
            }

        } catch (Exception e) {
            log.error("[RAG] Ошибка поиска", e);
            sendMessage("❌ Произошла техническая ошибка. Попробуйте ещё раз.");
        }
    }

    /**
     * 🔧 ПОЛНЫЙ ПАРСЕР ФИЛЬТРОВ из естественного языка
     */
    private HotelFilters parseFiltersFromQuery(String query) {
        HotelFilters.HotelFiltersBuilder builder = HotelFilters.builder();
        String lower = query.toLowerCase();

        log.debug("[RAG] Парсим явные фильтры из: {}", lower);

        // ============ ЦЕНА (regex, очень надежно) ============
        Pattern priceMaxPattern = Pattern.compile("(?:не дороже|до|максимум|дешевле|не более)\\s+(\\d+)");
        var priceMaxMatcher = priceMaxPattern.matcher(lower);
        if (priceMaxMatcher.find()) {
            int maxPrice = Integer.parseInt(priceMaxMatcher.group(1));
            builder.maxPrice(maxPrice);
            log.debug("[RAG] Макс цена: {}", maxPrice);
        }

        Pattern priceMinPattern = Pattern.compile("(?:от|минимум|свыше)\\s+(\\d+)");
        var priceMinMatcher = priceMinPattern.matcher(lower);
        if (priceMinMatcher.find()) {
            int minPrice = Integer.parseInt(priceMinMatcher.group(1));
            builder.minPrice(minPrice);
            log.debug("[RAG] Мин цена: {}", minPrice);
        }

        // ============ СТРАНА ============
        if (lower.contains("египет")) builder.country("Египет");
        else if (lower.contains("турц")) builder.country("Турция");
        else if (lower.contains("таиланд") || lower.contains("тайланд")) builder.country("Таиланд");
        else if (lower.contains("оаэ") || lower.contains("эмират") || lower.contains("дубай")) builder.country("ОАЭ");
        else if (lower.contains("мальдив")) builder.country("Мальдивы");
        else if (lower.contains("росси")) builder.country("Россия");

        HotelFilters temp = builder.build();
        if (temp.getCountry() != null) {
            log.debug("[RAG] Страна: {}", temp.getCountry());
        }

        // ============ ГОРОД ============
        if (lower.contains("антал")) builder.city("Анталья");
        else if (lower.contains("кемер")) builder.city("Кемер");
        else if (lower.contains("сочи")) builder.city("Сочи");
        else if (lower.contains("анап")) builder.city("Анапа");
        else if (lower.contains("ялт")) builder.city("Ялта");
        else if (lower.contains("дубай")) builder.city("Дубай");
        else if (lower.contains("хургад")) builder.city("Хургада");
        else if (lower.contains("пхукет") || lower.contains("пукет")) builder.city("Пхукет");

        temp = builder.build();
        if (temp.getCity() != null) {
            log.debug("[RAG] Город: {}", temp.getCity());
        }

        // ============ ЗВЁЗДЫ ============
        if (lower.contains("5 зв") || lower.contains("пятизв") || lower.contains("люкс")) {
            builder.minStars(5);
            log.debug("[RAG] Звёзды: 5");
        }

        HotelFilters filters = builder.build();
        log.info("[RAG] Явные фильтры: цена={}, страна={}, город={}",
                filters.getMaxPrice(),
                filters.getCountry(),
                filters.getCity());
        return filters;
    }

    /**
     * 🔧 Отправка текстового сообщения потоком в /topic/messages
     * ✅ Используем convertAndSend (публичный канал)
     */
    private void sendMessage(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        log.debug("[RAG] Отправляю текст ({} chars)", text.length());

        // Разбиваем на слова и отправляем с задержкой (для эффекта стриминга)
        String[] parts = text.split("(?=\\s|\\n)");

        for (String part : parts) {
            if (part.isEmpty()) continue;

            ChatMessage chunk = new ChatMessage();
            chunk.setContent(part);
            chunk.setSender("assistant");
            chunk.setType("text");
            chunk.setTimestamp(System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/messages", chunk);  // ✅ В публичный канал

            try {
                Thread.sleep(20);  // Имитируем стриминг (20ms между токенами)
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 🔧 Поиск отелей с фильтрацией по отрицательным отзывам
     */
    public List<HotelSearchResult> searchHotelsByReviews(
            String query,
            String negativeKeyword,
            HotelFilters filters,
            int topK
    ) {
        log.info("[RAG] searchHotelsByReviews: query='{}', negative='{}'", query, negativeKeyword);

        // 1. Поиск отелей по основному запросу
        List<HotelSearchResult> results = searchHotels(query, filters, topK * 2);

        // 2. Фильтруем по отрицательным ключевым словам в отзывах
        return results.stream()
                .filter(hotel -> !containsNegativeReview(hotel, negativeKeyword))
                .limit(topK)
                .toList();
    }

    /**
     * 🔧 Базовый поиск отелей в Qdrant
     */
    public List<HotelSearchResult> searchHotels(
            String query,
            HotelFilters filters,
            int limit
    ) {
        log.info("[RAG] searchHotels: query='{}', limit={}", query, limit);

        try {
            // Используем QdrantService для поиска
            return qdrantService.searchHotels(query, filters, limit);

        } catch (Exception e) {
            log.error("[RAG] Ошибка при поиске отелей", e);
            return new ArrayList<>();
        }
    }

    /**
     * 🔧 Проверка отрицательных отзывов
     */
    private boolean containsNegativeReview(HotelSearchResult hotel, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return false;
        }

        if (hotel.getReviews() == null || hotel.getReviews().isEmpty()) {
            return false;
        }

        String lower = keyword.toLowerCase();
        return hotel.getReviews().stream()
                .anyMatch(review -> review.getText().toLowerCase().contains(lower));
    }

    /**
     * 🔧 Отправка карточки отеля в /topic/messages
     * ✅ Используем convertAndSend (публичный канал)
     */
    private void sendHotelCard(HotelSearchResult hotel) {
        try {
            log.info("[RAG] Отправляю карточку: {}", hotel.getName());

            ChatMessage hotelMessage = new ChatMessage();
            hotelMessage.setType("hotel_card");
            hotelMessage.setHotelData(hotel);
            hotelMessage.setSender("assistant");
            hotelMessage.setTimestamp(System.currentTimeMillis());

            messagingTemplate.convertAndSend("/topic/messages", hotelMessage);  // ✅ В публичный канал

        } catch (Exception e) {
            log.error("[RAG] Ошибка отправки карточки", e);
        }
    }
}