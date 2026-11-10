package com.cropscanner.model;

/**
 * Immutable GPS coordinate pair supplied by the client's device (browser
 * Geolocation API or mobile GPS). Latitude/longitude are validated at the
 * controller boundary via bean-validation annotations on the request DTO,
 * so this record itself stays a plain, defensively-checked value type.
 */
public record Location(double latitude, double longitude) {

    public Location {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90 degrees, got: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180 degrees, got: " + longitude);
        }
    }

    /**
     * Great-circle distance to another point in kilometres, using the
     * haversine formula. Used to rank candidate authorities by proximity.
     */
    public double distanceKmTo(Location other) {
        final double earthRadiusKm = 6371.0088;
        double dLat = Math.toRadians(other.latitude - this.latitude);
        double dLon = Math.toRadians(other.longitude - this.longitude);
        double lat1 = Math.toRadians(this.latitude);
        double lat2 = Math.toRadians(other.latitude);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusKm * c;
    }
}
