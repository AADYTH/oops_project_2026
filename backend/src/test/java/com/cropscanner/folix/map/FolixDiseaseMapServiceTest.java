package com.cropscanner.folix.map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Stage 6C: the map has a defined time basis and reads only through the read-only map repository. */
class FolixDiseaseMapServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void onlyReportsInsideTheLookbackWindowAreRequested() {
        FolixDiseaseMapRepository repo = Mockito.mock(FolixDiseaseMapRepository.class);
        when(repo.findLocatedRowsSince(any(Instant.class))).thenReturn(List.of());
        FolixDiseaseMapService service = new FolixDiseaseMapService(repo, FolixMapSettings.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        FolixDiseaseMapResponse map = service.currentMap();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repo).findLocatedRowsSince(since.capture());
        assertEquals(Instant.parse("2026-08-22T10:00:00Z"), since.getValue(), "30 days before 'now'");
        assertTrue(map.regions().isEmpty(), "an empty database is an empty map, not an error");
        assertEquals(30, map.lookbackDays());
    }

    @Test
    void theServiceAppliesTheAggregationRules() {
        FolixDiseaseMapRepository repo = Mockito.mock(FolixDiseaseMapRepository.class);
        when(repo.findLocatedRowsSince(any(Instant.class))).thenReturn(List.of(
                new FolixMapRow(11250, 75750, "Tomato", "Early blight", "mild", "high", UUID.randomUUID()),
                new FolixMapRow(11250, 75750, "Tomato", "Early blight", "mild", "high", UUID.randomUUID())));
        FolixDiseaseMapService service = new FolixDiseaseMapService(repo, new FolixMapSettings(100, 3, 3, 30),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(service.currentMap().regions().isEmpty(), "two reports are below the minimum");
    }
}
