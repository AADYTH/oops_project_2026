package com.cropscanner.service;

import com.cropscanner.entity.ScanRecordEntity;
import com.cropscanner.repository.ScanRecordJpaRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates individual severe scans into a regional heat map (Tier 3
 * upgrade #7), converting one-off farmer alerts into something an
 * authority can see clustering/spreading patterns in.
 *
 * Privacy design: cells are snapped to a coarse grid (~5.5km at the
 * equator, configurable) and any cell with fewer than
 * {@code minScansPerCell} distinct scans is dropped entirely rather than
 * shown with a small/exact count — this is a basic k-anonymity threshold
 * so a lone severe scan on a single remote farm never becomes an
 * individually-identifiable dot on a public map.
 */
@Service
public class OutbreakService {

    public record HeatmapCell(
            double latitude,
            double longitude,
            int scanCount,
            String dominantDiseaseName,
            String dominantCropName,
            double averageSeverityPercent,
            boolean anyEpidemicRisk
    ) {}

    private final ScanRecordJpaRepository scanRecordRepository;
    private final double gridSizeDegrees;
    private final int minScansPerCell;

    public OutbreakService(
            ScanRecordJpaRepository scanRecordRepository,
            @Value("${cropscanner.outbreak.grid-size-degrees:0.05}") double gridSizeDegrees,
            @Value("${cropscanner.outbreak.min-scans-per-cell:3}") int minScansPerCell) {
        this.scanRecordRepository = scanRecordRepository;
        this.gridSizeDegrees = gridSizeDegrees;
        this.minScansPerCell = minScansPerCell;
    }

    public List<HeatmapCell> getSevereScanHeatmap(int lookbackDays) {
        Instant since = Instant.now().minus(lookbackDays, ChronoUnit.DAYS);
        List<ScanRecordEntity> severeScans = scanRecordRepository.findBySevereTrueAndAnalyzedAtAfter(since);

        Map<String, List<ScanRecordEntity>> byCell = severeScans.stream()
                .filter(s -> s.getRoundedLatitude() != null && s.getRoundedLongitude() != null)
                .collect(Collectors.groupingBy(this::gridKey));

        List<HeatmapCell> cells = new ArrayList<>();
        for (var entry : byCell.entrySet()) {
            List<ScanRecordEntity> scans = entry.getValue();
            if (scans.size() < minScansPerCell) {
                continue; // k-anonymity: suppress sparse cells rather than expose an individual farm
            }

            double avgLat = scans.stream().mapToDouble(ScanRecordEntity::getRoundedLatitude).average().orElse(0);
            double avgLon = scans.stream().mapToDouble(ScanRecordEntity::getRoundedLongitude).average().orElse(0);
            double avgSeverity = scans.stream().mapToDouble(ScanRecordEntity::getSeverityPercent).average().orElse(0);
            boolean anyEpidemic = scans.stream().anyMatch(ScanRecordEntity::isEpidemicRisk);

            String dominantDisease = mostCommon(scans.stream().map(ScanRecordEntity::getDiseaseName).toList());
            String dominantCrop = mostCommon(scans.stream().map(ScanRecordEntity::getCropName).toList());

            cells.add(new HeatmapCell(
                    Math.round(avgLat * 1000.0) / 1000.0,
                    Math.round(avgLon * 1000.0) / 1000.0,
                    scans.size(),
                    dominantDisease,
                    dominantCrop,
                    Math.round(avgSeverity * 10.0) / 10.0,
                    anyEpidemic
            ));
        }
        return cells;
    }

    private String gridKey(ScanRecordEntity scan) {
        double snappedLat = Math.floor(scan.getRoundedLatitude() / gridSizeDegrees) * gridSizeDegrees;
        double snappedLon = Math.floor(scan.getRoundedLongitude() / gridSizeDegrees) * gridSizeDegrees;
        return snappedLat + ":" + snappedLon;
    }

    private String mostCommon(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Unknown");
    }
}
