package com.example.springai.chatservice.api.controller.conversation;

import com.example.springai.chatservice.api.dto.conversation.ConversationDetailResponse;
import com.example.springai.chatservice.api.dto.conversation.ConversationSummaryResponse;
import com.example.springai.chatservice.api.dto.conversation.CreateConversationRequest;
import com.example.springai.chatservice.application.service.conversation.ConversationQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

@RestController
@RequestMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConversationController {

    private final ConversationQueryService conversationQueryService;

    public ConversationController(ConversationQueryService conversationQueryService) {
        this.conversationQueryService = conversationQueryService;
    }

    @GetMapping
    public Flux<ConversationSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Mono.fromCallable(() -> conversationQueryService.listConversations(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @GetMapping("/{conversationId}")
    public Mono<ConversationDetailResponse> detail(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Mono.fromCallable(() -> conversationQueryService.getConversation(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ConversationSummaryResponse> create(
            @Valid @RequestBody CreateConversationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Mono.fromCallable(() -> conversationQueryService.createConversation(userId, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(
            @PathVariable UUID conversationId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Mono.fromRunnable(() -> conversationQueryService.deleteConversation(userId, conversationId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}