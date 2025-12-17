package ru.hotelgenxi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.PointStruct;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

@Component
public class EmbedHotelsJob implements CommandLineRunner {

    private final QdrantClient qdrantClient;
    private final LocalEmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final boolean ENABLED = true;

    public EmbedHotelsJob(LocalEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.qdrantClient = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
        );
    }

    @Override
    public void run(String... args) throws Exception {
        if (!ENABLED) {
            System.out.println("⏭ EmbedHotelsJob отключен");
            return;
        }

        System.out.println("=".repeat(70));
        System.out.println("🚀 Запуск EmbedHotelsJob");
        System.out.println("=".repeat(70));

        embedAndUploadHotels();
    }

    private void embedAndUploadHotels() throws Exception {
        String collectionName = "hotels";
        ensureCollectionExists(collectionName);

        long existingCount = qdrantClient.countAsync(collectionName).get();
        if (existingCount > 0) {
            System.out.println("⚠ Коллекция уже содержит " + existingCount + " записей.");
            return;
        }

        List<Map<String, Object>> hotels = loadRawHotels();
        System.out.println("✓ Загружено " + hotels.size() + " отелей");

        List<PointStruct> points = generateEmbeddingsAndPoints(hotels);
        uploadToQdrant(collectionName, points);

        System.out.println("=".repeat(70));
        System.out.println("✅ ГОТОВО! " + points.size() + " отелей загружены!");
        System.out.println("=".repeat(70));
    }

    private void ensureCollectionExists(String collectionName) throws Exception {
        boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
        if (!exists) {
            int vectorSize = embeddingService.getDimension();
            VectorParams vectorParams = VectorParams.newBuilder()
                    .setSize(vectorSize)
                    .setDistance(Distance.Cosine)
                    .build();
            qdrantClient.createCollectionAsync(collectionName, vectorParams).get();
            System.out.println("✓ Коллекция создана (размер: " + vectorSize + ")");
        }
    }

    private List<Map<String, Object>> loadRawHotels() throws Exception {
        try (var inputStream = new ClassPathResource("hotels_raw.json").getInputStream()) {
            List<Map<String, Object>> hotels = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            if (!hotels.isEmpty()) {
                Map<String, Object> first = hotels.get(0);
                System.out.println("🔍 Первый отель из JSON:");
                System.out.println("  country = " + first.get("country"));
                System.out.println("  city = " + first.get("city"));
                System.out.println("  name = " + first.get("name"));
            }

            return hotels;
        }
    }

    private List<PointStruct> generateEmbeddingsAndPoints(List<Map<String, Object>> hotels) {
        AtomicInteger counter = new AtomicInteger(0);
        int total = hotels.size();
        List<PointStruct> points = new ArrayList<>();

        for (Map<String, Object> hotel : hotels) {
            try {
                String text = buildHotelText(hotel);
                List<Double> embedding = embeddingService.getEmbedding(text);

                if (embedding.isEmpty()) {
                    continue;
                }

                float[] vectorArray = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vectorArray[i] = embedding.get(i).floatValue();
                }

                Map<String, JsonWithInt.Value> payload = buildPayload(hotel);

                if (payload.isEmpty()) {
                    System.err.println("⚠ Payload пуст для отеля: " + hotel.get("name"));
                } else {
                    System.out.println("✓ Payload: " + payload.size() + " fields для: " + hotel.get("name"));
                }

                UUID uuid = UUID.randomUUID();
                long numericId = uuid.getMostSignificantBits() & Long.MAX_VALUE;

                PointStruct.Builder pointBuilder = PointStruct.newBuilder()
                        .setId(id(numericId))
                        .setVectors(vectors(vectorArray));

                for (Map.Entry<String, JsonWithInt.Value> entry : payload.entrySet()) {
                    pointBuilder.putPayload(entry.getKey(), entry.getValue());
                }

                PointStruct point = pointBuilder.build();
                points.add(point);

                int current = counter.incrementAndGet();
                if (current % 50 == 0) {
                    System.out.println("  📊 " + current + "/" + total);
                }

                Thread.sleep(5);

            } catch (Exception e) {
                System.err.println("❌ Ошибка: " + hotel.get("name") + " - " + e.getMessage());
                e.printStackTrace();
            }
        }

        return points;
    }

    /**
     * 🔧 FIX: Лучший текст для эмбеддинга
     * Включаем описание, удобства, отзывы (если есть)
     */
    private String buildHotelText(Map<String, Object> hotel) {
        StringBuilder text = new StringBuilder();

        // Основная информация
        text.append(hotel.get("name")).append(". ");
        text.append(hotel.get("description")).append(" ");

        // Страна и город
        if (hotel.get("country") != null) {
            text.append("в ").append(hotel.get("country")).append(" ");
        }
        if (hotel.get("city") != null) {
            text.append("в городе ").append(hotel.get("city")).append(" ");
        }

        // Звёзды
        if (hotel.get("stars") != null) {
            text.append(hotel.get("stars")).append(" звёзд ");
        }

        // Удобства (текстовые названия)
        if (Boolean.TRUE.equals(hotel.get("all_inclusive"))) {
            text.append("all inclusive питание ");
        }
        if (Boolean.TRUE.equals(hotel.get("kids_club"))) {
            text.append("детский клуб развлечение ");
        }
        if (Boolean.TRUE.equals(hotel.get("aquapark"))) {
            text.append("аквапарк водные горки ");
        }

        // Цена
        if (hotel.get("price_per_night") != null) {
            text.append("цена ").append(hotel.get("price_per_night")).append(" ");
        }

        // Рейтинг
        if (hotel.get("rating") != null) {
            text.append("рейтинг ").append(hotel.get("rating")).append(" ");
        }

        // Отзывы (если есть)
        if (hotel.get("reviews") != null) {
            text.append("отзывы ").append(hotel.get("reviews")).append(" ");
        }

        return text.toString().trim();
    }

    /**
     * 🔧 FIX: Правильная сериализация всех типов в Qdrant
     */
    private Map<String, JsonWithInt.Value> buildPayload(Map<String, Object> hotel) {
        Map<String, JsonWithInt.Value> payload = new HashMap<>();

        hotel.forEach((key, val) -> {
            if (val != null) {
                try {
                    JsonWithInt.Value qdrantValue = convertToQdrantValue(val);
                    payload.put(key, qdrantValue);
                } catch (Exception e) {
                    System.err.println("⚠ Ошибка конвертации " + key + ": " + e.getMessage());
                }
            }
        });

        return payload;
    }

    /**
     * 🔧 FIX: Правильное преобразование типов для Qdrant
     * ВАЖНО: Boolean → String ("true"/"false"), чтобы потом парсить корректно
     */
    private JsonWithInt.Value convertToQdrantValue(Object val) {
        if (val == null) {
            return value((String) null);
        } else if (val instanceof String) {
            // UTF-8 encoding
            String strVal = (String) val;
            strVal = new String(strVal.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8);
            return value(strVal);
        } else if (val instanceof Integer) {
            return value((Integer) val);
        } else if (val instanceof Long) {
            return value(((Long) val).intValue());
        } else if (val instanceof Double) {
            return value((Double) val);
        } else if (val instanceof Float) {
            return value(((Float) val).doubleValue());
        } else if (val instanceof Boolean) {
            // 🔧 КРИТИЧНО: Сохраняем как строку "true"/"false"
            return value(((Boolean) val).toString());
        } else if (val instanceof List) {
            // Для списков (отзывы, фото) — конвертируем в JSON строку
            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(val);
                return value(json);
            } catch (Exception e) {
                return value(val.toString());
            }
        } else {
            return value(val.toString());
        }
    }

    private void uploadToQdrant(String collectionName, List<PointStruct> points) throws Exception {
        int batchSize = 50;

        for (int i = 0; i < points.size(); i += batchSize) {
            int end = Math.min(i + batchSize, points.size());
            List<PointStruct> batch = points.subList(i, end);

            qdrantClient.upsertAsync(collectionName, batch).get();
            System.out.println("✓ Загружено " + end + "/" + points.size());

            Thread.sleep(500);
        }
    }
}
