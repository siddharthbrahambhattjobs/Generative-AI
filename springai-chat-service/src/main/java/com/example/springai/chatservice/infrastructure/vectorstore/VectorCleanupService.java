package com.example.springai.chatservice.infrastructure.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class VectorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(VectorCleanupService.class);

    private final VectorStore vectorStore;

    public VectorCleanupService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void deleteChunksForAttachment(UUID attachmentId) {
        try {
            var filter = new FilterExpressionBuilder()
                    .eq("attachmentId", attachmentId.toString())
                    .build();

            vectorStore.delete(filter);

            log.info("Deleted vector chunks for attachmentId={}", attachmentId);
        } catch (Exception e) {
            log.warn("Failed to delete vector chunks for attachmentId={}: {}",
                    attachmentId, e.getMessage());
        }
    }

    public void deleteChunksForUser(UUID userId) {
        try {
            var filter = new FilterExpressionBuilder()
                    .eq("userId", userId.toString())
                    .build();
            vectorStore.delete(filter);
            log.info("Deleted all vector chunks for userId={}", userId);
        } catch (Exception e) {
            log.warn("Failed to delete vector chunks for userId={}: {}",
                    userId, e.getMessage());
        }
    }
}