package com.cropscanner.folix.farmeralert;

import com.cropscanner.folix.FolixReportException;
import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.AuthenticatedFarmerArgumentResolver;
import com.cropscanner.folix.auth.FolixAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FolixFarmerAlertControllerTest {

    private static final AuthenticatedFarmer FARMER = new AuthenticatedFarmer(
            UUID.fromString("11111111-1111-1111-1111-111111111111"), "Test Farmer", "farmer@example.com",
            Instant.parse("2026-09-01T00:00:00Z"));

    private FolixFarmerAlertService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(FolixFarmerAlertService.class);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new FolixFarmerAlertController(service))
                .setControllerAdvice(new FolixFarmerAlertExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticatedFarmerArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static RequestPostProcessor asFarmer() {
        return request -> {
            request.setAttribute(FolixAuthInterceptor.FARMER_ATTRIBUTE, FARMER);
            return request;
        };
    }

    @Test
    void triggerAlertsSuccessReturnsOkWithSentCount() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(service.triggerAlerts(eq(reportId), eq(FARMER)))
                .thenReturn(new FolixFarmerAlertTriggerResponse(reportId, 3));

        mvc.perform(post("/api/farmer/alerts/trigger/" + reportId).with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reportId").value(reportId.toString()))
                .andExpect(jsonPath("$.alertsSent").value(3));
    }

    @Test
    void triggerAlertsNonEpidemicReturns400() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(service.triggerAlerts(eq(reportId), eq(FARMER)))
                .thenThrow(FolixFarmerAlertException.notEpidemicRisk());

        mvc.perform(post("/api/farmer/alerts/trigger/" + reportId).with(asFarmer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NOT_EPIDEMIC_RISK"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void triggerAlertsNoLocationReturns400() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(service.triggerAlerts(eq(reportId), eq(FARMER)))
                .thenThrow(FolixFarmerAlertException.noLocation());

        mvc.perform(post("/api/farmer/alerts/trigger/" + reportId).with(asFarmer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("NO_LOCATION"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void triggerAlertsUnownedOrNotFoundReturns404() throws Exception {
        UUID reportId = UUID.randomUUID();
        when(service.triggerAlerts(eq(reportId), eq(FARMER)))
                .thenThrow(FolixReportException.notFound(reportId));

        mvc.perform(post("/api/farmer/alerts/trigger/" + reportId).with(asFarmer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Report not found."))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }

    @Test
    void getMyAlertsReturnsPrivacySafeList() throws Exception {
        Instant created = Instant.parse("2026-09-23T10:00:00Z");
        Instant expires = Instant.parse("2026-09-30T10:00:00Z");

        FolixFarmerAlertResponse alert = new FolixFarmerAlertResponse(
                42L, "Late blight", "Tomato", "Nearby area", true, created, expires);

        when(service.getMyAlerts(eq(FARMER))).thenReturn(List.of(alert));

        mvc.perform(get("/api/farmer/alerts").with(asFarmer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].alertId").value(42))
                .andExpect(jsonPath("$[0].diseaseName").value("Late blight"))
                .andExpect(jsonPath("$[0].cropName").value("Tomato"))
                .andExpect(jsonPath("$[0].regionDescription").value("Nearby area"))
                .andExpect(jsonPath("$[0].epidemicRisk").value(true))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-23T10:00:00Z"))
                .andExpect(jsonPath("$[0].expiresAt").value("2026-09-30T10:00:00Z"))
                // Ensure privacy: no IDs or coordinates exposed
                .andExpect(jsonPath("$[0].farmerId").doesNotExist())
                .andExpect(jsonPath("$[0].recipientFarmerId").doesNotExist())
                .andExpect(jsonPath("$[0].reportId").doesNotExist())
                .andExpect(jsonPath("$[0].lat").doesNotExist())
                .andExpect(jsonPath("$[0].lng").doesNotExist());
    }
}
