package com.example.springai.chatservice.application.service.file;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FileVersionIntentDetector {

    private static final Pattern QUOTED_FILE_PATTERN = Pattern.compile(
            "\"([^\"]+\\.(txt|md|pdf|json|html|css|java|xml|yaml|yml|properties|csv|docx|xlsx|xls|pptx))\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_AFTER_KEYWORD_PATTERN = Pattern.compile(
            "(?:from|in|of)\\s+file\\s+([\\w .()\\-]+\\.(txt|md|pdf|json|html|css|java|xml|yaml|yml|properties|csv|docx|xlsx|xls|pptx))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FILE_EXTENSION_PATTERN = Pattern.compile(
            "([\\w .()\\-]+\\.(txt|md|pdf|json|html|css|java|xml|yaml|yml|properties|csv|docx|xlsx|xls|pptx))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile(
            "\\bversion\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPARE_TWO_VERSIONS_PATTERN = Pattern.compile(
            "\\bcompare\\s+version\\s+(\\d+)\\s+(?:and|with|to)\\s+version\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    public FileVersionIntent detect(String prompt) {
        String text = prompt == null ? "" : prompt.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        // Fast-fail: conversation/chat history is never a file intent
        if (isConversationHistoryRequest(lower)) {
            return new FileVersionIntent(FileVersionIntentType.NONE, null, null, null, false, false, false);
        }

        String fileName = extractFileName(text);
        boolean allVersionsRequested = requestsAllVersions(lower);
        boolean contentQuery = requestsContent(lower);

        // Compare two specific versions
        Matcher compareMatcher = COMPARE_TWO_VERSIONS_PATTERN.matcher(lower);
        if (compareMatcher.find()) {
            return new FileVersionIntent(
                    FileVersionIntentType.COMPARE_SELECTED_VERSIONS,
                    fileName,
                    parseInt(compareMatcher.group(1)),
                    parseInt(compareMatcher.group(2)),
                    false,
                    false,
                    false);
        }

        // Latest file name query
        if (containsAny(lower, "latest file name", "latest filename", "current file name")) {
            return new FileVersionIntent(
                    FileVersionIntentType.LATEST_FILENAME,
                    fileName, null, null, false, false, false);
        }

        // Older file names query
        if (containsAny(lower, "older file names", "old file names", "previous file names")) {
            return new FileVersionIntent(
                    FileVersionIntentType.OLDER_FILENAMES,
                    fileName, null, null, false, false, false);
        }

        // "oldest/earliest" + data retrieval intent = CONTENT_QUERY from oldest version
        // Must be checked BEFORE the OLDER_VERSION metadata-only check below
        if (containsAny(lower, "oldest", "earliest")
                && (contentQuery || containsAny(lower,
                        "find", "get", "show", "tell me", "give me",
                        "what is", "what are", "retrieve", "fetch"))) {
            return new FileVersionIntent(
                    FileVersionIntentType.CONTENT_QUERY,
                    fileName,
                    null,
                    null,
                    false,
                    true,
                    true); // useOldestVersion = true
        }

        // Older version metadata (not content) query
        if (containsAny(lower, "old version", "older version", "previous version", "first version")
                && !contentQuery) {
            return new FileVersionIntent(
                    FileVersionIntentType.OLDER_VERSION,
                    fileName, null, null, false, false, false);
        }

        // Specific version number query
        Matcher singleVersionMatcher = VERSION_NUMBER_PATTERN.matcher(lower);
        if (singleVersionMatcher.find() && containsAny(lower, "show", "give", "provide", "get")) {
            return new FileVersionIntent(
                    FileVersionIntentType.SPECIFIC_VERSION,
                    fileName,
                    parseInt(singleVersionMatcher.group(1)),
                    null,
                    false,
                    false,
                    false);
        }

        // List all versions metadata — if user ALSO wants content, redirect to
        // CONTENT_QUERY
        if (containsAny(lower, "list all versions", "version history", "different versions",
                "available all versions")) {
            if (contentQuery || messageWantsContent(lower)) {
                return new FileVersionIntent(
                        FileVersionIntentType.CONTENT_QUERY,
                        fileName, null, null, true, true, false);
            }
            return new FileVersionIntent(
                    FileVersionIntentType.LIST_ALL_VERSIONS,
                    fileName, null, null, false, false, false);
        }

        // General content query with file name present
        if (fileName != null && (allVersionsRequested || contentQuery)) {
            return new FileVersionIntent(
                    FileVersionIntentType.CONTENT_QUERY,
                    fileName, null, null, allVersionsRequested, true, false);
        }

        return new FileVersionIntent(FileVersionIntentType.NONE, fileName, null, null, false, false, false);
    }

    // ─── Guard Methods ────────────────────────────────────────────────────────

    private boolean isConversationHistoryRequest(String lower) {
        return containsAny(lower,
                "in the conversation",
                "this conversation",
                "my questions",
                "questions i have asked",
                "questions i asked",
                "chat history",
                "past messages",
                "previous messages",
                "what did i ask",
                "what was my first question",
                "what was my last question");
    }

    private boolean requestsAllVersions(String lower) {
        return containsAny(lower,
                "all versions",
                "every version",
                "all version",
                "all 3 versions",
                "all 2 versions",
                "across versions",
                "across all versions",
                "from all versions",
                "from every version",
                "in all versions",
                "in every version",
                "mention content",
                "content of file",
                "show content",
                "include content");
    }

    private boolean requestsContent(String lower) {
        return containsAny(lower,
                "what is in", "what are in",
                "extract content", "extract text",
                "content of", "inside file",
                "give me the content", "show me the content",
                "read the file", "full content", "all content",
                "summarize this file",
                "give me details about this file",
                "give me content of this file",
                "find my", "find the",
                "what is my", "what are my",
                "tell me my", "tell me the",
                "mobile number", "phone number",
                "all mobile numbers", "all phone numbers",
                "all email", "all data", "all records",
                "mention content", "content of file",
                "show content", "include content");
    }

    private boolean messageWantsContent(String lower) {
        return containsAny(lower,
                "content", "extract", "summarize", "summary",
                "read", "mention", "what is in", "full text",
                "show me", "tell me", "give me", "find");
    }

    // ─── File Name Extraction ────────────────────────────────────────────────

    private String extractFileName(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }

        Matcher quotedMatcher = QUOTED_FILE_PATTERN.matcher(prompt);
        if (quotedMatcher.find()) {
            return quotedMatcher.group(1).trim();
        }

        Matcher keywordMatcher = FILE_AFTER_KEYWORD_PATTERN.matcher(prompt);
        if (keywordMatcher.find()) {
            return keywordMatcher.group(1).trim();
        }

        Matcher extMatcher = FILE_EXTENSION_PATTERN.matcher(prompt);
        String lastMatch = null;
        while (extMatcher.find()) {
            lastMatch = extMatcher.group(1);
        }

        return lastMatch != null ? lastMatch.trim() : null;
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
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
            boolean useOldestVersion) { // NEW: true = retrieve from oldest version
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