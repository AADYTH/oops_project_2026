package com.cropscanner.service;

import com.cropscanner.model.AuthorityContact;
import com.cropscanner.model.ScanAnalysisResult;

import java.util.concurrent.CompletableFuture;

/**
 * Dispatches the automated emergency-escalation alert to a regional
 * authority once a scan has been classified as severe.
 */
public interface NotificationService {

    /**
     * Sends the alert asynchronously and returns a future carrying the HTTP
     * status code the receiving webhook returned. The future completes
     * exceptionally on network failure, timeout, or a non-2xx response, so
     * callers can distinguish "sent and acknowledged" from "attempted but
     * failed" and record that in the scan's escalation outcome.
     */
    CompletableFuture<Integer> sendSevereScanAlert(ScanAnalysisResult result, AuthorityContact authority);
}
