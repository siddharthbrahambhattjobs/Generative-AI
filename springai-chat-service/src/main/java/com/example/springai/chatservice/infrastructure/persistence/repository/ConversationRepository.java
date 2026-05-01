package com.example.springai.chatservice.infrastructure.persistence.repository;

import com.example.springai.chatservice.infrastructure.persistence.entity.ConversationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    List<ConversationEntity> findByUser_IdOrderByUpdatedAtDesc(UUID userId);

    Optional<ConversationEntity> findByIdAndUser_Id(UUID id, UUID userId);
}
