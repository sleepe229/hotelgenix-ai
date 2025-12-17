package ru.hotelgenxi.service;

// Импортируем только конкретную модель и служебные классы

import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel; // Оставим его, он нужен для типа поля
// dev.langchain4j.data.segment.Embedding - больше не нужен, т.к. IntelliJ/IDE найдет его сам,
// если он подтянут транзитивно, и мы не будем полагаться на ручной импорт.

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LocalEmbeddingService {

    private final EmbeddingModel embeddingModel;
    private static final int EMBEDDING_SIZE = 384;

    public LocalEmbeddingService() {
        // Инициализируем конкретный класс ONNX-модели
        this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        System.out.println("✅ Локальная модель эмбеддингов AllMiniLmL6V2 (ONNX) инициализирована.");
    }

    /**
     * Получает эмбеддинг для текста через локальную модель.
     * @param text текст для эмбеддинга
     * @return список чисел двойной точности (List<Double>)
     */
    public List<Double> getEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        // 1. Вызываем embed(), получаем объект, и сразу вызываем методы
        // LangChain4j::Embedding.vectorAsList()
        return embeddingModel.embed(text).content().vectorAsList().stream()
                .mapToDouble(Float::doubleValue) // Преобразуем Float к примитиву double
                .boxed() // "Упаковываем" примитивы double в объекты Double
                .collect(Collectors.toList());
    }

    public int getDimension() {
        return EMBEDDING_SIZE;
    }
}