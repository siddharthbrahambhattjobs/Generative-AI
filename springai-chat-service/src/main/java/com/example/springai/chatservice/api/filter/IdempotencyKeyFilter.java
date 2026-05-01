package com.example.springai.chatservice.api.filter;

import com.example.springai.chatservice.application.service.idempotency.IdempotencyService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(20)
public class IdempotencyKeyFilter implements WebFilter {

    private final IdempotencyService idempotencyService;

    public IdempotencyKeyFilter(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!"POST".equalsIgnoreCase(request.getMethod().name()) || !request.getPath().value().startsWith("/api/files")) {
            return chain.filter(exchange);
        }

        String idempotencyKey = request.getHeaders().getFirst("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return chain.filter(exchange);
        }

        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("developer@example.com")
                .flatMap(email -> {
                    String fingerprint = idempotencyService.fingerprint(request.getPath().value());
                    return idempotencyService.resolveDuplicateResponse(email, idempotencyKey, fingerprint)
                            .map(response -> writeDuplicateResponse(exchange.getResponse(), response))
                            .orElseGet(() -> chain.filter(exchange));
                });
    }

    private Mono<Void> writeDuplicateResponse(ServerHttpResponse response, String body) {
        response.setStatusCode(org.springframework.http.HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}
