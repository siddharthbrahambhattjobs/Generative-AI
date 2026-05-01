package com.example.springai.chatservice.application.service.chat;

import com.example.springai.chatservice.infrastructure.persistence.entity.MessageEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.MessageRepository;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private static final int DEFAULT_HISTORY_LIMIT = 15;
    private final MessageRepository messageRepository;

    @Transactional(readOnly = true)
    public List<MessageEntity> loadRecentMessages(UUID conversationId) {
        if (conversationId == null) {
            return List.of();
        }

        List<MessageEntity> recent = messageRepository.findByConversation_IdOrderByCreatedAtDesc(
                conversationId, PageRequest.of(0, DEFAULT_HISTORY_LIMIT));

        if (recent == null || recent.isEmpty()) {
            return List.of();
        }

        Collections.reverse(recent);
        return recent;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> buildChatMessages(UUID conversationId, String currentUserMessage) {
        List<MessageEntity> history = loadRecentMessages(conversationId);

        List<ChatMessage> result = history.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .sorted(Comparator.comparing(MessageEntity::getCreatedAt))
                .map(m -> new ChatMessage(normalizeRole(m.getRole()), m.getContent()))
                .toList();

        return result;
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        return switch (role.trim().toUpperCase()) {
            case "ASSISTANT" -> "assistant";
            case "SYSTEM" -> "system";
            default -> "user";
        };
    }

    public record ChatMessage(String role, String content) {
    }
}