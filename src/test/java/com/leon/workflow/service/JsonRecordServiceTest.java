package com.leon.workflow.service;

import com.leon.workflow.dao.entity.JsonRecord;
import com.leon.workflow.dao.repository.JsonRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JsonRecordServiceTest {
    private final JsonRecordRepository repository = mock(JsonRecordRepository.class);
    private final JsonRecordService service = new JsonRecordService(repository, new JsonRecordParser());

    @Test void concurrentDuplicateReturnsTheWinningRecord() {
        JsonRecord winner = new JsonRecord(); winner.setId(1L);
        when(repository.findByContentHash(anyString())).thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertSame(winner, service.save("{\"test\":1}"));
    }

    @Test void otherDatabaseFailuresAreNotHiddenAsDuplicateSuccess() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("constraint failure");
        when(repository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.save("{\"test\":1}")));
    }

    @Test void matchingKeepsFieldPriorityAndBuildsPreviewFromTheSameField() {
        JsonRecord record = new JsonRecord(); record.setId(3L);
        record.setReqJson("{\"req\":\"MATCH\"}");
        record.setRespJson("{\"resp\":\"match\"}");
        record.setBodyJson("{\"body\":1}");
        record.setRawText("original match");
        when(repository.search(anyString(), any())).thenReturn(List.of(record));
        var requestMatch = service.search(" match ", 10).getFirst();
        assertEquals("reqJson", requestMatch.getMatchField());
        assertEquals(record.getReqJson(), requestMatch.getPreview());
        record.setReqJson(null);
        var responseMatch = service.search("match", 10).getFirst();
        assertEquals("respJson", responseMatch.getMatchField());
        assertEquals(record.getRespJson(), responseMatch.getPreview());
        assertEquals("respJson", service.search("", 10).getFirst().getMatchField());
        record.setRespJson(null);
        assertEquals("bodyJson", service.search("absent", 10).getFirst().getMatchField());
        record.setBodyJson(null);
        assertEquals("rawText", service.search("", 10).getFirst().getMatchField());
    }
}
