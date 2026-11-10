package com.cropscanner.folix.map;

import java.util.UUID;

/**
 * One saved report reduced to what aggregation needs: its coarse region, what was diagnosed, and WHICH farmer it
 * came from — the farmer id is used only to count distinct contributors (the k-anonymity rule) and is dropped
 * by {@link FolixDiseaseMapAggregator}; it never reaches {@link FolixDiseaseMapResponse}. This type is internal
 * and is never serialized to a client.
 *
 * Boxed types because it doubles as a JPQL constructor-expression target (see {@link FolixDiseaseMapRepository}).
 */
public record FolixMapRow(Integer regionLatMdeg, Integer regionLngMdeg, String crop, String disease,
                          String severity, String confidence, UUID farmerId, Integer severityPercent) {

    public FolixMapRow(Integer regionLatMdeg, Integer regionLngMdeg, String crop, String disease,
                       String severity, String confidence, UUID farmerId) {
        this(regionLatMdeg, regionLngMdeg, crop, disease, severity, confidence, farmerId, null);
    }
}
