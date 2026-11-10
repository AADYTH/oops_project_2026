package com.cropscanner.folix.map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code folix.map.*} to {@link FolixMapSettings} (the record clamps unsafe values). */
@Configuration
public class FolixMapConfig {

    @Bean
    public FolixMapSettings folixMapSettings(
            @Value("${folix.map.cell-size-millidegrees:100}") int cellSizeMillidegrees,
            @Value("${folix.map.min-cases:1}") int minCases,
            @Value("${folix.map.min-farmers:1}") int minFarmers,
            @Value("${folix.map.lookback-days:30}") int lookbackDays) {
        return new FolixMapSettings(cellSizeMillidegrees, minCases, minFarmers, lookbackDays);
    }
}
