package com.leon.workflow.controller;

import com.leon.workflow.dao.entity.MusicTrack;
import com.leon.workflow.service.MusicLibraryService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MusicControllerTest {
    @Test
    void radioReturnsTheTrackWithoutCaching() {
        MusicLibraryService library = mock(MusicLibraryService.class);
        MusicController controller = new MusicController(library);
        MusicTrack track = new MusicTrack();
        UUID exclude = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        track.setId(exclude.toString());
        when(library.radio(exclude)).thenReturn(Optional.of(track));

        ResponseEntity<MusicTrack> response = controller.radio(exclude);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertSame(track, response.getBody());
    }

    @Test
    void radioReturnsNoContentWhenNothingCanPlay() {
        MusicLibraryService library = mock(MusicLibraryService.class);
        MusicController controller = new MusicController(library);
        when(library.radio(null)).thenReturn(Optional.empty());

        ResponseEntity<MusicTrack> response = controller.radio(null);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertNull(response.getBody());
    }
}
