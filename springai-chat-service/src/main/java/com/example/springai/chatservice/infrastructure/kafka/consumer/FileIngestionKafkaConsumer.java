package com.example.springai.chatservice.infrastructure.kafka.consumer;

import com.example.springai.chatservice.application.event.AttachmentIngestionRequestedEvent;
import com.example.springai.chatservice.application.service.file.FileIngestionService;
import com.example.springai.chatservice.infrastructure.kafka.config.KafkaTopics;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FileIngestionKafkaConsumer {

    private final AttachmentRepository attachmentRepository;
    private final FileIngestionService fileIngestionService;

    @KafkaListener(topics = KafkaTopics.ATTACHMENT_INGESTION_REQUESTED, groupId = "file-ingestion-group")
    @Transactional
    // FIXED: Only ONE parameter — the payload itself, NOT (String key, Event event)
    public void processAttachmentIngestion(AttachmentIngestionRequestedEvent event) {
        log.info("📥 Kafka received attachment: {} for user: {}", event.attachmentId(), event.userId());

        AttachmentEntity attachment = attachmentRepository.findById(event.attachmentId())
                .orElseThrow(() -> new IllegalStateException("Attachment not found: " + event.attachmentId()));

        try {
            fileIngestionService.ingest(attachment);

            attachment = attachmentRepository.findById(event.attachmentId())
                    .orElseThrow(() -> new IllegalStateException("Attachment lost after ingestion"));

            if (isUsable(attachment.getProcessingStatus())) {
                attachment.setActive(true);
                attachmentRepository.save(attachment);
                log.info("✅ Attachment {} ingestion completed, status: {}",
                        event.attachmentId(), attachment.getProcessingStatus());
            } else {
                log.error("❌ Ingestion failed for {}: {}", event.attachmentId(), attachment.getProcessingStatus());
                attachment.setActive(false);
                attachmentRepository.save(attachment);
            }
        } catch (Exception e) {
            log.error("❌ Kafka consumer failed for {}: {}", event.attachmentId(), e.getMessage(), e);
            attachment.setProcessingStatus("FAILED");
            attachment.setActive(false);
            attachmentRepository.save(attachment);
        }
    }

    private boolean isUsable(String status) {
        // ✅ FIX: Added underscores to match the actual database status
        return "COMPLETED".equals(status) || "NO_TEXT".equals(status) || "NO_CHUNKS".equals(status);
    }
}