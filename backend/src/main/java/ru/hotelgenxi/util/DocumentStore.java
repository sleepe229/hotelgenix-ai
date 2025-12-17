// DocumentStore.java
package ru.hotelgenxi.util;

import java.util.*;

public class DocumentStore {
    private static final Map<String, String> documents = new LinkedHashMap<>();
    private static final Map<String, byte[]> fileBytes = new LinkedHashMap<>(); // ← ДОБАВИЛИ
    private static final Map<String, String> fileNames = new LinkedHashMap<>();  // ← ДОБАВИЛИ

    public static void saveDocument(String fileName, String content, byte[] bytes) { // ← ИЗМЕНИЛИ
        documents.put(fileName, content);
        fileBytes.put(fileName, bytes);
        fileNames.put(fileName, fileName);
    }

    public static int getDocumentCount() {
        return documents.size();
    }

    public static String getFirstDocument() {
        return documents.values().stream().findFirst().orElse(null);
    }

    public static String getSecondDocument() {
        return documents.values().stream().skip(1).findFirst().orElse(null);
    }

    public static byte[] getFirstFileBytes() { // ← НОВЫЙ МЕТОД
        return fileBytes.values().stream().findFirst().orElse(null);
    }

    public static byte[] getSecondFileBytes() { // ← НОВЫЙ МЕТОД
        return fileBytes.values().stream().skip(1).findFirst().orElse(null);
    }

    public static String getFirstFileName() { // ← НОВЫЙ МЕТОД
        return fileNames.values().stream().findFirst().orElse(null);
    }

    public static String getSecondFileName() { // ← НОВЫЙ МЕТОД
        return fileNames.values().stream().skip(1).findFirst().orElse(null);
    }

    public static void clear() {
        documents.clear();
        fileBytes.clear();
        fileNames.clear();
    }
}
