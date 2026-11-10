package com.cropscanner.folix;

/**
 * The single controlled-failure type of the FOLIX Scanner path.
 *
 * Every failure mode of the analysis pipeline (bad upload, missing Gemini
 * configuration, Gemini auth/rate-limit/server failure, malformed model
 * output, timeout) is expressed as one of these. Nothing else is allowed to
 * reach the browser, and NONE of them may ever be turned into a diagnosis.
 *
 * {@link #getMessage()} is an INTERNAL detail for server logs only — it must
 * never contain an API key, a request body, or model output. What the client
 * sees is {@link #clientMessage()}, a fixed calm sentence per failure kind.
 */
public class FolixAnalysisException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_IMAGE(400),
        UNSUPPORTED_IMAGE_TYPE(415),
        IMAGE_TOO_LARGE(413),
        RATE_LIMITED(429),
        ANALYSIS_TIMEOUT(504),
        /** Not configured / credentials rejected / model gone / billing: retrying immediately cannot help. */
        ANALYSIS_UNAVAILABLE(503),
        /** Upstream 5xx, network failure, malformed or blocked model output. */
        ANALYSIS_FAILED(502);

        private final int httpStatus;

        Code(int httpStatus) { this.httpStatus = httpStatus; }

        public int httpStatus() { return httpStatus; }
    }

    private final Code code;
    private final String clientMessage;
    private final boolean retryable;
    private final Integer retryAfterSeconds;

    private FolixAnalysisException(Code code, String clientMessage, boolean retryable,
                                   Integer retryAfterSeconds, String internalDetail) {
        super(internalDetail);
        this.code = code;
        this.clientMessage = clientMessage;
        this.retryable = retryable;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Code code() { return code; }

    public String clientMessage() { return clientMessage; }

    public boolean retryable() { return retryable; }

    /** Seconds the client should wait, when Google told us; otherwise null. */
    public Integer retryAfterSeconds() { return retryAfterSeconds; }

    // ---- factories: the fixed, user-safe wording lives here and nowhere else ----

    public static FolixAnalysisException invalidImage(String internalDetail) {
        return new FolixAnalysisException(Code.INVALID_IMAGE,
                "That file couldn't be read as an image. Please choose a different photo.",
                false, null, internalDetail);
    }

    public static FolixAnalysisException unsupportedType(String internalDetail) {
        return new FolixAnalysisException(Code.UNSUPPORTED_IMAGE_TYPE,
                "That file type isn't supported. Please choose a JPG, PNG or WEBP image.",
                false, null, internalDetail);
    }

    public static FolixAnalysisException tooLarge(String internalDetail) {
        return new FolixAnalysisException(Code.IMAGE_TOO_LARGE,
                "That image is too large. Please choose a smaller photo.",
                false, null, internalDetail);
    }

    public static FolixAnalysisException rateLimited(Integer retryAfterSeconds, String internalDetail) {
        return new FolixAnalysisException(Code.RATE_LIMITED,
                "FOLIX is handling a lot of requests right now. Please wait a moment, then try again.",
                true, retryAfterSeconds, internalDetail);
    }

    public static FolixAnalysisException timeout(String internalDetail) {
        return new FolixAnalysisException(Code.ANALYSIS_TIMEOUT,
                "The analysis took too long to finish. Please try again.",
                true, null, internalDetail);
    }

    public static FolixAnalysisException unavailable(String internalDetail) {
        return new FolixAnalysisException(Code.ANALYSIS_UNAVAILABLE,
                "FOLIX analysis isn't available right now. Please try again later.",
                false, null, internalDetail);
    }

    public static FolixAnalysisException failed(String internalDetail, boolean retryable) {
        return new FolixAnalysisException(Code.ANALYSIS_FAILED,
                retryable
                        ? "FOLIX couldn't complete the analysis. Please try again."
                        : "FOLIX couldn't analyze this image. Please try a different photo.",
                retryable, null, internalDetail);
    }
}
