package com.example.springai.chatservice.application.service.file;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FileNameSimilarityMatcher {

    private static final double SIMILARITY_THRESHOLD = 0.78;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    public MatchResult findBestMatch(String requestedFileName, List<String> candidateFileNames) {
        if (requestedFileName == null || requestedFileName.isBlank()
                || candidateFileNames == null || candidateFileNames.isEmpty()) {
            return null;
        }

        String normalizedRequested = normalize(requestedFileName);

        return candidateFileNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new MatchResult(name, similarity(normalizedRequested, normalize(name))))
                .filter(match -> match.score() >= SIMILARITY_THRESHOLD)
                .max(Comparator.comparingDouble(MatchResult::score))
                .orElse(null);
    }

    private String normalize(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT).trim();
        int dotIndex = lower.lastIndexOf('.');
        String baseName = dotIndex > 0 ? lower.substring(0, dotIndex) : lower;
        return NON_ALNUM.matcher(baseName).replaceAll("");
    }

    private double similarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        int distance = levenshtein(a, b);
        return 1.0 - ((double) distance / maxLen);
    }

    private int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];

        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] temp = prev;
            prev = curr;
            curr = temp;
        }

        return prev[b.length()];
    }

    public record MatchResult(String fileName, double score) {
    }
}