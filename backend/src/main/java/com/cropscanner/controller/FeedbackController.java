package com.cropscanner.controller;

import com.cropscanner.dto.FeedbackDtos;
import com.cropscanner.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Tier 1 upgrade #2: lets a user (or an agronomist reviewing scans) correct
 * a wrong diagnosis. Every submission becomes a row in
 * {@code ScanFeedbackEntity} — see that class's Javadoc for what this
 * does and does not automate.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ResponseEntity<FeedbackDtos.FeedbackAck> submit(@Valid @RequestBody FeedbackDtos.SubmitFeedbackRequest req) {
        return ResponseEntity.ok(feedbackService.submit(req));
    }
}
