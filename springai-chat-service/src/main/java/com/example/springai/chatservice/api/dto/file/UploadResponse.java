package com.example.springai.chatservice.api.dto.file;

import java.util.List;
import java.util.UUID;
import lombok.Builder;

@Builder
public record UploadResponse(
                String conversationId,
                List<UploadedFileItem> files) {
        @Builder
        public record UploadedFileItem(
                        UUID fileId,
                        String fileName,
                        String processingStatus,
                        Integer versionNumber,
                        boolean active) {
        }
}