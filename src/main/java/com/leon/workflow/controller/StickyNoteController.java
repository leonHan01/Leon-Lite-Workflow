package com.leon.workflow.controller;

import com.leon.workflow.service.StickyNoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.leon.workflow.controller.ApiResponses.ok;

@RestController
@RequestMapping("/api/notes")
public class StickyNoteController {
    private final StickyNoteService stickyNoteService;

    public StickyNoteController(StickyNoteService stickyNoteService) {
        this.stickyNoteService = stickyNoteService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listNotes() {
        return ok(stickyNoteService.listNotes());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createNote(@RequestBody Map<String, Object> request) {
        return ok(stickyNoteService.createNote(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateNote(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> request) {
        return ok(stickyNoteService.updateNote(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteNote(@PathVariable Long id) {
        stickyNoteService.deleteNote(id);
        return ok("deleted");
    }
}
