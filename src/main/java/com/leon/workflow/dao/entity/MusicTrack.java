package com.leon.workflow.dao.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Entity
@Data
@Table(name = "music_track")
public class MusicTrack {
    @Id private String id;
    @Column(nullable = false, length = 240) private String title;
    @Column(nullable = false, length = 240) private String artist;
    @Column(nullable = false, length = 240) private String album;
    @Column(nullable = false, length = 8) private String extension;
    @Column(nullable = false, length = 64) private String sha256;
    private Long playCount = 0L;
    private long size;
    private double duration;
    private Instant uploadedAt;
}
