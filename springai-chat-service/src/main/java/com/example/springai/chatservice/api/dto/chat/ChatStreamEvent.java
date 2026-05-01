package com.example.springai.chatservice.api.dto.chat;

import lombok.Builder;

@Builder(toBuilder = true)
public record ChatStreamEvent(
                String type,
                String conversationId,
                String content,
                Integer sequence,
                Boolean done) {
}