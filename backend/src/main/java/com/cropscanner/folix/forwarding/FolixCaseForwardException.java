package com.cropscanner.folix.forwarding;

/**
 * Controlled failures of case forwarding. A missing/unowned report is reported with the existing
 * {@link com.cropscanner.folix.FolixReportException#notFound(java.util.UUID)} (404), not from here.
 * Messages are fixed strings written for the client.
 */
public class FolixCaseForwardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        VALIDATION_FAILED(400),
        ALREADY_FORWARDED(409);

        private final int httpStatus;

        Code(int httpStatus) { this.httpStatus = httpStatus; }

        public int httpStatus() { return httpStatus; }
    }

    private final Code code;

    private FolixCaseForwardException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    public static FolixCaseForwardException contactRequired() {
        return new FolixCaseForwardException(Code.VALIDATION_FAILED, "Choose a support contact to forward this report to.");
    }

    /** Unknown id and inactive contact are deliberately indistinguishable. */
    public static FolixCaseForwardException contactUnavailable() {
        return new FolixCaseForwardException(Code.VALIDATION_FAILED, "That support contact is not available.");
    }

    public static FolixCaseForwardException alreadyForwarded() {
        return new FolixCaseForwardException(Code.ALREADY_FORWARDED, "This report has already been forwarded.");
    }
}
