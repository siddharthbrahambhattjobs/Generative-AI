package com.example.springai.chatservice.security.jwt;

import com.example.springai.chatservice.config.properties.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class JwtTokenService {

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    Logger log = org.slf4j.LoggerFactory.getLogger(JwtTokenService.class);

    public JwtTokenService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        byte[] keyBytes = jwtProperties.secret().length() >= 32
                ? jwtProperties.secret().getBytes(StandardCharsets.UTF_8)
                : Decoders.BASE64.decode("c3ByaW5nYWktcGxhdGZvcm0tcGhhc2UyLXNlY3JldC1rZXktZm9yLWRldg==");
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(String userId, String email, String displayName) {
        Instant now = Instant.now();
        Instant expiry = Instant.now().plus(Duration.ofMillis(jwtProperties.accessTokenExpiration()));

        // in JwtTokenService.generateAccessToken():
        log.debug("Token issued at={}, expires={}, sub={}", Date.from(now), Date.from(expiry), userId);
        // JwtTokenService.java — make algorithm explicit
        return Jwts.builder()
                .subject(userId)
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("email", email)
                .claim("name", displayName)
                .claim("scope", "chat.read chat.write")
                .signWith(secretKey, Jwts.SIG.HS512)
                .compact();
    }
}
