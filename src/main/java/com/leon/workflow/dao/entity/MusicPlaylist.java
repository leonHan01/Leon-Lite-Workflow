package com.leon.workflow.dao.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
@Table(name = "music_playlist")
public class MusicPlaylist {
    @Id private String id;
    @Column(nullable = false, length = 80) private String name;
    private Instant createdAt;
    private Instant updatedAt;
    @OneToMany(mappedBy = "playlist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<MusicPlaylistItem> items = new ArrayList<>();
}
