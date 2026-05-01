package com.example.springai.chatservice.api.dto.conversation;

import jakarta.validation.constraints.NotBlank;

public record CreateConversationRequest(@NotBlank String title) {
}
