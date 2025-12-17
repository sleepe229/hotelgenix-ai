package ru.hotelgenxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.hotelgenxi.dto.ChatMessage;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;

@Service
public class SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(SpeechToTextService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OAUTH_ENDPOINT = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    private static final String SPEECH_RECOGNIZE_ENDPOINT = "https://smartspeech.sber.ru/rest/v1/speech:recognize";

    @Value("${salute.speech.client-id}")
    private String clientId;

    @Value("${salute.speech.client-secret}")
    private String clientSecret;

    @Value("${salute.speech.scope:SALUTE_SPEECH_PERS}")
    private String scope;

    private final SimpMessagingTemplate messagingTemplate;
    private final SupervisorAgent supervisorAgent;
    private final OkHttpClient httpClient;

    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAt;
    private final Object tokenLock = new Object();

    public SpeechToTextService(
            SimpMessagingTemplate messagingTemplate,
            SupervisorAgent supervisorAgent
    ) {
        this.messagingTemplate = messagingTemplate;
        this.supervisorAgent = supervisorAgent;
        this.httpClient = createTrustAllOkHttpClient();
    }

    /**
     * 🔓 Создает OkHttpClient который доверяет ВСЕ сертификатам
     * ⚠️ ТОЛЬКО ДЛЯ РАЗРАБОТКИ! В production используйте настоящие сертификаты
     */
    private OkHttpClient createTrustAllOkHttpClient() {
        try {
            log.warn("[STT] ⚠️ Инициализация OkHttpClient с отключенной SSL проверкой (ТОЛЬКО ДЛЯ РАЗРАБОТКИ!)");

            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            final javax.net.ssl.SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);
            builder.connectTimeout(java.time.Duration.ofSeconds(30));
            builder.readTimeout(java.time.Duration.ofSeconds(60));

            log.info("[STT] ✅ OkHttpClient инициализирован");
            return builder.build();

        } catch (Exception e) {
            log.error("[STT] ❌ Ошибка при создании SSL контекста", e);
            return new OkHttpClient.Builder()
                    .connectTimeout(java.time.Duration.ofSeconds(30))
                    .readTimeout(java.time.Duration.ofSeconds(60))
                    .build();
        }
    }

    /**
     * 🎤 Распознавание аудио через Salute Speech REST API
     */
    public void transcribeAndProcess(byte[] audioBytes, String filename, String sessionId) {
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("[STT] Пустой аудиофайл");
            sendErrorMessage("❌ Аудиофайл пуст");
            return;
        }

        try {
            String transcribedText = transcribeAudioSalute(audioBytes);

            if (transcribedText != null && !transcribedText.trim().isEmpty()) {
                log.info("[STT] ✅ Распознано: {}", transcribedText);
                handleTranscribedText(transcribedText, sessionId);
            } else {
                sendErrorMessage("❌ Не удалось распознать речь");
            }

        } catch (Exception e) {
            log.error("[STT] Ошибка при распознавании", e);
            sendErrorMessage("❌ Ошибка: " + e.getMessage());
        }
    }

    /**
     * 🔐 Получение Access Token через OAuth 2.0
     */
    private String getAccessToken() throws Exception {
        synchronized (tokenLock) {
            if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60000) {
                log.debug("[STT] Используем закешированный токен");
                return cachedAccessToken;
            }
        }

        log.info("[STT] Запрашиваем новый access token");

        String credentials = clientId + ":" + clientSecret;
        String base64Credentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String rqUID = UUID.randomUUID().toString();

        FormBody requestBody = new FormBody.Builder()
                .add("scope", scope)
                .build();

        Request request = new Request.Builder()
                .url(OAUTH_ENDPOINT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + base64Credentials)
                .header("RqUID", rqUID)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            log.info("[STT] OAuth ответ код: {}", response.code());

            if (!response.isSuccessful()) {
                log.error("[STT] ❌ Ошибка OAuth: {} - {}", response.code(), responseBody);
                throw new Exception("OAuth ошибка: " + response.code() + " - " + responseBody);
            }

            JsonNode jsonNode = objectMapper.readTree(responseBody);
            String accessToken = jsonNode.path("access_token").asText();
            long expiresIn = jsonNode.path("expires_in").asLong(1800);

            if (accessToken.isEmpty()) {
                throw new Exception("Access token не найден в ответе");
            }

            synchronized (tokenLock) {
                cachedAccessToken = accessToken;
                tokenExpiresAt = System.currentTimeMillis() + (expiresIn * 1000);
            }

            log.info("[STT] ✅ Получен новый access token, действителен {} сек", expiresIn);
            return accessToken;

        } catch (Exception e) {
            log.error("[STT] Ошибка при получении access token", e);
            throw new Exception("Не удалось получить access token", e);
        }
    }

    /**
     * 🎤 REST API запрос к Salute Speech для распознавания
     */
    private String transcribeAudioSalute(byte[] audioBytes) throws Exception {
        String accessToken = getAccessToken();

        log.info("[STT] Отправляем PCM аудио на распознавание ({} bytes)", audioBytes.length);

        String contentType = "audio/x-pcm;bit=16;rate=16000";

        Request request = new Request.Builder()
                .url(SPEECH_RECOGNIZE_ENDPOINT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", contentType)
                .post(RequestBody.create(audioBytes, MediaType.parse(contentType)))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            log.info("[STT] Ответ распознавания код: {}", response.code());

            if (!response.isSuccessful()) {
                log.error("[STT] ❌ Ошибка распознавания: {} - {}", response.code(), responseBody);
                throw new Exception("Ошибка распознавания: " + response.code());
            }

            JsonNode root = objectMapper.readTree(responseBody);

            log.debug("[STT] JSON структура: {}", root.toPrettyString());

            int status = root.path("status").asInt(0);
            if (status != 200) {
                log.error("[STT] ❌ Статус ошибки: {}", status);
                return null;
            }

            // ✅ Основная структура Sber: result - это массив транскриптов
            JsonNode resultNode = root.path("result");

            if (resultNode.isArray() && resultNode.size() > 0) {
                String transcript = resultNode.get(0).asText();

                log.info("[STT] ✅ Распознано: '{}'", transcript);

                if (!transcript.isEmpty() && !transcript.isBlank()) {
                    // ✅ Опционально: логируем уверенность
                    JsonNode emotions = root.path("emotions");
                    if (emotions.isArray() && emotions.size() > 0) {
                        double confidence = emotions.get(0).path("neutral").asDouble(0);
                        log.info("[STT] Уверенность: {}%", String.format("%.1f", (1 - confidence) * 100));
                    }

                    return transcript;
                } else {
                    log.warn("[STT] ⚠️ Пустой транскрипт в ответе");
                    return null;
                }
            } else if (resultNode.isArray()) {
                log.warn("[STT] ⚠️ Массив result пуст");
                return null;
            }

            log.warn("[STT] ⚠️ Неожиданная структура ответа");
            log.warn("[STT] Полный ответ: {}", responseBody);
            return null;

        } catch (Exception e) {
            log.error("[STT] Ошибка парсинга JSON ответа", e);
            throw new Exception("Ошибка при парсинге ответа API", e);
        }
    }

    /**
     * 📝 Обработка распознанного текста
     */
    private void handleTranscribedText(String transcribedText, String sessionId) {
        String normalizedText = normalizeText(transcribedText);
        log.info("[STT] Нормализованный текст: {}", normalizedText);

        sendUserMessage(normalizedText);

        // ✅ Передаём распознанный текст в SupervisorAgent
        new Thread(() -> {
            try {
                supervisorAgent.handleUserQuery(normalizedText, sessionId);
            } catch (Exception e) {
                log.error("[STT] Ошибка в SupervisorAgent", e);
                sendErrorMessage("❌ Ошибка при обработке команды: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 🎯 Анализ голосовой команды
     */
    public Map<String, Object> analyzeVoiceQuery(String text) {
        Map<String, Object> analysis = new HashMap<>();
        String lowerText = text.toLowerCase();

        boolean aboutHotels = lowerText.matches(".*\\b(отель|отели|гостиниц|апартамент|бронь|бронирова)\\b.*");
        String detectedCountry = detectCountry(lowerText);
        Integer budget = parseBudget(lowerText);
        String dates = parseDates(lowerText);
        int adults = parseAdults(lowerText);
        int children = parseChildren(lowerText);

        analysis.put("aboutHotels", aboutHotels);
        analysis.put("country", detectedCountry);
        analysis.put("budget", budget);
        analysis.put("dates", dates);
        analysis.put("adults", adults > 0 ? adults : 1);
        analysis.put("children", children);

        return analysis;
    }

    private String detectCountry(String text) {
        Map<String, String[]> countries = Map.ofEntries(
                Map.entry("турция", new String[]{"турция", "стамбул", "анталия", "бодрум"}),
                Map.entry("египет", new String[]{"египет", "каир", "гиза", "хургада", "шарм"}),
                Map.entry("таиланд", new String[]{"таиланд", "бангкок", "паттайя", "пхукет"}),
                Map.entry("оаэ", new String[]{"оаэ", "дубай", "абу-даби", "шарджа"}),
                Map.entry("мальдивы", new String[]{"мальдивы", "малдивы", "мале"}),
                Map.entry("испания", new String[]{"испания", "барселона", "мадрид", "малага"}),
                Map.entry("греция", new String[]{"греция", "афины", "крит", "греции", "санторини"}),
                Map.entry("россия", new String[]{"россия", "сочи", "крым", "анапа", "калининград"})
        );

        for (Map.Entry<String, String[]> entry : countries.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private Integer parseBudget(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(\\d+)\\s*(?:тыс|тысяч|k|р|руб|₽)?"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number < 500 && (text.contains("тыс") || text.contains("k"))) {
                    return number * 1000;
                }
                return number;
            } catch (NumberFormatException e) {
                log.debug("[STT] Ошибка парсинга бюджета", e);
            }
        }
        return null;
    }

    private String parseDates(String text) {
        String[] months = {
                "январь", "январ", "февраль", "феврал", "март",
                "апрель", "апрел", "май", "июнь", "июль",
                "август", "сентябрь", "сентябр", "октябрь", "октябр",
                "ноябрь", "ноябр", "декабрь", "декабр"
        };

        for (String month : months) {
            if (text.contains(month)) {
                return month;
            }
        }

        if (text.contains("лето")) return "июнь-август";
        if (text.contains("зима")) return "декабрь-январь";
        if (text.contains("весна")) return "март-май";
        if (text.contains("осень")) return "сентябрь-ноябрь";

        return null;
    }

    private int parseAdults(String text) {
        if (text.matches(".*\\b(вдвоем|вдвоих|двое|пара)\\b.*")) return 2;
        if (text.matches(".*\\b(трое|втроем)\\b.*")) return 3;
        if (text.matches(".*\\b(четверо|вчетвером)\\b.*")) return 4;
        if (text.matches(".*\\b(пятеро|впятером)\\b.*")) return 5;
        return 0;
    }

    private int parseChildren(String text) {
        if (text.matches(".*\\b(один ребенок|один ребёнок)\\b.*")) return 1;
        if (text.matches(".*\\b(два ребенка|два ребёнка|двое детей)\\b.*")) return 2;
        if (text.matches(".*\\b(три ребенка|три ребёнка)\\b.*")) return 3;
        if (text.matches(".*\\b(дети|ребенок|ребёнок|малыш)\\b.*")) return 1;
        return 0;
    }

    private String normalizeText(String text) {
        return text
                .trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[.]{2,}", ".")
                .toLowerCase();
    }

    private void sendUserMessage(String text) {
        ChatMessage userMsg = new ChatMessage();
        userMsg.setContent(text);
        userMsg.setSender("user");
        userMsg.setType("text");
        userMsg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", userMsg);
    }

    private void sendErrorMessage(String errorText) {
        ChatMessage errorMsg = new ChatMessage();
        errorMsg.setContent(errorText);
        errorMsg.setSender("assistant");
        errorMsg.setType("error");
        errorMsg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", errorMsg);
    }
}