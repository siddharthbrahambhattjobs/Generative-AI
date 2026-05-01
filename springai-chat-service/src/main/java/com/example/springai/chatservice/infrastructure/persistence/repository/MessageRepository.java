package com.example.springai.chatservice.infrastructure.persistence.repository;

import com.example.springai.chatservice.infrastructure.persistence.entity.MessageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    List<MessageEntity> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    List<MessageEntity> findByConversation_IdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    void deleteByConversation_Id(UUID conversationId);
}