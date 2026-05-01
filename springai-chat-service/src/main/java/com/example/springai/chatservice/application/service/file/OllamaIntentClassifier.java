package com.example.springai.chatservice.application.service.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class OllamaIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(OllamaIntentClassifier.class);

    private static final String SYSTEM_PROMPT = """
            You are a strict intent classifier for a file chat system.
            Return only valid JSON.
            Do not add markdown.
            Do not explain.

            Allowed intent values:
            NONE
            CONTENT_QUERY
            OLDER_VERSION
            LIST_ALL_VERSIONS
            LATEST_FILENAME
            OLDER_FILENAMES
            SPECIFIC_VERSION
            COMPARE_SELECTED_VERSIONS
            """;

    private static final String USER_PROMPT = """
            Extract:
            - intent
            - fileName
            - leftVersion
            - rightVersion
            - versionNumber

            Rules:
            - If user asks for file summary, file content, details from file, use CONTENT_QUERY.
            - If user asks all versions / available versions / version history, use LIST_ALL_VERSIONS.
            - If user asks older version / previous version / first version, use OLDER_VERSION.
            - If user asks latest file name, use LATEST_FILENAME.
            - If user asks older file names, use OLDER_FILENAMES.
            - If user asks for a specific version like version 2, use SPECIFIC_VERSION.
            - If user asks compare version X and version Y, use COMPARE_SELECTED_VERSIONS.
            - Extract exact file name with extension if present.
            - If missing, keep fileName null.

            User message:
            %s
            """;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${app.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${app.ollama.model:llama3.2}")
    private String ollamaModel;

    public OllamaIntentClassifier(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public Mono<ClassifiedIntent> classify(String message) {
        String safeMessage = message == null ? "" : message.trim();

        Map<String, Object> payload = Map.of(
                "model", ollamaModel,
                "system", SYSTEM_PROMPT,
                "prompt", USER_PROMPT.formatted(safeMessage),
                "stream", false,
                "format", "json",
                "options", Map.of(
                        "temperature", 0.0,
                        "top_p", 1.0,
                        "num_predict", 200,
                        "num_ctx", 4096));

        return webClient.post()
                .uri(normalizeBaseUrl(ollamaBaseUrl) + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(OllamaGenerateResponse.class)
                .map(resp -> parseResponse(resp.response()))
                .onErrorResume(ex -> {
                    log.warn("Intent classification failed, fallback to NONE. error={}", ex.getMessage(), ex);
                    return Mono.just(ClassifiedIntent.none());
                });
    }

    private ClassifiedIntent parseResponse(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return ClassifiedIntent.none();
            }
            String trimmed = raw.trim();
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
            ClassifiedIntent parsed = objectMapper.readValue(trimmed, ClassifiedIntent.class);
            return normalize(parsed);
        } catch (Exception ex) {
            log.warn("Failed to parse classifier JSON: {}", raw, ex);
            return ClassifiedIntent.none();
        }
    }

    private ClassifiedIntent normalize(ClassifiedIntent intent) {
        if (intent == null || intent.intent() == null || intent.intent().isBlank()) {
            return ClassifiedIntent.none();
        }
        return new ClassifiedIntent(
                intent.intent().trim().toUpperCase(),
                blankToNull(intent.fileName()),
                intent.leftVersion(),
                intent.rightVersion(),
                intent.versionNumber());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:11434";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OllamaGenerateResponse(String response) {
    }

    public record ClassifiedIntent(
            String intent,
            String fileName,
            Integer leftVersion,
            Integer rightVersion,
            Integer versionNumber) {

        public static ClassifiedIntent none() {
            return new ClassifiedIntent("NONE", null, null, null, null);
        }
    }
}