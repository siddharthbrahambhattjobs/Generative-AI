package com.example.springai.chatservice.application.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttachmentCleanupRequestedEvent(
        UUID userId,
        UUID documentKey,
        UUID rootAttachmentId,
        List<UUID> attachmentIds,
        OffsetDateTime requestedAt) {

    public AttachmentCleanupRequestedEvent {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        requestedAt = requestedAt == null ? OffsetDateTime.now() : requestedAt;
    }

    public static AttachmentCleanupRequestedEvent of(
            UUID userId,
            UUID documentKey,
            UUID rootAttachmentId,
            List<UUID> attachmentIds) {
        return new AttachmentCleanupRequestedEvent(
                userId,
                documentKey,
                rootAttachmentId,
                attachmentIds,
                OffsetDateTime.now());
    }

    public boolean hasWork() {
        return !attachmentIds.isEmpty();
    }
}