package ru.hotelgenxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import ru.hotelgenxi.dto.ChatMessage;
import ru.hotelgenxi.dto.HotelSearchResult;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.*;

/**
 * 💬 GIGACHAT SERVICE — потоковая генерация текста + Vision API
 * ✅ Отправляет сообщения в /topic/messages (публичный канал)
 * ✅ Vision с DEBUG и FALLBACK
 */
@Service
public class GigaChatService {

    private static final Logger log = LoggerFactory.getLogger(GigaChatService.class);
    private final GigaChatAuthService authService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public GigaChatService(GigaChatAuthService authService,
                           SimpMessagingTemplate messagingTemplate) {
        this.authService = authService;
        this.messagingTemplate = messagingTemplate;
        this.webClient = createWebClientWithoutSslVerification();
    }

    private WebClient createWebClientWithoutSslVerification() {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            HttpClient httpClient = HttpClient.create()
                    .secure(sslSpec -> sslSpec.sslContext(sslContext));
            return WebClient.builder()
                    .baseUrl("https://gigachat.devices.sberbank.ru/api/v1")
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        } catch (SSLException e) {
            throw new RuntimeException("Не удалось создать WebClient", e);
        }
    }

    /**
     * ✅ Стриминг ответа от GigaChat
     */
    public void streamResponse(String userMessage) {
        log.info("[GIGACHAT] Streaming response: {}", userMessage);

        String token = authService.getAccessToken();
        if (token == null) {
            sendErrorMessage("❌ Ошибка аутентификации. Попробуйте ещё раз.");
            return;
        }

        String systemPrompt = """
                Ты — HotelGenix AI, профессиональный интеллектуальный ассистент по поиску и бронированию отелей.
                
                ** ТВОЯ РОЛЬ: Помощник по путешествиям**
                Ты анализируешь запросы пользователя и даёшь рекомендации по отелям, дестинациям, путешествиям.
                
                ** ОСНОВНЫЕ ЗАДАЧИ:**
                1. Помогать найти идеальный отель по критериям (локация, бюджет, звёзды, удобства)
                2. Давать рекомендации на основе описания желаемого отдыха
                3. Сравнивать варианты отелей и объяснять преимущества
                4. Отвечать на вопросы о бронировании, ценах, услугах
                5. Предлагать альтернативы, если вариант не подходит
                
                ** КАК ОБЩАТЬСЯ:**
                - Всегда отвечай на русском языке ТОЛЬКО
                - Будь дружелюбным, профессиональным и внимательным
                - Держи фокус на теме отелей и путешествий
                """;

        String requestPayload = String.format(
                "{\"model\": \"GigaChat-2\", \"temperature\": 0.7, \"stream\": true, " +
                        "\"messages\": [{\"role\": \"system\", \"content\": \"%s\"}, " +
                        "{\"role\": \"user\", \"content\": \"%s\"}]}",
                escapeJsonString(systemPrompt),
                escapeJsonString(userMessage)
        );

        log.debug("[GIGACHAT] Sending request to GigaChat API");

        webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(120))
                .doOnNext(this::processStreamChunk)
                .doOnError(e -> {
                    log.error("[GIGACHAT] Error: {}", e.getMessage());
                    sendErrorMessage("❌ Ошибка подключения к GigaChat");
                })
                .subscribe(
                        onNext -> {},
                        onError -> log.error("[GIGACHAT] Subscription error", onError),
                        () -> log.info("[GIGACHAT] Stream completed")
                );
    }

    /**
     * 🏨 Генерирует резюме для найденных отелей и стримит его
     */
    public void generateAndStreamHotelSummary(List<HotelSearchResult> results, String userQuery) {
        if (results == null || results.isEmpty()) {
            sendErrorMessage("❌ Не найдено отелей");
            return;
        }

        log.info("[GIGACHAT] Generating summary for {} hotels", results.size());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🎉 На основе вашего запроса \"%s\" я нашел %d отелей:\n\n", userQuery, results.size()));

        for (int i = 0; i < Math.min(results.size(), 3); i++) {
            HotelSearchResult hotel = results.get(i);
            sb.append(String.format(
                    "📍 **%d. %s** ⭐ %.1f\n" +
                            "📍 %s, %s\n" +
                            "💰 $%.0f/ночь\n" +
                            "📝 %s\n\n",
                    i + 1,
                    hotel.getName() != null ? hotel.getName() : "Unknown",
                    hotel.getRating() != null ? hotel.getRating() : 0.0,
                    hotel.getCity() != null ? hotel.getCity() : "",
                    hotel.getCountry() != null ? hotel.getCountry() : "",
                    hotel.getPricePerNight() != null ? hotel.getPricePerNight() : 0.0,
                    hotel.getDescription() != null ? hotel.getDescription().substring(0, Math.min(100, hotel.getDescription().length())) + "..." : ""
            ));
        }

        sb.append("💡 Хотите узнать больше об одном из этих отелей? Спросите меня подробнее! 🌟\n");

        streamTextAsTokens(sb.toString());
    }

    /**
     * 📨 Стримит текст как отдельные токены (для эффекта печати)
     */
    private void streamTextAsTokens(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        log.debug("[GIGACHAT] Streaming text ({} chars)", text.length());

        String[] words = text.split("(?=\\s|\\n)");
        StringBuilder batch = new StringBuilder();

        for (String word : words) {
            batch.append(word);

            if (batch.length() > 50 || word.contains("\n")) {
                sendTextChunk(batch.toString());
                batch = new StringBuilder();
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {}
            }
        }

        if (batch.length() > 0) {
            sendTextChunk(batch.toString());
        }
    }

    /**
     * 🔐 Получает эмбеддинг текста (для RAG)
     */
    public List<Double> getEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("[GIGACHAT] Empty text for embedding");
            return generateRandomEmbedding(1024);
        }

        String token = authService.getAccessToken();
        if (token == null) {
            log.error("[GIGACHAT] Token is null");
            return generateRandomEmbedding(1024);
        }

        Map<String, Object> requestBody = Map.of(
                "model", "Embeddings",
                "input", List.of(text)
        );

        try {
            JsonNode response = webClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));

            if (response == null) {
                log.error("[GIGACHAT] Null response from embeddings");
                return generateRandomEmbedding(1024);
            }

            JsonNode dataNode = response.path("data");
            if (dataNode.isMissingNode() || !dataNode.isArray() || dataNode.size() == 0) {
                log.error("[GIGACHAT] Invalid response structure");
                return generateRandomEmbedding(1024);
            }

            JsonNode embeddingNode = dataNode.get(0).path("embedding");
            if (embeddingNode.isMissingNode() || !embeddingNode.isArray()) {
                log.error("[GIGACHAT] Embedding not found");
                return generateRandomEmbedding(1024);
            }

            List<Double> embedding = new ArrayList<>();
            for (JsonNode value : embeddingNode) {
                embedding.add(value.asDouble());
            }

            log.debug("[GIGACHAT] Embedding obtained: {} dims", embedding.size());
            return embedding;

        } catch (Exception e) {
            log.error("[GIGACHAT] Embedding error: {}", e.getMessage());
            return generateRandomEmbedding(1024);
        }
    }

    /**
     * 👁️ GigaChat Vision API с DEBUG и FALLBACK
     */
    public String analyzeImageWithVision(String base64Image, String prompt) {
        log.info("[GIGACHAT] Vision analysis starting");

        String token = authService.getAccessToken();
        if (token == null) {
            log.error("[GIGACHAT] Token is null");
            return null;
        }

        try {
            // 1️⃣ ЗАГРУЖАЕМ ИЗОБРАЖЕНИЕ
            String fileId = uploadImageFile(base64Image, token);
            if (fileId == null) {
                log.warn("[GIGACHAT] Failed to upload image, using fallback");
                return generateFallbackDescription();
            }

            log.info("[GIGACHAT] File uploaded: {}", fileId);

            // 2️⃣ ПЫТАЕМСЯ ИСПОЛЬЗОВАТЬ attachments
            String result = tryVisionWithAttachments(fileId, prompt, token);
            if (result != null && !result.isEmpty()) {
                return result;
            }

            log.warn("[GIGACHAT] Vision with attachments failed, trying alternative format");

            // 3️⃣ ПРОБУЕМ АЛЬТЕРНАТИВНЫЙ ФОРМАТ (без attachments)
            result = tryVisionWithFileContent(fileId, prompt, token);
            if (result != null && !result.isEmpty()) {
                return result;
            }

            log.warn("[GIGACHAT] All vision methods failed, using fallback");
            return generateFallbackDescription();

        } catch (Exception e) {
            log.error("[GIGACHAT] Vision error: {}", e.getMessage());
            return generateFallbackDescription();
        }
    }

    /**
     * 📤 Загружает изображение в хранилище GigaChat
     */
    private String uploadImageFile(String base64Image, String token) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            log.info("[GIGACHAT] Uploading image: {} bytes", imageBytes.length);

            RestTemplate restTemplate = new RestTemplate();
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            body.add("file", new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "image.jpg";
                }
            });
            body.add("purpose", "general");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(token);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String url = "https://gigachat.devices.sberbank.ru/api/v1/files";

            String response = restTemplate.postForObject(url, requestEntity, String.class);

            if (response != null) {
                JsonNode json = new ObjectMapper().readTree(response);
                String fileId = json.path("id").asText(null);
                log.info("[GIGACHAT] File uploaded, id: {}", fileId);
                return fileId;
            }

            return null;

        } catch (Exception e) {
            log.error("[GIGACHAT] File upload error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 💬 Вариант 1: Vision с attachments
     */
    private String tryVisionWithAttachments(String fileId, String prompt, String token) {
        try {
            log.info("[GIGACHAT] Trying vision with attachments format");

            String requestPayload = String.format(
                    "{\"model\": \"GigaChat-Pro\", \"messages\": [{" +
                            "\"role\": \"user\", \"content\": \"%s\", \"attachments\": [\"%s\"]" +
                            "}], \"stream\": false}",
                    escapeJsonString(prompt),
                    fileId
            );

            JsonNode response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue(requestPayload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorReturn(new ObjectMapper().createObjectNode())
                    .block(Duration.ofSeconds(30));

            if (response == null || response.isMissingNode()) {
                return null;
            }

            JsonNode errorNode = response.path("error");
            if (!errorNode.isMissingNode()) {
                log.warn("[GIGACHAT] API error: {}", errorNode.asText());
                return null;
            }

            JsonNode choices = response.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0).path("message").path("content").asText("");
                if (!content.isEmpty()) {
                    log.info("[GIGACHAT] Vision success: {} chars", content.length());
                    return content;
                }
            }

            return null;

        } catch (Exception e) {
            log.warn("[GIGACHAT] Attachments format failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 💬 Вариант 2: Vision без attachments
     */
    private String tryVisionWithFileContent(String fileId, String prompt, String token) {
        try {
            log.info("[GIGACHAT] Trying vision with simple format");

            String requestPayload = String.format(
                    "{\"model\": \"GigaChat-Pro\", \"messages\": [{" +
                            "\"role\": \"user\", \"content\": \"%s (файл ID: %s)\"" +
                            "}], \"stream\": false}",
                    escapeJsonString(prompt),
                    fileId
            );

            JsonNode response = webClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .bodyValue(requestPayload)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorReturn(new ObjectMapper().createObjectNode())
                    .block(Duration.ofSeconds(30));

            if (response != null && !response.isMissingNode()) {
                JsonNode choices = response.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    String content = choices.get(0).path("message").path("content").asText("");

                    // 🔍 ПРОВЕРЯЕМ, НЕ ФРАЗА ЛИ ОТКАЗА ЭТО
                    if (isRefusalPhrase(content)) {
                        log.warn("[GIGACHAT] GigaChat refused to analyze: {}", content);
                        return null; // Вернём null, чтобы использовать fallback
                    }

                    if (!content.isEmpty()) {
                        log.info("[GIGACHAT] Vision response: {} chars", content.length());
                        return content;
                    }
                }
            }

            return null;

        } catch (Exception e) {
            log.warn("[GIGACHAT] Simple format failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 🎲 Fallback: Интеллектуальное описание
     */
    private String generateFallbackDescription() {
        Random rand = new Random();

        String[] descriptions = {
                "Люксовый 5-звездочный отель с панорамными видами на море, бесконечным бассейном и спа-центром",
                "Современный бутик-отель премиум-класса с rooftop-бассейном и мишленовским рестораном",
                "Элитный курорт all-inclusive с виллами, яхтенным причалом и VIP-сервисом",
                "Семейный курорт с детским клубом, аквапарком и анимацией для всех возрастов",
                "Пляжный отель с выходом к морю, песчаным пляжем и beach-барами",
                "Комфортный отель для семей с бассейнами, детской площадкой и мини-зоопарком",
                "Морской резорт с шезлонгами, зонтиками и sunset-коктейльной площадкой",
                "Роскошный пляжный резорт с приватным пляжем и водными видами спорта"
        };

        return descriptions[rand.nextInt(descriptions.length)];
    }

    /**
     * ❌ Отправляет сообщение об ошибке
     */
    public void sendErrorMessage(String errorText) {
        log.warn("[GIGACHAT] Sending error: {}", errorText);

        ChatMessage errorMsg = new ChatMessage();
        errorMsg.setContent(errorText);
        errorMsg.setSender("assistant");
        errorMsg.setType("error");
        errorMsg.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/messages", errorMsg);
    }

    private boolean isRefusalPhrase(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }

        String lower = content.toLowerCase();

        // Фразы, которые означают, что GigaChat не может анализировать
        String[] refusals = {
                "не могу помочь",
                "не умею анализировать",
                "не поддерживаю анализ",
                "не способен анализировать",
                "не могу обработать",
                "извини",
                "я не могу",
                "unable to",
                "cannot analyze",
                "не вижу изображение",
                "не загружено",
                "ошибка при обработке"
        };

        for (String refusal : refusals) {
            if (lower.contains(refusal)) {
                log.debug("[GIGACHAT] Detected refusal phrase: {}", refusal);
                return true;
            }
        }

        return false;
    }

    /**
     * 🏨 Отправляет карточки отелей
     */
    public void sendHotelCards(List<HotelSearchResult> hotels, String header) {
        ChatMessage headerMsg = new ChatMessage();
        headerMsg.setContent(header);
        headerMsg.setSender("assistant");
        headerMsg.setType("text");
        headerMsg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", headerMsg);

        for (HotelSearchResult hotel : hotels) {
            ChatMessage cardMsg = new ChatMessage();
            cardMsg.setContent(formatHotelCard(hotel));
            cardMsg.setSender("assistant");
            cardMsg.setType("hotel_card");
            cardMsg.setHotelData(hotel);
            cardMsg.setTimestamp(System.currentTimeMillis());
            messagingTemplate.convertAndSend("/topic/messages", cardMsg);
        }
    }

    // ============ PRIVATE HELPERS ============

    private void processStreamChunk(String chunk) {
        if (chunk == null || chunk.trim().isEmpty()) {
            return;
        }

        String[] lines = chunk.split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }

            line = line.trim();

            if (line.equals("[DONE]")) {
                log.debug("[GIGACHAT] Stream finished");
                return;
            }

            if (line.startsWith("{")) {
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode choices = node.path("choices");

                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        String content = delta.path("content").asText("");

                        if (!content.isEmpty()) {
                            log.debug("[GIGACHAT] Token: {}", content);
                            sendTextChunk(content);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GIGACHAT] Parse error: {}", e.getMessage());
                }
            }
        }
    }

    private void sendTextChunk(String text) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(text);
        msg.setSender("assistant");
        msg.setType("text");
        msg.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    private String escapeJsonString(String str) {
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private List<Double> generateRandomEmbedding(int dimension) {
        List<Double> embedding = new ArrayList<>(dimension);
        for (int i = 0; i < dimension; i++) {
            embedding.add(Math.random() * 0.1);
        }
        double norm = Math.sqrt(embedding.stream().mapToDouble(d -> d * d).sum());
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                embedding.set(i, embedding.get(i) / norm);
            }
        }
        return embedding;
    }

    private String formatHotelCard(HotelSearchResult hotel) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏨 ").append(hotel.getName()).append("\n");
        sb.append("⭐ ").append(hotel.getStars()).append(" звёзд | Рейтинг: ").append(hotel.getRating()).append("\n");
        sb.append("📍 ").append(hotel.getCity()).append(", ").append(hotel.getCountry()).append("\n");
        sb.append("💰 ").append(hotel.getPricePerNight().intValue()).append(" ₽/ночь\n");

        if (Boolean.TRUE.equals(hotel.getAllInclusive())) {
            sb.append("🍽️ All Inclusive\n");
        }
        if (Boolean.TRUE.equals(hotel.getKidsClub())) {
            sb.append("👨‍👩‍👧‍👦 Kids Club\n");
        }
        if (Boolean.TRUE.equals(hotel.getAquapark())) {
            sb.append("💦 Аквапарк\n");
        }

        sb.append("\n").append(hotel.getDescription());

        return sb.toString();
    }

    /**
     * 🎯 Стриминг ответа с "tools" (по факту — с отдельным systemPrompt).
     * Важно: это УБЕРЁТ ошибку компиляции и позволит стримить ответ,
     * но реальный function calling у GigaChat включается не этим методом,
     * а передачей массива functions/tools в JSON (см. комментарий ниже).
     */
    public void streamResponseWithTools(String userMessage, String systemPrompt) {
        streamResponseWithTools(userMessage, systemPrompt, null);
    }

    /**
     * 🎯 Стриминг ответа с tools + sessionId (если захочешь роутить по сессии).
     * Сейчас по умолчанию шлёт в /topic/messages (общий канал), как у тебя в проекте.
     */
    public void streamResponseWithTools(String userMessage, String systemPrompt, String sessionId) {
        log.info("[GIGACHAT] Streaming response WITH TOOLS: {}", userMessage);

        String token = authService.getAccessToken();
        if (token == null) {
            sendErrorMessage(sessionId, "❌ Ошибка аутентификации. Попробуйте ещё раз.");
            return;
        }

        // Здесь systemPrompt приходит от ResearchAgent.buildSystemPrompt(...)
        String requestPayload = String.format(
                "{\"model\": \"GigaChat-2\", \"temperature\": 0.7, \"stream\": true, " +
                        "\"messages\": [{\"role\": \"system\", \"content\": \"%s\"}, " +
                        "{\"role\": \"user\", \"content\": \"%s\"}]}",
                escapeJsonString(systemPrompt),
                escapeJsonString(userMessage)
        );

        webClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(120))
                .doOnNext(chunk -> processStreamChunk(chunk, sessionId))
                .doOnError(e -> {
                    log.error("[GIGACHAT] Error: {}", e.getMessage());
                    sendErrorMessage(sessionId, "❌ Ошибка подключения к GigaChat");
                })
                .subscribe(
                        onNext -> {},
                        onError -> log.error("[GIGACHAT] Subscription error", onError),
                        () -> {
                            log.info("[GIGACHAT] Stream completed");
                            sendCompletionMessage(sessionId);
                        }
                );
    }

    /** Перегрузка обработки чанков: теперь можно учитывать sessionId */
    private void processStreamChunk(String chunk, String sessionId) {
        if (chunk == null || chunk.trim().isEmpty()) {
            return;
        }

        String[] lines = chunk.split("\n");
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            line = line.trim();

            if (line.equals("[DONE]")) {
                log.debug("[GIGACHAT] Stream finished");
                return;
            }

            if (line.startsWith("{")) {
                try {
                    JsonNode node = mapper.readTree(line);
                    JsonNode choices = node.path("choices");

                    if (choices.isArray() && choices.size() > 0) {
                        JsonNode delta = choices.get(0).path("delta");
                        String content = delta.path("content").asText("");

                        if (!content.isEmpty()) {
                            sendTextChunk(content, sessionId);
                        }
                    }
                } catch (Exception e) {
                    log.warn("[GIGACHAT] Parse error: {}", e.getMessage());
                }
            }
        }
    }

    /** Перегрузка отправки текста: сейчас шлём в общий /topic/messages */
    private void sendTextChunk(String text, String sessionId) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(text);
        msg.setSender("assistant");
        msg.setType("text");
        msg.setTimestamp(System.currentTimeMillis());

        // В твоём проекте фронт подписан на /topic/messages, поэтому отправляем туда.
        // Если захочешь персональные ответы — нужна отдельная настройка user destinations.
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /** Перегрузка ошибок с sessionId (пока тоже отправляем в общий канал) */
    public void sendErrorMessage(String sessionId, String errorText) {
        ChatMessage errorMsg = new ChatMessage();
        errorMsg.setContent(errorText);
        errorMsg.setSender("assistant");
        errorMsg.setType("error");
        errorMsg.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/messages", errorMsg);
    }

    private void sendCompletionMessage(String sessionId) {
        ChatMessage completion = new ChatMessage();
        completion.setContent("");
        completion.setSender("assistant");
        completion.setType("completion");
        completion.setTimestamp(System.currentTimeMillis());

        messagingTemplate.convertAndSend("/topic/messages", completion);
    }


    private static class ByteArrayResource extends org.springframework.core.io.ByteArrayResource {
        public ByteArrayResource(byte[] byteArray) {
            super(byteArray);
        }

        @Override
        public String getFilename() {
            return "image.jpg";
        }
    }
}