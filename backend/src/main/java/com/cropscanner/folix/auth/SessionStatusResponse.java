package com.cropscanner.folix.auth;

/**
 * Answer of {@code GET /api/auth/session}: always HTTP 200, so the site header can ask "am I logged
 * in?" on every page without producing a 401 in the browser console for anonymous visitors.
 * {@code farmer} is {@code null} when {@code authenticated} is false.
 */
public record SessionStatusResponse(boolean authenticated, FarmerProfileResponse farmer) {

    public static SessionStatusResponse anonymous() {
        return new SessionStatusResponse(false, null);
    }

    public static SessionStatusResponse of(AuthenticatedFarmer farmer) {
        return new SessionStatusResponse(true, FarmerProfileResponse.from(farmer));
    }
}
