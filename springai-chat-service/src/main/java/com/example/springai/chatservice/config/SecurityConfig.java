package com.example.springai.chatservice.config;

import com.example.springai.chatservice.application.service.auth.UserProvisioningService;
import com.example.springai.chatservice.config.properties.JwtProperties;
import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.security.jwt.JwtTokenService;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private final JwtProperties jwtProperties;

    public SecurityConfig(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    // SecurityConfig.java secretKey() — mirrors JwtTokenService exactly
    private SecretKey secretKey() {
        byte[] keyBytes = jwtProperties.secret().length() >= 32
                ? jwtProperties.secret().getBytes(StandardCharsets.UTF_8)
                : Decoders.BASE64.decode("c3ByaW5nYWktcGxhdGZvcm0tcGhhc2UyLXNlY3JldC1rZXktZm9yLWRldg==");
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder() {
        return NimbusReactiveJwtDecoder
                .withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS512)
                .build();
    }

    @Bean
    public ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("scope"); // your token has "scope" claim

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    @Bean
    @Order(1)
    public SecurityWebFilterChain apiFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher("/api/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .pathMatchers("/api/auth/dev-token").permitAll()
                        .pathMatchers("/api/auth/me").authenticated()
                        .anyExchange().authenticated())
                // ✅ THIS IS WHAT WAS MISSING:
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((exchange, e) -> {
                    ServerHttpResponse response = exchange.getResponse();
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    byte[] bytes = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                    DataBuffer buffer = response.bufferFactory().wrap(bytes);
                    return response.writeWith(Mono.just(buffer));
                }))
                .build();
    }

    @Bean
    public ServerAuthenticationSuccessHandler oauth2SuccessHandler(
            UserProvisioningService userProvisioningService,
            JwtTokenService jwtTokenService) {

        return (webFilterExchange, authentication) -> {
            OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

            // Extract Google profile data
            String email = oAuth2User.getAttribute("email");
            String name = oAuth2User.getAttribute("name");

            // 1. Find or create the user in your database
            AppUserEntity user = userProvisioningService.findOrCreateByEmail(
                    email,
                    name != null ? name : email,
                    "GOOGLE");

            // 2. Generate your custom JWT
            String token = jwtTokenService.generateAccessToken(
                    user.getId().toString(),
                    user.getEmail(),
                    user.getDisplayName());

            // 3. Redirect back to your Angular callback route with the token
            String redirectUrl = "http://localhost:4200/auth/callback?accessToken=" + token + "&email=" + email;
            RedirectServerAuthenticationSuccessHandler redirectHandler = new RedirectServerAuthenticationSuccessHandler(
                    redirectUrl);

            return redirectHandler.onAuthenticationSuccess(webFilterExchange, authentication);
        };
    }

    @Bean
    @Order(2)
    public SecurityWebFilterChain oauthFilterChain(
            ServerHttpSecurity http,
            ServerAuthenticationSuccessHandler successHandler) { // Inject the handler here
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/oauth2/**", "/login/oauth2/**", "/login").permitAll()
                        .anyExchange().permitAll())
                // ✅ Attach the custom success handler to the OAuth2 login flow
                .oauth2Login(oauth2 -> oauth2
                        .authenticationSuccessHandler(successHandler))
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:4200"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}