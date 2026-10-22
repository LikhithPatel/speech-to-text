package com.example.stt;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TranscriptionRepository extends JpaRepository<Transcription, Long> {
    List<Transcription> findAllByOrderByCreatedAtDesc();
}
