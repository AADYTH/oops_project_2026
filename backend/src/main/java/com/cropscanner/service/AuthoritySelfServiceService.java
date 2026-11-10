package com.cropscanner.service;

import com.cropscanner.dto.AuthorityDtos;
import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.repository.AuthorityJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Lets an authority claim/create its own directory listing instead of you
 * maintaining every office's contact details by hand in a CSV forever
 * (Tier 3 upgrade #9). Also updates its own webhook/phone/email whenever it
 * changes, using the API key issued at registration to authenticate.
 */
@Service
public class AuthoritySelfServiceService {

    private static final Logger log = LoggerFactory.getLogger(AuthoritySelfServiceService.class);
    private static final String NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search";

    private final AuthorityJpaRepository repository;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthoritySelfServiceService(AuthorityJpaRepository repository) {
        this.repository = repository;
    }

    public AuthorityDtos.RegistrationResponse register(AuthorityDtos.RegistrationRequest req) {
        AuthorityEntity entity = new AuthorityEntity(
                req.name(), req.district(), req.state(), req.rawAddress(),
                blankToNull(req.webhookUrl()), blankToNull(req.phoneNumber()), blankToNull(req.email()));
        entity.markSelfRegistered();
        entity.setApiKey(UUID.randomUUID().toString());

        boolean geocoded;
        if (req.latitude() != null && req.longitude() != null) {
            entity.applyManualLocation(req.latitude(), req.longitude());
            geocoded = true;
        } else {
            geocoded = attemptGeocode(entity, req.rawAddress() + ", " + req.district() + ", " + req.state());
        }

        entity = repository.save(entity);

        String message = geocoded
                ? "Registered successfully. Save your API key now — it will not be shown again."
                : "Registered, but the address could not be automatically geocoded. This office will not " +
                        "receive proximity-based alerts until coordinates are set — contact support with your " +
                        "latitude/longitude, or re-register supplying them directly.";

        return new AuthorityDtos.RegistrationResponse(entity.getId(), entity.getApiKey(), geocoded, message);
    }

    public Optional<AuthorityEntity> findByIdAndApiKey(Long id, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        return repository.findById(id).filter(a -> apiKey.equals(a.getApiKey()));
    }

    public void updateContactInfo(AuthorityEntity entity, AuthorityDtos.ContactUpdateRequest req) {
        entity.updateContactInfo(blankToNull(req.webhookUrl()), blankToNull(req.phoneNumber()), blankToNull(req.email()));
        repository.save(entity);
    }

    /** Best-effort single geocode attempt; self-registration should not hang the request if this fails. */
    private boolean attemptGeocode(AuthorityEntity entity, String query) {
        try {
            String url = NOMINATIM_ENDPOINT + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&format=json&limit=1";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "CropDiseaseScannerApp/1.0 (contact: agri-support@example.org)")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;

            JsonNode results = objectMapper.readTree(response.body());
            if (!results.isArray() || results.isEmpty()) return false;

            entity.applyGeocode(results.get(0).get("lat").asDouble(), results.get(0).get("lon").asDouble());
            return true;
        } catch (Exception e) {
            log.warn("Could not geocode self-registered authority '{}': {}", entity.getName(), e.getMessage());
            return false;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
