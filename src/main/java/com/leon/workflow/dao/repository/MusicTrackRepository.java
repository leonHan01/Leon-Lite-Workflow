package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.MusicTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MusicTrackRepository extends JpaRepository<MusicTrack, String> {
    List<MusicTrack> findAllByOrderByUploadedAtDesc();
}
