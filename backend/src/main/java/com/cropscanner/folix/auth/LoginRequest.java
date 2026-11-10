package com.cropscanner.folix.auth;

/** Body of {@code POST /api/auth/login}. {@link #toString()} never prints the password. */
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[password=<redacted>]";
    }
}
