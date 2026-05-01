package com.example.springai.chatservice.application.service.file;

import com.example.springai.chatservice.api.dto.file.AttachmentStatus;
import com.example.springai.chatservice.api.dto.file.UploadResponse;
import com.example.springai.chatservice.application.event.AttachmentIngestionRequestedEvent;
import com.example.springai.chatservice.application.service.memory.UserMemoryService;
import com.example.springai.chatservice.infrastructure.ingestion.FileTypePolicy;
import com.example.springai.chatservice.infrastructure.kafka.config.KafkaTopics;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.ConversationEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AppUserRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import com.example.springai.chatservice.infrastructure.persistence.repository.ConversationRepository;

import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@RequiredArgsConstructor
public class UploadApplicationService {

    private final AppUserRepository appUserRepository;
    private final ConversationRepository conversationRepository;
    private final AttachmentRepository attachmentRepository;
    private final FileTypePolicy fileTypePolicy;
    private final UserMemoryService userMemoryService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    // FIXED: Restore similarity matcher for version detection
    private final FileNameSimilarityMatcher fileNameSimilarityMatcher;

    @Autowired
    @Lazy
    private UploadApplicationService self;

    public Mono<UploadResponse> upload(UUID userId, String message, String conversationId, Flux<FilePart> files) {
        Mono<AppUserEntity> userMono = Mono
                .fromCallable(() -> appUserRepository.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("User not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .cache();

        Mono<ConversationEntity> conversationMono = userMono
                .flatMap(user -> Mono.fromCallable(() -> resolveConversation(user, conversationId, message))
                        .subscribeOn(Schedulers.boundedElastic()))
                .cache();

        return Mono.zip(userMono, conversationMono)
                .flatMapMany(tuple -> {
                    AppUserEntity user = tuple.getT1();
                    ConversationEntity conversation = tuple.getT2();

                    userMemoryService.ingestFacts(user, message)
                            .subscribeOn(Schedulers.boundedElastic())
                            .subscribe();

                    return files.flatMap(filePart -> saveAttachmentRecordOnly(user, conversation, filePart));
                })
                .collectList()
                .flatMap(items -> conversationMono.map(conv -> UploadResponse.builder()
                        .conversationId(conv.getId().toString())
                        .files(items)
                        .build()));
    }

    private Mono<UploadResponse.UploadedFileItem> saveAttachmentRecordOnly(
            AppUserEntity user, ConversationEntity conversation, FilePart filePart) {

        String fileName = normalizeFileName(filePart.filename());

        if (!fileTypePolicy.isSupported(fileName)) {
            return Mono.error(new IllegalArgumentException("Unsupported file type: " + fileName));
        }

        Path basePath = Path.of("data", "uploads", user.getId().toString());

        return Mono.fromCallable(() -> {
            Files.createDirectories(basePath);
            return basePath.resolve(UUID.randomUUID() + "-" + fileName);
        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(target -> filePart.transferTo(target).thenReturn(target))
                .flatMap(target -> Mono
                        .fromCallable(() -> self.doSaveQueued(user, conversation, filePart, fileName, target))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    // FIXED: @Transactional + version detection restored
    @Transactional
    public UploadResponse.UploadedFileItem doSaveQueued(
            AppUserEntity user, ConversationEntity conversation,
            FilePart filePart, String fileName, Path target) {

        // FIXED: Detect previous version exactly like original code
        AttachmentEntity previous = findPreviousVersionFuzzy(user.getId(), fileName);

        UUID documentKey = (previous != null && previous.getDocumentKey() != null)
                ? previous.getDocumentKey()
                : UUID.randomUUID();

        UUID rootAttachmentId = (previous != null && previous.getRootAttachmentId() != null)
                ? previous.getRootAttachmentId()
                : null;

        // FIXED: Correct version number from chain
        int nextVersion = (previous != null && previous.getVersionNumber() != null)
                ? previous.getVersionNumber() + 1
                : 1;

        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setUser(user);
        attachment.setConversation(conversation);
        attachment.setDocumentKey(documentKey);
        attachment.setFileName(fileName);

        MediaType mediaType = filePart.headers().getContentType();
        attachment.setContentType(mediaType != null ? mediaType.toString() : "application/octet-stream");

        attachment.setStoragePath(target.toString());
        attachment.setSizeBytes(readSize(target));
        attachment.setProcessingStatus("QUEUED");
        attachment.setContentHash(computeSha256(target));
        attachment.setActive(false);
        attachment.setVersionNumber(nextVersion); // FIXED: correct version
        attachment.setSupersededAttachmentId(previous != null ? previous.getId() : null);
        attachment.setDocType(previous != null && previous.getDocType() != null
                ? previous.getDocType()
                : fileTypePolicy.detectCategory(fileName));

        AttachmentEntity saved = attachmentRepository.save(attachment);

        // rootAttachmentId — v1 points to itself
        if (rootAttachmentId == null) {
            saved.setRootAttachmentId(saved.getId());
        } else {
            saved.setRootAttachmentId(rootAttachmentId);
        }
        saved = attachmentRepository.save(saved);

        // Send to Kafka for async processing
        kafkaTemplate.send(
                KafkaTopics.ATTACHMENT_INGESTION_REQUESTED,
                saved.getId().toString(),
                new AttachmentIngestionRequestedEvent(
                        saved.getId(),
                        user.getId(),
                        saved.getFileName(),
                        saved.getStoragePath(),
                        saved.getSizeBytes(),
                        saved.getContentType(),
                        saved.getContentHash()));

        return UploadResponse.UploadedFileItem.builder()
                .fileId(saved.getId())
                .fileName(saved.getFileName())
                .processingStatus("QUEUED")
                .active(false)
                .versionNumber(saved.getVersionNumber())
                .build();
    }

    public AttachmentStatus getAttachmentStatus(UUID userId, UUID attachmentId) {
        AttachmentEntity attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new IllegalArgumentException("Attachment not found: " + attachmentId));

        if (!attachment.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Access denied for attachment: " + attachmentId);
        }

        return new AttachmentStatus(
                attachment.getId(),
                attachment.getFileName(),
                attachment.getProcessingStatus(),
                attachment.isActive(),
                attachment.getVersionNumber());
    }

    // FIXED: Restored from original — finds active previous version by fuzzy name
    private AttachmentEntity findPreviousVersionFuzzy(UUID userId, String uploadedFileName) {
        List<AttachmentEntity> attachments = attachmentRepository.findByUser_IdOrderByUpdatedAtDesc(userId);

        if (attachments.isEmpty())
            return null;

        List<String> candidateNames = attachments.stream()
                .filter(AttachmentEntity::isActive)
                .map(AttachmentEntity::getFileName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();

        FileNameSimilarityMatcher.MatchResult bestMatch = fileNameSimilarityMatcher.findBestMatch(uploadedFileName,
                candidateNames);

        if (bestMatch == null)
            return null;

        return attachments.stream()
                .filter(AttachmentEntity::isActive)
                .filter(a -> a.getFileName() != null)
                .filter(a -> a.getFileName().equalsIgnoreCase(bestMatch.fileName()))
                .max(Comparator.comparingInt(a -> a.getVersionNumber() != null ? a.getVersionNumber() : 0))
                .orElse(null);
    }

    private String normalizeFileName(String fileName) {
        return fileName == null ? "file" : Path.of(fileName).getFileName().toString();
    }

    private long readSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to read file size", ex);
        }
    }

    private String computeSha256(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute file hash", ex);
        }
    }

    private ConversationEntity resolveConversation(AppUserEntity user, String conversationId, String message) {
        if (conversationId == null || conversationId.isBlank()) {
            return createConversation(user, message);
        }
        UUID conversationUuid;
        try {
            conversationUuid = UUID.fromString(conversationId.trim());
        } catch (IllegalArgumentException ex) {
            return createConversation(user, message);
        }
        return conversationRepository.findByIdAndUser_Id(conversationUuid, user.getId())
                .orElseGet(() -> createConversation(user, message));
    }

    private ConversationEntity createConversation(AppUserEntity user, String message) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setUser(user);
        conversation.setTitle(message == null || message.isBlank() ? "File upload" : message.trim());
        conversation.setStatus("ACTIVE");
        return conversationRepository.save(conversation);
    }
}