package com.cropscanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Weather-integrated risk alerts (Tier 3 upgrade #8): cross-references the
 * short-term forecast against known disease-favorable conditions so a
 * farmer can be warned BEFORE symptoms appear, not just after a scan finds
 * them.
 *
 * Uses the free, keyless Open-Meteo forecast API
 * (https://open-meteo.com — no account or API key required, unlike most
 * weather providers), so this upgrade needs no credentials to work
 * out of the box. Forecasts are fetched hourly and aggregated into daily
 * buckets here rather than relying on Open-Meteo's daily-aggregate
 * parameters, to keep the exact aggregation logic auditable.
 *
 * The risk rules below are simplified, well-known agronomy heuristics
 * (e.g. late blight favours cool + persistently humid conditions; powdery
 * mildew favours warm + dry conditions) — they are NOT a substitute for a
 * calibrated regional disease-forecasting model (e.g. BLITECAST for late
 * blight). Treat the output as "conditions worth extra vigilance", not a
 * guaranteed prediction.
 */
@Service
public class WeatherRiskService {

    private static final Logger log = LoggerFactory.getLogger(WeatherRiskService.class);

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record DailyRisk(
            LocalDate date,
            double avgTemperatureCelsius,
            double avgHumidityPercent,
            double totalPrecipitationMm,
            List<String> riskFlags,
            String riskLevel,
            String explanation
    ) {}

    public List<DailyRisk> getRiskForecast(double latitude, double longitude, String cropNameHint) {
        JsonNode hourly = fetchHourlyForecast(latitude, longitude);
        List<DailyRisk> days = aggregateToDailyRisk(hourly);
        if (cropNameHint != null && !cropNameHint.isBlank()) {
            log.debug("Weather risk requested with crop hint '{}'", cropNameHint);
        }
        return days;
    }

    private JsonNode fetchHourlyForecast(double latitude, double longitude) {
        try {
            String url = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s" +
                            "&hourly=temperature_2m,relative_humidity_2m,precipitation&forecast_days=5&timezone=auto",
                    latitude, longitude);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Open-Meteo returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body()).path("hourly");
        } catch (Exception e) {
            throw new WeatherServiceException("Could not fetch weather forecast: " + e.getMessage(), e);
        }
    }

    private List<DailyRisk> aggregateToDailyRisk(JsonNode hourly) {
        List<String> times = toStringList(hourly.path("time"));
        List<Double> temps = toDoubleList(hourly.path("temperature_2m"));
        List<Double> humidity = toDoubleList(hourly.path("relative_humidity_2m"));
        List<Double> precip = toDoubleList(hourly.path("precipitation"));

        // Group hourly readings by calendar date.
        java.util.Map<LocalDate, List<Integer>> byDate = new java.util.LinkedHashMap<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDate date = LocalDateTime.parse(times.get(i)).toLocalDate();
            byDate.computeIfAbsent(date, d -> new ArrayList<>()).add(i);
        }

        List<DailyRisk> result = new ArrayList<>();
        for (var entry : byDate.entrySet()) {
            List<Integer> idxs = entry.getValue();
            double avgTemp = idxs.stream().mapToDouble(temps::get).average().orElse(0);
            double avgHumidity = idxs.stream().mapToDouble(humidity::get).average().orElse(0);
            double totalPrecip = idxs.stream().mapToDouble(precip::get).sum();

            List<String> flags = new ArrayList<>();
            // Late blight (Phytophthora infestans) — cool + persistently humid, the classic
            // "Smith Period"-style rule of thumb used in simplified extension advisories.
            if (avgTemp >= 10 && avgTemp <= 24 && avgHumidity >= 85) {
                flags.add("LATE_BLIGHT_FAVORABLE");
            }
            // Powdery mildew — warm + dry favours spore germination on many crops.
            if (avgTemp >= 20 && avgTemp <= 30 && avgHumidity <= 70 && totalPrecip < 1) {
                flags.add("POWDERY_MILDEW_FAVORABLE");
            }
            // Bacterial blight / rice blast — warm, wet, high humidity with standing water risk.
            if (avgTemp >= 24 && avgHumidity >= 80 && totalPrecip >= 5) {
                flags.add("BACTERIAL_BLIGHT_RICE_BLAST_FAVORABLE");
            }

            String level = flags.isEmpty() ? "LOW" : (flags.size() >= 2 ? "HIGH" : "MODERATE");
            String explanation = flags.isEmpty()
                    ? "No known disease-favorable pattern detected in the forecast for this day."
                    : "Forecast conditions (avg " + Math.round(avgTemp) + "\u00b0C, " + Math.round(avgHumidity)
                            + "% humidity, " + Math.round(totalPrecip) + "mm rain) match known risk factors for: "
                            + String.join(", ", flags).replace('_', ' ');

            result.add(new DailyRisk(entry.getKey(), round1(avgTemp), round1(avgHumidity), round1(totalPrecip),
                    flags, level, explanation));
        }
        return result;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private List<Double> toDoubleList(JsonNode arr) {
        List<Double> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> out.add(n.asDouble()));
        return out;
    }

    public static class WeatherServiceException extends RuntimeException {
        public WeatherServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
