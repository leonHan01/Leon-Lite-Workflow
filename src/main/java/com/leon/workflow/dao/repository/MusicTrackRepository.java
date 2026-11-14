package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.MusicTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MusicTrackRepository extends JpaRepository<MusicTrack, String> {
    List<MusicTrack> findAllByOrderByUploadedAtDesc();
    @Modifying
    @Query("update MusicTrack t set t.playCount = coalesce(t.playCount, 0) + 1 where t.id = :id")
    int incrementPlayCount(@Param("id") String id);
    java.util.Optional<MusicTrack> findFirstBySha256AndSizeAndExtensionOrderByUploadedAtAsc(String sha256, long size, String extension);
    @Query("select t.id from MusicTrack t where (:excluded is null or t.id <> :excluded)")
    List<String> findRadioCandidates(@Param("excluded") String excluded);
}
