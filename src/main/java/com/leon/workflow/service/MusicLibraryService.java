package com.leon.workflow.service;

import com.leon.workflow.dao.entity.MusicTrack;
import com.leon.workflow.dao.repository.MusicTrackRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class MusicLibraryService {
    public static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "aac", "wav", "aif", "aiff", "caf", "flac");
    private final MusicTrackRepository tracks;
    private final Path root;

    public MusicLibraryService(MusicTrackRepository tracks, @Value("${leon.music.directory:./data/music}") String directory) {
        this.tracks = tracks;
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public List<MusicTrack> list() { return tracks.findAllByOrderByUploadedAtDesc(); }

    public MusicTrack get(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw missing();
        return tracks.findById(id).orElseThrow(MusicLibraryService::missing);
    }

    public Path audio(MusicTrack track) {
        Path path = root.resolve(track.getId() + "." + track.getExtension()).normalize();
        if (!path.getParent().equals(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
        return path;
    }

    public MusicTrack upload(MultipartFile file, String title, String artist, String album, double duration) throws IOException {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("不支持的音频格式");
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new IllegalArgumentException("歌曲不能为空且不能超过 128 MB");
        if (!Double.isFinite(duration) || duration < 0 || duration > 7 * 24 * 3600) throw new IllegalArgumentException("歌曲时长无效");
        var track = new MusicTrack();
        track.setId(UUID.randomUUID().toString());
        track.setExtension(extension);
        track.setTitle(label(title, name.substring(0, dot)));
        track.setArtist(label(artist, "未知艺术家"));
        track.setAlbum(label(album, "未知专辑"));
        track.setDuration(duration);
        track.setUploadedAt(Instant.now());
        Files.createDirectories(root);
        Path temporary = Files.createTempFile(root, ".upload-", ".tmp");
        Path target = root.resolve(track.getId() + "." + extension);
        boolean saved = false;
        try {
            MessageDigest hash = sha256();
            try (var input = new DigestInputStream(file.getInputStream(), hash)) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(temporary);
            if (size == 0 || size > MAX_BYTES) throw new IllegalArgumentException("歌曲大小无效");
            try (var input = Files.newInputStream(temporary)) {
                if (!audioSignature(extension, input.readNBytes(16))) throw new IllegalArgumentException("文件内容不是支持的音频");
            }
            track.setSize(size);
            track.setSha256(HexFormat.of().formatHex(hash.digest()));
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            MusicTrack result = tracks.saveAndFlush(track);
            saved = true;
            return result;
        } finally {
            Files.deleteIfExists(temporary);
            if (!saved) Files.deleteIfExists(target);
        }
    }

    @Transactional
    public void delete(String id) throws IOException {
        MusicTrack track = get(id);
        // Keep the row if the filesystem refuses deletion.
        Path path = root.resolve(track.getId() + "." + track.getExtension());
        Files.deleteIfExists(path);
        tracks.delete(track);
    }

    private static String label(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.strip();
        result = result.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (result.isEmpty()) result = "未命名歌曲";
        if (result.length() > 240) throw new IllegalArgumentException("名称不能超过 240 个字符");
        return result;
    }

    private static boolean starts(byte[] bytes, int offset, String text) {
        if (bytes.length < offset + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if (bytes[offset + i] != text.charAt(i)) return false;
        return true;
    }

    private static boolean audioSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "mp3" -> starts(bytes, 0, "ID3") || (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224);
            case "aac" -> bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 246) == 240;
            case "m4a" -> starts(bytes, 4, "ftyp");
            case "wav" -> starts(bytes, 0, "RIFF") && starts(bytes, 8, "WAVE");
            case "aif", "aiff" -> starts(bytes, 0, "FORM") && (starts(bytes, 8, "AIFF") || starts(bytes, 8, "AIFC"));
            case "caf" -> starts(bytes, 0, "caff");
            case "flac" -> starts(bytes, 0, "fLaC");
            default -> false;
        };
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "歌曲不存在"); }
}
