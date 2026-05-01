package com.example.springai.chatservice.api.controller.chat;

import com.example.springai.chatservice.api.dto.chat.ChatRequest;
import com.example.springai.chatservice.api.dto.chat.ChatStreamEvent;
import com.example.springai.chatservice.application.service.chat.ChatStreamingService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatStreamController {

        private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);
        private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
        private static final Duration RETRY_INTERVAL = Duration.ofSeconds(3);

        private final ChatStreamingService chatStreamingService;

        public ChatStreamController(ChatStreamingService chatStreamingService) {
                this.chatStreamingService = chatStreamingService;
        }

        @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<ServerSentEvent<ChatStreamEvent>> stream(
                        @Valid @RequestBody ChatRequest request,
                        @AuthenticationPrincipal Jwt jwt) {

                UUID userId = UUID.fromString(jwt.getSubject());
                AtomicReference<String> effectiveConversationId = new AtomicReference<>(request.conversationId());

                Flux<ChatStreamEvent> sharedSource = chatStreamingService.streamChat(request, userId)
                                .doOnSubscribe(sub -> log.info("SSE subscribed userId={}, initialConversationId={}",
                                                userId, effectiveConversationId.get()))
                                .doOnNext(event -> {
                                        if (event != null && event.conversationId() != null
                                                        && !event.conversationId().isBlank()) {
                                                effectiveConversationId.set(event.conversationId());
                                        }
                                })
                                .doOnCancel(() -> log.info("SSE cancelled userId={}, conversationId={}",
                                                userId, effectiveConversationId.get()))
                                .doOnComplete(() -> log.info("SSE completed userId={}, conversationId={}",
                                                userId, effectiveConversationId.get()))
                                .doOnError(ex -> log.error("SSE controller failure userId={}, conversationId={}",
                                                userId, effectiveConversationId.get(), ex))
                                .onErrorResume(ex -> Flux.just(ChatStreamEvent.builder()
                                                .type("error")
                                                .conversationId(effectiveConversationId.get())
                                                .content("Streaming failed: " + ex.getMessage())
                                                .sequence(-1)
                                                .done(true)
                                                .build()))
                                .share();

                Flux<ServerSentEvent<ChatStreamEvent>> eventStream = sharedSource
                                .map(event -> ServerSentEvent.<ChatStreamEvent>builder()
                                                .id(buildEventId(event))
                                                .event(event.type())
                                                .data(event)
                                                .retry(RETRY_INTERVAL)
                                                .build());

                Flux<ServerSentEvent<ChatStreamEvent>> heartbeatStream = Flux.interval(HEARTBEAT_INTERVAL)
                                .map(seq -> ServerSentEvent.<ChatStreamEvent>builder()
                                                .comment("keep-alive")
                                                .build())
                                .takeUntilOther(sharedSource.ignoreElements());

                return Flux.merge(eventStream, heartbeatStream);
        }

        private String buildEventId(ChatStreamEvent event) {
                String conversationId = event.conversationId() != null ? event.conversationId() : "unknown";
                String sequence = event.sequence() != null ? String.valueOf(event.sequence()) : "0";
                return conversationId + "-" + sequence;
        }
}