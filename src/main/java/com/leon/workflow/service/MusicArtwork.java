package com.leon.workflow.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Reads bounded embedded artwork without loading the audio stream. */
public final class MusicArtwork {
    private static final int MAX_TAG = 12 * 1024 * 1024, MAX_IMAGE = 8 * 1024 * 1024;
    public record Image(byte[] bytes, String type) {}
    private MusicArtwork() {}

    public static Optional<Image> read(Path path, String extension) throws IOException {
        try (var file = new RandomAccessFile(path.toFile(), "r")) {
            return switch (extension) {
                case "mp3" -> id3(file);
                case "flac" -> flac(file);
                case "m4a" -> mp4(file, 0, file.length(), 0, new int[]{0}, false);
                default -> Optional.empty();
            };
        }
    }

    private static Optional<Image> id3(RandomAccessFile file) throws IOException {
        if (file.length() < 10) return Optional.empty();
        byte[] header = new byte[10]; file.readFully(header);
        int version = header[3] & 255;
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3' || version < 2 || version > 4
                || (header[5] & 0xc0) != 0 || !synchsafe(header, 6)) return Optional.empty();
        int length = syncInt(header, 6);
        if (length > MAX_TAG || 10L + length > file.length()) return Optional.empty();
        byte[] data = new byte[length]; file.readFully(data);
        int pos = 0, frameHeader = version == 2 ? 6 : 10;
        while (pos + frameHeader <= data.length) {
            int size = version == 2 ? int24(data, pos + 3) : version == 4
                    ? (synchsafe(data, pos + 4) ? syncInt(data, pos + 4) : -1) : int32(data, pos + 4);
            int start = pos + frameHeader;
            if (size <= 0 || size > data.length - start) break;
            boolean picture = version == 2 ? matches(data, pos, "PIC") : matches(data, pos, "APIC");
            // Compressed, encrypted, grouped, or unsynchronised frames need decoding first.
            if (picture && (version == 2 || (data[pos + 9] & (version == 3 ? 0xe0 : 0x4f)) == 0)) {
                int p = start, end = start + size;
                if (p >= end) break;
                int encoding = data[p++] & 255;
                if (encoding > 3) break;
                if (version == 2) p += 3;
                else { while (p < end && data[p] != 0) p++; p++; }
                p++; // picture type
                int width = encoding == 1 || encoding == 2 ? 2 : 1;
                while (p + width <= end) {
                    boolean zero = data[p] == 0 && (width == 1 || data[p + 1] == 0);
                    p += width;
                    if (zero) break;
                }
                if (p <= end) return image(data, p, end - p);
            }
            pos = start + size;
        }
        return Optional.empty();
    }

    private static Optional<Image> flac(RandomAccessFile file) throws IOException {
        if (file.length() < 4 || file.readInt() != 0x664c6143) return Optional.empty();
        long scanned = 4;
        while (scanned + 4 <= file.length() && scanned < MAX_TAG) {
            int type = file.readUnsignedByte(), size = (file.readUnsignedByte() << 16) | (file.readUnsignedByte() << 8) | file.readUnsignedByte();
            scanned += 4;
            if (size > MAX_TAG - scanned || scanned + size > file.length()) break;
            if ((type & 127) == 6) {
                byte[] data = new byte[size]; file.readFully(data);
                if (size < 32) return Optional.empty();
                int mime = int32(data, 4);
                if (mime < 0 || mime > size - 12) return Optional.empty();
                int descPos = 8 + mime, desc = int32(data, descPos);
                if (desc < 0 || desc > size - descPos - 24) return Optional.empty();
                int imageSizePos = descPos + 4 + desc + 16, imageSize = int32(data, imageSizePos);
                if (imageSize < 0 || imageSize > size - imageSizePos - 4) return Optional.empty();
                return image(data, imageSizePos + 4, imageSize);
            }
            file.seek(file.getFilePointer() + size); scanned += size;
            if ((type & 128) != 0) break;
        }
        return Optional.empty();
    }

    private static Optional<Image> mp4(RandomAccessFile file, long start, long end, int depth, int[] count, boolean inCover) throws IOException {
        if (depth > 6) return Optional.empty();
        long pos = start;
        while (pos <= end - 8 && count[0]++ < 10000) {
            file.seek(pos);
            long size = Integer.toUnsignedLong(file.readInt());
            int type = file.readInt();
            int header = 8;
            if (size == 1) { if (pos > end - 16) break; size = file.readLong(); header = 16; }
            else if (size == 0) size = end - pos;
            if (size < header || size > end - pos) break;
            long payload = pos + header, next = pos + size;
            if (type == 0x64617461 && inCover && size - header >= 8 && size - header - 8 <= MAX_IMAGE) { // data inside covr
                file.seek(payload + 8);
                byte[] data = new byte[(int) (size - header - 8)]; file.readFully(data);
                return image(data, 0, data.length);
            }
            if (type == 0x6d6f6f76 || type == 0x75647461 || type == 0x6d657461 || type == 0x696c7374 || type == 0x636f7672) {
                long childStart = payload + (type == 0x6d657461 ? 4 : 0);
                if (childStart <= next) {
                    Optional<Image> result = mp4(file, childStart, next, depth + 1, count, type == 0x636f7672);
                    if (result.isPresent()) return result;
                }
            }
            pos = next;
        }
        return Optional.empty();
    }

    private static Optional<Image> image(byte[] data, int offset, int size) {
        if (size < 8 || size > MAX_IMAGE || offset < 0 || offset > data.length - size) return Optional.empty();
        String type;
        if ((data[offset] & 255) == 255 && (data[offset + 1] & 255) == 216 && (data[offset + 2] & 255) == 255) type = "image/jpeg";
        else if (matches(data, offset, "\u0089PNG\r\n\u001a\n")) type = "image/png";
        else if (matches(data, offset, "GIF87a") || matches(data, offset, "GIF89a")) type = "image/gif";
        else if (size >= 12 && matches(data, offset, "RIFF") && matches(data, offset + 8, "WEBP")) type = "image/webp";
        else return Optional.empty();
        return Optional.of(new Image(Arrays.copyOfRange(data, offset, offset + size), type));
    }
    private static boolean matches(byte[] b, int p, String s) {
        if (p < 0 || p + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if ((b[p + i] & 255) != s.charAt(i)) return false;
        return true;
    }
    private static boolean synchsafe(byte[] b, int p) { return ((b[p] | b[p + 1] | b[p + 2] | b[p + 3]) & 0x80) == 0; }
    private static int syncInt(byte[] b, int p) { return (b[p] << 21) | (b[p + 1] << 14) | (b[p + 2] << 7) | b[p + 3]; }
    private static int int24(byte[] b, int p) { return ((b[p] & 255) << 16) | ((b[p + 1] & 255) << 8) | (b[p + 2] & 255); }
    private static int int32(byte[] b, int p) { return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255); }
}
