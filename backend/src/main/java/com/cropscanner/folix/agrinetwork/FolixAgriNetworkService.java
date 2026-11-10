package com.cropscanner.folix.agrinetwork;

import com.cropscanner.folix.auth.AuthenticatedFarmer;
import com.cropscanner.folix.auth.FolixAuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 6D — a farmer's own agricultural profile, and the read-only agricultural support directory.
 *
 * <ul>
 *   <li>Every profile method takes the {@link AuthenticatedFarmer} resolved from the server-side session;
 *       the farmer id is never read from the request, so nobody can read or change another farmer's
 *       profile.</li>
 *   <li>The directory has no per-farmer identity at all — it is the same list for every authenticated
 *       farmer, filtered to active entries only.</li>
 * </ul>
 * This is the Agricultural Network's profile + directory ONLY. Nothing here sends anything anywhere —
 * there is no case forwarding, no notification, and no authority-side write path.
 */
@Service
public class FolixAgriNetworkService {

    private static final int MAX_TEXT_LENGTH = 150;
    private static final int MAX_CROPS_LENGTH = 300;

    private final FolixFarmerAgriProfileRepository profileRepo;
    private final FolixSupportContactRepository contactRepo;
    private final Clock clock;

    @Autowired
    public FolixAgriNetworkService(FolixFarmerAgriProfileRepository profileRepo,
                                    FolixSupportContactRepository contactRepo) {
        this(profileRepo, contactRepo, Clock.systemUTC());
    }

    public FolixAgriNetworkService(FolixFarmerAgriProfileRepository profileRepo,
                                    FolixSupportContactRepository contactRepo, Clock clock) {
        this.profileRepo = profileRepo;
        this.contactRepo = contactRepo;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FolixAgriProfileResponse getProfile(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        return profileRepo.findById(farmer.id())
                .map(FolixAgriProfileResponse::of)
                .orElseGet(FolixAgriProfileResponse::empty);
    }

    @Transactional
    public FolixAgriProfileResponse saveProfile(FolixAgriProfileRequest request, AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        if (request == null) {
            throw FolixAuthException.malformed("A profile request is required.");
        }
        String villageOrArea = normalize(request.villageOrArea());
        String crops = normalize(request.crops());

        if (villageOrArea != null && villageOrArea.length() > MAX_TEXT_LENGTH) {
            throw FolixAuthException.validation(Map.of("villageOrArea",
                    "Keep this under " + MAX_TEXT_LENGTH + " characters."));
        }
        if (crops != null && crops.length() > MAX_CROPS_LENGTH) {
            throw FolixAuthException.validation(Map.of("crops",
                    "Keep this under " + MAX_CROPS_LENGTH + " characters."));
        }

        Instant now = clock.instant();
        Optional<FolixFarmerAgriProfileEntity> existing = profileRepo.findById(farmer.id());
        FolixFarmerAgriProfileEntity entity;
        if (existing.isPresent()) {
            entity = existing.get();
            entity.apply(villageOrArea, crops, request.preferredCategory(), now);
        } else {
            entity = new FolixFarmerAgriProfileEntity(farmer.id(), villageOrArea, crops,
                    request.preferredCategory(), now);
        }
        return FolixAgriProfileResponse.of(profileRepo.save(entity));
    }

    /** The active support directory. No farmer identity is involved: this is the same list for everyone. */
    @Transactional(readOnly = true)
    public List<FolixSupportContactResponse> directory(AuthenticatedFarmer farmer) {
        requireFarmer(farmer);
        return contactRepo.findByActiveTrueOrderByNameAsc().stream()
                .map(FolixSupportContactResponse::from)
                .toList();
    }

    /** Blank input becomes null (a real absence), never an empty string. */
    private static String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Fails closed: a missing farmer (or id) is never treated as "anonymous". */
    private static void requireFarmer(AuthenticatedFarmer farmer) {
        if (farmer == null || farmer.id() == null) {
            throw FolixAuthException.unauthenticated();
        }
    }
}
