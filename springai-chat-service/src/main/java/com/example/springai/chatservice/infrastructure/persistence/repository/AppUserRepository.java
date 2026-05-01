package com.example.springai.chatservice.infrastructure.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.springai.chatservice.infrastructure.persistence.entity.AppUserEntity;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    Optional<AppUserEntity> findByEmail(String email);

    // Add this — lookup by the JWT sub claim (UUID stored as provider_subject)
    Optional<AppUserEntity> findByProviderSubject(String providerSubject);
}