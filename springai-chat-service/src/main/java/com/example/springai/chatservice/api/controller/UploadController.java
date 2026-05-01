package com.example.springai.chatservice.api.controller;

import com.example.springai.chatservice.api.dto.file.AttachmentStatus;
import com.example.springai.chatservice.api.dto.file.UploadResponse;
import com.example.springai.chatservice.application.service.file.UploadApplicationService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(value = "/api/uploads", produces = MediaType.APPLICATION_JSON_VALUE)
public class UploadController {

    private final UploadApplicationService uploadApplicationService;

    public UploadController(UploadApplicationService uploadApplicationService) {
        this.uploadApplicationService = uploadApplicationService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadResponse> upload(
            @RequestPart("message") String message,
            @RequestPart(value = "conversationId", required = false) String conversationId,
            @RequestPart("files") Flux<FilePart> files,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return uploadApplicationService.upload(userId, message, conversationId, files);
    }

    // NEW: Status polling endpoint for Kafka async processing
    @GetMapping("/status/{attachmentId}")
    public Mono<AttachmentStatus> getStatus(
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return Mono.fromCallable(() -> uploadApplicationService.getAttachmentStatus(userId, attachmentId))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}