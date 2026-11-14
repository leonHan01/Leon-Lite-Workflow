package com.leon.workflow.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MusicArtworkTest {
    @TempDir Path root;
    private static final byte[] PNG = {(byte) 0x89, 80, 78, 71, 13, 10, 26, 10, 1};
    private Path file(byte[] data) throws Exception { Path path = root.resolve("song"); Files.write(path, data); return path; }
    private static byte[] id3(byte[] image) throws Exception {
        var frame = new ByteArrayOutputStream(); frame.write(0); frame.write("image/png".getBytes()); frame.write(0); frame.write(3); frame.write(0); frame.write(image);
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeBytes("ID3"); out.writeByte(3); out.writeByte(0); out.writeByte(0);
        int n = frame.size() + 10; out.write(new byte[]{(byte)(n >> 21), (byte)(n >> 14), (byte)(n >> 7), (byte)(n & 127)});
        out.writeBytes("APIC"); out.writeInt(frame.size()); out.writeShort(0); frame.writeTo(out); return bytes.toByteArray();
    }
    @Test void readsId3CoverAndRejectsUnsupportedImages() throws Exception {
        var image = MusicArtwork.read(file(id3(PNG)), "mp3").orElseThrow();
        assertEquals("image/png", image.type()); assertArrayEquals(PNG, image.bytes());
        assertTrue(MusicArtwork.read(file(id3("<svg>bad".getBytes())), "mp3").isEmpty());
        assertTrue(MusicArtwork.read(file(new byte[]{'I','D','3'}), "mp3").isEmpty());
        assertTrue(MusicArtwork.read(file(id3(PNG)), "wav").isEmpty());
    }
    private static byte[] atom(String type, byte[] payload) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeInt(payload.length + 8); out.writeBytes(type); out.write(payload); return bytes.toByteArray();
    }
    @Test void readsM4aCover() throws Exception {
        var data = new ByteArrayOutputStream(); data.write(new byte[8]); data.write(PNG);
        byte[] covr = atom("covr", atom("data", data.toByteArray()));
        byte[] metaPayload = new byte[4 + atom("ilst", covr).length];
        System.arraycopy(atom("ilst", covr), 0, metaPayload, 4, metaPayload.length - 4);
        byte[] file = atom("moov", atom("udta", atom("meta", metaPayload)));
        assertArrayEquals(PNG, MusicArtwork.read(file(file), "m4a").orElseThrow().bytes());
        assertTrue(MusicArtwork.read(file(java.util.Arrays.copyOf(file, file.length - 1)), "m4a").isEmpty());
    }
    @Test void readsFlacPictureAndRejectsTruncation() throws Exception {
        var picture = new ByteArrayOutputStream(); var p = new DataOutputStream(picture);
        p.writeInt(3); p.writeInt(9); p.writeBytes("image/png"); p.writeInt(0);
        p.writeInt(1); p.writeInt(1); p.writeInt(24); p.writeInt(0); p.writeInt(PNG.length); p.write(PNG);
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeBytes("fLaC"); out.writeByte(0x86); out.writeByte(0); out.writeByte(0); out.writeByte(picture.size()); picture.writeTo(out);
        var image = MusicArtwork.read(file(bytes.toByteArray()), "flac").orElseThrow();
        assertArrayEquals(PNG, image.bytes());
        byte[] truncated = java.util.Arrays.copyOf(bytes.toByteArray(), bytes.size() - 3);
        assertTrue(MusicArtwork.read(file(truncated), "flac").isEmpty());
    }
}
