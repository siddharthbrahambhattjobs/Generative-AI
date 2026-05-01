package com.example.springai.chatservice.infrastructure.ingestion;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".css", ".html", ".json", ".xml", ".yaml", ".yml",
            ".properties", ".md", ".txt", ".csv", ".sql", ".js", ".ts");

    private final Tika tika = new Tika();

    public String extract(Path path) {
        try {
            String fileName = path.getFileName().toString().toLowerCase();

            if (isPlainTextFile(fileName)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }

            try (InputStream in = Files.newInputStream(path)) {
                String parsed = tika.parseToString(in);

                if (parsed != null && !parsed.isBlank()) {
                    return parsed.trim();
                }
            }

            log.warn("Tika returned blank content for file={}", path);
            return "";
        } catch (Exception ex) {
            log.error("Failed to extract text from file={}: {}", path, ex.getMessage(), ex);
            return "";
        }
    }

    private boolean isPlainTextFile(String fileName) {
        return TEXT_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
}