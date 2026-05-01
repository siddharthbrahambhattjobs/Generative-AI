package com.example.springai.chatservice.application.service.memory;

import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UserMemoryService {

    private static final Pattern NAME_PATTERN = Pattern
            .compile("(?i)\\b(my name is|i am|i'm)\\s+([A-Za-z][A-Za-z .'-]{1,80})");

    private final VectorStore vectorStore;

    public UserMemoryService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public Mono<Void> ingestFacts(AppUserEntity user, String message) {
        return Mono.fromRunnable(() -> {
                    List<Document> docs = extractFacts(user, message);
                    if (!docs.isEmpty()) {
                        vectorStore.add(docs);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    public Mono<List<Document>> retrieveUserFacts(AppUserEntity user, String query) {
        return Mono.fromCallable(() -> {
                    SearchRequest request = SearchRequest.builder()
                            .query(query)
                            .topK(5)
                            .similarityThreshold(0.45)
                            .filterExpression("userId == '" + user.getId() + "' && memoryType == 'USER_FACT' && active == true")
                            .build();
                    List<Document> results = vectorStore.similaritySearch(request);
                    return results != null ? results : List.<Document>of();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<Document> extractFacts(AppUserEntity user, String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        List<Document> docs = new ArrayList<>();
        Matcher matcher = NAME_PATTERN.matcher(message.trim());

        if (matcher.find()) {
            String name = matcher.group(2).trim();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", user.getId().toString());
            metadata.put("memoryType", "USER_FACT");
            metadata.put("factType", "NAME");
            metadata.put("active", true);
            docs.add(new Document("User's name is " + name, metadata));
        }

        return docs;
    }
}