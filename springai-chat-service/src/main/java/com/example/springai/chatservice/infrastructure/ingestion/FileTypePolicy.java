package com.example.springai.chatservice.infrastructure.ingestion;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FileTypePolicy {

    // ✅ Added png, jpg, jpeg, webp, and gif
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "txt", "md", "pdf", "json", "html", "css", "java", "xml", "yaml", "yml", "properties", "csv", "docx",
            "xlsx", "pptx", "png", "jpg", "jpeg", "webp", "gif");

    public boolean isSupported(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return false;
        }
        String extension = filename.substring(index + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    public String detectCategory(String filename) {
        String lower = filename.toLowerCase();
        if (lower.contains("resume")) {
            return "RESUME";
        }
        // You could also add a custom category for images here if you wanted to track
        // them separately:
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "IMAGE";
        }
        return "GENERAL";
    }
}