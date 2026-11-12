package com.leon.workflow.service;

import com.leon.workflow.dao.entity.*;
import com.leon.workflow.dao.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class MusicPlaylistService {
    public record PlaylistView(String id, String name, List<String> trackIds, Instant createdAt, Instant updatedAt) {}
    private final MusicPlaylistRepository playlists;
    private final MusicTrackRepository tracks;

    public MusicPlaylistService(MusicPlaylistRepository playlists, MusicTrackRepository tracks) {
        this.playlists = playlists; this.tracks = tracks;
    }

    @Transactional(readOnly = true)
    public List<PlaylistView> list() {
        return playlists.findAllByOrderByCreatedAtAscIdAsc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PlaylistView get(UUID id) {
        return view(playlists.findById(id.toString()).orElseThrow(MusicPlaylistService::missing));
    }

    public PlaylistView create(String name) {
        MusicPlaylist list = new MusicPlaylist();
        list.setId(UUID.randomUUID().toString()); list.setName(validName(name));
        list.setCreatedAt(Instant.now()); list.setUpdatedAt(list.getCreatedAt());
        return view(playlists.save(list));
    }

    public PlaylistView rename(UUID id, String name) {
        MusicPlaylist list = locked(id); list.setName(validName(name)); list.setUpdatedAt(Instant.now());
        return view(list);
    }

    public PlaylistView add(UUID id, List<UUID> trackIds) {
        if (trackIds == null || trackIds.isEmpty() || trackIds.size() > 1000 || trackIds.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("请选择 1–1000 首歌曲");
        MusicPlaylist list = locked(id);
        Set<String> existing = new HashSet<>();
        list.getItems().forEach(item -> existing.add(item.getTrack().getId()));
        List<String> requested = trackIds.stream().map(UUID::toString).distinct().toList();
        Map<String, MusicTrack> songs = new HashMap<>();
        tracks.findAllById(requested).forEach(track -> songs.put(track.getId(), track));
        if (songs.size() != requested.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "部分歌曲已被删除，请刷新曲库");
        if (existing.size() + requested.stream().filter(song -> !existing.contains(song)).count() > 10000)
            throw new IllegalArgumentException("单张歌单最多 10000 首歌曲");
        int position = list.getItems().stream().mapToInt(MusicPlaylistItem::getPosition).max().orElse(-1) + 1;
        for (String trackId : requested) {
            if (!existing.add(trackId)) continue;
            MusicPlaylistItem item = new MusicPlaylistItem(); item.setId(UUID.randomUUID().toString());
            item.setPlaylist(list); item.setTrack(songs.get(trackId)); item.setPosition(position++);
            list.getItems().add(item);
        }
        list.setUpdatedAt(Instant.now());
        return view(list);
    }

    public PlaylistView remove(UUID id, UUID trackId) {
        MusicPlaylist list = locked(id);
        list.getItems().removeIf(item -> item.getTrack().getId().equals(trackId.toString()));
        list.setUpdatedAt(Instant.now());
        return view(list);
    }

    public PlaylistView reorder(UUID id, List<UUID> trackIds, List<UUID> expectedTrackIds) {
        if (trackIds == null || expectedTrackIds == null || trackIds.size() > 10000 || expectedTrackIds.size() > 10000
                || trackIds.stream().anyMatch(Objects::isNull) || expectedTrackIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(trackIds).size() != trackIds.size() || new HashSet<>(expectedTrackIds).size() != expectedTrackIds.size())
            throw new IllegalArgumentException("请提交完整且不重复的歌曲顺序和原顺序");
        MusicPlaylist list = locked(id);
        List<String> current = list.getItems().stream().map(item -> item.getTrack().getId()).toList();
        List<String> requested = trackIds.stream().map(UUID::toString).toList();
        // Retrying an already-applied order is safe and does not change membership.
        if (current.equals(requested)) return view(list);
        if (!current.equals(expectedTrackIds.stream().map(UUID::toString).toList()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "歌单已被修改，请刷新后重新排序");
        if (!new HashSet<>(current).equals(new HashSet<>(requested)))
            throw new IllegalArgumentException("排序只能调整歌单现有歌曲的位置，不能增删歌曲");
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) positions.put(requested.get(i), i);
        list.getItems().forEach(item -> item.setPosition(positions.get(item.getTrack().getId())));
        list.getItems().sort(Comparator.comparingInt(MusicPlaylistItem::getPosition));
        list.setUpdatedAt(Instant.now());
        return view(list);
    }

    public void delete(UUID id) { playlists.delete(locked(id)); }

    private MusicPlaylist locked(UUID id) { return playlists.lockById(id.toString()).orElseThrow(MusicPlaylistService::missing); }
    private PlaylistView view(MusicPlaylist list) {
        return new PlaylistView(list.getId(), list.getName(), list.getItems().stream().map(item -> item.getTrack().getId()).toList(), list.getCreatedAt(), list.getUpdatedAt());
    }
    private static String validName(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 80 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("歌单名称应为 1–80 个字符，不能包含控制字符");
        return value.strip();
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "在线歌单不存在"); }
}
