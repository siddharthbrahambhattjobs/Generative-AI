package com.example.springai.chatservice.application.service.file;

import com.example.springai.chatservice.infrastructure.ingestion.ChunkingService;
import com.example.springai.chatservice.infrastructure.ingestion.TextExtractionService;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import com.example.springai.chatservice.infrastructure.vectorstore.VectorDocumentIngestionService;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FileIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    private final AttachmentRepository attachmentRepository;
    private final TextExtractionService textExtractionService;
    private final ChunkingService chunkingService;
    private final VectorDocumentIngestionService vectorDocumentIngestionService;

    @Transactional
    public void ingest(AttachmentEntity attachment) {
        try {
            attachment.setProcessingStatus("PROCESSING");
            attachmentRepository.save(attachment);

            String extracted = textExtractionService.extract(Path.of(attachment.getStoragePath()));
            int extractedLength = extracted != null ? extracted.length() : 0;
            log.info("Extracted text for attachment id={}, file={}, length={}",
                    attachment.getId(), attachment.getFileName(), extractedLength);

            if (extracted == null || extracted.isBlank()) {
                attachment.setExtractedText(null);
                attachment.setProcessingStatus("NO_TEXT");
                attachmentRepository.save(attachment);
                return;
            }

            attachment.setExtractedText(extracted);
            attachment.setProcessingStatus("EXTRACTED");
            attachmentRepository.save(attachment);

            List<String> chunks = chunkingService.chunk(extracted, 1200, 150);
            int chunkCount = chunks != null ? chunks.size() : 0;
            log.info("Chunked attachment id={}, file={}, chunkCount={}",
                    attachment.getId(), attachment.getFileName(), chunkCount);

            if (chunks == null || chunks.isEmpty()) {
                attachment.setProcessingStatus("NO_CHUNKS");
                attachmentRepository.save(attachment);
                return;
            }

            vectorDocumentIngestionService.replaceChunks(attachment, chunks);

            attachment.setProcessingStatus("COMPLETED");
            attachmentRepository.save(attachment);

            log.info("Completed ingestion for attachment id={}, file={}",
                    attachment.getId(), attachment.getFileName());
        } catch (Exception ex) {
            try {
                attachment.setProcessingStatus("FAILED");
                attachmentRepository.save(attachment);
            } catch (Exception saveEx) {
                log.error("Also failed to persist FAILED status for attachment id={}, file={}",
                        attachment.getId(), attachment.getFileName(), saveEx);
            }

            log.error("Failed ingestion for attachment id={}, file={}",
                    attachment.getId(), attachment.getFileName(), ex);

            throw new IllegalStateException(
                    "Failed ingestion for attachment id=" + attachment.getId() + ", file=" + attachment.getFileName(),
                    ex);
        }
    }
}