package com.cropscanner.folix.agrinetwork;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * On startup, and ONLY when {@code folix_support_contact} is empty, seeds a handful of clearly-labelled
 * DEMO/placeholder directory rows so the Agricultural Network page has something to show out of the box.
 *
 * No phone number, email or address is invented for any of them — every contact detail field is left null.
 * Names and the organization field are explicit that these are placeholders, not real verified contacts
 * ("real verified contacts can be added later without changing the overall architecture" — Stage 6D spec).
 * This never touches {@code authority_contact} (the separate, real KVK-derived directory imported by
 * {@code AuthorityImportService} for the authority/case-management side of FOLIX).
 */
@Component
@Order(200) // after AuthorityImportService (order-independent tables, but keep startup order predictable)
public class FolixSupportContactSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(FolixSupportContactSeeder.class);

    private final FolixSupportContactRepository repository;

    public FolixSupportContactSeeder(FolixSupportContactRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        List<FolixSupportContactEntity> demoRows = List.of(
                new FolixSupportContactEntity(
                        "Demo Agricultural Officer (placeholder)",
                        FolixSupportCategory.AGRICULTURAL_OFFICER,
                        "FOLIX Demo Directory — replace with a verified local contact",
                        "Sample locality", null, null, null, true, true),
                new FolixSupportContactEntity(
                        "Demo Krishi Bhavan (placeholder)",
                        FolixSupportCategory.KRISHI_BHAVAN,
                        "FOLIX Demo Directory — replace with a verified local office",
                        "Sample locality", null, null, null, true, true),
                new FolixSupportContactEntity(
                        "Demo Extension Support Line (placeholder)",
                        FolixSupportCategory.EXTENSION_SUPPORT,
                        "FOLIX Demo Directory — replace with a verified extension contact",
                        "Regional", null, null, null, true, true)
        );
        repository.saveAll(demoRows);
        log.info("Seeded {} demo/placeholder Agricultural Support Directory rows.", demoRows.size());
    }
}
