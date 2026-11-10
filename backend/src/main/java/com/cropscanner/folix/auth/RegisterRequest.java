package com.cropscanner.folix.auth;

/** Body of {@code POST /api/auth/register}. {@link #toString()} never prints the password. */
public record RegisterRequest(String name, String email, String password) {

    @Override
    public String toString() {
        return "RegisterRequest[password=<redacted>]";
    }
}
