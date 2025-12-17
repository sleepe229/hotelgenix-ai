package ru.hotelgenxi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.PointStruct;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

//@Service
public class QdrantInitService {

    private final QdrantClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QdrantInitService() {
        this.client = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
        );
    }

    @PostConstruct
    public void init() throws Exception {
        String collectionName = "hotels";

        // Определяем размерность эмбеддингов из первого отеля
        int vectorSize = detectVectorSize();
        System.out.println("📏 Обнаружена размерность эмбеддингов: " + vectorSize);

        // Проверяем существование коллекции
        boolean exists = client.collectionExistsAsync(collectionName).get();

        if (!exists) {
            // Создаём коллекцию с правильной размерностью
            createCollection(collectionName, vectorSize);
        }

        // Проверяем количество точек в коллекции
        long count = client.countAsync(collectionName).get();

        if (count > 0) {
            System.out.println("✓ Коллекция уже содержит " + count + " записей. Пропускаем загрузку.");
            return;
        }

        // Загружаем данные из JSON
        System.out.println("Загружаем отели из hotels.json...");
        List<Map<String, Object>> hotels = loadHotelsFromJson();

        if (hotels.isEmpty()) {
            System.out.println("⚠ Файл hotels.json пуст или не найден");
            return;
        }

        // Преобразуем в PointStruct
        List<PointStruct> points = convertToPoints(hotels);

        // Загружаем в Qdrant пачками с обработкой ошибок размерности
        int batchSize = 100;
        for (int i = 0; i < points.size(); i += batchSize) {
            int end = Math.min(i + batchSize, points.size());
            List<PointStruct> batch = points.subList(i, end);

            try {
                client.upsertAsync(collectionName, batch).get();
                System.out.println("✓ Загружено " + end + "/" + points.size() + " отелей");
            } catch (Exception e) {
                if (e.getMessage().contains("Vector dimension error")) {
                    System.out.println("❌ Ошибка размерности! Пересоздаём коллекцию с размерностью " + vectorSize);
                    client.deleteCollectionAsync(collectionName).get();
                    createCollection(collectionName, vectorSize);

                    // Повторяем загрузку с начала
                    System.out.println("🔄 Повторная загрузка данных...");
                    uploadAllPoints(collectionName, points);
                    return;
                } else {
                    throw e;
                }
            }
        }

        System.out.println("✓ УСПЕШНО! Загружено " + points.size() + " отелей в Qdrant");
    }

    private void createCollection(String collectionName, int vectorSize) throws Exception {
        VectorParams vectorParams = VectorParams.newBuilder()
                .setSize(vectorSize)
                .setDistance(Distance.Cosine)
                .build();

        client.createCollectionAsync(collectionName, vectorParams).get();
        System.out.println("✓ Коллекция '" + collectionName + "' создана с размерностью " + vectorSize);
    }

    private void uploadAllPoints(String collectionName, List<PointStruct> points) throws Exception {
        int batchSize = 100;
        for (int i = 0; i < points.size(); i += batchSize) {
            int end = Math.min(i + batchSize, points.size());
            List<PointStruct> batch = points.subList(i, end);
            client.upsertAsync(collectionName, batch).get();
            System.out.println("✓ Загружено " + end + "/" + points.size() + " отелей");
        }
    }

    /**
     * Определяет размерность векторов из первого отеля в JSON
     */
    private int detectVectorSize() {
        try (var inputStream = new ClassPathResource("hotels.json").getInputStream()) {
            List<Map<String, Object>> hotels = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<Map<String, Object>>>() {}
            );

            if (hotels.isEmpty()) {
                System.out.println("⚠ hotels.json пуст, используем размерность по умолчанию: 1024");
                return 1024;
            }

            Object embeddingObj = hotels.get(0).get("embedding");
            if (embeddingObj instanceof List) {
                int size = ((List<?>) embeddingObj).size();
                System.out.println("✓ Обнаружена размерность из данных: " + size);
                return size;
            }

            System.out.println("⚠ Эмбеддинг не найден, используем размерность по умолчанию: 1024");
            return 1024;
        } catch (Exception e) {
            System.err.println("⚠ Не удалось определить размерность, используем 1024: " + e.getMessage());
            return 1024;
        }
    }

    private List<Map<String, Object>> loadHotelsFromJson() {
        try (var inputStream = new ClassPathResource("hotels.json").getInputStream()) {
            return objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<Map<String, Object>>>() {}
            );
        } catch (Exception e) {
            System.err.println("Ошибка загрузки hotels.json: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<PointStruct> convertToPoints(List<Map<String, Object>> hotels) {
        return hotels.stream()
                .map(this::convertToPoint)
                .collect(Collectors.toList());
    }

    private PointStruct convertToPoint(Map<String, Object> hotel) {
        // Генерируем уникальный ID как long из UUID
        UUID uuid = UUID.randomUUID();
        long numericId = uuid.getMostSignificantBits() & Long.MAX_VALUE;

        // Извлекаем embedding и конвертируем в float[]
        float[] vectorArray = extractEmbedding(hotel);

        // Создаём payload с правильным типом JsonWithInt.Value
        Map<String, JsonWithInt.Value> payload = new HashMap<>();

        hotel.forEach((key, val) -> {
            if (!"embedding".equals(key) && val != null) {
                payload.put(key, convertToQdrantValue(val));
            }
        });

        // Создаём точку
        return PointStruct.newBuilder()
                .setId(id(numericId))
                .setVectors(vectors(vectorArray))
                .putAllPayload(payload)
                .build();
    }

    @SuppressWarnings("unchecked")
    private float[] extractEmbedding(Map<String, Object> hotel) {
        Object embeddingObj = hotel.get("embedding");

        if (embeddingObj instanceof List) {
            List<?> embeddingList = (List<?>) embeddingObj;
            float[] result = new float[embeddingList.size()];

            for (int i = 0; i < embeddingList.size(); i++) {
                Object item = embeddingList.get(i);
                if (item instanceof Number) {
                    result[i] = ((Number) item).floatValue();
                } else {
                    result[i] = 0.0f;
                }
            }

            return result;
        }

        // Fallback: пустой вектор размером 1024
        return new float[1024];
    }

    private JsonWithInt.Value convertToQdrantValue(Object val) {
        if (val == null) {
            return value((String) null);
        } else if (val instanceof String) {
            return value((String) val);
        } else if (val instanceof Integer) {
            return value((Integer) val);
        } else if (val instanceof Long) {
            return value(((Long) val).intValue());
        } else if (val instanceof Double) {
            return value((Double) val);
        } else if (val instanceof Float) {
            return value(((Float) val).doubleValue());
        } else if (val instanceof Boolean) {
            return value((Boolean) val);
        } else {
            return value(val.toString());
        }
    }
}