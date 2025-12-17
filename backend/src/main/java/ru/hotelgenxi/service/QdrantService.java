package ru.hotelgenxi.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.stereotype.Service;
import ru.hotelgenxi.dto.HotelFilters;
import ru.hotelgenxi.dto.HotelSearchResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class QdrantService {

    private final QdrantClient qdrantClient;
    private final LocalEmbeddingService embeddingService;

    public QdrantService(QdrantClient qdrantClient,
                         LocalEmbeddingService embeddingService) {
        this.qdrantClient = qdrantClient;
        this.embeddingService = embeddingService;
    }

    /**
     * 🔧 FIX: Семантический поиск отелей с ПРАВИЛЬНОЙ фильтрацией
     */
    public List<HotelSearchResult> searchHotels(
            String query,
            HotelFilters filters,
            int topK
    ) throws Exception {

        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query не может быть пустым");
        }

        List<Double> queryEmbedding = embeddingService.getEmbedding(query);
        List<Float> floatVector = queryEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

        System.out.println("🔍 Ищем: \"" + query + "\" (топ " + topK + ")");
        System.out.println("📊 Фильтры: " + filters);

        // 🔧 FIX: ИСПОЛЬЗУЕМ ПОЛНЫЙ ФИЛЬТР, А НЕ buildNumericOnlyFilter()
        Common.Filter qdrantFilter = buildQdrantFilter(filters);

        List<Points.ScoredPoint> results = qdrantClient.searchAsync(
                Points.SearchPoints.newBuilder()
                        .setCollectionName("hotels")
                        .addAllVector(floatVector)
                        .setFilter(qdrantFilter)
                        .setLimit(topK * 5)  // Берём с запасом
                        .setWithPayload(Points.WithPayloadSelector.newBuilder()
                                .setEnable(true)
                                .build())
                        .build()
        ).get();

        System.out.println("✓ Найдено в Qdrant (с фильтрами): " + results.size());

        // Преобразуем результаты в удобный формат
        List<HotelSearchResult> hotelResults = results.stream()
                .map(this::parseHotelResult)
                .limit(topK)
                .collect(Collectors.toList());

        System.out.println("✓ Итоговый результат: " + hotelResults.size() + " отелей");

        return hotelResults;
    }

    /**
     * 🔧 FIX: ПРАВИЛЬНЫЙ ПОЛНЫЙ ФИЛЬТР для Qdrant
     * Включает фильтрацию по: цена, звёзды, страна, город, удобства
     */
    private Common.Filter buildQdrantFilter(HotelFilters filters) {
        Common.Filter.Builder filterBuilder = Common.Filter.newBuilder();

        if (filters == null) {
            System.out.println("⚠ Фильтры пусты, возвращаем все отели");
            return filterBuilder.build();
        }

        // ✅ ФИЛЬТР ПО ЦЕНЕ
        if (filters.getMinPrice() != null || filters.getMaxPrice() != null) {
            Common.Range.Builder rangeBuilder = Common.Range.newBuilder();
            if (filters.getMinPrice() != null) {
                rangeBuilder.setGte(filters.getMinPrice());
            }
            if (filters.getMaxPrice() != null) {
                rangeBuilder.setLte(filters.getMaxPrice());
            }
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("price_per_night")
                            .setRange(rangeBuilder.build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр цены: " + filters.getMinPrice() + " - " + filters.getMaxPrice());
        }

        // ✅ ФИЛЬТР ПО ЗВЁЗДАМ
        if (filters.getMinStars() != null || filters.getMaxStars() != null) {
            Common.Range.Builder rangeBuilder = Common.Range.newBuilder();
            if (filters.getMinStars() != null) {
                rangeBuilder.setGte(filters.getMinStars());
            }
            if (filters.getMaxStars() != null) {
                rangeBuilder.setLte(filters.getMaxStars());
            }
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("stars")
                            .setRange(rangeBuilder.build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр звёзд: " + filters.getMinStars() + " - " + filters.getMaxStars());
        }

        // ✅ ФИЛЬТР ПО СТРАНЕ
        if (filters.getCountry() != null && !filters.getCountry().isEmpty()) {
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("country")
                            .setMatch(Common.Match.newBuilder()
                                    .setText(filters.getCountry())
                                    .build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр страны: " + filters.getCountry());
        }

        // ✅ ФИЛЬТР ПО ГОРОДУ
        if (filters.getCity() != null && !filters.getCity().isEmpty()) {
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("city")
                            .setMatch(Common.Match.newBuilder()
                                    .setText(filters.getCity())
                                    .build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр города: " + filters.getCity());
        }

        // ✅ ФИЛЬТРЫ ПО УДОБСТВАМ (как строки "true"/"false")
        if (Boolean.TRUE.equals(filters.getKidsClub())) {
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("kids_club")
                            .setMatch(Common.Match.newBuilder()
                                    .setText("true")  // ← Сохранили как строку
                                    .build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр: детский клуб");
        }

        if (Boolean.TRUE.equals(filters.getAllInclusive())) {
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("all_inclusive")
                            .setMatch(Common.Match.newBuilder()
                                    .setText("true")
                                    .build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр: all-inclusive");
        }

        if (Boolean.TRUE.equals(filters.getAquapark())) {
            filterBuilder.addMust(Common.Condition.newBuilder()
                    .setField(Common.FieldCondition.newBuilder()
                            .setKey("aquapark")
                            .setMatch(Common.Match.newBuilder()
                                    .setText("true")
                                    .build())
                            .build())
                    .build());
            System.out.println("  ✓ Фильтр: аквапарк");
        }

        return filterBuilder.build();
    }

    /**
     * 🔧 FIX: Правильный парсинг результатов из Qdrant
     */
    private HotelSearchResult parseHotelResult(Points.ScoredPoint point) {
        var payload = point.getPayloadMap();

        return HotelSearchResult.builder()
                .id(getString(payload, "id"))
                .name(getString(payload, "name"))
                .country(getString(payload, "country"))
                .city(getString(payload, "city"))
                .stars(getInt(payload, "stars"))
                .pricePerNight(getDouble(payload, "price_per_night"))
                .rating(getDouble(payload, "rating"))
                .description(getString(payload, "description"))
                .similarity((double) point.getScore())
                .kidsClub(getBoolean(payload, "kids_club"))
                .allInclusive(getBoolean(payload, "all_inclusive"))
                .aquapark(getBoolean(payload, "aquapark"))
                .build();
    }

    /**
     * 🔧 FIX: Правильный парсинг boolean из Qdrant
     * Так как мы сохраняли как строку "true"/"false", парсим как строку
     */
    private Boolean getBoolean(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        JsonWithInt.Value value = payload.get(key);
        if (value == null) {
            return null;
        }

        // Парсим как строку "true"/"false"
        if (value.hasStringValue()) {
            String stringValue = value.getStringValue();
            return "true".equalsIgnoreCase(stringValue);
        }

        // На случай если вдруг пришло как булево значение
        if (value.hasBoolValue()) {
            return value.getBoolValue();
        }

        return null;
    }

    private String getString(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        JsonWithInt.Value value = payload.get(key);
        if (value == null || !value.hasStringValue()) {
            return null;
        }
        return value.getStringValue();
    }

    private Integer getInt(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        JsonWithInt.Value value = payload.get(key);
        if (value == null) {
            return null;
        }

        if (value.hasIntegerValue()) {
            return (int) value.getIntegerValue();
        } else if (value.hasDoubleValue()) {
            return (int) value.getDoubleValue();
        }

        return null;
    }

    private Double getDouble(Map<String, JsonWithInt.Value> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        JsonWithInt.Value value = payload.get(key);
        if (value == null) {
            return null;
        }

        if (value.hasDoubleValue()) {
            return value.getDoubleValue();
        } else if (value.hasIntegerValue()) {
            return (double) value.getIntegerValue();
        }

        return null;
    }
}
