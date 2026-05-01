package com.example.springai.chatservice.application.service.file;

import com.example.springai.chatservice.infrastructure.persistence.entity.AttachmentEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AttachmentRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FileVersionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(FileVersionHistoryService.class);

    private final AttachmentRepository attachmentRepository;
    private final FileNameSimilarityMatcher fileNameSimilarityMatcher;

    @Transactional(readOnly = true)
    public VersionHistoryResult getVersionHistory(UUID userId, String requestedFileName) {
        if (requestedFileName == null || requestedFileName.isBlank()) {
            throw new IllegalArgumentException("Requested file name is required");
        }

        AttachmentEntity anchor = resolveFamilyAnchor(userId, requestedFileName);
        if (anchor == null) {
            throw new IllegalArgumentException(
                    "No matching file family found for: " + requestedFileName);
        }

        List<AttachmentEntity> family = loadFamily(userId, anchor);
        if (family.isEmpty()) {
            throw new IllegalArgumentException("No versions found for the requested file");
        }

        AttachmentEntity latest = family.stream()
                .filter(AttachmentEntity::isActive)
                .findFirst()
                .orElse(family.get(0));

        String latestFileName = latest.getFileName();

        // Exclude the latest item itself AND any entry whose file name matches the
        // latest name,
        // so the "older names" list only shows genuinely different file names from the
        // past.
        List<String> olderFileNames = family.stream()
                .filter(item -> item.getId() != null && !item.getId().equals(latest.getId()))
                .map(AttachmentEntity::getFileName)
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> !name.equalsIgnoreCase(latestFileName))
                .distinct()
                .toList();

        log.info(
                "Version history resolved: userId={}, requestedFileName={}, matchedFileName={}, versions={}, olderNames={}",
                userId,
                requestedFileName,
                anchor.getFileName(),
                family.size(),
                olderFileNames.size());

        return new VersionHistoryResult(
                requestedFileName,
                anchor.getFileName(),
                anchor.getDocumentKey(),
                anchor.getRootAttachmentId(),
                latestFileName,
                latest.getVersionNumber(),
                family,
                olderFileNames);
    }

    /**
     * Optional-returning variant. Use this when the caller wants to handle a
     * missing
     * file family gracefully without a try-catch.
     */
    @Transactional(readOnly = true)
    public Optional<VersionHistoryResult> findVersionHistory(UUID userId, String requestedFileName) {
        try {
            return Optional.of(getVersionHistory(userId, requestedFileName));
        } catch (IllegalArgumentException ex) {
            log.debug(
                    "No version history found: userId={}, fileName={}: {}",
                    userId,
                    requestedFileName,
                    ex.getMessage());
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public AttachmentEntity getSpecificVersion(UUID userId, String requestedFileName, int versionNumber) {
        VersionHistoryResult history = getVersionHistory(userId, requestedFileName);

        return history.versions().stream()
                .filter(v -> v.getVersionNumber() != null && v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Version " + versionNumber + " not found for file: " + requestedFileName));
    }

    @Transactional(readOnly = true)
    public VersionComparisonResult compareVersions(
            UUID userId,
            String requestedFileName,
            int leftVersion,
            int rightVersion) {

        if (leftVersion == rightVersion) {
            throw new IllegalArgumentException(
                    "Please provide two different version numbers to compare.");
        }

        AttachmentEntity left = getSpecificVersion(userId, requestedFileName, leftVersion);
        AttachmentEntity right = getSpecificVersion(userId, requestedFileName, rightVersion);

        List<String> differences = new ArrayList<>();

        if (!safeEquals(left.getFileName(), right.getFileName())) {
            differences.add(
                    "File name changed from \""
                            + left.getFileName()
                            + "\" to \""
                            + right.getFileName()
                            + "\"");
        }

        if (!safeEquals(left.getContentHash(), right.getContentHash())) {
            differences.add("File content changed (hash mismatch)");
        }

        if (!safeEquals(trim(left.getExtractedText()), trim(right.getExtractedText()))) {
            differences.add("Extracted text changed");
        }

        if (!safeEquals(left.getProcessingStatus(), right.getProcessingStatus())) {
            differences.add(
                    "Processing status changed from \""
                            + left.getProcessingStatus()
                            + "\" to \""
                            + right.getProcessingStatus()
                            + "\"");
        }

        if (!safeEquals(left.getSizeBytes(), right.getSizeBytes())) {
            differences.add(
                    "File size changed from "
                            + formatBytes(left.getSizeBytes())
                            + " to "
                            + formatBytes(right.getSizeBytes()));
        }

        return new VersionComparisonResult(left, right, differences);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Searches ALL attachments (active and inactive) so that old file names
     * are also valid search anchors. The highest version number among name
     * matches is selected as the anchor so the family load starts from the
     * most recent known name.
     */
    private AttachmentEntity resolveFamilyAnchor(UUID userId, String requestedFileName) {
        List<AttachmentEntity> allAttachments = attachmentRepository.findByUser_IdOrderByUpdatedAtDesc(userId);
        if (allAttachments.isEmpty()) {
            return null;
        }

        List<String> candidateNames = allAttachments.stream()
                .map(AttachmentEntity::getFileName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();

        FileNameSimilarityMatcher.MatchResult match = fileNameSimilarityMatcher.findBestMatch(requestedFileName,
                candidateNames);
        if (match == null) {
            return null;
        }

        return allAttachments.stream()
                .filter(a -> a.getFileName() != null)
                .filter(a -> a.getFileName().equalsIgnoreCase(match.fileName()))
                .max(Comparator.comparingInt(a -> a.getVersionNumber() != null ? a.getVersionNumber() : 0))
                .orElse(null);
    }

    /**
     * Loads the complete document family ordered by version descending.
     *
     * Priority 1 — documentKey : covers versions even when the file was renamed.
     * Priority 2 — rootAttachmentId : legacy fallback for older uploads.
     * Priority 3 — anchor only : single-version file, no family yet.
     */
    private List<AttachmentEntity> loadFamily(UUID userId, AttachmentEntity anchor) {
        if (anchor.getDocumentKey() != null) {
            List<AttachmentEntity> byDocumentKey = attachmentRepository
                    .findByUser_IdAndDocumentKeyOrderByVersionNumberDesc(
                            userId, anchor.getDocumentKey());
            if (!byDocumentKey.isEmpty()) {
                return byDocumentKey;
            }
        }

        if (anchor.getRootAttachmentId() != null) {
            List<AttachmentEntity> byRoot = attachmentRepository
                    .findByUser_IdAndRootAttachmentIdOrderByVersionNumberDesc(
                            userId, anchor.getRootAttachmentId());
            if (!byRoot.isEmpty()) {
                return byRoot;
            }
        }

        return List.of(anchor);
    }

    private boolean safeEquals(Object left, Object right) {
        return Objects.equals(left, right);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String formatBytes(Long bytes) {
        if (bytes == null) {
            return "unknown size";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // -------------------------------------------------------------------------
    // Result records
    // -------------------------------------------------------------------------

    public record VersionHistoryResult(
            String requestedFileName,
            String matchedFileName,
            UUID matchedDocumentKey,
            UUID rootAttachmentId,
            String latestFileName,
            Integer latestVersionNumber,
            List<AttachmentEntity> versions,
            List<String> olderFileNames) {
    }

    public record VersionComparisonResult(
            AttachmentEntity left,
            AttachmentEntity right,
            List<String> differences) {
    }
}