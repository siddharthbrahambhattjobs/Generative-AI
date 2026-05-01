package com.example.springai.chatservice.infrastructure.kafka.config;

public final class KafkaTopics {

    public static final String ATTACHMENT_INGESTION_REQUESTED = "attachment.ingestion.requested";
    public static final String USER_MEMORY_REFRESH_REQUESTED = "user.memory.refresh.requested";

    private KafkaTopics() {
    }
}
