package com.example.springai.chatservice.infrastructure.vectorstore;

import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VectorDocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(VectorDocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final VectorCleanupService vectorCleanupService;

    public void replaceChunks(AttachmentEntity attachment, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        vectorCleanupService.deleteChunksForAttachment(attachment.getId());

        List<Document> documents = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(i -> buildDocument(chunks.get(i), attachment, i))
                .toList();

        vectorStore.add(documents);

        log.info("Indexed chunks for attachment id={}, file={}, chunkCount={}",
                attachment.getId(), attachment.getFileName(), documents.size());
    }

    private Document buildDocument(String chunkText, AttachmentEntity attachment, int seq) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", attachment.getUser().getId().toString());
        metadata.put("attachmentId", attachment.getId().toString());
        metadata.put("active", attachment.isActive());
        metadata.put("fileName", attachment.getFileName());
        metadata.put("versionNumber", String.valueOf(attachment.getVersionNumber()));
        metadata.put("chunkSeq", String.valueOf(seq));

        if (attachment.getConversation() != null) {
            metadata.put("conversationId", attachment.getConversation().getId().toString());
        }

        if (attachment.getRootAttachmentId() != null) {
            metadata.put("rootAttachmentId", attachment.getRootAttachmentId().toString());
        }

        return new Document(chunkText, metadata);
    }
}