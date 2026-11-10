package com.cropscanner.folix;

import java.util.UUID;

/**
 * The single controlled-failure type of FOLIX report persistence.
 *
 * Deliberately separate from {@link FolixAnalysisException}: that type
 * covers failures of the Gemini analysis call itself (Stage 4), this one
 * covers failures of turning an already-completed diagnosis into a stored
 * report (Stage 5A) — a malformed/invalid report request, or a lookup for a
 * report that does not exist. No controller/exception-handler wiring is
 * added for this in Stage 5A, since no API endpoint uses this service yet.
 */
public class FolixReportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        VALIDATION_FAILED,
        NOT_FOUND
    }

    private final Code code;

    private FolixReportException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    public static FolixReportException malformed(String detail) {
        return new FolixReportException(Code.VALIDATION_FAILED, "malformed report request: " + detail);
    }

    public static FolixReportException invalidField(String field, String value) {
        return new FolixReportException(Code.VALIDATION_FAILED,
                "invalid " + field + ": " + (value == null ? "null" : value));
    }

    public static FolixReportException invariantViolation(String detail) {
        return new FolixReportException(Code.VALIDATION_FAILED, detail);
    }

    public static FolixReportException notFound(UUID id) {
        return new FolixReportException(Code.NOT_FOUND, "no FOLIX scan report with id " + id);
    }
}
