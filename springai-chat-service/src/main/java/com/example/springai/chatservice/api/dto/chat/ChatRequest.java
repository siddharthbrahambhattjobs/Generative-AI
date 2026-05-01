package com.example.springai.chatservice.api.dto.chat;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequest(
                @NotBlank(message = "message is required") String message,
                String conversationId,
                String provider,
                String model,
                List<String> fileIds) {
}