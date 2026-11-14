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
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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

    public Optional<MusicTrack> radio(UUID excluded) {
        List<String> candidates = new ArrayList<>(tracks.findRadioCandidates(excluded == null ? null : excluded.toString()));
        // A library with one song can keep playing that song.
        if (candidates.isEmpty() && excluded != null) candidates = new ArrayList<>(tracks.findRadioCandidates(null));
        // Skip removed/missing files, including songs deleted during selection.
        while (!candidates.isEmpty()) {
            int index = ThreadLocalRandom.current().nextInt(candidates.size());
            String id = candidates.remove(index);
            Optional<MusicTrack> track = tracks.findById(id);
            if (track.isPresent()) {
                Path file = root.resolve(id + "." + track.get().getExtension());
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return track;
            }
        }
        if (excluded != null) {
            Optional<MusicTrack> last = tracks.findById(excluded.toString());
            if (last.isPresent() && Files.isRegularFile(root.resolve(last.get().getId() + "." + last.get().getExtension()), LinkOption.NOFOLLOW_LINKS)) return last;
        }
        return Optional.empty();
    }

    public MusicTrack get(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw missing();
        return tracks.findById(id).orElseThrow(MusicLibraryService::missing);
    }

    public record MetadataEdit(List<String> ids, String title, String artist, String album) {}

    @Transactional
    public List<MusicTrack> editMetadata(MetadataEdit edit) {
        if (edit == null || edit.ids() == null || edit.ids().isEmpty() || edit.ids().size() > 100
                || new HashSet<>(edit.ids()).size() != edit.ids().size())
            throw new IllegalArgumentException("请选择 1 到 100 首不同的歌曲");
        if (edit.title() == null && edit.artist() == null && edit.album() == null)
            throw new IllegalArgumentException("请至少修改一个字段");
        String title = edit.title() == null ? null : editLabel(edit.title());
        String artist = edit.artist() == null ? null : editLabel(edit.artist());
        String album = edit.album() == null ? null : editLabel(edit.album());
        List<MusicTrack> result = new ArrayList<>();
        for (String id : edit.ids()) {
            MusicTrack track = get(id);
            if (title != null) track.setTitle(title);
            if (artist != null) track.setArtist(artist);
            if (album != null) track.setAlbum(album);
            result.add(track);
        }
        return tracks.saveAll(result);
    }

    private static String editLabel(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (cleaned.isEmpty() || cleaned.length() > 240)
            throw new IllegalArgumentException("名称不能为空且不能超过 240 个字符");
        return cleaned;
    }

    @Transactional
    public void setArtwork(String id, MultipartFile image) throws IOException {
        MusicTrack track = get(id);
        if (image.isEmpty() || image.getSize() > 2 * 1024 * 1024)
            throw new IllegalArgumentException("封面不能为空且不能超过 2 MB");
        byte[] bytes = image.getBytes();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("封面必须是 JPEG 或 PNG");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("png")) throw new IllegalArgumentException("封面必须是 JPEG 或 PNG");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 16_000_000 || reader.read(0) == null)
                    throw new IllegalArgumentException("封面尺寸无效");
                track.setCustomArtwork(bytes);
                track.setCustomArtworkType(format.equals("png") ? "image/png" : "image/jpeg");
            } finally { reader.dispose(); }
        }
        tracks.save(track);
    }

    @Transactional
    public void clearArtwork(String id) {
        MusicTrack track = get(id);
        track.setCustomArtwork(null);
        track.setCustomArtworkType(null);
        tracks.save(track);
    }

    @Transactional
    public void recordPlay(String id) {
        if (id == null || !id.matches("[0-9a-f-]{36}") || tracks.incrementPlayCount(id) == 0) throw missing();
    }

    public Path audio(MusicTrack track) {
        Path path = root.resolve(track.getId() + "." + track.getExtension()).normalize();
        if (!path.getParent().equals(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
        return path;
    }

    public MusicTrack upload(MultipartFile file, String title, String artist, String album, double duration) throws IOException {
        return upload(file, title, artist, album, duration, false);
    }

    public synchronized MusicTrack upload(MultipartFile file, String title, String artist, String album, double duration, boolean reuseExisting) throws IOException {
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
            if (reuseExisting) {
                var existing = tracks.findFirstBySha256AndSizeAndExtensionOrderByUploadedAtAsc(track.getSha256(), size, extension);
                if (existing.isPresent() && Files.isRegularFile(root.resolve(existing.get().getId() + "." + extension), LinkOption.NOFOLLOW_LINKS)) return existing.get();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            MusicTrack result = tracks.saveAndFlush(track);
            saved = true;
            return result;
        } finally {
            Files.deleteIfExists(temporary);
            if (!saved) Files.deleteIfExists(target);
        }
    }

    @Transactional(rollbackFor = IOException.class)
    public void delete(String id) throws IOException {
        MusicTrack track = get(id);
        // Flush constraint checks first; filesystem failures roll the transaction back.
        Path path = root.resolve(track.getId() + "." + track.getExtension());
        if (!path.normalize().getParent().equals(root)) throw new IOException("Invalid music path");
        tracks.delete(track);
        tracks.flush();
        Files.deleteIfExists(path);
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
