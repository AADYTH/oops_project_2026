package com.cropscanner.folix.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The controlled-failure type of the FOLIX account system. Every message here is written for the
 * client: it never contains a stack trace, a password, a hash or a session token, and the
 * credentials failure is deliberately a single generic message so the API does not reveal whether
 * an email address has an account.
 *
 * Rendered by {@link FolixAuthExceptionHandler} in the same envelope the rest of FOLIX uses:
 * {@code {"error": {code, message, retryable, fields?}}}.
 */
public class FolixAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        VALIDATION_FAILED(400),
        INVALID_CREDENTIALS(401),
        UNAUTHENTICATED(401),
        ORIGIN_NOT_ALLOWED(403),
        EMAIL_ALREADY_REGISTERED(409);

        private final int httpStatus;

        Code(int httpStatus) { this.httpStatus = httpStatus; }

        public int httpStatus() { return httpStatus; }
    }

    private final Code code;
    private final transient Map<String, String> fields;

    private FolixAuthException(Code code, String message, Map<String, String> fields) {
        super(message);
        this.code = code;
        this.fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public Code code() { return code; }

    /** Per-field messages for {@link Code#VALIDATION_FAILED}; empty otherwise. */
    public Map<String, String> fields() { return fields; }

    public static FolixAuthException validation(Map<String, String> fields) {
        return new FolixAuthException(Code.VALIDATION_FAILED, "Please correct the highlighted fields.", fields);
    }

    public static FolixAuthException malformed(String message) {
        return new FolixAuthException(Code.VALIDATION_FAILED, message, null);
    }

    public static FolixAuthException emailTaken() {
        return new FolixAuthException(Code.EMAIL_ALREADY_REGISTERED,
                "An account with this email already exists. Try logging in instead.", null);
    }

    public static FolixAuthException invalidCredentials() {
        return new FolixAuthException(Code.INVALID_CREDENTIALS, "Incorrect email or password.", null);
    }

    public static FolixAuthException unauthenticated() {
        return new FolixAuthException(Code.UNAUTHENTICATED,
                "You need to log in to do that. Your session may have expired.", null);
    }

    public static FolixAuthException originNotAllowed() {
        return new FolixAuthException(Code.ORIGIN_NOT_ALLOWED, "This request came from an origin that is not allowed.", null);
    }
}
