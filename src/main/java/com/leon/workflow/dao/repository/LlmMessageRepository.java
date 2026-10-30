package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.LlmMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LlmMessageRepository extends JpaRepository<LlmMessage, Long> {
    interface SessionMessageCount {
        Long getSessionId();
        int getMessageCount();
    }

    @Query("select m.sessionId as sessionId, count(m) as messageCount from LlmMessage m "
            + "where m.deleted = false and m.sessionId in :ids group by m.sessionId")
    List<SessionMessageCount> countMessagesBySessionIds(@Param("ids") List<Long> ids);

    List<LlmMessage> findBySessionIdInAndDeletedFalseOrderByIdAsc(List<Long> sessionIds);

    List<LlmMessage> findByDeletedFalseOrderByIdAsc();

    List<LlmMessage> findBySessionIdAndDeletedFalseOrderByIdAsc(Long sessionId);

    List<LlmMessage> findBySessionIdIsNullAndDeletedFalseOrderByIdAsc();
}
