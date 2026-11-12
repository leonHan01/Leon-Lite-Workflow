package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.dao.repository.MusicPlaylistRepository;
import com.leon.workflow.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:musicplaylists;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
    "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
    "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MusicPlaylistIntegrationTest {
    private static final String AUTH = "Bearer integration-test-token", BASE = "/api/music/playlists";
    private static final Path ROOT;
    static { try { ROOT = Files.createTempDirectory("music-playlists-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("leon.music.directory", ROOT::toString); }
    @Autowired WebApplicationContext context;
    @Autowired MusicLibraryService library;
    @Autowired MusicPlaylistRepository repository;
    @Autowired MusicPlaylistService playlists;
    private MockMvc mvc;
    private final List<String> uploaded = new ArrayList<>();
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }
    @AfterEach void cleanup() throws Exception { repository.deleteAll(); for (String id : uploaded) library.delete(id); }
    private String song() throws Exception {
        byte[] bytes = new byte[44]; System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4); System.arraycopy("WAVE".getBytes(), 0, bytes, 8, 4);
        String id = library.upload(new MockMultipartFile("file", "test.wav", "audio/wav", bytes), "Song", "", "", 12).getId();
        uploaded.add(id); return id;
    }
    private String create(String name) throws Exception {
        var response = mvc.perform(post(BASE).header("Authorization", AUTH).contentType("application/json").content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return com.alibaba.fastjson.JSON.parseObject(response.getResponse().getContentAsString()).getString("id");
    }
    private ResultActions add(String list, String... ids) throws Exception {
        return mvc.perform(post(BASE + "/" + list + "/tracks").header("Authorization", AUTH).contentType("application/json")
            .content(com.alibaba.fastjson.JSON.toJSONString(Map.of("trackIds", Arrays.asList(ids)))));
    }
    @Test void oneSongCanBelongToDifferentPlaylistsAndMembershipIsOrderedAndIdempotent() throws Exception {
        String a = song(), b = song(), first = create("晨间"), second = create("夜晚");
        add(first, b, a, b).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds.length()").value(2)).andExpect(jsonPath("$.trackIds[0]").value(b));
        add(first, a).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds.length()").value(2));
        add(second, a).andExpect(status().isOk());
        mvc.perform(get(BASE).header("Authorization", AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete(BASE + "/" + first + "/tracks/" + a).header("Authorization", AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds.length()").value(1));
        mvc.perform(get(BASE + "/" + second).header("Authorization", AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds[0]").value(a));
        assertTrue(Files.isRegularFile(ROOT.resolve(a + ".wav")));
        mvc.perform(put(BASE + "/" + second).header("Authorization", AUTH).contentType("application/json").content("{\"name\":\"晚安\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("晚安"));
        mvc.perform(delete(BASE + "/" + first).header("Authorization", AUTH)).andExpect(status().isNoContent());
        assertTrue(Files.isRegularFile(ROOT.resolve(a + ".wav"))); assertTrue(Files.isRegularFile(ROOT.resolve(b + ".wav")));
    }
    @Test void deletingAudioCleansMembershipsWithoutDeletingPlaylists() throws Exception {
        String track = song(), first = create("A"), second = create("B"); add(first, track).andExpect(status().isOk()); add(second, track).andExpect(status().isOk());
        mvc.perform(delete("/api/music/tracks/" + track).header("Authorization", AUTH)).andExpect(status().isNoContent()); uploaded.remove(track);
        for (String list : List.of(first, second)) mvc.perform(get(BASE + "/" + list).header("Authorization", AUTH)).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds").isEmpty());
    }
    @Test void invalidChangesAreRejectedAtomically() throws Exception {
        for (String name : new String[]{"", " ", "x".repeat(81)}) mvc.perform(post(BASE).header("Authorization", AUTH).contentType("application/json").content(com.alibaba.fastjson.JSON.toJSONString(Map.of("name", name)))).andExpect(status().isBadRequest());
        String track = song(), list = create("Valid");
        add(list, track, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/" + list).header("Authorization", AUTH)).andExpect(jsonPath("$.trackIds").isEmpty());
        add(list).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).header("Authorization", AUTH)).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/bad-id").header("Authorization", AUTH)).andExpect(status().isBadRequest());
    }
    @Test void cookieWritesRequireOriginAndNativeRequestsRequireToken() throws Exception {
        String list = create("Protected"), track = song();
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE).header("Authorization", "Bearer bad-token")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType("application/json").content("{\"name\":\"No\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + list + "/tracks").contentType("application/json").content("{\"trackIds\":[]}")).andExpect(status().isUnauthorized());
        String header = mvc.perform(post("/auth/login").header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
            .content("{\"token\":\"integration-test-token\",\"remember\":false}")).andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        Cookie cookie = new Cookie(BrowserSessionService.COOKIE_NAME, header.split(";", 2)[0].split("=", 2)[1]);
        mvc.perform(get(BASE).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(put(BASE + "/" + list).cookie(cookie).header("Origin", "https://evil.test").contentType("application/json").content("{\"name\":\"No\"}")).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/" + list).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + list + "/tracks").cookie(cookie).header("Origin", "https://example.test").header("X-Requested-With", "XMLHttpRequest").contentType("application/json")
            .content("{\"trackIds\":[\"" + track + "\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.trackIds[0]").value(track));
    }
}
