package com.example.springai.chatservice.application.service.auth;

import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;
import com.example.springai.chatservice.infrastructure.persistence.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final AppUserRepository appUserRepository;

    /**
     * Finds an existing user by email, or creates a new one.
     * This is the single entry point for user resolution in dev and OAuth flows.
     * The returned entity always has a stable, persisted UUID that becomes the JWT
     * sub.
     */
    @Transactional
    public AppUserEntity findOrCreateByEmail(String email, String displayName, String authProvider) {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        return appUserRepository.findByEmail(email)
                .orElseGet(() -> {
                    AppUserEntity user = new AppUserEntity();
                    user.setEmail(email);
                    user.setDisplayName(displayName != null ? displayName : deriveDisplayName(email));
                    user.setAuthProvider(authProvider);
                    user.setProviderSubject(email); // dev provider uses email as subject
                    AppUserEntity saved = appUserRepository.save(user);
                    log.info("Created new user id={}, email={}, provider={}", saved.getId(), email, authProvider);
                    return saved;
                });
    }

    private String deriveDisplayName(String email) {
        if (email == null)
            return "User";
        int atIndex = email.indexOf('@');
        return atIndex > 0 ? email.substring(0, atIndex) : email;
    }
}
