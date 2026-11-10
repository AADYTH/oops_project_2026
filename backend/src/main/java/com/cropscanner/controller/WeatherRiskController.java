package com.cropscanner.controller;

import com.cropscanner.service.WeatherRiskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/weather-risk")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class WeatherRiskController {

    private final WeatherRiskService weatherRiskService;

    public WeatherRiskController(WeatherRiskService weatherRiskService) {
        this.weatherRiskService = weatherRiskService;
    }

    @GetMapping
    public ResponseEntity<?> getRisk(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(required = false) String cropName) {
        try {
            List<WeatherRiskService.DailyRisk> forecast =
                    weatherRiskService.getRiskForecast(latitude, longitude, cropName);
            return ResponseEntity.ok(forecast);
        } catch (WeatherRiskService.WeatherServiceException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("errorCode", "WEATHER_UNAVAILABLE", "message", e.getMessage()));
        }
    }
}
