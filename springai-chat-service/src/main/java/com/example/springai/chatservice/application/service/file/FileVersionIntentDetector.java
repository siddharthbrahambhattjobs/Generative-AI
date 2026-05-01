package com.example.springai.chatservice.application.service.file;

import com.example.springai.chatservice.infrastructure.ingestion.FileTypePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FileVersionIntentDetector {

    private final ChatClient chatClient;

    // ✅ Dynamically builds extensions from your Policy
    private static final String EXTENSIONS = String.join("|", FileTypePolicy.SUPPORTED_EXTENSIONS);

    private static final Pattern QUOTED_FILE_PATTERN = Pattern.compile(
            "\"([^\"]+\\.(" + EXTENSIONS + "))\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_AFTER_KEYWORD_PATTERN = Pattern.compile(
            "(?:from|in|of)\\s+file\\s+([\\w .()\\-]+\\.(" + EXTENSIONS + "))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile(
            "([\\w .()\\-]+\\.(" + EXTENSIONS + "))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile(
            "\\bversion\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPARE_TWO_VERSIONS_PATTERN = Pattern.compile(
            "\\bcompare\\s+version\\s+(\\d+)\\s+(?:and|with|to)\\s+version\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    // ✅ Constructor Injection for Spring AI ChatClient
    public FileVersionIntentDetector(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public FileVersionIntent detect(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        // ─── 1. FAST-FAIL GUARDS ──────────────────────────────────────────────────
        if (isConversationHistoryRequest(lower)) {
            return new FileVersionIntent(FileVersionIntentType.NONE, null, null, null, false, false, false);
        }

        String fileName = extractFileName(text);
        boolean allVersionsRequested = requestsAllVersions(lower);
        boolean contentQuery = requestsContent(lower);

        // ─── 2. FAST PROGRAMMATIC ROUTING (Regex & Keyword) ───────────────────────

        Matcher compareMatcher = COMPARE_TWO_VERSIONS_PATTERN.matcher(lower);
        if (compareMatcher.find()) {
            return new FileVersionIntent(
                    FileVersionIntentType.COMPARE_SELECTED_VERSIONS,
                    fileName, parseInt(compareMatcher.group(1)), parseInt(compareMatcher.group(2)),
                    false, false, false);
        }

        if (containsAny(lower, "latest file name", "latest filename", "current file name")) {
            return new FileVersionIntent(FileVersionIntentType.LATEST_FILENAME, fileName, null, null, false, false,
                    false);
        }

        if (containsAny(lower, "older file names", "old file names", "previous file names")) {
            return new FileVersionIntent(FileVersionIntentType.OLDER_FILENAMES, fileName, null, null, false, false,
                    false);
        }

        if (containsAny(lower, "oldest", "earliest")
                && (contentQuery || containsAny(lower, "find", "get", "show", "tell me", "give me", "what is",
                        "what are", "retrieve", "fetch"))) {
            return new FileVersionIntent(FileVersionIntentType.CONTENT_QUERY, fileName, null, null, false, true, true);
        }

        if (containsAny(lower, "old version", "older version", "previous version", "first version") && !contentQuery) {
            return new FileVersionIntent(FileVersionIntentType.OLDER_VERSION, fileName, null, null, false, false,
                    false);
        }

        Matcher singleVersionMatcher = VERSION_NUMBER_PATTERN.matcher(lower);
        if (singleVersionMatcher.find() && containsAny(lower, "show", "give", "provide", "get")) {
            return new FileVersionIntent(
                    FileVersionIntentType.SPECIFIC_VERSION,
                    fileName, parseInt(singleVersionMatcher.group(1)), null, false, false, false);
        }

        if (containsAny(lower, "list all versions", "version history", "different versions",
                "available all versions")) {
            if (contentQuery || messageWantsContent(lower)) {
                return new FileVersionIntent(FileVersionIntentType.CONTENT_QUERY, fileName, null, null, true, true,
                        false);
            }
            return new FileVersionIntent(FileVersionIntentType.LIST_ALL_VERSIONS, fileName, null, null, false, false,
                    false);
        }

        if (fileName != null && (allVersionsRequested || contentQuery)) {
            return new FileVersionIntent(FileVersionIntentType.CONTENT_QUERY, fileName, null, null,
                    allVersionsRequested, true, false);
        }

        // ─── 3. AI SEMANTIC FALLBACK (If keywords failed) ─────────────────────────
        if (!text.isBlank()) {
            try {
                return detectWithAi(text, fileName);
            } catch (Exception ex) {
                // If the LLM call fails (e.g., timeout), gracefully fallback to NONE
                // Log this exception in your actual code: log.error("AI Intent mapping failed",
                // ex);
            }
        }

        return new FileVersionIntent(FileVersionIntentType.NONE, fileName, null, null, false, false, false);
    }

    // ─── AI Processing Method ─────────────────────────────────────────────────

    private FileVersionIntent detectWithAi(String userPrompt, String extractedFileName) {
        BeanOutputConverter<FileVersionIntent> converter = new BeanOutputConverter<>(FileVersionIntent.class);

        String systemPrompt = """
                You are a semantic routing assistant for a file versioning system.
                Determine the user's intent based on their prompt and map it to exactly one intent type.

                Intent Types:
                - CONTENT_QUERY: Wants to read/extract the text content of a file.
                - OLDER_VERSION: Wants metadata/details about older versions.
                - LIST_ALL_VERSIONS: Wants to see the full version history.
                - LATEST_FILENAME: Wants to know the name of the most recent file.
                - OLDER_FILENAMES: Wants to know the names of past files.
                - SPECIFIC_VERSION: Mentions a specific numeric version to view.
                - COMPARE_SELECTED_VERSIONS: Wants to compare two specific versions.
                - NONE: Does not match any file intent.

                Mapping Rules:
                1. If they ask for the "oldest", "earliest", or "first" file's content, set useOldestVersion to true.
                2. If they ask to see "all versions" of the content, set allVersionsRequested to true.
                3. If they ask for the "newest", "latest", or "current" file content, it is just a CONTENT_QUERY.
                4. Extract the filename. If missing, use the provided fallback: {fallbackFileName}

                {format}
                """;

        return chatClient.prompt()
                .system(sp -> sp.text(systemPrompt)
                        .param("fallbackFileName", extractedFileName != null ? extractedFileName : "null")
                        .param("format", converter.getFormat()))
                .user(userPrompt)
                .call()
                .entity(converter);
    }

    // ─── Guard Methods ────────────────────────────────────────────────────────

    private boolean isConversationHistoryRequest(String lower) {
        return containsAny(lower, "in the conversation", "this conversation", "my questions", "questions i have asked",
                "questions i asked", "chat history", "past messages", "previous messages", "what did i ask",
                "what was my first question", "what was my last question");
    }

    private boolean requestsAllVersions(String lower) {
        return containsAny(lower, "all versions", "every version", "all version", "all 3 versions", "all 2 versions",
                "across versions", "across all versions", "from all versions", "from every version", "in all versions",
                "in every version", "mention content", "content of file", "show content", "include content");
    }

    private boolean requestsContent(String lower) {
        return containsAny(lower, "what is in", "what are in", "extract content", "extract text", "content of",
                "inside file", "give me the content", "show me the content", "read the file", "full content",
                "all content", "summarize this file", "give me details about this file", "give me content of this file",
                "find my", "find the", "what is my", "what are my", "tell me my", "tell me the", "mobile number",
                "phone number", "all mobile numbers", "all phone numbers", "all email", "all data", "all records",
                "mention content", "content of file", "show content", "include content");
    }

    private boolean messageWantsContent(String lower) {
        return containsAny(lower, "content", "extract", "summarize", "summary", "read", "mention", "what is in",
                "full text", "show me", "tell me", "give me", "find");
    }

    // ─── File Name Extraction ────────────────────────────────────────────────

    private String extractFileName(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        Matcher quotedMatcher = QUOTED_FILE_PATTERN.matcher(prompt);
        if (quotedMatcher.find())
            return quotedMatcher.group(1).trim();

        Matcher keywordMatcher = FILE_AFTER_KEYWORD_PATTERN.matcher(prompt);
        if (keywordMatcher.find())
            return keywordMatcher.group(1).trim();

        Matcher extMatcher = FILE_EXTENSION_PATTERN.matcher(prompt);
        String lastMatch = null;
        while (extMatcher.find())
            lastMatch = extMatcher.group(1);

        return lastMatch != null ? lastMatch.trim() : null;
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase))
                return true;
        }
        return false;
    }

    private Integer parseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (Exception ex) {
            return null;
        }
    }

    // ─── Data Types ───────────────────────────────────────────────────────────

    public record FileVersionIntent(
            FileVersionIntentType type,
            String fileName,
            Integer leftVersion,
            Integer rightVersion,
            boolean allVersionsRequested,
            boolean contentQuery,
            boolean useOldestVersion) {
    }

    public enum FileVersionIntentType {
        NONE,
        CONTENT_QUERY,
        OLDER_VERSION,
        PREVIOUS_VERSION,
        LIST_ALL_VERSIONS,
        LATEST_FILENAME,
        OLDER_FILENAMES,
        SPECIFIC_VERSION,
        COMPARE_SELECTED_VERSIONS
    }
}