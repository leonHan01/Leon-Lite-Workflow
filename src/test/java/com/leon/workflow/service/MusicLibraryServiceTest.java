package com.leon.workflow.service;

import com.leon.workflow.dao.entity.MusicTrack;
import com.leon.workflow.dao.repository.MusicTrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicLibraryServiceTest {
    @TempDir
    Path root;

    private MusicTrackRepository tracks;
    private MusicLibraryService library;

    @BeforeEach
    void setUp() {
        tracks = mock(MusicTrackRepository.class);
        library = new MusicLibraryService(tracks, root.toString());
    }

    @Test
    void radioIsEmptyWhenTheLibraryHasNoSongs() {
        when(tracks.findRadioCandidates(null)).thenReturn(List.of());

        assertTrue(library.radio(null).isEmpty());

        verify(tracks, times(1)).findRadioCandidates(null);
        verify(tracks, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void radioReturnsTheOnlyPlayableFile() throws Exception {
        MusicTrack only = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "mp3");
        Files.writeString(root.resolve(only.getId() + ".mp3"), "audio");
        when(tracks.findRadioCandidates(null)).thenReturn(List.of(only.getId()));
        when(tracks.findById(only.getId())).thenReturn(Optional.of(only));

        assertEquals(only.getId(), library.radio(null).orElseThrow().getId());
    }

    @Test
    void radioReplaysTheOnlySongWhenItWasJustPlayed() throws Exception {
        MusicTrack only = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "wav");
        Files.writeString(root.resolve(only.getId() + ".wav"), "audio");
        UUID excluded = UUID.fromString(only.getId());
        when(tracks.findRadioCandidates(only.getId())).thenReturn(List.of());
        when(tracks.findRadioCandidates(null)).thenReturn(List.of(only.getId()));
        when(tracks.findById(only.getId())).thenReturn(Optional.of(only));

        assertEquals(only.getId(), library.radio(excluded).orElseThrow().getId());
    }

    @Test
    void radioUsesTheCandidateListFromTheRepository() throws Exception {
        MusicTrack next = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "flac");
        Files.writeString(root.resolve(next.getId() + ".flac"), "audio");
        String previous = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
        when(tracks.findRadioCandidates(previous)).thenReturn(List.of(next.getId()));
        when(tracks.findById(next.getId())).thenReturn(Optional.of(next));

        assertEquals(next.getId(), library.radio(UUID.fromString(previous)).orElseThrow().getId());

        verify(tracks, never()).findRadioCandidates(null);
    }

    @Test
    void radioSkipsACandidateDeletedWhileChoosing() {
        String gone = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
        when(tracks.findRadioCandidates(null)).thenReturn(List.of(gone));
        when(tracks.findById(gone)).thenReturn(Optional.empty());

        assertTrue(library.radio(null).isEmpty());
    }

    @Test
    void radioSkipsACandidateWhoseFileIsMissing() {
        MusicTrack missing = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "mp3");
        when(tracks.findRadioCandidates(null)).thenReturn(List.of(missing.getId()));
        when(tracks.findById(missing.getId())).thenReturn(Optional.of(missing));

        assertTrue(library.radio(null).isEmpty());
    }

    @Test
    void radioSkipsSymlinks() throws Exception {
        MusicTrack linked = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3", "mp3");
        Files.writeString(root.resolve("outside.mp3"), "audio");
        Files.createSymbolicLink(root.resolve(linked.getId() + ".mp3"), root.resolve("outside.mp3"));
        when(tracks.findRadioCandidates(null)).thenReturn(List.of(linked.getId()));
        when(tracks.findById(linked.getId())).thenReturn(Optional.of(linked));

        assertTrue(library.radio(null).isEmpty());
    }

    @Test
    void radioFallsBackToTheExcludedSongWhenNothingElseCanPlay() throws Exception {
        MusicTrack broken = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "mp3");
        MusicTrack previous = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1", "wav");
        Files.writeString(root.resolve(previous.getId() + ".wav"), "audio");
        when(tracks.findRadioCandidates(previous.getId())).thenReturn(List.of(broken.getId()));
        when(tracks.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(tracks.findById(previous.getId())).thenReturn(Optional.of(previous));

        assertEquals(previous.getId(), library.radio(UUID.fromString(previous.getId())).orElseThrow().getId());
    }

    @Test
    void radioIsEmptyWhenEveryAudioFileIsGone() throws Exception {
        MusicTrack broken = track("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2", "mp3");
        String previous = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
        when(tracks.findRadioCandidates(previous)).thenReturn(List.of(broken.getId()));
        when(tracks.findById(broken.getId())).thenReturn(Optional.of(broken));
        when(tracks.findById(previous)).thenReturn(Optional.empty());

        assertTrue(library.radio(UUID.fromString(previous)).isEmpty());
    }

    private static MusicTrack track(String id, String extension) {
        MusicTrack track = new MusicTrack();
        track.setId(id);
        track.setExtension(extension);
        return track;
    }
}
