package ru.hotelgenxi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {
    private String content;
    private String sender; // "user" или "assistant"

    @JsonProperty("timestamp")
    private long timestamp;

    private String type;  // "text", "hotel_card", "error", "comparison"
    private Object hotelData;  // Для карточек отелей
    private Object comparisonData;  // Для сравнения

    public ChatMessage(String content, String sender) {
        this.content = content;
        this.sender = sender;
        this.timestamp = System.currentTimeMillis();
        this.type = "text";
    }

    public ChatMessage(String content, String sender, String type) {
        this.content = content;
        this.sender = sender;
        this.timestamp = System.currentTimeMillis();
        this.type = type;
    }
}
