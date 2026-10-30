package com.leon.workflow.dao.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Table(name = "browser_session")
@Data
public class BrowserSession {
    @Id
    @Column(length = 64)
    private String id;
    @Column(nullable = false, length = 64)
    private String tokenFingerprint;
    @Column(nullable = false)
    private Instant expiresAt;
}
