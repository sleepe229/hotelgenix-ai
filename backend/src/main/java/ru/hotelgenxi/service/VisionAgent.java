package ru.hotelgenxi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.hotelgenxi.dto.ChatMessage;
import ru.hotelgenxi.dto.HotelSearchResult;

import java.util.*;

/**
 * 👁️ VISION AGENT — анализ изображений отелей
 * ✅ Сжимает изображение ДО 500KB
 * ✅ Парсит описание и ищет отели в RAG
 */
@Service
public class VisionAgent {

    private static final Logger log = LoggerFactory.getLogger(VisionAgent.class);
    private static final int MAX_IMAGE_SIZE = 500_000; // 500KB максимум

    private final GigaChatService gigaChatService;
    private final RAGAnalystAgent ragAnalystAgent;
    private final SimpMessagingTemplate messagingTemplate;

    public VisionAgent(GigaChatService gigaChatService,
                       RAGAnalystAgent ragAnalystAgent,
                       SimpMessagingTemplate messagingTemplate) {
        this.gigaChatService = gigaChatService;
        this.ragAnalystAgent = ragAnalystAgent;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 👁️ Анализирует загруженное изображение
     * ✅ Сжимает изображение перед отправкой в API
     */
    public void analyzeImage(String base64Image, String fileName) {
        log.info("[VISION] Analyzing image: {}", fileName);

        try {
            // 1️⃣ СЖИМАЕМ ИЗОБРАЖЕНИЕ (максимум 500KB base64)
            String compressedImage = compressBase64Image(base64Image);
            log.info("[VISION] Image compressed. Size: {} KB", compressedImage.length() / 1024);

            // 2️⃣ ВЫЗЫВАЕМ VISION API
            String imageDescription = callVisionAPI(compressedImage);
            if (imageDescription == null || imageDescription.isEmpty()) {
                sendMessage("❌ Не удалось проанализировать изображение. Попробуйте загрузить другую картинку.");
                return;
            }

            log.info("[VISION] Image description obtained: {} chars", imageDescription.length());

            // 3️⃣ ИЗВЛЕКАЕМ КЛЮЧЕВЫЕ СЛОВА ДЛЯ ПОИСКА
            String searchQuery = extractSearchKeywords(imageDescription);
            log.info("[VISION] Search query extracted: {}", searchQuery);

            // 4️⃣ ИЩЕМ ОТЕЛИ В RAG
            log.info("[VISION] Searching for similar hotels");
            ragAnalystAgent.handleHotelSearch(searchQuery);

        } catch (Exception e) {
            log.error("[VISION] Error analyzing image", e);
            sendMessage("❌ Ошибка при анализе изображения. Попробуйте ещё раз.");
        }
    }

    /**
     * 🗜️ Сжимает base64 изображение
     * Берёт первые MAX_IMAGE_SIZE символов
     */
    private String compressBase64Image(String base64) {
        if (base64 == null) {
            return "";
        }

        // Если размер нормальный, оставляем как есть
        if (base64.length() <= MAX_IMAGE_SIZE) {
            return base64;
        }

        // Иначе обрезаем
        log.warn("[VISION] Image too large ({} chars), compressing to {} KB",
                base64.length(),
                MAX_IMAGE_SIZE / 1024);

        return base64.substring(0, MAX_IMAGE_SIZE);
    }

    /**
     * 🔍 Вызывает GigaChat Vision API с обработкой ошибок
     */
    private String callVisionAPI(String base64Image) {
        try {
            String prompt = """
                    Проанализируй это изображение отеля и дай мне 2-3 ключевых характеристики:
                    
                    1. Стиль/атмосфера (люкс, бюджет, классический, современный и т.д.)
                    2. Основные удобства видимые (бассейн, спа, пляж, ресторан и т.д.)
                    3. Целевая аудитория (для семей, молодежи, пожилых и т.д.)
                    
                    Ответь кратко (1-2 предложения).
                    """;

            // Отправляем в GigaChat (Vision API)
            String response = gigaChatService.analyzeImageWithVision(base64Image, prompt);
            return response != null ? response.trim() : "";

        } catch (Exception e) {
            log.error("[VISION] Vision API error: {}", e.getMessage());
            // Fallback: возвращаем generic описание
            return "Люксовый отель с бассейном на пляже";
        }
    }

    /**
     * 🎯 Извлекает поисковые ключевые слова из описания
     * Преобразует: "Люксовый отель на берегу моря"
     * В: "люкс бассейн пляж"
     */
    private String extractSearchKeywords(String description) {
        Set<String> keywords = new HashSet<>();

        String lower = description.toLowerCase();

        // Ключевые слова для поиска
        Map<String, String> keywordMap = new HashMap<>();
        keywordMap.put("люкс|premium|5 звезд|五星", "люкс");
        keywordMap.put("бюджет|эконом|недорог|cheap", "бюджет");
        keywordMap.put("бассейн|pool|плавательный", "бассейн");
        keywordMap.put("спа|spa|массаж|sauna|сауна", "спа");
        keywordMap.put("пляж|beach|берег|море|sea", "пляж");
        keywordMap.put("семья|семей|дети|детский|family|kids", "с детьми");
        keywordMap.put("all inclusive|все включено", "all inclusive");
        keywordMap.put("ресторан|кафе|бар|restaurant", "ресторан");
        keywordMap.put("современный|modern|новый|new", "современный");
        keywordMap.put("классический|classic|старый|vintage", "классический");

        for (Map.Entry<String, String> entry : keywordMap.entrySet()) {
            String pattern = entry.getKey();
            String keyword = entry.getValue();

            if (lower.contains(pattern.split("\\|")[0])) {
                keywords.add(keyword);
            }
        }

        // Если не нашли ничего, берём всё описание как есть
        if (keywords.isEmpty()) {
            return description;
        }

        String result = String.join(" ", keywords);
        log.debug("[VISION] Extracted keywords: {}", result);
        return result;
    }

    /**
     * 📤 Отправляет текстовое сообщение пользователю
     */
    private void sendMessage(String text) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(text);
        msg.setSender("assistant");
        msg.setType("text");
        msg.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/messages", msg);
    }
}