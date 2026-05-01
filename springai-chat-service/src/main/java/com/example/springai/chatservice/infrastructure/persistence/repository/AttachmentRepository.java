package com.example.springai.chatservice.infrastructure.persistence.repository;

import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, UUID> {

        Optional<AttachmentEntity> findByIdAndUser_Id(UUID attachmentId, UUID userId);

        List<AttachmentEntity> findByUser_IdAndIdIn(UUID userId, Collection<UUID> ids);

        List<AttachmentEntity> findByConversation_IdAndUser_IdAndActiveTrue(UUID conversationId, UUID userId);

        Optional<AttachmentEntity> findFirstByUser_IdAndFileNameIgnoreCaseAndActiveTrueOrderByVersionNumberDesc(
                        UUID userId,
                        String fileName);

        List<AttachmentEntity> findByUser_IdAndFileNameOrderByVersionNumberDesc(UUID userId, String fileName);

        Optional<AttachmentEntity> findByUser_IdAndFileNameAndVersionNumber(UUID userId, String fileName,
                        int versionNumber);

        Optional<AttachmentEntity> findByUser_IdAndFileNameAndActiveTrue(UUID userId, String fileName);

        List<AttachmentEntity> findByUser_IdAndDocumentKeyOrderByVersionNumberDesc(UUID userId, UUID documentKey);

        Optional<AttachmentEntity> findFirstByUser_IdAndDocumentKeyOrderByVersionNumberDesc(UUID userId,
                        UUID documentKey);

        Optional<AttachmentEntity> findFirstByUser_IdAndRootAttachmentIdOrderByVersionNumberDesc(
                        UUID userId,
                        UUID rootAttachmentId);

        List<AttachmentEntity> findByUser_IdAndRootAttachmentIdAndActiveFalseOrderByVersionNumberDesc(
                        UUID userId,
                        UUID rootAttachmentId);

        List<AttachmentEntity> findByUser_IdAndActiveTrueOrderByUpdatedAtDesc(UUID userId);

        List<AttachmentEntity> findByConversation_Id(UUID conversationId);

        void deleteByConversation_Id(UUID conversationId);

        Optional<AttachmentEntity> findFirstByUser_IdAndFileNameIgnoreCaseOrderByUpdatedAtDesc(UUID userId,
                        String fileName);

        List<AttachmentEntity> findByUser_IdAndRootAttachmentIdOrderByVersionNumberDesc(UUID userId,
                        UUID rootAttachmentId);

        List<AttachmentEntity> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

        List<AttachmentEntity> findByUser_IdOrderByUpdatedAtDesc(UUID userId);
}