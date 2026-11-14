package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.service.BrowserSessionService;
import com.leon.workflow.service.MusicLibraryService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:musictest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MusicIntegrationTest {
    private static final String AUTH = "Bearer integration-test-token";
    private static final Path ROOT;
    static {
        try { ROOT = Files.createTempDirectory("music-integration-"); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("leon.music.directory", ROOT::toString); }
    @Autowired WebApplicationContext context;
    @Autowired MusicLibraryService library;
    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }

    private MockMultipartFile audio(String name) {
        byte[] bytes = new byte[44];
        System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, bytes, 8, 4);
        return new MockMultipartFile("file", name, "audio/wav", bytes);
    }

    @Test void uploadListRangeDownloadAndDeleteRequireAuthentication() throws Exception {
        var mvc = mvc();
        mvc.perform(get("/api/music/tracks")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/music/tracks").file(audio("song.wav"))).andExpect(status().isUnauthorized());
        var saved = mvc.perform(multipart("/api/music/tracks").file(audio("../../song.wav"))
                .param("artist", "测试艺术家").param("duration", "12.5").header("Authorization", AUTH))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("song"))
                .andExpect(jsonPath("$.artist").value("测试艺术家"))
                .andExpect(jsonPath("$.size").value(44)).andReturn();
        String id = com.alibaba.fastjson.JSON.parseObject(saved.getResponse().getContentAsString()).getString("id");
        assertTrue(Files.exists(ROOT.resolve(id + ".wav")));
        assertFalse(Files.exists(ROOT.getParent().resolve("song.wav")));
        mvc.perform(get("/api/music/tracks").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].sha256").isString());
        mvc.perform(get("/api/music/tracks/" + id + "/artwork")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/music/tracks/" + id + "/artwork").header("Authorization", AUTH)).andExpect(status().isNoContent());
        mvc.perform(get("/api/music/tracks/" + id + "/audio")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/music/tracks/" + id + "/audio").header("Authorization", AUTH))
                .andExpect(status().isOk()).andExpect(content().bytes(audio("x.wav").getBytes()));
        mvc.perform(get("/api/music/tracks/" + id + "/audio").header("Authorization", AUTH).header("Range", "bytes=0-3"))
                .andExpect(status().isPartialContent()).andExpect(content().bytes("RIFF".getBytes()));
        mvc.perform(delete("/api/music/tracks/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/music/tracks/" + id).header("Authorization", AUTH)).andExpect(status().isNoContent());
        assertFalse(Files.exists(ROOT.resolve(id + ".wav")));
        mvc.perform(get("/api/music/tracks/" + id + "/audio").header("Authorization", AUTH)).andExpect(status().isNotFound());
    }

    @Test void editsMetadataAtomicallyAndStoresArtworkWithoutChangingAudio() throws Exception {
        var mvc = mvc();
        var saved = mvc.perform(multipart("/api/music/tracks").file(audio("edit.wav")).header("Authorization", AUTH))
                .andExpect(status().isCreated()).andReturn();
        String id = com.alibaba.fastjson.JSON.parseObject(saved.getResponse().getContentAsString()).getString("id");
        String edit = "{\"ids\":[\"" + id + "\"],\"title\":\"新歌名\",\"artist\":\"歌手\",\"album\":\"专辑\"}";
        mvc.perform(patch("/api/music/tracks/metadata").contentType("application/json").content(edit)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/music/tracks/metadata").header("Authorization", AUTH).contentType("application/json").content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("新歌名"));
        String bad = "{\"ids\":[\"" + id + "\",\"00000000-0000-0000-0000-000000000000\"],\"artist\":\"不应保存\"}";
        mvc.perform(patch("/api/music/tracks/metadata").header("Authorization", AUTH).contentType("application/json").content(bad)).andExpect(status().isNotFound());
        assertEquals("歌手", library.get(id).getArtist());
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", out); byte[] png = out.toByteArray();
        mvc.perform(multipart("/api/music/tracks/" + id + "/artwork").file(new MockMultipartFile("file", "cover.png", "image/png", png)).header("Authorization", AUTH)).andExpect(status().isNoContent());
        mvc.perform(get("/api/music/tracks/" + id + "/artwork").header("Authorization", AUTH)).andExpect(status().isOk()).andExpect(content().bytes(png));
        mvc.perform(get("/api/music/tracks/" + id + "/audio").header("Authorization", AUTH)).andExpect(content().bytes(audio("edit.wav").getBytes()));
        mvc.perform(delete("/api/music/tracks/" + id + "/artwork").header("Authorization", AUTH)).andExpect(status().isNoContent());
        mvc.perform(get("/api/music/tracks/" + id + "/artwork").header("Authorization", AUTH)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/music/tracks/" + id).header("Authorization", AUTH)).andExpect(status().isNoContent());
    }

    @Test void browserUploadsAndDeletesRequireTrustedOrigin() throws Exception {
        var mvc = mvc();
        String cookieHeader = mvc.perform(post("/auth/login").header("Origin", "https://example.test")
                .header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
                .content("{\"token\":\"integration-test-token\",\"remember\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        var cookie = new Cookie(BrowserSessionService.COOKIE_NAME, cookieHeader.split(";", 2)[0].split("=", 2)[1]);
        mvc.perform(multipart("/api/music/tracks").file(audio("song.wav")).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/music/tracks").file(audio("song.wav")).cookie(cookie)
                .header("Origin", "https://evil.test").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden());
        var saved = mvc.perform(multipart("/api/music/tracks").file(audio("browser.wav")).cookie(cookie)
                .header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isCreated()).andReturn();
        String id = com.alibaba.fastjson.JSON.parseObject(saved.getResponse().getContentAsString()).getString("id");
        mvc.perform(get("/api/music/tracks/" + id + "/artwork").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/api/music/tracks/" + id + "/audio").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(delete("/api/music/tracks/" + id).cookie(cookie).header("Origin", "https://evil.test"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/music/tracks/" + id).cookie(cookie).header("Origin", "https://example.test")
                .header("X-Requested-With", "XMLHttpRequest")).andExpect(status().isNoContent());
    }

    @Test void rejectsInvalidFilesMetadataAndUnknownPaths() throws Exception {
        var mvc = mvc();
        for (String name : new String[]{"bad.exe", "bad.mp3"}) {
            mvc.perform(multipart("/api/music/tracks").file(new MockMultipartFile("file", name, "audio/mpeg", "<html>bad</html>".getBytes()))
                    .header("Authorization", AUTH)).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/music/tracks").file(audio("x.wav")).param("duration", "NaN").header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/music/tracks").file(audio("x.wav")).param("title", "x".repeat(241)).header("Authorization", AUTH))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/music/tracks/not-a-uuid/audio").header("Authorization", AUTH)).andExpect(status().isNotFound());
        try (var files = Files.list(ROOT)) { assertFalse(files.anyMatch(file -> file.getFileName().toString().startsWith(".upload-"))); }
    }

    @Test void radioReturnsOneSongAndAvoidsPreviousWhenPossible() throws Exception {
        var mvc = mvc();
        mvc.perform(get("/api/music/tracks/radio")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/music/tracks/radio").header("Authorization", AUTH)).andExpect(status().isNoContent());
        var first = library.upload(audio("first.wav"), "First", "", "", 0);
        try {
            mvc.perform(get("/api/music/tracks/radio").header("Authorization", AUTH).param("exclude", first.getId()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.id").value(first.getId())).andExpect(jsonPath("$.sha256").isString());
            var second = library.upload(audio("second.wav"), "Second", "", "", 0);
            try {
                for (int i = 0; i < 8; i++) {
                    mvc.perform(get("/api/music/tracks/radio").header("Authorization", AUTH).param("exclude", first.getId()))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(second.getId()));
                }
                mvc.perform(get("/api/music/tracks/radio").header("Authorization", AUTH).param("exclude", "invalid"))
                        .andExpect(status().isBadRequest());
                Files.delete(ROOT.resolve(second.getId() + ".wav"));
                mvc.perform(get("/api/music/tracks/radio").header("Authorization", AUTH).param("exclude", first.getId()))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first.getId()));
            } finally { library.delete(second.getId()); }
        } finally { library.delete(first.getId()); }
    }

    @Test void radioIgnoresMissingAudioFiles() throws Exception {
        var song = library.upload(audio("missing.wav"), "Missing", "", "", 0);
        try {
            Files.delete(ROOT.resolve(song.getId() + ".wav"));
            mvc().perform(get("/api/music/tracks/radio").header("Authorization", AUTH)).andExpect(status().isNoContent());
        } finally { library.delete(song.getId()); }
    }
}
