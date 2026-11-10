package com.cropscanner.folix.agrinetwork;

/**
 * One farmer-facing directory entry. Deliberately narrow: no {@code active} flag (only active rows are
 * ever returned), no {@code isDemoData} distinction beyond what {@link #organization} already conveys for
 * seeded rows, and nothing about how the row was created. Phone/email/address are simply null — never a
 * placeholder string — when the contact has no verified value on file.
 */
public record FolixSupportContactResponse(Long id, String name, FolixSupportCategory category,
                                           String organization, String locality, String phone,
                                           String email, String address) {

    public static FolixSupportContactResponse from(FolixSupportContactEntity e) {
        return new FolixSupportContactResponse(e.getId(), e.getName(), e.getCategory(), e.getOrganization(),
                e.getLocality(), e.getPhone(), e.getEmail(), e.getAddress());
    }
}
