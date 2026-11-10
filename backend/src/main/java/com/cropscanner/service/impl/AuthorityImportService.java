package com.cropscanner.service.impl;

import com.cropscanner.entity.AuthorityEntity;
import com.cropscanner.repository.AuthorityJpaRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * On startup, loads {@code /data/kvk_directory_seed.csv} — a real subset of
 * ICAR's published Krishi Vigyan Kendra directory (source:
 * https://icar.org.in) — into the database, then geocodes every
 * not-yet-geocoded row via the free OpenStreetMap Nominatim API.
 *
 * This is deliberately NOT hardcoded latitude/longitude data: the CSV ships
 * with only the official name/address/host-institution fields exactly as
 * published, and coordinates are resolved from a real geocoding service at
 * import time and cached in the database. This keeps the directory
 * auditable against the public source and avoids ever inventing GPS
 * coordinates.
 *
 * Nominatim's usage policy caps unauthenticated use at 1 request/second and
 * requires a descriptive User-Agent — both are respected below. For
 * production use with a large directory, replace this with a paid geocoding
 * provider (Google Geocoding API, Mapbox, HERE) or a self-hosted Nominatim
 * instance to avoid rate limits.
 */
@Component
public class AuthorityImportService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthorityImportService.class);
    private static final String NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final Duration RATE_LIMIT_DELAY = Duration.ofMillis(1100);

    private final AuthorityJpaRepository repository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthorityImportService(AuthorityJpaRepository repository) {
        this.repository = repository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void run(String... args) {
        if (repository.count() == 0) {
            importSeedCsv();
        }
        CompletableFuture.runAsync(this::geocodePendingEntities);
    }

    private void importSeedCsv() {
        try (InputStream is = new ClassPathResource("data/kvk_directory_seed.csv").getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String header = reader.readLine(); // name,district,state,rawAddress,webhookUrl,phoneNumber,email
            String line;
            int imported = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = splitCsvLine(line);
                if (cols.length < 7) {
                    log.warn("Skipping malformed authority directory row: {}", line);
                    continue;
                }
                AuthorityEntity entity = new AuthorityEntity(
                        cols[0], cols[1], cols[2], cols[3],
                        blankToNull(cols[4]), blankToNull(cols[5]), blankToNull(cols[6]));
                repository.save(entity);
                imported++;
            }
            log.info("Imported {} authority directory rows from kvk_directory_seed.csv", imported);
        } catch (Exception e) {
            log.error("Failed to import authority directory seed CSV. Escalation lookups will find no " +
                    "authorities until this is fixed and the app is restarted.", e);
        }
    }

    private void geocodePendingEntities() {
        List<AuthorityEntity> pending = repository.findAllByGeocodedFalse();
        if (pending.isEmpty()) {
            return;
        }
        log.info("Geocoding {} authority addresses via Nominatim in background...", pending.size());

        int consecutiveErrors = 0;
        for (AuthorityEntity entity : pending) {
            if (consecutiveErrors >= 3) {
                log.info("Geocoding service unavailable or rate-limited; remaining rows will keep existing data.");
                break;
            }
            try {
                geocodeOne(entity);
                repository.save(entity);
                consecutiveErrors = 0;
                Thread.sleep(RATE_LIMIT_DELAY.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Geocoding interrupted; remaining rows will be retried on next startup.");
                return;
            } catch (Exception e) {
                consecutiveErrors++;
                log.warn("Could not geocode authority '{}' ({}): {}.",
                        entity.getName(), entity.getRawAddress(), e.getMessage());
            }
        }
    }

    private void geocodeOne(AuthorityEntity entity) throws Exception {
        String query = entity.getRawAddress() + ", " + entity.getDistrict() + ", " + entity.getState() + ", India";
        String url = NOMINATIM_ENDPOINT
                + "?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&limit=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "CropDiseaseScannerApp/1.0 (contact: agri-support@example.org)")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Nominatim returned HTTP " + response.statusCode());
        }

        JsonNode results = objectMapper.readTree(response.body());
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("No geocoding match for address");
        }

        double lat = results.get(0).get("lat").asDouble();
        double lon = results.get(0).get("lon").asDouble();
        entity.applyGeocode(lat, lon);
        log.debug("Geocoded '{}' -> ({}, {})", entity.getName(), lat, lon);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Minimal CSV splitter that honours double-quoted fields containing commas. */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
