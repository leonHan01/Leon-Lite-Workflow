package com.leon.workflow.controller;

import com.leon.workflow.dao.entity.MusicTrack;
import com.leon.workflow.service.MusicLibraryService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/music/tracks")
public class MusicController {
    private final MusicLibraryService library;
    public MusicController(MusicLibraryService library) { this.library = library; }

    @GetMapping
    public ResponseEntity<List<MusicTrack>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(library.list());
    }

    @GetMapping("/radio")
    public ResponseEntity<MusicTrack> radio(@RequestParam(required = false) java.util.UUID exclude) {
        return library.radio(exclude)
                .map(track -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(track))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MusicTrack> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "") String title, @RequestParam(defaultValue = "") String artist,
            @RequestParam(defaultValue = "") String album, @RequestParam(defaultValue = "0") double duration) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(library.upload(file, title, artist, album, duration));
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String id) {
        MusicTrack track = library.get(id);
        String type = switch (track.getExtension()) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "wav" -> "audio/wav";
            case "aif", "aiff" -> "audio/aiff";
            case "caf" -> "audio/x-caf";
            default -> "audio/flac";
        };
        // Spring's Resource converter also handles HTTP byte ranges for previews.
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + track.getId() + "." + track.getExtension() + "\"")
                .cacheControl(CacheControl.noStore()).body(new FileSystemResource(library.audio(track)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        library.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({IllegalArgumentException.class, MultipartException.class})
    public ResponseEntity<Map<String, String>> badUpload(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("message", ignored instanceof IllegalArgumentException
                ? ignored.getMessage() : "上传失败，单首歌曲不能超过 128 MB"));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> storageUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "歌曲存储暂不可用，请稍后重试"));
    }
}
