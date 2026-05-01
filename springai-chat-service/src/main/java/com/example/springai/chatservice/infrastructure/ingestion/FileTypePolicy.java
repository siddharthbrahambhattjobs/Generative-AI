package com.example.springai.chatservice.infrastructure.ingestion;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FileTypePolicy {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "json", "html", "css", "java", "xml", "yaml", "yml", "properties", "csv", "docx",
            "xlsx", "xls", "pptx", "png", "jpg", "jpeg", "webp", "gif");

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "webp", "gif");

    public boolean isSupported(String filename) {
        String extension = getExtension(filename);
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension);
    }

    public String detectCategory(String filename) {
        if (filename == null || filename.isBlank()) {
            return "UNKNOWN";
        }

        String lower = filename.toLowerCase();

        // Domain-specific keyword check
        if (lower.contains("resume")) {
            return "RESUME";
        }

        // Extension-based categorization
        String extension = getExtension(filename);
        if (extension != null && IMAGE_EXTENSIONS.contains(extension)) {
            return "IMAGE";
        }

        return "GENERAL";
    }

    /**
     * Extracts the extension from a filename.
     * Returns null if no extension is found.
     */
    private String getExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return null;
        }
        return filename.substring(index + 1).toLowerCase();
    }
}