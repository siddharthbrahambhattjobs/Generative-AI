package com.example.springai.chatservice.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "attachments")
public class AttachmentEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    private ConversationEntity conversation;

    @Column(name = "root_attachment_id")
    private UUID rootAttachmentId;

    @Column(name = "superseded_attachment_id")
    private UUID supersededAttachmentId;

    @Column(name = "document_key", nullable = false)
    private UUID documentKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "storage_path")
    private String storagePath;

    @Column(name = "processing_status", nullable = false)
    private String processingStatus;

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber = 1;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public AppUserEntity getUser() {
        return user;
    }

    public void setUser(AppUserEntity user) {
        this.user = user;
    }

    public ConversationEntity getConversation() {
        return conversation;
    }

    public void setConversation(ConversationEntity conversation) {
        this.conversation = conversation;
    }

    public UUID getRootAttachmentId() {
        return rootAttachmentId;
    }

    public void setRootAttachmentId(UUID rootAttachmentId) {
        this.rootAttachmentId = rootAttachmentId;
    }

    public UUID getSupersededAttachmentId() {
        return supersededAttachmentId;
    }

    public void setSupersededAttachmentId(UUID supersededAttachmentId) {
        this.supersededAttachmentId = supersededAttachmentId;
    }

    public UUID getDocumentKey() {
        return documentKey;
    }

    public void setDocumentKey(UUID documentKey) {
        this.documentKey = documentKey;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public Integer getVersionNumber() {
        return versionNumber;
    }

    public void setVersionNumber(Integer versionNumber) {
        this.versionNumber = versionNumber;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public boolean isRootVersion() {
        return id != null && id.equals(rootAttachmentId);
    }

    public boolean isVersionedSuccessor() {
        return supersededAttachmentId != null;
    }

    public boolean belongsToSameDocument(UUID candidateDocumentKey) {
        return documentKey != null && documentKey.equals(candidateDocumentKey);
    }

    public boolean isUsableForRetrieval() {
        return active && ("COMPLETED".equals(processingStatus)
                || "NO_TEXT".equals(processingStatus)
                || "NO_CHUNKS".equals(processingStatus));
    }

    public void markSuperseded() {
        this.active = false;
        this.processingStatus = "SUPERSEDED";
    }
}
