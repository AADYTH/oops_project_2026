package com.cropscanner.folix.map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds the Disease Map from real FOLIX reports: reports saved with an opted-in coarse region during the
 * configured look-back window, aggregated and privacy-filtered by {@link FolixDiseaseMapAggregator}.
 * Reports without a region (every pre-6C report, every farmer who did not opt in) are never selected.
 *
 * Aggregation happens in memory over the window's located reports — appropriate for the current prototype;
 * a database-side GROUP BY can replace it later without changing the API or the rules.
 */
@Service
public class FolixDiseaseMapService {

    private final FolixDiseaseMapRepository repository;
    private final FolixMapSettings settings;
    private final Clock clock;

    @Autowired
    public FolixDiseaseMapService(FolixDiseaseMapRepository repository, FolixMapSettings settings) {
        this(repository, settings, Clock.systemUTC());
    }

    public FolixDiseaseMapService(FolixDiseaseMapRepository repository, FolixMapSettings settings, Clock clock) {
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FolixDiseaseMapResponse currentMap() {
        Instant since = clock.instant().minus(settings.lookbackDays(), ChronoUnit.DAYS);
        List<FolixMapRow> rows = repository.findLocatedRowsSince(since);
        return FolixDiseaseMapAggregator.aggregate(rows, settings);
    }
}
