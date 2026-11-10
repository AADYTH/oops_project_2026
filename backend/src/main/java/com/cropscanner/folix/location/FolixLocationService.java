package com.cropscanner.folix.location;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import com.cropscanner.folix.map.FolixMapSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 6C — the farmer's explicit location choice.
 *
 * <ul>
 *   <li>Every method takes the {@link AuthenticatedFarmer} resolved from the server-side session; the farmer id
 *       is never read from the request, so nobody can set, read or withdraw another farmer's location.</li>
 *   <li>{@link #enable} requires {@code consent == true}, validates the coordinates, reduces them to a coarse
 *       grid cell and stores ONLY that cell. The exact coordinates leave this method unused and unstored.</li>
 *   <li>{@link #disable} withdraws consent: the stored region is deleted and the region is removed from the
 *       farmer's existing reports, so they no longer contribute to the map. Reports themselves are kept.</li>
 * </ul>
 * Nothing here is ever required for scanning or for Save Report — a farmer with no row simply has
 * "location unavailable".
 */
@Service
public class FolixLocationService {

    private final FolixFarmerLocationRepository locationRepo;
    private final FolixMapSettings settings;
    private final Clock clock;

    @Autowired
    public FolixLocationService(FolixFarmerLocationRepository locationRepo, FolixMapSettings settings) {
        this(locationRepo, settings, Clock.systemUTC());
    }

    public FolixLocationService(FolixFarmerLocationRepository locationRepo, FolixMapSettings settings, Clock clock) {
        this.locationRepo = locationRepo;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FolixLocationResponse status(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        return locationRepo.findById(farmer.id())
                .map(e -> FolixLocationResponse.of(e, settings.cellSizeDegrees()))
                .orElseGet(FolixLocationResponse::disabled);
    }

    @Transactional
    public FolixLocationResponse enable(FolixLocationRequest request, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        if (request == null) {
            throw FolixAuthException.malformed("A location request is required.");
        }
        if (!Boolean.TRUE.equals(request.consent())) {
            throw FolixAuthException.validation(Map.of("consent",
                    "Location sharing needs your explicit consent."));
        }
        if (!FolixRegionGrid.isValid(request.latitude(), request.longitude())) {
            throw FolixAuthException.validation(Map.of("location", "The location is missing or not valid."));
        }

        FolixRegionCell cell = FolixRegionGrid.coarsen(request.latitude(), request.longitude(),
                settings.cellSizeMillidegrees());
        Instant now = clock.instant();

        Optional<FolixFarmerLocationEntity> existing = locationRepo.findById(farmer.id());
        FolixFarmerLocationEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.moveTo(cell, now);
        } else {
            entity = new FolixFarmerLocationEntity(farmer.id(), cell, now);
        }
        return FolixLocationResponse.of(locationRepo.save(entity), settings.cellSizeDegrees());
    }

    @Transactional
    public FolixLocationResponse disable(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        locationRepo.clearRegionFromReportsOf(farmer.id());
        locationRepo.deleteById(farmer.id());
        return FolixLocationResponse.disabled();
    }

    /** Fails closed: a missing farmer (or id) is never treated as "anonymous". */
    private static void requireFarmer(AuthenticatedFarmer farmer) {
        if (farmer == null || farmer.id() == null) {
            throw FolixAuthException.unauthenticated();
        }
    }
}
