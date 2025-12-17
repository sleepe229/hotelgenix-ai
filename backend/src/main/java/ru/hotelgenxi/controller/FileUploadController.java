package ru.hotelgenxi.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.hotelgenxi.dto.ChatMessage;
import ru.hotelgenxi.service.DocumentParserService;
import ru.hotelgenxi.service.SpeechToTextService;
import ru.hotelgenxi.service.SupervisorAgent;
import ru.hotelgenxi.service.VisionAgent;
import ru.hotelgenxi.util.DocumentStore;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 🎮 REST Controller для загрузки файлов (с CORS)
 */
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")  // 🔥 Разрешить все origins (для локального тестирования)
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final VisionAgent visionAgent;
    private final DocumentParserService documentParserService;
    private final SpeechToTextService speechToTextService;


    public FileUploadController(SimpMessagingTemplate messagingTemplate,
                                VisionAgent visionAgent, DocumentParserService documentParserService, SpeechToTextService speechToTextService) {
        this.messagingTemplate = messagingTemplate;
        this.visionAgent = visionAgent;
        this.documentParserService = documentParserService;
        this.speechToTextService = speechToTextService;
    }

    /**
     * 📷 Загрузка изображения
     *
     * POST http://localhost:8080/api/upload/image
     */
    @PostMapping("/image")
    public ResponseEntity<String> uploadImage(@RequestParam("file") MultipartFile file) {
        log.info("[VISION] Image upload started: {}", file.getOriginalFilename());

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("❌ Файл пуст");
            }

            byte[] fileBytes = file.getBytes();
            String base64Image = Base64.getEncoder().encodeToString(fileBytes);

            log.info("[VISION] Image size: {} bytes", fileBytes.length);

            // Запускаем Vision Agent в отдельном потоке (не блокируем ответ)
            new Thread(() -> {
                visionAgent.analyzeImage(base64Image, file.getOriginalFilename());
            }).start();

            return ResponseEntity.ok("✅ Изображение загружено, анализируем...");

        } catch (IOException e) {
            log.error("[VISION] Error processing image", e);
            return ResponseEntity.status(500).body("❌ Ошибка: " + e.getMessage());
        }
    }

    /**
     * 🎤 Загрузка аудио
     *
     * POST http://localhost:8080/api/upload/audio
     */
    @PostMapping("/audio")
    public ResponseEntity<String> uploadAudio(@RequestParam("audio") MultipartFile audio) {
        log.info("[AUDIO] Audio upload started: {}", audio.getOriginalFilename());

        try {
            if (audio.isEmpty()) {
                return ResponseEntity.badRequest().body("❌ Аудио пусто");
            }

            byte[] audioBytes = audio.getBytes();
            log.info("[AUDIO] Audio size: {} bytes", audioBytes.length);

            String sessionId = UUID.randomUUID().toString();

            // Запускаем распознавание в отдельном потоке
            new Thread(() -> {
                speechToTextService.transcribeAndProcess(
                        audioBytes,
                        audio.getOriginalFilename(),
                        sessionId
                );
            }).start();

            return ResponseEntity.ok("✅ Аудио загружено, распознаём речь...");

        } catch (IOException e) {
            log.error("[AUDIO] Error processing audio", e);
            return ResponseEntity.status(500).body("❌ Ошибка: " + e.getMessage());
        }
    }


    @PostMapping("/document")
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file
    ) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("❌ Файл пуст");
            }

            // 📄 Парсим документ
            String text = documentParserService.parseDocument(file);

            // 💾 Сохраняем в памяти приложения для будущего сравнения
            DocumentStore.saveDocument(file.getOriginalFilename(), text, file.getBytes());

            // 📊 Проверяем, есть ли второй документ
            int docCount = DocumentStore.getDocumentCount();

            if (docCount == 2) {
                // 🎉 У нас есть оба документа - сравниваем!
                byte[] fileBytes1 = DocumentStore.getFirstFileBytes();   // ← НОВОЕ
                byte[] fileBytes2 = DocumentStore.getSecondFileBytes();  // ← НОВОЕ
                String fileName1 = DocumentStore.getFirstFileName();     // ← НОВОЕ
                String fileName2 = DocumentStore.getSecondFileName();
                String text1 = DocumentStore.getFirstDocument();
                String text2 = DocumentStore.getSecondDocument();

                // 💰 Пытаемся достать цены из текста
                List<Integer> prices1 = documentParserService.extractPrices(text1);
                List<Integer> prices2 = documentParserService.extractPrices(text2);

                log.warn("[CONTROLLER] Text layer - File1 prices: {}, File2 prices: {}",
                        prices1, prices2);

                // 🎯 Если цен нет в текстовом слое → пробуем OCR (только для PDF)
                if (prices1.isEmpty() && fileName1 != null && fileName1.endsWith(".pdf")) {
                    log.warn("[CONTROLLER] File1: No prices in text, trying OCR...");
                    prices1 = documentParserService.extractPricesWithOcrFromBytes(fileBytes1); // ← НОВЫЙ МЕТОД
                    log.warn("[CONTROLLER] OCR result for File1: {}", prices1);
                }

                if (prices2.isEmpty() && fileName2 != null && fileName2.endsWith(".pdf")) {
                    log.warn("[CONTROLLER] File2: No prices in text, trying OCR...");
                    prices2 = documentParserService.extractPricesWithOcrFromBytes(fileBytes2); // ← НОВЫЙ МЕТОД
                    log.warn("[CONTROLLER] OCR result for File2: {}", prices2);
                }

                // 🔄 Получаем результат сравнения
                Map<String, Object> comparison =
                        documentParserService.compareDocuments(text1, text2);

                // 🔧 Обновляем цены с учётом OCR результатов
                Integer p1 = prices1.isEmpty() ? null : prices1.get(0);
                Integer p2 = prices2.isEmpty() ? null : prices2.get(0);

                Map<String, String> hotel1 = (Map<String, String>) comparison.get("hotel1");
                Map<String, String> hotel2 = (Map<String, String>) comparison.get("hotel2");

                hotel1.put("pricePerNight", p1 == null ? "N/A" : String.valueOf(p1));
                hotel2.put("pricePerNight", p2 == null ? "N/A" : String.valueOf(p2));

                comparison.put("price1", p1);
                comparison.put("price2", p2);

                if (p1 != null && p2 != null) {
                    comparison.put("difference", Math.abs(p1 - p2));
                    comparison.put("cheaper", p1 <= p2 ? "hotel1" : "hotel2");
                } else {
                    comparison.put("difference", null);
                    comparison.put("cheaper", null);
                }

                log.warn("[CONTROLLER] Final comparison result: {}", comparison);

                ChatMessage comparisonMsg = new ChatMessage();
                comparisonMsg.setContent("📊 Сравнение отелей");
                comparisonMsg.setSender("assistant");
                comparisonMsg.setType("comparison");
                comparisonMsg.setComparisonData(comparison);
                comparisonMsg.setTimestamp(System.currentTimeMillis());
                messagingTemplate.convertAndSend("/topic/messages", comparisonMsg);

                // 🧹 Очищаем после сравнения
                DocumentStore.clear();

                return ResponseEntity.ok(comparison);
            } else {
                // 📌 Это первый документ - ждём второго
                sendMessage("📄 Документ загружен: " + file.getOriginalFilename() +
                        "\n\nЗагрузи второй документ для сравнения!");

                return ResponseEntity.ok(Map.of(
                        "status", "Document stored",
                        "documentCount", docCount
                ));
            }

        } catch (Exception e) {
            log.error("[PARSER] Error", e);
            sendErrorMessage("❌ Ошибка: " + e.getMessage());
            return ResponseEntity.status(500).body("Ошибка: " + e.getMessage());
        }
    }


    private void sendErrorMessage(String errorText) {
        ChatMessage errorMsg = new ChatMessage();
        errorMsg.setContent(errorText);
        errorMsg.setSender("assistant");
        errorMsg.setType("error");
        errorMsg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", errorMsg);
    }
    /**
     * 📤 Отправляет сообщение в чат
     */
    private void sendMessage(String text) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(text);
        msg.setSender("assistant");
        msg.setType("text");
        msg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /**
     * 🆔 Генерирует ID сессии
     */
    private String generateSessionId() {
        return java.util.UUID.randomUUID().toString();
    }
}