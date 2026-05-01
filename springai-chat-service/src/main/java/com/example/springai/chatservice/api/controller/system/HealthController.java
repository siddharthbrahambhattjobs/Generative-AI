package com.example.springai.chatservice.api.controller.system;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/system")
public class HealthController {

    @GetMapping("/ping")
    public Mono<Map<String, String>> ping() {
        return Mono.just(Map.of("status", "ok", "service", "springai-chat-service"));
    }
}
