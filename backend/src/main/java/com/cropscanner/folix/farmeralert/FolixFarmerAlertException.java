package com.cropscanner.folix.farmeralert;

/**
 * Controlled failures of farmer-to-farmer alerts.
 */
public class FolixFarmerAlertException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        NOT_EPIDEMIC_RISK(400),
        NO_LOCATION(400),
        VALIDATION_FAILED(400),
        NOT_FOUND(404);

        private final int httpStatus;

        Code(int httpStatus) { this.httpStatus = httpStatus; }

        public int httpStatus() { return httpStatus; }
    }

    private final Code code;

    public FolixFarmerAlertException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }

    public static FolixFarmerAlertException notEpidemicRisk() {
        return new FolixFarmerAlertException(Code.NOT_EPIDEMIC_RISK,
                "Alerts can only be triggered for reports diagnosed with an epidemic-risk disease.");
    }

    public static FolixFarmerAlertException noLocation() {
        return new FolixFarmerAlertException(Code.NO_LOCATION,
                "This report does not have an associated region to determine nearby farmers.");
    }

    public static FolixFarmerAlertException notFound(String message) {
        return new FolixFarmerAlertException(Code.NOT_FOUND, message);
    }
}
