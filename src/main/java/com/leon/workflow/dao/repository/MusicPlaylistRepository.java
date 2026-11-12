package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.MusicPlaylist;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface MusicPlaylistRepository extends JpaRepository<MusicPlaylist, String> {
    @EntityGraph(attributePaths = {"items", "items.track"})
    List<MusicPlaylist> findAllByOrderByCreatedAtAscIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MusicPlaylist p where p.id = :id")
    Optional<MusicPlaylist> lockById(@Param("id") String id);
}
