package com.cropscanner.folix.auth;

/**
 * Basic account status of a {@link FolixFarmerEntity}. Only {@link #ACTIVE} accounts can log in or
 * keep using an existing session; nothing in Stage 6A sets {@link #DISABLED} yet, but the column
 * exists so a later admin/moderation stage can suspend an account without deleting it.
 */
public enum FolixFarmerStatus {
    ACTIVE,
    DISABLED
}
