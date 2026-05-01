package com.example.springai.chatservice.application.service.chat;

import com.example.springai.chatservice.api.dto.chat.ChatRequest;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.ConversationEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.MessageEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AppUserRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.ConversationRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.MessageRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatPersistenceService {

    private static final int MAX_CONVERSATION_TITLE_LENGTH = 60;

    private final AppUserRepository appUserRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Value("${spring.ai.ollama.chat.options.model}")
    private String ollamaModel;

    public ChatPersistenceService(
            AppUserRepository appUserRepository,
            ConversationRepository conversationRepository,
            MessageRepository messageRepository) {
        this.appUserRepository = appUserRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional
    public ConversationContext prepareConversation(UUID userId, ChatRequest request) {
        AppUserEntity user = appUserRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        ConversationEntity conversation = resolveConversation(user, request);
        persistUserMessage(user, conversation, request);

        return new ConversationContext(user, conversation);
    }

    @Transactional
    public void persistAssistantMessage(ConversationContext context, String content, ChatRequest request) {
        if (content == null || content.isBlank()) {
            return;
        }

        MessageEntity message = new MessageEntity();
        message.setConversation(context.conversation());
        message.setUser(context.user());
        message.setRole("ASSISTANT");
        message.setContent(content);
        message.setStatus("COMPLETED");
        message.setProviderName(request.provider() != null ? request.provider() : "ollama");
        message.setModelName(request.model() != null ? request.model() : ollamaModel);
        messageRepository.save(message);
    }

    private ConversationEntity resolveConversation(AppUserEntity user, ChatRequest request) {
        String conversationId = request.conversationId();

        if (conversationId == null || conversationId.isBlank()) {
            return createConversation(user, request.message());
        }

        UUID conversationUuid;
        try {
            conversationUuid = UUID.fromString(conversationId.trim());
        } catch (IllegalArgumentException ex) {
            return createConversation(user, request.message());
        }

        return conversationRepository.findByIdAndUser_Id(conversationUuid, user.getId())
                .orElseGet(() -> createConversation(user, request.message()));
    }

    private ConversationEntity createConversation(AppUserEntity user, String message) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setUser(user);
        conversation.setTitle(generateTitle(message));
        conversation.setStatus("ACTIVE");
        return conversationRepository.save(conversation);
    }

    private void persistUserMessage(AppUserEntity user, ConversationEntity conversation, ChatRequest request) {
        MessageEntity message = new MessageEntity();
        message.setConversation(conversation);
        message.setUser(user);
        message.setRole("USER");
        message.setContent(request.message());
        message.setStatus("COMPLETED");
        message.setProviderName(request.provider() != null ? request.provider() : "ollama");
        message.setModelName(request.model() != null ? request.model() : ollamaModel);
        messageRepository.save(message);
    }

    private String generateTitle(String message) {
        String trimmed = message == null ? "New conversation" : message.trim();
        if (trimmed.isBlank()) {
            return "New conversation";
        }
        return trimmed.length() > MAX_CONVERSATION_TITLE_LENGTH
                ? trimmed.substring(0, MAX_CONVERSATION_TITLE_LENGTH) + "..."
                : trimmed;
    }

    public record ConversationContext(AppUserEntity user, ConversationEntity conversation) {
    }
}