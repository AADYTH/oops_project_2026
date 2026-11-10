package com.cropscanner.entity;

import jakarta.persistence.*;

/**
 * Persisted record for a Krishi Vigyan Kendra / District Agriculture
 * Extension Office (or equivalent regional authority in other countries).
 * Rows are populated by {@code AuthorityImportService} from a real,
 * user-supplied directory CSV — see /data/kvk_directory_seed.csv — geocoded
 * once via the OpenStreetMap Nominatim API and cached here so the app never
 * needs to invent coordinates.
 */
@Entity
@Table(name = "authority_contact", indexes = {
        @Index(name = "idx_authority_lat_lon", columnList = "latitude,longitude")
})
public class AuthorityEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String district;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String rawAddress;

    private Double latitude;

    private Double longitude;

    private String webhookUrl;

    private String phoneNumber;

    private String email;

    /** True once {@code AuthorityImportService} has successfully geocoded {@code rawAddress}. */
    @Column(nullable = false)
    private boolean geocoded = false;

    /**
     * Self-registration support (Tier 3 upgrade #9): an authority that
     * registers itself through the dashboard gets an API key here, used to
     * authenticate its dashboard requests (list/acknowledge/resolve its own
     * alerts, update its own contact/webhook info) instead of you
     * maintaining every office's details by hand in the seed CSV forever.
     * Null for rows that only came from the seed CSV and haven't claimed
     * their listing yet.
     */
    @Column(unique = true)
    private String apiKey;

    /** True for authorities created via self-service registration rather than the seed CSV import. */
    @Column(nullable = false)
    private boolean selfRegistered = false;

    protected AuthorityEntity() {
        // required by JPA
    }

    public AuthorityEntity(String name, String district, String state, String rawAddress,
                            String webhookUrl, String phoneNumber, String email) {
        this.name = name;
        this.district = district;
        this.state = state;
        this.rawAddress = rawAddress;
        this.webhookUrl = webhookUrl;
        this.phoneNumber = phoneNumber;
        this.email = email;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDistrict() { return district; }
    public String getState() { return state; }
    public String getRawAddress() { return rawAddress; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getEmail() { return email; }
    public boolean isGeocoded() { return geocoded; }
    public String getApiKey() { return apiKey; }
    public boolean isSelfRegistered() { return selfRegistered; }

    public void applyGeocode(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.geocoded = true;
    }

    public void applyManualLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.geocoded = true;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void markSelfRegistered() {
        this.selfRegistered = true;
    }

    public void updateContactInfo(String webhookUrl, String phoneNumber, String email) {
        this.webhookUrl = webhookUrl;
        this.phoneNumber = phoneNumber;
        this.email = email;
    }
}
