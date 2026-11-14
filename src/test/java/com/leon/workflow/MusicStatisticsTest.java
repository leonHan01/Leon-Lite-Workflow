package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.dao.entity.MusicTrack;
import com.leon.workflow.dao.repository.MusicTrackRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:musicstatstest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
        "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
        "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class MusicStatisticsTest {
    private static final String AUTH = "Bearer integration-test-token";
    @Autowired WebApplicationContext context;
    @Autowired MusicTrackRepository tracks;

    @Test void recordsPlaysForExistingSongsAndRequiresAuthentication() throws Exception {
        MusicTrack track = new MusicTrack();
        track.setId(UUID.randomUUID().toString()); track.setTitle("Song"); track.setArtist("Artist");
        track.setAlbum("Album"); track.setExtension("wav"); track.setSha256("test"); track.setUploadedAt(Instant.now());
        track.setPlayCount(null); tracks.saveAndFlush(track);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String path = "/api/music/tracks/" + track.getId() + "/plays";
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", AUTH)).andExpect(status().isNoContent());
        mvc.perform(post(path).header("Authorization", AUTH)).andExpect(status().isNoContent());
        assertEquals(2L, tracks.findById(track.getId()).orElseThrow().getPlayCount());
        mvc.perform(get("/api/music/tracks").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].playCount").value(2));
        mvc.perform(post("/api/music/tracks/" + UUID.randomUUID() + "/plays").header("Authorization", AUTH)).andExpect(status().isNotFound());
    }
}
