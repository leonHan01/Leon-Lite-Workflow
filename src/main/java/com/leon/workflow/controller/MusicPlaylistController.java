package com.leon.workflow.controller;

import com.leon.workflow.service.MusicPlaylistService;
import com.leon.workflow.service.MusicPlaylistService.PlaylistView;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/music/playlists")
public class MusicPlaylistController {
    public record NameRequest(String name, UUID id) {}
    public record TracksRequest(List<UUID> trackIds) {}
    public record OrderRequest(List<UUID> trackIds, List<UUID> expectedTrackIds) {}
    private final MusicPlaylistService playlists;
    public MusicPlaylistController(MusicPlaylistService playlists) { this.playlists = playlists; }
    @GetMapping public ResponseEntity<List<PlaylistView>> list() { return ok(playlists.list()); }
    @GetMapping("/{id}") public ResponseEntity<PlaylistView> get(@PathVariable UUID id) { return ok(playlists.get(id)); }
    @PostMapping public ResponseEntity<PlaylistView> create(@RequestBody NameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(playlists.create(request.name(), request.id()));
    }
    @PutMapping("/{id}") public ResponseEntity<PlaylistView> rename(@PathVariable UUID id, @RequestBody NameRequest request) { return ok(playlists.rename(id, request.name())); }
    @PostMapping("/{id}/tracks") public ResponseEntity<PlaylistView> add(@PathVariable UUID id, @RequestBody TracksRequest request) { return ok(playlists.add(id, request.trackIds())); }
    @PutMapping("/{id}/order") public ResponseEntity<PlaylistView> reorder(@PathVariable UUID id, @RequestBody OrderRequest request) { return ok(playlists.reorder(id, request.trackIds(), request.expectedTrackIds())); }
    @DeleteMapping("/{id}/tracks/{trackId}") public ResponseEntity<PlaylistView> remove(@PathVariable UUID id, @PathVariable UUID trackId) { return ok(playlists.remove(id, trackId)); }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id) { playlists.delete(id); return ResponseEntity.noContent().build(); }
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String, String>> rejected(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", Objects.requireNonNullElse(error.getReason(), "操作未完成，请刷新后重试"))); }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
