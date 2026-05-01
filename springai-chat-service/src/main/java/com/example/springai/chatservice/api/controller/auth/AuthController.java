package com.example.springai.chatservice.api.controller.auth;

import com.example.springai.chatservice.application.service.auth.UserProvisioningService;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.security.jwt.JwtTokenService;
import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtTokenService jwtTokenService;
    private final UserProvisioningService userProvisioningService;

    /**
     * Dev-only token endpoint.
     * Each unique email resolves to its own AppUserEntity row,
     * so different callers get different JWT sub values and separate data.
     *
     * Request body: { "email": "alice@example.com", "displayName": "Alice" }
     * displayName is optional — defaults to the email local part.
     */
    @PostMapping("/dev-token")
    public Mono<ResponseEntity<Map<String, Object>>> devToken(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        String displayName = body.getOrDefault("displayName", "");

        if (email == null || email.isBlank()) {
            return Mono.just(ResponseEntity
                    .badRequest()
                    .body(Map.of("error", "Email must not be blank")));
        }

        return Mono.fromCallable(() -> {
            AppUserEntity user = userProvisioningService.findOrCreateByEmail(
                    email.trim(),
                    displayName.isBlank() ? email.trim() : displayName.trim(),
                    "DEV");
            String token = jwtTokenService.generateAccessToken(
                    user.getId().toString(),
                    user.getEmail(),
                    user.getDisplayName());

            log.info("Dev token issued userId={}, email={}", user.getId(), user.getEmail());

            return ResponseEntity.ok(Map.of(
                    "accessToken", token,
                    "tokenType", "Bearer",
                    "user", Map.of(
                            "id", user.getId().toString(),
                            "email", user.getEmail(),
                            "name", user.getDisplayName())));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/me")
    public Mono<AuthMeResponse> me(Mono<Principal> principalMono) {
        return principalMono.map(principal -> new AuthMeResponse(
                null,
                principal.getName(),
                principal.getName(),
                null));
    }

    public record AuthMeResponse(
            String id,
            String email,
            String name,
            String pictureUrl) {
    }
}
