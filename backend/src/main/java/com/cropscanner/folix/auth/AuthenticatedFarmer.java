package com.cropscanner.folix.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * "Which farmer is making this request?" — the server-side answer.
 *
 * Built ONLY from a validated server-side session (see {@link FolixAuthService#authenticate(String)}),
 * never from anything the browser sends as an id. Later stages take it as a plain controller
 * parameter ({@code save(..., AuthenticatedFarmer farmer)}); it is resolved by
 * {@link AuthenticatedFarmerArgumentResolver} and fails closed with 401 when there is no session.
 *
 * Carries no password material and nothing beyond what the basic profile shows.
 */
public record AuthenticatedFarmer(UUID id, String name, String email, Instant createdAt) {
}
