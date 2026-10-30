package com.leon.workflow.service;

import com.leon.workflow.dao.entity.StickyNote;
import com.leon.workflow.dao.repository.StickyNoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StickyNoteServiceTest {
    @Mock
    private StickyNoteRepository noteRepository;

    @InjectMocks
    private StickyNoteService stickyNoteService;

    @Test
    void createNotePersistsContent() {
        when(noteRepository.save(any())).thenAnswer(invocation -> {
            StickyNote note = invocation.getArgument(0);
            note.setId(5L);
            return note;
        });

        Map<String, Object> created = stickyNoteService.createNote(Map.of("content", "一条便签"));

        assertEquals(5L, created.get("id"));
        assertEquals("一条便签", created.get("content"));
        assertEquals(false, created.get("archived"));
    }

    @Test
    void updateNoteChangesContent() {
        StickyNote note = new StickyNote();
        note.setId(5L);
        note.setContent("旧");
        note.setDeleted(false);
        note.setArchived(false);
        when(noteRepository.findById(5L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = stickyNoteService.updateNote(5L, Map.of("content", "新内容"));

        assertEquals("新内容", updated.get("content"));
        assertEquals(false, updated.get("archived"));
    }

    @Test
    void updateNoteArchivesWithoutChangingContent() {
        StickyNote note = new StickyNote();
        note.setId(5L);
        note.setContent("内容");
        note.setDeleted(false);
        note.setArchived(false);
        when(noteRepository.findById(5L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> updated = stickyNoteService.updateNote(5L, Map.of("archived", true));

        assertEquals("内容", updated.get("content"));
        assertEquals(true, updated.get("archived"));
    }

    @Test
    void deleteNoteSoftDeletes() {
        StickyNote note = new StickyNote();
        note.setId(5L);
        note.setDeleted(false);
        note.setArchived(true);
        when(noteRepository.findById(5L)).thenReturn(Optional.of(note));

        stickyNoteService.deleteNote(5L);

        assertTrue(note.getDeleted());
        verify(noteRepository).save(note);
    }

    @Test
    void deleteNoteRejectsUnarchived() {
        StickyNote note = new StickyNote();
        note.setId(5L);
        note.setDeleted(false);
        note.setArchived(false);
        when(noteRepository.findById(5L)).thenReturn(Optional.of(note));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> stickyNoteService.deleteNote(5L));
        assertEquals("请先归档再删除便签", error.getMessage());
    }

    @Test
    void blankContentRejected() {
        assertThrows(IllegalArgumentException.class, () -> stickyNoteService.createNote(Map.of()));
    }

    @Test
    void missingNoteThrows() {
        when(noteRepository.findById(8L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> stickyNoteService.deleteNote(8L));
    }

    @Test
    void listNotesReturnsMappedRows() {
        StickyNote note = new StickyNote();
        note.setId(1L);
        note.setContent("hello");
        note.setArchived(true);
        when(noteRepository.findByDeletedFalseOrderByCreateTimeAscIdAsc()).thenReturn(List.of(note));

        assertEquals(1, stickyNoteService.listNotes().size());
        assertEquals("hello", stickyNoteService.listNotes().get(0).get("content"));
        assertEquals(true, stickyNoteService.listNotes().get(0).get("archived"));
    }
}
