package ru.hotelgenxi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;

@Service
public class GigaChatAuthService {

    @Value("${gigachat.client-id}")
    private String clientId;

    @Value("${gigachat.client-secret}")
    private String clientSecret;

    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    private final RestTemplate restTemplate;

    public GigaChatAuthService() {
        // ОТКЛЮЧАЕМ SSL-ПРОВЕРКУ + СОЗДАЁМ RestTemplate
        disableSslVerification();
        this.restTemplate = new RestTemplate();
    }

    @PostConstruct
    public void init() {
        refreshToken();
        // Автообновление каждые 25 минут
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(25 * 60 * 1000); } catch (InterruptedException ignored) {}
                refreshToken();
            }
        }).start();
    }

    public synchronized String getAccessToken() {
        if (accessToken == null || Instant.now().isAfter(tokenExpiresAt.minusSeconds(60))) {
            refreshToken();
        }
        return accessToken;
    }

    private synchronized void refreshToken() {
        try {
            String auth = clientId + ":" + clientSecret;
            String base64 = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Basic " + base64);
            headers.set("RqUID", clientId);                    // ← КЛЮЧЕВОЙ заголовок!
            headers.set("Accept", "application/json");

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("scope", "GIGACHAT_API_PERS");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<TokenResponse> response = restTemplate.postForEntity(
                    "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
                    request,
                    TokenResponse.class
            );

            this.accessToken = response.getBody().access_token;
            this.tokenExpiresAt = Instant.now().plusSeconds(response.getBody().expires_at - 60);

            System.out.println("GIGACHAT ТОКЕН ПОЛУЧЕН УСПЕШНО! Живёт до: " + tokenExpiresAt);

        } catch (Exception e) {
            System.err.println("Ошибка получения токена: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void disableSslVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    private static class TokenResponse {
        public String access_token;
        public long expires_at;
    }

    public String getClientId() { return clientId; }
}