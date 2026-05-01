package com.example.springai.chatservice.application.service.memory;

import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileContextRetrievalService {

    private static final int TOP_K = 6;
    private static final double SIMILARITY_THRESHOLD = 0.0;

    private final AttachmentRepository attachmentRepository;
    private final VectorStore vectorStore;

    public Mono<List<Document>> retrieveForUserFiles(AppUserEntity user, String query, List<String> fileIds) {
        return Mono.fromCallable(() -> {
            List<UUID> requestedIds = parseIds(fileIds);
            if (requestedIds.isEmpty()) {
                return List.<Document>of();
            }

            List<AttachmentEntity> attachments = attachmentRepository.findByUser_IdAndIdIn(user.getId(), requestedIds);
            List<AttachmentEntity> latestActiveAttachments = latestActiveOnly(attachments);
            if (latestActiveAttachments.isEmpty()) {
                log.info("No latest active attachments found for retrieval userId={}, requestedCount={}",
                        user.getId(), requestedIds.size());
                return List.<Document>of();
            }

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression(buildUserAndAttachmentFilter(user.getId(), latestActiveAttachments))
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            log.info("Retrieved file docs userId={}, requestedCount={}, activeCount={}, resultCount={}",
                    user.getId(), requestedIds.size(), latestActiveAttachments.size(),
                    results != null ? results.size() : 0);
            return results != null ? results : List.<Document>of();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Document>> retrieveForSpecificAttachmentIds(AppUserEntity user, String query,
            List<String> attachmentIds) {
        return Mono.fromCallable(() -> {
            List<UUID> requestedIds = parseIds(attachmentIds);
            if (requestedIds.isEmpty()) {
                return List.<Document>of();
            }

            List<AttachmentEntity> attachments = attachmentRepository.findByUser_IdAndIdIn(user.getId(), requestedIds);
            if (attachments.isEmpty()) {
                return List.<Document>of();
            }

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .filterExpression(buildUserAndAttachmentFilter(user.getId(), attachments))
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);
            return results != null ? results : List.<Document>of();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Filter.Expression buildUserAndAttachmentFilter(UUID userId, List<AttachmentEntity> attachments) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        FilterExpressionBuilder.Op attachmentExpression = null;
        for (AttachmentEntity attachment : attachments) {
            FilterExpressionBuilder.Op current = b.eq("attachmentId", attachment.getId().toString());
            attachmentExpression = (attachmentExpression == null)
                    ? current
                    : b.or(attachmentExpression, current);
        }

        if (attachmentExpression == null) {
            return b.eq("userId", userId.toString()).build();
        }

        return b.and(
                b.eq("userId", userId.toString()),
                b.group(attachmentExpression))
                .build();
    }

    private List<AttachmentEntity> latestActiveOnly(List<AttachmentEntity> attachments) {
        Map<UUID, AttachmentEntity> latestByDocumentKey = new LinkedHashMap<>();

        for (AttachmentEntity attachment : attachments) {
            if (attachment == null || !attachment.isActive()) {
                continue;
            }
            if (!isUsableForRetrieval(attachment)) {
                continue;
            }

            UUID documentKey = attachment.getDocumentKey() != null ? attachment.getDocumentKey() : attachment.getId();
            AttachmentEntity existing = latestByDocumentKey.get(documentKey);
            if (existing == null || compareVersion(existing, attachment) < 0) {
                latestByDocumentKey.put(documentKey, attachment);
            }
        }

        return List.copyOf(latestByDocumentKey.values());
    }

    private int compareVersion(AttachmentEntity left, AttachmentEntity right) {
        int leftVersion = left.getVersionNumber() != null ? left.getVersionNumber() : 0;
        int rightVersion = right.getVersionNumber() != null ? right.getVersionNumber() : 0;
        return Integer.compare(leftVersion, rightVersion);
    }

    private boolean isUsableForRetrieval(AttachmentEntity attachment) {
        String status = attachment.getProcessingStatus();
        return "COMPLETED".equalsIgnoreCase(status)
                || "NO_TEXT".equalsIgnoreCase(status)
                || "NO_CHUNKS".equalsIgnoreCase(status)
                || "READY_FOR_RETRIEVAL".equalsIgnoreCase(status);
    }

    private List<UUID> parseIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }

        return fileIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> {
                    try {
                        return UUID.fromString(id);
                    } catch (Exception ex) {
                        log.warn("Ignoring invalid attachment id={}", id);
                        return null;
                    }
                })
                .filter(id -> id != null)
                .toList();
    }
}