package ru.hotelgenxi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 🔍 TAVILY SEARCH — поиск информации в интернете
 * Используется для актуальных цен, погоды, новостей
 *
 * 📌 ВАЖНО: 1000 запросов в месяц бесплатно!
 * Использовать кеш на 24 часа для экономии квоты
 */
@Service
public class TavilySearchService {

    private static final Logger log = LoggerFactory.getLogger(TavilySearchService.class);

    @Value("${tavily.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private static class CachedResult {
        String result;
        long timestamp;

        CachedResult(String result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            // 24 часа кеша
            return System.currentTimeMillis() - timestamp > 86400000;
        }
    }

    /**
     * 🔍 Поиск в интернете с кешированием
     */
    public String search(String query) {
        if (!isEnabled()) {
            log.warn("[TAVILY] Disabled (no API key)");
            return null;
        }

        // Проверяем кеш
        if (cache.containsKey(query)) {
            CachedResult cached = cache.get(query);
            if (!cached.isExpired()) {
                log.info("[TAVILY] Cache hit for: {}", query);
                return cached.result;
            }
        }

        try {
            String answer = callTavilyAPI(query);
            if (answer != null) {
                cache.put(query, new CachedResult(answer));
            }
            return answer;
        } catch (Exception e) {
            log.error("[TAVILY] Error", e);
            return null;
        }
    }

    private String callTavilyAPI(String query) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String payload = String.format("""
            {
                "api_key": "%s",
                "query": "%s",
                "include_answer": true,
                "max_results": 5,
                "search_depth": "basic"
            }
            """, apiKey, escapeJson(query));

        HttpEntity<String> request = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                "https://api.tavily.com/search",
                HttpMethod.POST,
                request,
                String.class
        );

        JsonNode root = mapper.readTree(response.getBody());
        return root.at("/answer").asText(null);
    }

    private String escapeJson(String str) {
        return str.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
