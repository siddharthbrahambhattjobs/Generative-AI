package com.example.springai.chatservice.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

@Getter
@Setter
@Entity
@Table(name = "app_user")
public class AppUserEntity extends BaseAuditEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "auth_provider", nullable = false, length = 50)
    private String authProvider;

    @Column(name = "provider_subject")
    private String providerSubject;
}
