package com.example.springai.chatservice.application.service.conversation;

import com.example.springai.chatservice.api.dto.conversation.ConversationDetailResponse;
import com.example.springai.chatservice.api.dto.conversation.ConversationSummaryResponse;
import com.example.springai.chatservice.api.dto.conversation.CreateConversationRequest;
import com.example.springai.chatservice.api.dto.conversation.MessageResponse;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.ConversationEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AppUserRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.ConversationRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.MessageRepository;
import com.example.springai.chatservice.infrastructure.vectorstore.VectorCleanupService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationQueryService {

        private final AppUserRepository appUserRepository;
        private final ConversationRepository conversationRepository;
        private final MessageRepository messageRepository;
        private final AttachmentRepository attachmentRepository;
        private final VectorCleanupService vectorCleanupService;

        // ─────────────────────────────────────────────────────────────
        // Queries
        // ─────────────────────────────────────────────────────────────

        @Transactional(readOnly = true)
        public List<ConversationSummaryResponse> listConversations(UUID userId) {
                return conversationRepository.findByUser_IdOrderByUpdatedAtDesc(userId)
                                .stream()
                                .map(c -> ConversationSummaryResponse.builder()
                                                .id(c.getId())
                                                .title(c.getTitle())
                                                .status(c.getStatus())
                                                .updatedAt(c.getUpdatedAt())
                                                .build())
                                .toList();
        }

        @Transactional(readOnly = true)
        public ConversationDetailResponse getConversation(UUID userId, UUID conversationId) {
                ConversationEntity conversation = conversationRepository
                                .findByIdAndUser_Id(conversationId, userId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Conversation not found: " + conversationId + " for user: " + userId));

                List<MessageResponse> messages = messageRepository
                                .findByConversation_IdOrderByCreatedAtAsc(conversation.getId())
                                .stream()
                                .map(m -> MessageResponse.builder()
                                                .id(m.getId())
                                                .role(m.getRole())
                                                .content(m.getContent())
                                                .status(m.getStatus())
                                                .createdAt(m.getCreatedAt())
                                                .build())
                                .toList();

                return ConversationDetailResponse.builder()
                                .id(conversation.getId())
                                .title(conversation.getTitle())
                                .status(conversation.getStatus())
                                .updatedAt(conversation.getUpdatedAt())
                                .messages(messages)
                                .build();
        }

        // ─────────────────────────────────────────────────────────────
        // Commands
        // ─────────────────────────────────────────────────────────────

        @Transactional
        public ConversationSummaryResponse createConversation(UUID userId, CreateConversationRequest request) {
                AppUserEntity user = appUserRepository.findById(userId)
                                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

                ConversationEntity conversation = new ConversationEntity();
                conversation.setUser(user);
                conversation.setTitle(request.title());
                conversation.setStatus("ACTIVE");

                ConversationEntity saved = conversationRepository.save(conversation);
                log.info("Created conversation id={}, userId={}", saved.getId(), userId);

                return ConversationSummaryResponse.builder()
                                .id(saved.getId())
                                .title(saved.getTitle())
                                .status(saved.getStatus())
                                .updatedAt(saved.getUpdatedAt())
                                .build();
        }

        @Transactional
        public void deleteConversation(UUID userId, UUID conversationId) {
                ConversationEntity conversation = conversationRepository
                                .findByIdAndUser_Id(conversationId, userId)
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "Conversation not found or access denied"));

                // 1. Delete all messages
                messageRepository.deleteByConversation_Id(conversationId);

                // 2. Delete each attachment's vector chunks, then the attachment rows
                List<AttachmentEntity> attachments = attachmentRepository.findByConversation_Id(conversationId);

                for (AttachmentEntity attachment : attachments) {
                        try {
                                vectorCleanupService.deleteChunksForAttachment(attachment.getId());
                        } catch (Exception ex) {
                                log.warn("Failed to clean vector chunks for attachmentId={}, continuing. error={}",
                                                attachment.getId(), ex.getMessage());
                        }
                }

                attachmentRepository.deleteByConversation_Id(conversationId);

                // 3. Delete the conversation itself
                conversationRepository.delete(conversation);
                log.info("Deleted conversation id={}, userId={}", conversationId, userId);
        }
}
