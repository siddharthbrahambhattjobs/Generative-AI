package com.example.springai.chatservice.application.service.chat;

import com.example.springai.chatservice.api.dto.chat.ChatRequest;
import com.example.springai.chatservice.api.dto.chat.ChatStreamEvent;
import com.example.springai.chatservice.application.service.chat.ChatPersistenceService.ConversationContext;
import com.example.springai.chatservice.application.service.chat.ConversationMemoryService.ChatMessage;
import com.example.springai.chatservice.application.service.file.FileVersionHistoryService;
import com.example.springai.chatservice.application.service.file.FileVersionIntentDetector;
import com.example.springai.chatservice.application.service.file.FileVersionIntentDetector.FileVersionIntent;
import com.example.springai.chatservice.application.service.file.FileVersionIntentDetector.FileVersionIntentType;
import com.example.springai.chatservice.application.service.memory.FileContextRetrievalService;
import com.example.springai.chatservice.application.service.memory.UserMemoryService;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

@Service
public class ChatStreamingService {

        private static final Logger log = LoggerFactory.getLogger(ChatStreamingService.class);

        private static final List<String> IN_PROGRESS_STATUSES = List.of("QUEUED", "UPLOADED", "PROCESSING",
                        "EXTRACTED");
        private static final String FAILED_STATUS = "FAILED";
        private static final String READY_FOR_RETRIEVAL = "READY_FOR_RETRIEVAL";
        private static final int MAX_ATTACHMENT_POLL_RETRIES = 10;
        private static final Duration ATTACHMENT_POLL_DELAY = Duration.ofMillis(700);
        private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);
        private static final int MAX_SYSTEM_CONTEXT_LENGTH = 12000;
        private static final int MAX_DOC_PREVIEW_LENGTH = 300;
        private static final int MAX_USER_FACT_LENGTH = 500;
        private static final int MAX_FILE_DOC_LENGTH = 1500;
        private final Map<String, String> conversationFileContext = new ConcurrentHashMap<>();

        private static final String INTENT_CLASSIFIER_SYSTEM = """
                        You are a semantic intent classifier for a file-versioning assistant.
                        Analyze the user's message and return ONLY compact JSON with these fields:

                        {
                          "intent": "NONE|CONTENT_QUERY|OLDER_VERSION|PREVIOUS_VERSION|LIST_ALL_VERSIONS|LATEST_FILENAME|OLDER_FILENAMES|SPECIFIC_VERSION|COMPARE_SELECTED_VERSIONS",
                          "fileName": "string or null",
                          "leftVersion": "number or null",
                          "rightVersion": "number or null",
                          "allVersionsRequested": true/false,
                          "contentQuery": true/false,
                          "useOldestVersion": true/false
                        }

                        Semantic rules — think carefully about what the user ACTUALLY wants:

                        CONTENT_QUERY: User wants to READ actual data/text FROM a file.
                          Examples: "find my mobile number", "what is in this file", "read my resume",
                                    "find the oldest phone number", "show me the email from this file",
                                    "what does the file say", "tell me my gym timings", "extract the data"
                          → contentQuery=true

                        CONTENT_QUERY with useOldestVersion=true: User wants content specifically from the OLDEST/FIRST version.
                          Examples: "find my oldest mobile number", "what was in the first version",
                                    "show me data from the earliest version", "find my first registered number"
                          → contentQuery=true, useOldestVersion=true

                        LIST_ALL_VERSIONS: User asks about versions as metadata — NOT content.
                          Examples: "how many versions exist", "list all versions", "show version history"
                          → allVersionsRequested=false, contentQuery=false

                        LIST_ALL_VERSIONS with contentQuery=true: User wants version list AND the content of each.
                          Examples: "show all versions with their content", "list versions and mention what's in each"
                          → allVersionsRequested=true, contentQuery=true

                        OLDER_VERSION: User wants metadata about an older version (file name, version number).
                          Examples: "what was the previous version called", "show me the older version name"

                        SPECIFIC_VERSION: User asks for a specific version number explicitly.
                          Examples: "show me version 2", "give me version 3 of resume.pdf"

                        COMPARE_SELECTED_VERSIONS: User wants to compare two specific versions.
                          Examples: "compare version 1 and version 2"

                        LATEST_FILENAME: "what is the latest file name", "current file name"

                        OLDER_FILENAMES: "what were the older file names", "previous file names"

                        NONE: User is asking about the CONVERSATION, CHAT HISTORY, GENERAL KNOWLEDGE,
                              or anything NOT related to file content or version management.
                          Examples: "what did I ask earlier", "who is the PM of India",
                                    "summarize our conversation", "list my questions"

                        Additional rules:
                        - Extract file name if present (e.g., "my mobile number.txt", "resume.pdf")
                        - "oldest/earliest/first" + data retrieval = CONTENT_QUERY + useOldestVersion=true
                        - "find/get/show/tell me/what is my [data]" = CONTENT_QUERY
                        - Never return OLDER_VERSION when the user is asking for actual DATA, not version metadata
                        - Return ONLY the JSON object, no markdown, no explanation
                        """;

        private final AttachmentRepository attachmentRepository;
        private final UserMemoryService userMemoryService;
        private final FileContextRetrievalService fileContextRetrievalService;
        private final ConversationMemoryService conversationMemoryService;
        private final ChatPersistenceService chatPersistenceService;
        private final FileVersionIntentDetector fileVersionIntentDetector;
        private final FileVersionHistoryService fileVersionHistoryService;
        private final WebClient webClient;
        private final ObjectMapper objectMapper;

        @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
        private String ollamaBaseUrl;

        @Value("${spring.ai.ollama.chat.options.model:llama3.2:3b}")
        private String ollamaModel;

        @Value("${spring.ai.ollama.chat.options.temperature:0.3}")
        private double temperature;

        public ChatStreamingService(
                        AttachmentRepository attachmentRepository,
                        UserMemoryService userMemoryService,
                        FileContextRetrievalService fileContextRetrievalService,
                        ConversationMemoryService conversationMemoryService,
                        ChatPersistenceService chatPersistenceService,
                        FileVersionIntentDetector fileVersionIntentDetector,
                        FileVersionHistoryService fileVersionHistoryService,
                        WebClient.Builder webClientBuilder,
                        ObjectMapper objectMapper) {

                this.attachmentRepository = attachmentRepository;
                this.userMemoryService = userMemoryService;
                this.fileContextRetrievalService = fileContextRetrievalService;
                this.conversationMemoryService = conversationMemoryService;
                this.chatPersistenceService = chatPersistenceService;
                this.fileVersionIntentDetector = fileVersionIntentDetector;
                this.fileVersionHistoryService = fileVersionHistoryService;
                this.objectMapper = objectMapper;
                HttpClient httpClient = HttpClient.create()
                                .responseTimeout(java.time.Duration.ofMinutes(5));
                this.webClient = webClientBuilder
                                .clientConnector(new ReactorClientHttpConnector(httpClient))
                                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                                .build();
        }

        public Flux<ChatStreamEvent> streamChat(ChatRequest request, UUID userId) {
                String conversationId = resolveConversationId(request);
                ChatRequest normalizedRequest = withConversationId(request, conversationId);

                log.info("Chat request conversationId={}, userId={}, fileIds={}",
                                conversationId, userId, normalizedRequest.fileIds());

                return detectIntentWithOllama(normalizedRequest.message())
                                .defaultIfEmpty(new FileVersionIntent(
                                                FileVersionIntentType.NONE, null, null, null, false, false, false))
                                .flatMapMany(intent -> {

                                        // NEW: Route multi-file queries to the standard retrieval flow
                                        boolean isStandardMultiFileQuery = intent
                                                        .type() == FileVersionIntentType.CONTENT_QUERY
                                                        && !intent.allVersionsRequested()
                                                        && !intent.useOldestVersion()
                                                        && hasMultipleFiles(normalizedRequest);

                                        if (intent.type() != FileVersionIntentType.NONE && !isStandardMultiFileQuery) {
                                                return handleFileVersionIntent(normalizedRequest, userId, intent,
                                                                conversationId);
                                        }

                                        // Standard multi-file retrieval flow
                                        return prepareConversationReactive(userId, normalizedRequest)
                                                        .flatMap(conversationContext -> resolveStreamContext(
                                                                        conversationContext, normalizedRequest))
                                                        .flatMapMany(ctx -> ctx.immediateResponse()
                                                                        ? immediateResponseFlow(ctx, normalizedRequest)
                                                                        : successStreamingFlow(ctx, normalizedRequest));
                                });
        }

        private boolean hasMultipleFiles(ChatRequest request) {
                return request.fileIds() != null && request.fileIds().size() > 1;
        }

        private Mono<FileVersionIntent> detectIntentWithOllama(String message) {
                String userMessage = message == null ? "" : message.trim();
                if (userMessage.isBlank()) {
                        return Mono.just(new FileVersionIntent(FileVersionIntentType.NONE, null, null, null, false,
                                        false, false));
                }

                String model = (ollamaModel == null || ollamaModel.isBlank()) ? "llama3.2:3b" : ollamaModel;
                String baseUrl = normalizeBaseUrl(ollamaBaseUrl);

                Map<String, Object> payload = Map.of(
                                "model", model,
                                "system", INTENT_CLASSIFIER_SYSTEM,
                                "prompt", "Classify this user request:\n" + userMessage,
                                "stream", false,
                                "format", "json",
                                "options", Map.of(
                                                "temperature", 0.0,
                                                "top_p", 1.0,
                                                "num_predict", 256,
                                                "num_ctx", 4096));

                return webClient.post()
                                .uri(baseUrl + "/api/generate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .bodyValue(payload)
                                .retrieve()
                                .bodyToMono(OllamaGenerateResponse.class)
                                .map(response -> parseClassifiedIntent(response.response()))
                                .onErrorResume(ex -> {
                                        log.warn("Ollama intent classification failed, falling back to regex detector. error={}",
                                                        ex.getMessage(), ex);
                                        return Mono.just(fileVersionIntentDetector.detect(userMessage));
                                });
        }

        private FileVersionIntent parseClassifiedIntent(String raw) {
                if (raw == null || raw.isBlank()) {
                        return new FileVersionIntent(FileVersionIntentType.NONE, null, null, null, false, false, false);
                }

                try {
                        String trimmed = raw.trim();
                        int start = trimmed.indexOf('{');
                        int end = trimmed.lastIndexOf('}');
                        if (start >= 0 && end > start) {
                                trimmed = trimmed.substring(start, end + 1);
                        }

                        IntentClassifierResult result = objectMapper.readValue(trimmed, IntentClassifierResult.class);

                        FileVersionIntentType type;
                        try {
                                type = result.intent == null
                                                ? FileVersionIntentType.NONE
                                                : FileVersionIntentType.valueOf(
                                                                result.intent.trim().toUpperCase(Locale.ROOT));
                        } catch (Exception ex) {
                                type = FileVersionIntentType.NONE;
                        }

                        return new FileVersionIntent(
                                        type,
                                        blankToNull(result.fileName),
                                        result.leftVersion,
                                        result.rightVersion,
                                        Boolean.TRUE.equals(result.allVersionsRequested),
                                        Boolean.TRUE.equals(result.contentQuery),
                                        false);
                } catch (Exception ex) {
                        log.warn("Failed to parse Ollama classifier response, falling back to regex detector. raw={}",
                                        raw, ex);
                        return fileVersionIntentDetector.detect(raw);
                }
        }

        private String blankToNull(String value) {
                return value == null || value.isBlank() ? null : value.trim();
        }

        private ChatRequest withConversationId(ChatRequest request, String conversationId) {
                return new ChatRequest(
                                request.message(),
                                conversationId,
                                request.provider(),
                                request.model(),
                                request.fileIds());
        }

        private Flux<ChatStreamEvent> handleFileVersionIntent(ChatRequest request, UUID userId,
                        FileVersionIntent intent, String conversationId) {
                // Line 1: normalizedRequest declared here ↑↑↑
                ChatRequest normalizedRequest = withConversationId(request, conversationId);

                List<String> resolvedNames = resolveFileNames(intent.fileName(), userId, conversationId,
                                request.fileIds());

                // Cache logic...
                if (!resolvedNames.isEmpty()) {
                        conversationFileContext.put(conversationId, resolvedNames.get(0));
                }

                String resolvedFileName = resolvedNames.isEmpty() ? null : resolvedNames.get(0);

                if (resolvedFileName == null && conversationFileContext.containsKey(conversationId)) {
                        resolvedFileName = conversationFileContext.get(conversationId);
                        resolvedNames.add(resolvedFileName);
                }

                // Line 20+: normalizedRequest still in scope here ↓↓↓
                if (resolvedFileName == null || resolvedFileName.isBlank()) {
                        return singleMessageResponse(normalizedRequest, // ✅ Valid — declared at line 1
                                        "I couldn't identify which file you're referring to...");
                }

                try {
                        if (intent.type() == FileVersionIntentType.CONTENT_QUERY) {
                                return handleVersionContentQuery(normalizedRequest, userId, resolvedFileName,
                                                intent.allVersionsRequested(), intent.useOldestVersion());
                        }

                        // ✅ FIXED: LISTALLVERSIONS + content request
                        if (intent.type() == FileVersionIntentType.LIST_ALL_VERSIONS) {
                                if (messageWantsContent(request.message())) {
                                        // User wants versions + content → treat as content query for ALL versions
                                        return handleVersionContentQuery(normalizedRequest, userId, resolvedFileName,
                                                        true, intent.useOldestVersion());
                                } else {
                                        // Metadata only → wrap String in Flux
                                        return singleMessageResponse(normalizedRequest,
                                                        handleListAllVersions(userId, resolvedFileName));
                                }
                        }

                        // Other cases return String → wrap in Flux
                        String content = switch (intent.type()) {
                                case OLDER_VERSION, PREVIOUS_VERSION -> handleOlderVersion(userId, resolvedFileName);
                                case LATEST_FILENAME -> handleLatestFileName(userId, resolvedFileName);
                                case OLDER_FILENAMES -> handleOlderFileNames(userId, resolvedFileName);
                                case SPECIFIC_VERSION ->
                                        handleSpecificVersion(userId, resolvedFileName, intent.leftVersion());
                                case COMPARE_SELECTED_VERSIONS ->
                                        handleCompareSelectedVersions(userId, resolvedFileName,
                                                        intent.leftVersion(), intent.rightVersion());
                                default -> "I could not understand the version request.";
                        };

                        return singleMessageResponse(normalizedRequest, content);

                } catch (IllegalArgumentException ex) {
                        String displayFileName = intent.fileName() != null && !intent.fileName().isBlank()
                                        ? intent.fileName().trim()
                                        : resolvedFileName;
                        return singleMessageResponse(normalizedRequest,
                                        "I could not find version history for " + displayFileName);
                } catch (Exception ex) {
                        log.error("Version logic failed for fileName={}, error", resolvedFileName, ex.getMessage(), ex);
                        return singleMessageResponse(normalizedRequest,
                                        "I could not process the version request for file " + resolvedFileName);
                }
        }

        // NEW helper method
        private boolean messageWantsContent(String message) {
                if (message == null)
                        return false;
                String lower = message.toLowerCase(Locale.ROOT);
                return lower.contains("content") || lower.contains("extract") ||
                                lower.contains("summarize") || lower.contains("summary") ||
                                lower.contains("read") || lower.contains("mention") ||
                                lower.contains("what is in") || lower.contains("full text") ||
                                lower.contains("show me") || lower.contains("find") || lower.contains("tell me")
                                || lower.contains("describe") ||
                                lower.contains("what does the file says") || lower.contains("what is in this file");
        }

        private Flux<ChatStreamEvent> handleVersionContentQuery(
                        ChatRequest request, UUID userId, String resolvedFileName, boolean allVersionsRequested,
                        boolean useOldestVersion) {

                return prepareConversationReactive(userId, request)
                                .flatMap(conversationContext -> buildVersionContentStreamContext(conversationContext,
                                                request, resolvedFileName, allVersionsRequested, useOldestVersion))
                                .flatMapMany(ctx -> ctx.immediateResponse()
                                                ? immediateResponseFlow(ctx, request)
                                                : successStreamingFlow(ctx, request))
                                .onErrorResume(ex -> {
                                        log.warn("Version content query failed for fileName={}, error={}",
                                                        resolvedFileName, ex.getMessage(), ex);
                                        return singleMessageResponse(
                                                        request,
                                                        "I could not retrieve version-aware content for file "
                                                                        + resolvedFileName + ".");
                                });
        }

        private Mono<StreamContext> buildVersionContentStreamContext(
                        ConversationContext conversationContext,
                        ChatRequest request,
                        String resolvedFileName,
                        boolean allVersionsRequested,
                        boolean useOldestVersion) {
                List<String> resolvedNames = resolveFileNames(resolvedFileName, conversationContext.user().getId(),
                                conversationContext.conversation().getId().toString(), request.fileIds());
                String resolvedFileNameForSingleUse = resolvedNames.isEmpty() ? null : resolvedNames.get(0);
                if (resolvedFileNameForSingleUse != null) {
                        resolvedFileName = resolvedFileNameForSingleUse; // Use first for version logic
                }
                return userMemoryService.ingestFacts(conversationContext.user(), request.message())
                                .then(Mono.zip(
                                                Mono.just(conversationContext),
                                                userMemoryService.retrieveUserFacts(conversationContext.user(),
                                                                request.message()),
                                                retrieveVersionAwareFileDocs(
                                                                conversationContext.user(), request.message(),
                                                                resolvedFileName, allVersionsRequested,
                                                                useOldestVersion),
                                                loadConversationHistory(conversationContext)))
                                .map(tuple -> mapToStreamContext(
                                                tuple.getT1(),
                                                tuple.getT2(),
                                                tuple.getT3(),
                                                tuple.getT4(),
                                                true));
        }

        private Mono<List<Document>> retrieveVersionAwareFileDocs(
                        AppUserEntity user, String message, String resolvedFileName,
                        boolean allVersionsRequested, boolean useOldestVersion) {

                return Mono.fromCallable(
                                () -> fileVersionHistoryService.getVersionHistory(user.getId(), resolvedFileName))
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(history -> {
                                        List<String> attachmentIds = selectAttachmentIds(history, allVersionsRequested,
                                                        useOldestVersion);
                                        if (attachmentIds.isEmpty())
                                                return Mono.just(List.of());
                                        return fileContextRetrievalService
                                                        .retrieveForSpecificAttachmentIds(user, message, attachmentIds)
                                                        .defaultIfEmpty(List.of())
                                                        .map(docs -> enrichDocsWithVersionContext(history, docs,
                                                                        allVersionsRequested));
                                });
        }

        private List<String> selectAttachmentIds(
                        FileVersionHistoryService.VersionHistoryResult history, boolean allVersionsRequested,
                        boolean useOldestVersion) {

                if (allVersionsRequested) {
                        return history.versions().stream()
                                        .map(AttachmentEntity::getId)
                                        .filter(Objects::nonNull)
                                        .map(UUID::toString)
                                        .toList();
                }

                if (useOldestVersion) {
                        return history.versions().stream()
                                        .min(Comparator.comparingInt(
                                                        a -> a.getVersionNumber() != null ? a.getVersionNumber()
                                                                        : Integer.MAX_VALUE))
                                        .map(AttachmentEntity::getId).map(UUID::toString).stream().toList();
                }

                return history.versions().stream()
                                .filter(AttachmentEntity::isActive)
                                .findFirst()
                                .or(() -> history.versions().stream().findFirst())
                                .map(AttachmentEntity::getId)
                                .map(UUID::toString)
                                .stream()
                                .toList();
        }

        private List<Document> enrichDocsWithVersionContext(
                        FileVersionHistoryService.VersionHistoryResult history,
                        List<Document> docs,
                        boolean allVersionsRequested) {

                if (docs == null || docs.isEmpty()) {
                        return List.of();
                }

                Map<String, AttachmentEntity> versionsById = history.versions().stream()
                                .filter(v -> v.getId() != null)
                                .collect(Collectors.toMap(
                                                v -> v.getId().toString(),
                                                v -> v,
                                                (left, right) -> left,
                                                LinkedHashMap::new));

                List<Document> enriched = new ArrayList<>();
                for (Document doc : docs) {
                        Map<String, Object> metadata = doc.getMetadata() != null
                                        ? new LinkedHashMap<>(doc.getMetadata())
                                        : new LinkedHashMap<>();

                        String attachmentId = metadata.get("attachmentId") != null
                                        ? String.valueOf(metadata.get("attachmentId"))
                                        : null;

                        AttachmentEntity version = attachmentId != null ? versionsById.get(attachmentId) : null;
                        String text = doc.getText() == null ? "" : doc.getText().trim();
                        String enrichedText = buildVersionLabel(version, allVersionsRequested) + "\n" + text;
                        enriched.add(new Document(enrichedText, metadata));
                }

                return enriched;
        }

        private String buildVersionLabel(AttachmentEntity version, boolean allVersionsRequested) {
                if (version == null) {
                        return allVersionsRequested
                                        ? "Retrieved content from requested document versions"
                                        : "Retrieved content from the latest document version";
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Document version ")
                                .append(version.getVersionNumber() != null ? version.getVersionNumber() : "?")
                                .append(", fileName ")
                                .append(version.getFileName() != null ? version.getFileName() : "unknown");

                if (version.isActive()) {
                        sb.append(", active=true");
                }

                return sb.toString();
        }

        private String resolveConversationId(ChatRequest request) {
                if (request != null && request.conversationId() != null && !request.conversationId().isBlank()) {
                        return request.conversationId().trim();
                }
                return UUID.randomUUID().toString();
        }

        private List<String> resolveFileNames(String promptFileName, UUID userId, String conversationId,
                        List<String> requestFileIds) {

                List<String> resolvedNames = new ArrayList<>();

                // Priority 1: filename extracted directly from the user's message
                if (promptFileName != null && !promptFileName.isBlank()) {
                        // Basic hallucination check - real filenames usually have an extension
                        if (promptFileName.contains(".")) {
                                resolvedNames.add(promptFileName.trim());
                        } else {
                                log.warn("Discarded hallucinated file name from intent: {}", promptFileName);
                        }
                }

                // Priority 2: ALL explicitly attached fileIds
                if (requestFileIds != null && !requestFileIds.isEmpty()) {
                        List<UUID> ids = parseFileIds(requestFileIds);
                        if (!ids.isEmpty()) {
                                List<AttachmentEntity> attachments = attachmentRepository.findByUserIdAndIdIn(userId,
                                                ids);
                                for (AttachmentEntity a : attachments) {
                                        if (a.getFileName() != null && !a.getFileName().isBlank()) {
                                                resolvedNames.add(a.getFileName());
                                                log.info("Resolved fileId={} -> {}", a.getId(), a.getFileName());
                                        }
                                }
                        }
                }

                // Priority 3: single-file conversation fallback (IMPROVED)
                if (conversationId != null && !conversationId.isBlank() && resolvedNames.isEmpty()) {
                        try {
                                UUID convId = UUID.fromString(conversationId);
                                List<AttachmentEntity> conversationFiles = attachmentRepository
                                                .findByConversation_IdAndUser_IdAndActiveTrue(convId, userId);
                                List<String> uniqueConversationFileNames = conversationFiles.stream()
                                                .map(AttachmentEntity::getFileName)
                                                .filter(name -> name != null && !name.isBlank())
                                                .collect(Collectors.toMap(
                                                                name -> name.toLowerCase(Locale.ROOT),
                                                                name -> name,
                                                                (first, second) -> first,
                                                                LinkedHashMap::new))
                                                .values().stream().toList();

                                // ✅ FIXED: Always pick MOST RECENT active file, even if multiple
                                if (!uniqueConversationFileNames.isEmpty()) {
                                        // Pick the MOST RECENT active file from conversation
                                        Optional<AttachmentEntity> mostRecentActive = conversationFiles.stream()
                                                        .filter(AttachmentEntity::isActive)
                                                        .max(Comparator.comparing(AttachmentEntity::getUpdatedAt));

                                        if (mostRecentActive.isPresent()) {
                                                resolvedNames.add(mostRecentActive.get().getFileName());
                                                log.info("Auto-selected most recent conversation file: {}",
                                                                mostRecentActive.get().getFileName());
                                        } else {
                                                // Fallback to last in list
                                                resolvedNames.add(uniqueConversationFileNames
                                                                .get(uniqueConversationFileNames.size() - 1));
                                                log.info("Fallback to last conversation file: {}",
                                                                resolvedNames.get(0));
                                        }
                                        return resolvedNames;
                                }
                        } catch (Exception ex) {
                                log.warn("Unable to resolve conversation file for conversationId={}, userId={}",
                                                conversationId, userId, ex.getMessage());
                        }
                }
                log.info("Final resolved file names: {} for fileIds: {}", resolvedNames, requestFileIds);
                return resolvedNames.isEmpty() ? List.of() : resolvedNames;
        }

        private Mono<ConversationContext> prepareConversationReactive(UUID userId, ChatRequest request) {
                return Mono.fromCallable(() -> chatPersistenceService.prepareConversation(userId, request))
                                .subscribeOn(Schedulers.boundedElastic());
        }

        private Mono<StreamContext> resolveStreamContext(ConversationContext conversationContext, ChatRequest request) {
                if (!hasFileIds(request)) {
                        return Mono.fromCallable(() -> attachmentRepository
                                        .findByConversation_IdAndUser_IdAndActiveTrue(
                                                        conversationContext.conversation().getId(),
                                                        conversationContext.user().getId())
                                        .stream()
                                        .map(attachment -> attachment.getId().toString())
                                        .toList())
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .flatMap(fileIds -> buildRetrievalContext(
                                                        conversationContext,
                                                        request,
                                                        fileIds,
                                                        !fileIds.isEmpty())); // ✅ FIX: Only flag as file request if
                                                                              // files exist
                }

                return waitForAttachmentsAndBuildContext(conversationContext, request);
        }

        private Mono<StreamContext> waitForAttachmentsAndBuildContext(
                        ConversationContext conversationContext, ChatRequest request) {

                return Mono.defer(() -> Mono.fromCallable(() -> attachmentRepository.findByUserIdAndIdIn(
                                conversationContext.user().getId(), parseFileIds(request.fileIds())))
                                .subscribeOn(Schedulers.boundedElastic()))
                                .flatMap(attachments -> resolveAttachmentContext(conversationContext, attachments))
                                .repeatWhenEmpty(repeat -> repeat.delayElements(ATTACHMENT_POLL_DELAY)
                                                .take(MAX_ATTACHMENT_POLL_RETRIES))
                                .switchIfEmpty(Mono.just(new StreamContext(
                                                conversationContext,
                                                "Your file is still being processed. Please wait a few seconds and try again.",
                                                true,
                                                List.of())))
                                .flatMap(ctx -> {
                                        if (ctx.immediateResponse()
                                                        || !READY_FOR_RETRIEVAL.equals(ctx.systemContext())) {
                                                return Mono.just(ctx);
                                        }
                                        return buildRetrievalContext(conversationContext, request, request.fileIds(),
                                                        true);
                                });
        }

        private Mono<StreamContext> resolveAttachmentContext(
                        ConversationContext conversationContext, List<AttachmentEntity> attachments) {

                boolean anyFailed = attachments.stream()
                                .anyMatch(attachment -> FAILED_STATUS
                                                .equalsIgnoreCase(attachment.getProcessingStatus()));

                boolean anyProcessing = attachments.stream()
                                .anyMatch(attachment -> isInProgress(attachment.getProcessingStatus()));

                if (anyFailed) {
                        String failedNames = attachments.stream()
                                        .filter(attachment -> FAILED_STATUS
                                                        .equalsIgnoreCase(attachment.getProcessingStatus()))
                                        .map(AttachmentEntity::getFileName)
                                        .collect(Collectors.joining(", "));

                        return Mono.just(new StreamContext(
                                        conversationContext,
                                        "File upload received, but processing failed for " + failedNames
                                                        + ". Please retry upload or check embedding service.",
                                        true,
                                        List.of()));
                }

                if (anyProcessing) {
                        return Mono.empty();
                }

                return Mono.just(new StreamContext(conversationContext, READY_FOR_RETRIEVAL, false, List.of()));
        }

        private Mono<StreamContext> buildRetrievalContext(
                        ConversationContext conversationContext,
                        ChatRequest request,
                        List<String> fileIds,
                        boolean filesExplicitlyRequested) {

                // FIXED: Resolve ALL filenames first
                List<String> resolvedNames = resolveFileNames(request.message(), conversationContext.user().getId(),
                                conversationContext.conversation().getId().toString(), fileIds);

                return userMemoryService.ingestFacts(conversationContext.user(), request.message())
                                .then(Mono.zip(
                                                Mono.just(conversationContext),
                                                userMemoryService.retrieveUserFacts(conversationContext.user(),
                                                                request.message()),
                                                // FIXED: Pass ALL fileIds
                                                retrieveFileDocs(conversationContext.user(), request.message(),
                                                                fileIds),
                                                loadConversationHistory(conversationContext)))
                                .map(tuple -> mapToStreamContext(
                                                tuple.getT1(),
                                                tuple.getT2(),
                                                tuple.getT3(),
                                                tuple.getT4(),
                                                filesExplicitlyRequested))
                                .doOnNext(ctx -> log.info("Retrieved docs branch=file-branch, count={}, files={}",
                                                ctx.messages().size(), resolvedNames));
        }

        private Mono<List<ChatMessage>> loadConversationHistory(ConversationContext conversationContext) {
                return Mono.fromCallable(() -> conversationMemoryService.buildChatMessages(
                                conversationContext.conversation().getId(), null))
                                .subscribeOn(Schedulers.boundedElastic());
        }

        private Mono<List<Document>> retrieveFileDocs(AppUserEntity user, String message, List<String> fileIds) {
                if (!hasFileIds(fileIds)) {
                        return Mono.just(List.of());
                }

                return fileContextRetrievalService.retrieveForUserFiles(user, message, fileIds)
                                .doOnNext(docs -> log.info("FileContextRetrievalService returned {} docs for {} files",
                                                docs.size(), fileIds.size()))
                                .flatMap(docs -> {
                                        if (docs != null && !docs.isEmpty()) {
                                                return Mono.just(docs);
                                        }

                                        log.info("Vector search returned no results for fileIds={}, falling back to extractedText",
                                                        fileIds);
                                        return loadExtractedTextFallback(user, fileIds);
                                })
                                .defaultIfEmpty(List.of());
        }

        private Mono<List<Document>> loadExtractedTextFallback(AppUserEntity user, List<String> fileIds) {
                return Mono.fromCallable(() -> {
                        List<UUID> ids = parseFileIds(fileIds);
                        return attachmentRepository.findByUserIdAndIdIn(user.getId(), ids).stream()
                                        .filter(a -> a.getExtractedText() != null && !a.getExtractedText().isBlank())
                                        .map(a -> {
                                                Map<String, Object> metadata = new LinkedHashMap<>();
                                                metadata.put("attachmentId", a.getId().toString());
                                                metadata.put("fileName", a.getFileName());
                                                metadata.put("userId", user.getId().toString());
                                                metadata.put("fallback", true);
                                                return new Document(a.getExtractedText(), metadata);
                                        })
                                        .toList();
                })
                                .subscribeOn(Schedulers.boundedElastic());
        }

        private StreamContext mapToStreamContext(
                        ConversationContext conversationContext,
                        List<Document> userFacts,
                        List<Document> fileDocs,
                        List<ChatMessage> conversationHistory,
                        boolean filesExplicitlyRequested) {

                logRetrievedDocs(filesExplicitlyRequested ? "file-branch" : "conversation-branch", fileDocs);

                // ✅ BULLETPROOF IMAGE CHECK: Checks file extensions in case the browser hides
                // the content-type
                boolean hasImages = attachmentRepository
                                .findByConversation_IdAndUser_IdAndActiveTrue(
                                                conversationContext.conversation().getId(),
                                                conversationContext.user().getId())
                                .stream()
                                .anyMatch(a -> {
                                        String fn = a.getFileName() != null ? a.getFileName().toLowerCase() : "";
                                        String ct = a.getContentType() != null ? a.getContentType().toLowerCase() : "";
                                        return ct.startsWith("image/") || fn.endsWith(".png") || fn.endsWith(".jpg")
                                                        || fn.endsWith(".jpeg") || fn.endsWith(".webp")
                                                        || fn.endsWith(".gif");
                                });

                // Only throw the missing text error if there are NO images in the chat
                if (filesExplicitlyRequested && !hasImages && (fileDocs == null || fileDocs.isEmpty())) {
                        return new StreamContext(
                                        conversationContext,
                                        "The file was uploaded and processed, but no readable text could be extracted from it. "
                                                        + "Please check the file content and try again.",
                                        true,
                                        List.of());
                }

                List<ChatMessage> finalMessages = buildOllamaMessages(
                                userFacts,
                                fileDocs,
                                conversationHistory,
                                conversationContext);

                return new StreamContext(conversationContext, "", false, finalMessages);
        }

        private Flux<ChatStreamEvent> successStreamingFlow(StreamContext ctx, ChatRequest request) {
                String conversationId = ctx.conversationContext().conversation().getId().toString();
                String baseUrl = normalizeBaseUrl(ollamaBaseUrl);

                List<Map<String, Object>> messages = new ArrayList<>();
                for (ChatMessage m : ctx.messages()) {
                        if (m.content() == null || m.content().isBlank())
                                continue;
                        Map<String, Object> msg = new java.util.HashMap<>();
                        msg.put("role", m.role());
                        msg.put("content", truncate(m.content(), MAX_SYSTEM_CONTEXT_LENGTH));
                        messages.add(msg);
                }

                String userMessageText = request.message() != null ? request.message() : "";
                Map<String, Object> userPayload = new java.util.HashMap<>();
                userPayload.put("role", "user");
                userPayload.put("content", userMessageText);

                // ✅ Grab images from current request OR conversation history
                List<AttachmentEntity> attachments;
                if (hasFileIds(request)) {
                        List<UUID> fileUuids = parseFileIds(request.fileIds());
                        attachments = attachmentRepository.findByUserIdAndIdIn(
                                        ctx.conversationContext().user().getId(), fileUuids);
                } else {
                        attachments = attachmentRepository.findByConversation_IdAndUser_IdAndActiveTrue(
                                        ctx.conversationContext().conversation().getId(),
                                        ctx.conversationContext().user().getId());
                }

                List<String> base64Images = new ArrayList<>();
                boolean hasNoTextImages = false;
                for (AttachmentEntity attachment : attachments) {
                        String fn = attachment.getFileName() != null ? attachment.getFileName().toLowerCase() : "";
                        String ct = attachment.getContentType() != null ? attachment.getContentType().toLowerCase()
                                        : "";

                        if (ct.startsWith("image/") || fn.endsWith(".png") || fn.endsWith(".jpg")
                                        || fn.endsWith(".jpeg") || fn.endsWith(".webp") || fn.endsWith(".gif")) {

                                // ✅ Track NO_TEXT images
                                if ("NO_TEXT".equals(attachment.getProcessingStatus())) {
                                        hasNoTextImages = true;
                                }

                                try {
                                        byte[] imageBytes = Files.readAllBytes(Path.of(attachment.getStoragePath()));
                                        base64Images.add(Base64.getEncoder().encodeToString(imageBytes));
                                        log.info("Successfully encoded image for Vision API: {}",
                                                        attachment.getFileName());
                                } catch (Exception e) {
                                        log.error("Failed to read image for vision prompt: {}",
                                                        attachment.getFileName(), e);
                                }
                        }
                }

                boolean hasActualTextContent = !hasNoTextImages
                                && ctx.messages().stream()
                                                .anyMatch(m -> "system".equals(m.role())
                                                                && m.content() != null
                                                                && m.content().length() > 100 // Actual content, not
                                                                                              // metadata
                                                                && !m.content().contains("length=0")
                                                                && !m.content().contains("NO_TEXT"));

                // ✅ Skip Vision ONLY if no NO_TEXT images AND actual text exists
                if (!base64Images.isEmpty() && hasActualTextContent) {
                        log.info("Actual OCR text found — skipping Vision API");
                        base64Images.clear();
                } else if (!base64Images.isEmpty() && hasNoTextImages) {
                        log.info("NO_TEXT image detected — forcing Vision API");
                        // Keep base64Images for vision
                }

                // ✅ Attach images to userPayload
                if (!base64Images.isEmpty()) {
                        userPayload.put("images", base64Images);
                }

                messages.add(userPayload);

                // ✅ Route model: Change the hardcoded fallback to llava:7b
                String activeModel = !base64Images.isEmpty() ? "llava:7b"
                                : request.model() != null && !request.model().isBlank()
                                                ? request.model()
                                                : ollamaModel;

                // ✅ Dynamically lower the context window for vision tasks to prevent OOM
                int dynamicNumCtx = !base64Images.isEmpty() ? 4096 : 8192;

                log.info("Routing chat to model: {} (vision={}, ocr={}, noTextImages={})",
                                activeModel, !base64Images.isEmpty(), hasActualTextContent, hasNoTextImages);

                Map<String, Object> payload = Map.of(
                                "model", activeModel,
                                "messages", messages,
                                "stream", true,
                                "options", Map.of(
                                                "temperature", temperature,
                                                "top_p", 0.9,
                                                "num_predict", 4096,
                                                "num_ctx", dynamicNumCtx // Use the dynamic context here
                                ));

                Flux<ChatStreamEvent> startEvent = Flux.just(ChatStreamEvent.builder()
                                .type("start")
                                .conversationId(conversationId)
                                .content("")
                                .sequence(0)
                                .done(false)
                                .build());

                StringBuilder assistantBuffer = new StringBuilder();
                StringBuilder partialBuffer = new StringBuilder();

                Flux<ChatStreamEvent> tokenStream = webClient.post()
                                .uri(baseUrl + "/api/chat")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .accept(org.springframework.http.MediaType.parseMediaType("application/x-ndjson"),
                                                org.springframework.http.MediaType.APPLICATION_JSON,
                                                org.springframework.http.MediaType.TEXT_PLAIN)
                                .bodyValue(payload)
                                .retrieve()
                                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                                .flatMap(dataBuffer -> extractNdjsonLines(dataBuffer, partialBuffer))
                                .concatWith(Flux.defer(() -> flushRemainingPartialLine(partialBuffer).flux()))
                                .flatMap(line -> parseOllamaChatLine(line, conversationId))
                                .doOnNext(event -> {
                                        if ("token".equals(event.type()) && event.content() != null) {
                                                assistantBuffer.append(event.content());
                                        }
                                })
                                .timeout(STREAM_TIMEOUT);

                return Flux.concat(
                                startEvent,
                                tokenStream,
                                Mono.defer(() -> {
                                        String raw = assistantBuffer.toString();

                                        if (isGarbageModelResponse(raw)) {
                                                raw = "I could not interpret the image content. " +
                                                                "Please try asking a more specific question about the file.";
                                        }

                                        String normalized = normalizeSpacing(raw);
                                        if (raw.isBlank() || raw.isEmpty()) {
                                                return Mono.just(ChatStreamEvent.builder()
                                                                .type("complete")
                                                                .conversationId(conversationId)
                                                                .content("")
                                                                .sequence(Integer.MAX_VALUE)
                                                                .done(true)
                                                                .build());
                                        }
                                        return Mono.fromCallable(() -> {
                                                chatPersistenceService.persistAssistantMessage(
                                                                ctx.conversationContext(), normalized, request);
                                                return ChatStreamEvent.builder()
                                                                .type("complete")
                                                                .conversationId(conversationId)
                                                                .content("")
                                                                .sequence(Integer.MAX_VALUE)
                                                                .done(true)
                                                                .build();
                                        }).subscribeOn(Schedulers.boundedElastic());
                                }))
                                .onErrorResume(ex -> {
                                        log.error("Ollama chat stream failed conversationId={}", conversationId, ex);
                                        return Flux.just(ChatStreamEvent.builder()
                                                        .type("error")
                                                        .conversationId(conversationId)
                                                        .content("Ollama call failed: " + ex.getMessage())
                                                        .sequence(-1)
                                                        .done(true)
                                                        .build());
                                })
                                .doFinally(signalType -> log.info("Chat stream finished conversationId={}, signal={}",
                                                conversationId, signalType));
        }

        private boolean isGarbageModelResponse(String text) {
                if (text == null || text.isBlank())
                        return false;
                String trimmed = text.trim();
                // Detect responses that are purely repeated special chars
                return trimmed.matches("[#\\-_=\\*~>|\\s]{5,}") ||
                                trimmed.chars().distinct().count() <= 2; // e.g. only '#' and ' '
        }

        private List<ChatMessage> buildOllamaMessages(
                        List<Document> userFacts,
                        List<Document> fileDocs,
                        List<ChatMessage> conversationHistory,
                        ConversationContext conversationContext) {

                List<ChatMessage> messages = new ArrayList<>();

                messages.add(new ChatMessage("system",
                                """
                                                You are a grounded conversational assistant for an authenticated user.

                                                Rules:
                                                1. Use recent conversation history to answer follow-up questions naturally.
                                                2. If the user changes topic suddenly, answer the new question normally using your general knowledge.
                                                3. CRITICAL: If a system message contains 'UPLOADED FILE CONTENT', that IS the file. Read it and answer directly from it. NEVER say the file is missing or not uploaded.
                                                4. Do not invent file contents beyond what is provided. If context is truly empty, say so.
                                                5. You may return exact text, values, phone numbers, emails, or personal data from the user's own uploaded files.
                                                6. Never mention version numbers, old versions, other versions, or version history unless the user explicitly asks about versions or history.
                                                7. Give direct, clean answers. No meta-commentary about hidden system behavior.
                                                """));

                String contextBlock = buildContextBlock(userFacts, fileDocs);
                if (!contextBlock.isBlank()) {
                        messages.add(new ChatMessage("system", contextBlock));
                }

                if (conversationHistory != null && !conversationHistory.isEmpty()) {
                        messages.addAll(conversationHistory);
                }

                return messages;
        }

        private String buildContextBlock(List<Document> userFacts, List<Document> fileDocs) {
                List<String> blocks = new ArrayList<>();

                if (userFacts != null && !userFacts.isEmpty()) {
                        String factText = userFacts.stream()
                                        .map(Document::getText)
                                        .filter(text -> text != null && !text.isBlank())
                                        .limit(5)
                                        .map(text -> "- " + truncate(text, MAX_USER_FACT_LENGTH))
                                        .reduce((a, b) -> a + "\n" + b)
                                        .orElse("");

                        if (!factText.isBlank()) {
                                blocks.add("Known user facts:\n" + factText);
                        }
                }

                if (fileDocs != null && !fileDocs.isEmpty()) {
                        String docText = fileDocs.stream()
                                        .map(Document::getText)
                                        .filter(text -> text != null && !text.isBlank())
                                        .limit(8)
                                        .map(text -> truncate(text, MAX_FILE_DOC_LENGTH))
                                        .reduce((a, b) -> a + "\n---\n" + b)
                                        .orElse("");

                        if (!docText.isBlank()) {
                                blocks.add("UPLOADED FILE CONTENT (answer ONLY from this):\n"
                                                + docText
                                                + "\n\nDo NOT say the file is missing. The above text IS the file content. Use it directly to answer the user.");
                        }
                }

                if (blocks.isEmpty()) {
                        return "";
                }

                return "Use the following retrieved context when relevant:\n\n" + String.join("\n\n", blocks);
        }

        private Flux<ChatStreamEvent> immediateResponseFlow(StreamContext ctx, ChatRequest request) {
                String conversationId = ctx.conversationContext().conversation().getId().toString();
                String content = ctx.systemContext();

                return Mono.fromRunnable(() -> chatPersistenceService.persistAssistantMessage(ctx.conversationContext(),
                                content, request))
                                .subscribeOn(Schedulers.boundedElastic())
                                .thenMany(immediateMessage(conversationId, content));
        }

        private String normalizeSpacing(String text) {
                if (text == null || text.isBlank())
                        return text;
                return text
                                .replaceAll(":([^\\s\\n])", ": $1") // "is:9752" → "is: 9752"
                                .replaceAll("([a-zA-Z])([0-9])", "$1 $2") // "still9752" → "still 9752"
                                .replaceAll("([0-9])([a-zA-Z])", "$1 $2"); // "9752is" → "9752 is"
        }

        private Flux<String> extractNdjsonLines(DataBuffer dataBuffer, StringBuilder partialBuffer) {
                try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        String chunk = new String(bytes, StandardCharsets.UTF_8);
                        partialBuffer.append(chunk);

                        List<String> lines = new ArrayList<>();
                        int newlineIndex;
                        while ((newlineIndex = partialBuffer.indexOf("\n")) >= 0) {
                                String line = partialBuffer.substring(0, newlineIndex).trim();
                                partialBuffer.delete(0, newlineIndex + 1);
                                if (!line.isBlank()) {
                                        lines.add(line);
                                }
                        }

                        return Flux.fromIterable(lines);
                } finally {
                        DataBufferUtils.release(dataBuffer);
                }
        }

        private Mono<String> flushRemainingPartialLine(StringBuilder partialBuffer) {
                String remaining = partialBuffer.toString().trim();
                partialBuffer.setLength(0);
                return remaining.isBlank() ? Mono.empty() : Mono.just(remaining);
        }

        private Flux<ChatStreamEvent> parseOllamaChatLine(String line, String conversationId) {
                if (line == null || line.isBlank()) {
                        return Flux.empty();
                }

                try {
                        OllamaChatChunk chunk = objectMapper.readValue(line, OllamaChatChunk.class);
                        if (Boolean.TRUE.equals(chunk.done())) {
                                return Flux.empty();
                        }

                        String content = chunk.message() != null ? chunk.message().content() : "";
                        if (content == null || content.isBlank()) {
                                return Flux.empty();
                        }

                        return Flux.just(ChatStreamEvent.builder()
                                        .type("token")
                                        .conversationId(conversationId)
                                        .content(content)
                                        .sequence(0)
                                        .done(false)
                                        .build());
                } catch (Exception e) {
                        log.warn("Failed to parse Ollama /api/chat NDJSON line conversationId={}, line={}",
                                        conversationId, line, e);
                        return Flux.empty();
                }
        }

        private Flux<ChatStreamEvent> immediateMessage(String conversationId, String content) {
                return Flux.just(
                                ChatStreamEvent.builder()
                                                .type("start")
                                                .conversationId(conversationId)
                                                .content("")
                                                .sequence(0)
                                                .done(false)
                                                .build(),
                                ChatStreamEvent.builder()
                                                .type("token")
                                                .conversationId(conversationId)
                                                .content(content)
                                                .sequence(1)
                                                .done(false)
                                                .build(),
                                ChatStreamEvent.builder()
                                                .type("complete")
                                                .conversationId(conversationId)
                                                .content("")
                                                .sequence(Integer.MAX_VALUE)
                                                .done(true)
                                                .build());
        }

        private boolean hasFileIds(ChatRequest request) {
                return request != null && hasFileIds(request.fileIds());
        }

        private boolean hasFileIds(List<String> fileIds) {
                return fileIds != null && fileIds.stream().anyMatch(id -> id != null && !id.isBlank());
        }

        private List<UUID> parseFileIds(List<String> fileIds) {
                if (fileIds == null || fileIds.isEmpty()) {
                        return List.of();
                }

                return fileIds.stream()
                                .filter(id -> id != null && !id.isBlank())
                                .map(id -> {
                                        try {
                                                return UUID.fromString(id);
                                        } catch (Exception ex) {
                                                log.warn("Ignoring invalid fileId={}", id);
                                                return null;
                                        }
                                })
                                .filter(Objects::nonNull)
                                .toList();
        }

        private boolean isInProgress(String status) {
                if (status == null) {
                        return false;
                }
                return IN_PROGRESS_STATUSES.stream()
                                .anyMatch(candidate -> candidate.equalsIgnoreCase(status));
        }

        private void logRetrievedDocs(String branch, List<Document> docs) {
                int size = docs != null ? docs.size() : 0;
                log.info("Retrieved docs branch={}, count={}", branch, size);

                if (docs != null) {
                        for (int i = 0; i < Math.min(docs.size(), 3); i++) {
                                Document doc = docs.get(i);
                                String text = doc != null ? doc.getText() : null;
                                log.info("Retrieved doc branch={}, index={}, preview={}",
                                                branch, i, truncate(text, MAX_DOC_PREVIEW_LENGTH));
                        }
                }
        }

        private String truncate(String value, int maxLen) {
                if (value == null) {
                        return "";
                }
                return value.length() <= maxLen ? value : value.substring(0, maxLen);
        }

        private String normalizeBaseUrl(String baseUrl) {
                if (baseUrl == null || baseUrl.isBlank()) {
                        return "http://localhost:11434";
                }
                return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }

        private String handleOlderVersion(UUID userId, String fileName) {
                FileVersionHistoryService.VersionHistoryResult history = fileVersionHistoryService
                                .getVersionHistory(userId, fileName);

                if (history.versions().size() < 2) {
                        return "I found the file family matched to " + history.matchedFileName()
                                        + ", but there is no older version available. Latest file name is "
                                        + history.latestFileName() + ".";
                }

                AttachmentEntity older = history.versions().stream()
                                .filter(v -> !v.isActive())
                                .findFirst()
                                .orElse(history.versions().get(1));

                return "I found an older version for the file family matched to " + history.matchedFileName()
                                + ". Latest file name is " + history.latestFileName()
                                + " version " + history.latestVersionNumber()
                                + ". Older version file name is " + older.getFileName()
                                + " version " + older.getVersionNumber() + ".";
        }

        private String handleListAllVersions(UUID userId, String fileName) {
                FileVersionHistoryService.VersionHistoryResult history = fileVersionHistoryService
                                .getVersionHistory(userId, fileName);

                StringBuilder sb = new StringBuilder();
                sb.append("I found ").append(history.versions().size())
                                .append(" versions for the file family matched to ")
                                .append(history.matchedFileName()).append(". ")
                                .append("Latest file name is ")
                                .append(history.latestFileName()).append(" version ")
                                .append(history.latestVersionNumber()).append(". ")
                                .append("Full version chain:\n");

                for (AttachmentEntity version : history.versions()) {
                        sb.append("- version ").append(version.getVersionNumber())
                                        .append(": ").append(version.getFileName());
                        if (version.isActive()) {
                                sb.append(" (active)");
                        }
                        sb.append("\n");
                }

                return sb.toString().trim();
        }

        private String handleLatestFileName(UUID userId, String fileName) {
                FileVersionHistoryService.VersionHistoryResult history = fileVersionHistoryService
                                .getVersionHistory(userId, fileName);

                return "The latest file name in this document family is "
                                + history.latestFileName()
                                + " and its version number is "
                                + history.latestVersionNumber() + ".";
        }

        private String handleOlderFileNames(UUID userId, String fileName) {
                FileVersionHistoryService.VersionHistoryResult history = fileVersionHistoryService
                                .getVersionHistory(userId, fileName);

                if (history.olderFileNames().isEmpty()) {
                        return "I found the file family matched to " + history.matchedFileName()
                                        + ", but there are no older file names recorded.";
                }

                StringBuilder sb = new StringBuilder("Older file names for the same document family:\n");
                for (String olderName : history.olderFileNames()) {
                        sb.append("- ").append(olderName).append("\n");
                }
                return sb.toString().trim();
        }

        private String handleSpecificVersion(UUID userId, String fileName, Integer versionNumber) {
                if (versionNumber == null) {
                        throw new IllegalArgumentException("Version number is missing from your request.");
                }

                AttachmentEntity version = fileVersionHistoryService.getSpecificVersion(userId, fileName,
                                versionNumber);
                return "I found version " + version.getVersionNumber()
                                + " for the requested file family. File name is "
                                + version.getFileName()
                                + ". Processing status is " + version.getProcessingStatus()
                                + ". Active=" + version.isActive() + ".";
        }

        private String handleCompareSelectedVersions(
                        UUID userId, String fileName, Integer leftVersion, Integer rightVersion) {

                if (leftVersion == null || rightVersion == null) {
                        throw new IllegalArgumentException(
                                        "Two version numbers are required for comparison. For example compare version 1 and version 2 of myfile.pdf.");
                }

                FileVersionHistoryService.VersionComparisonResult comparison = fileVersionHistoryService
                                .compareVersions(userId, fileName, leftVersion, rightVersion);

                StringBuilder sb = new StringBuilder();
                sb.append("Comparison between version ")
                                .append(comparison.left().getVersionNumber())
                                .append(" and version ")
                                .append(comparison.right().getVersionNumber())
                                .append(". ")
                                .append("Left v").append(comparison.left().getVersionNumber())
                                .append(" - ").append(comparison.left().getFileName())
                                .append(". Right v").append(comparison.right().getVersionNumber())
                                .append(" - ").append(comparison.right().getFileName())
                                .append(". ");

                List<String> differences = comparison.differences();
                if (differences.isEmpty()) {
                        sb.append("No differences detected between these two versions.");
                } else {
                        sb.append("Differences found:\n");
                        for (String diff : differences) {
                                sb.append("- ").append(diff).append("\n");
                        }
                }

                return sb.toString().trim();
        }

        private Flux<ChatStreamEvent> singleMessageResponse(ChatRequest request, String content) {
                String conversationId = resolveConversationId(request);
                return Flux.just(
                                ChatStreamEvent.builder()
                                                .type("start")
                                                .conversationId(conversationId)
                                                .content("")
                                                .sequence(0)
                                                .done(false)
                                                .build(),
                                ChatStreamEvent.builder()
                                                .type("token")
                                                .conversationId(conversationId)
                                                .content(content)
                                                .sequence(1)
                                                .done(false)
                                                .build(),
                                ChatStreamEvent.builder()
                                                .type("complete")
                                                .conversationId(conversationId)
                                                .content("")
                                                .sequence(Integer.MAX_VALUE)
                                                .done(true)
                                                .build());
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record OllamaGenerateResponse(String response) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static class IntentClassifierResult {
                public String intent;
                public String fileName;

                @JsonDeserialize(using = LenientIntegerDeserializer.class)
                public Integer leftVersion;

                @JsonDeserialize(using = LenientIntegerDeserializer.class)
                public Integer rightVersion;

                public Boolean allVersionsRequested;
                public Boolean contentQuery;
                // public Boolean useOldestVersion;
        }

        private static class LenientIntegerDeserializer
                        extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
                @Override
                public Integer deserialize(com.fasterxml.jackson.core.JsonParser p,
                                com.fasterxml.jackson.databind.DeserializationContext ctx)
                                throws java.io.IOException {
                        String value = p.getText();
                        try {
                                return Integer.parseInt(value.trim());
                        } catch (NumberFormatException e) {
                                return null; // "old", "latest", etc. → just return null
                        }
                }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record OllamaChatChunk(MessagePayload message, Boolean done) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record MessagePayload(String role, String content) {
        }

        private record StreamContext(
                        ConversationContext conversationContext,
                        String systemContext,
                        boolean immediateResponse,
                        List<ChatMessage> messages) {
        }
}