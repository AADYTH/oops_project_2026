package com.cropscanner.controller;

import com.cropscanner.entity.ReminderEntity;
import com.cropscanner.repository.ReminderJpaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Treatment tracking (Tier 3 upgrade #10): reminders are generated
 * automatically when a scan is saved (see {@code ScanPersistenceService}).
 * This controller just exposes them so a client can poll for due
 * reminders and fire a local notification, and lets the user mark one
 * done.
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class RemindersController {

    private final ReminderJpaRepository reminderRepository;

    public RemindersController(ReminderJpaRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    public record ReminderView(Long id, UUID scanId, String cropName, String diseaseName, String remedyName,
                                String type, Instant dueAt, boolean completed, boolean dueNow) {
        static ReminderView from(ReminderEntity e) {
            return new ReminderView(e.getId(), e.getScanId(), e.getCropName(), e.getDiseaseName(), e.getRemedyName(),
                    e.getType().name(), e.getDueAt(), e.isCompleted(), !e.isCompleted() && !e.getDueAt().isAfter(Instant.now()));
        }
    }

    @GetMapping("/scans/{scanId}/reminders")
    public ResponseEntity<List<ReminderView>> forScan(@PathVariable UUID scanId) {
        return ResponseEntity.ok(reminderRepository.findByScanId(scanId).stream().map(ReminderView::from).toList());
    }

    /** Reminders due now or overdue, across all scans — what a client polls to decide whether to notify. */
    @GetMapping("/reminders/due")
    public ResponseEntity<List<ReminderView>> due() {
        return ResponseEntity.ok(reminderRepository.findByCompletedFalseAndDueAtBefore(Instant.now())
                .stream().map(ReminderView::from).toList());
    }

    @PatchMapping("/reminders/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id) {
        return reminderRepository.findById(id)
                .map(reminder -> {
                    reminder.markCompleted();
                    reminderRepository.save(reminder);
                    return ResponseEntity.ok(ReminderView.from(reminder));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
