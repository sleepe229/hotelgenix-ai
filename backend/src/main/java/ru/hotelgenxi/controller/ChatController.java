package ru.hotelgenxi.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.hotelgenxi.dto.ChatMessage;
import ru.hotelgenxi.service.SupervisorAgent;
import ru.hotelgenxi.service.VisionAgent;

import java.io.IOException;
import java.util.Base64;

/**
 * 🎮 CHAT CONTROLLER + FILE/VOICE UPLOAD
 *
 * Обработка:
 * 1. WebSocket сообщения (/app/chat)
 * 2. Загрузка фото (/api/upload/image)
 * 3. Загрузка аудио (/api/upload/audio)
 */
@Controller
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final SupervisorAgent supervisorAgent;
    private final VisionAgent visionAgent;

    public ChatController(
            SimpMessagingTemplate messagingTemplate,
            SupervisorAgent supervisorAgent,
            VisionAgent visionAgent
    ) {
        this.messagingTemplate = messagingTemplate;
        this.supervisorAgent = supervisorAgent;
        this.visionAgent = visionAgent;
    }

    /**
     * 🎯 WebSocket эндпоинт для чата
     */
    @MessageMapping("/chat")
    public void handleChat(ChatMessage message) {
        log.info("[CHAT] Received: {}", message.getContent());

        try {
            if (message == null || message.getContent() == null || message.getContent().trim().isEmpty()) {
                log.warn("[CHAT] Empty message received");
                sendErrorMessage("❌ Сообщение не может быть пустым");
                return;
            }

            // ✅ Отправляем сообщение пользователя в чат
            message.setTimestamp(System.currentTimeMillis());
            message.setSender("user");
            message.setType("text");
            messagingTemplate.convertAndSend("/topic/messages", message);

            // ✅ Обрабатываем в SupervisorAgent
            new Thread(() -> {
                supervisorAgent.handleUserQuery(message.getContent(), generateSessionId());
            }).start();

        } catch (Exception e) {
            log.error("[CHAT] Error handling message", e);
            sendErrorMessage("❌ Произошла ошибка: " + e.getMessage());
        }
    }

    /**
     * 📤 Отправляет сообщение об ошибке в чат
     */
    private void sendErrorMessage(String errorText) {
        ChatMessage errorMsg = new ChatMessage();
        errorMsg.setContent(errorText);
        errorMsg.setSender("assistant");
        errorMsg.setType("error");
        errorMsg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", errorMsg);
    }

    /**
     * 📤 Отправляет обычное сообщение
     */
    private void sendMessage(String text) {
        ChatMessage msg = new ChatMessage();
        msg.setContent(text);
        msg.setSender("assistant");
        msg.setType("text");
        msg.setTimestamp(System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/messages", msg);
    }

    /**
     * 🆔 Генерирует уникальный ID сессии
     */
    private String generateSessionId() {
        return java.util.UUID.randomUUID().toString();
    }
}