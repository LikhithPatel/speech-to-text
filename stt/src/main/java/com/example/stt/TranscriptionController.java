package com.example.stt;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    public record TranscriptionRequest(String title, String text) {}

    private final TranscriptionRepository repo;

    public TranscriptionController(TranscriptionRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Transcription> all() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Transcription> one(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Transcription> create(@RequestBody TranscriptionRequest req) {
        if (req.text() == null || req.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Transcription t = new Transcription();
        t.setTitle(req.title());
        t.setText(req.text());
        return ResponseEntity.ok(repo.save(t));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Transcription> update(@PathVariable Long id,
                                                @RequestBody TranscriptionRequest req) {
        if (req.text() == null || req.text().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return repo.findById(id).map(t -> {
            t.setTitle(req.title());
            t.setText(req.text());
            return ResponseEntity.ok(repo.save(t));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
