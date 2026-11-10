package com.cropscanner.folix.agrinetwork;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One entry in the farmer-facing Agricultural Support Directory (Stage 6D) — a kind of agricultural
 * assistance/contact a farmer might want to know about (an officer, an office, an extension program, and
 * so on). This is deliberately separate from {@code com.cropscanner.entity.AuthorityEntity}, which backs
 * the authority/case-management side of FOLIX (self-registration, webhooks, API keys) that a later stage
 * (case forwarding) uses — Stage 6D never reads or writes that table.
 *
 * The simplest appropriate representation for this prototype: a flat row per contact/resource. Real,
 * verified contacts can be added later (or this table pointed at a real directory) without changing the
 * shape of the API, and {@code isDemoData} lets seeded placeholder rows be told apart from anything real
 * that gets added afterwards. Phone/email/address are nullable and are simply omitted from the API
 * response when not set — never invented.
 */
@Entity
@Table(name = "folix_support_contact")
public class FolixSupportContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 40)
    private FolixSupportCategory category;

    @Column(name = "organization", length = 150)
    private String organization;

    /** Free-text locality/region label (e.g. a district name) — never an exact address or coordinates. */
    @Column(name = "locality", length = 150)
    private String locality;

    /** Only ever set when verified/configured; left null rather than invented. */
    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** True for the seeded placeholder rows shipped with this prototype; false for verified real entries. */
    @Column(name = "is_demo_data", nullable = false)
    private boolean demoData = true;

    protected FolixSupportContactEntity() {
        // JPA only
    }

    public FolixSupportContactEntity(String name, FolixSupportCategory category, String organization,
                                      String locality, String phone, String email, String address,
                                      boolean active, boolean demoData) {
        this.name = name;
        this.category = category;
        this.organization = organization;
        this.locality = locality;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.active = active;
        this.demoData = demoData;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public FolixSupportCategory getCategory() { return category; }
    public String getOrganization() { return organization; }
    public String getLocality() { return locality; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getAddress() { return address; }
    public boolean isActive() { return active; }
    public boolean isDemoData() { return demoData; }
}
