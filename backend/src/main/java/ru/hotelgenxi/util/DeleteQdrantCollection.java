package ru.hotelgenxi.util;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

/**
 * Утилита для удаления коллекции Qdrant
 * Запускайте вручную когда нужно пересоздать коллекцию
 */
public class DeleteQdrantCollection {

    public static void main(String[] args) throws Exception {
        QdrantClient client = new QdrantClient(
                QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
        );

        String collectionName = "hotels";

        boolean exists = client.collectionExistsAsync(collectionName).get();

        if (exists) {
            System.out.println("🗑 Удаляем коллекцию '" + collectionName + "'...");
            client.deleteCollectionAsync(collectionName).get();
            System.out.println("✅ Коллекция успешно удалена!");
        } else {
            System.out.println("ℹ️  Коллекция '" + collectionName + "' не существует");
        }

        System.exit(0);
    }
}