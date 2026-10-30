package com.leon.workflow.service;

import com.leon.workflow.dao.entity.StickyNote;
import com.leon.workflow.dao.repository.StickyNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leon.workflow.service.Payloads.booleanValue;
import static com.leon.workflow.service.Payloads.requiredString;

@Service
public class StickyNoteService {
    private final StickyNoteRepository noteRepository;

    public StickyNoteService(StickyNoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public List<Map<String, Object>> listNotes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StickyNote note : noteRepository.findByDeletedFalseOrderByCreateTimeAscIdAsc()) {
            result.add(toResponse(note));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createNote(Map<String, Object> request) {
        LocalDateTime now = LocalDateTime.now();
        StickyNote note = new StickyNote();
        note.setContent(requiredString(request.get("content"), "便签内容不能为空"));
        note.setArchived(request.containsKey("archived") && booleanValue(request.get("archived")));
        note.setDeleted(false);
        note.setCreateTime(now);
        note.setUpdateTime(now);
        return toResponse(noteRepository.save(note));
    }

    @Transactional
    public Map<String, Object> updateNote(Long id, Map<String, Object> request) {
        StickyNote note = requireNote(id);
        if (request.containsKey("content")) {
            note.setContent(requiredString(request.get("content"), "便签内容不能为空"));
        }
        if (request.containsKey("archived")) {
            note.setArchived(booleanValue(request.get("archived")));
        }
        note.setUpdateTime(LocalDateTime.now());
        return toResponse(noteRepository.save(note));
    }

    @Transactional
    public void deleteNote(Long id) {
        StickyNote note = requireNote(id);
        if (!Boolean.TRUE.equals(note.getArchived())) {
            throw new IllegalArgumentException("请先归档再删除便签");
        }
        note.setDeleted(true);
        note.setUpdateTime(LocalDateTime.now());
        noteRepository.save(note);
    }

    private StickyNote requireNote(Long id) {
        return noteRepository.findById(id)
                .filter(note -> !Boolean.TRUE.equals(note.getDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("便签不存在: " + id));
    }

    private Map<String, Object> toResponse(StickyNote note) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", note.getId());
        result.put("content", note.getContent());
        result.put("archived", Boolean.TRUE.equals(note.getArchived()));
        result.put("createTime", note.getCreateTime());
        result.put("updateTime", note.getUpdateTime());
        return result;
    }
}
