package com.cropscanner.folix.location;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stage 6C: the privacy-critical arithmetic that turns browser coordinates into a coarse region. */
class FolixRegionGridTest {

    @Test
    void anExactPointBecomesTheCentreOfItsCell_neverItself() {
        FolixRegionCell cell = FolixRegionGrid.coarsen(11.2587, 75.7804, 100);
        assertEquals(11250, cell.latMdeg());
        assertEquals(75750, cell.lngMdeg());
        assertNotEquals(11.2587, cell.centerLatitude());
        assertNotEquals(75.7804, cell.centerLongitude());
    }

    @Test
    void everyPointInsideOneCellMapsToTheSameRegion() {
        FolixRegionCell a = FolixRegionGrid.coarsen(11.2001, 75.7001, 100);
        FolixRegionCell b = FolixRegionGrid.coarsen(11.2994, 75.7994, 100);
        assertEquals(a, b, "two different farms in the same ~11 km square are indistinguishable");
    }

    @Test
    void aPointInTheNextCellIsADifferentRegion() {
        assertNotEquals(FolixRegionGrid.coarsen(11.25, 75.75, 100), FolixRegionGrid.coarsen(11.35, 75.75, 100));
    }

    @Test
    void negativeCoordinatesFloorTowardsTheSouthWest() {
        FolixRegionCell cell = FolixRegionGrid.coarsen(-33.8688, -151.2093, 100);
        assertEquals(-33850, cell.latMdeg());
        assertEquals(-151250, cell.lngMdeg());
    }

    @Test
    void theCentreNeverLeavesTheValidRange() {
        FolixRegionCell corner = FolixRegionGrid.coarsen(90.0, 180.0, 100);
        assertTrue(corner.latMdeg() <= 90_000);
        assertTrue(corner.lngMdeg() <= 180_000);
        FolixRegionCell other = FolixRegionGrid.coarsen(-90.0, -180.0, 100);
        assertTrue(other.latMdeg() >= -90_000);
        assertTrue(other.lngMdeg() >= -180_000);
    }

    @Test
    void missingNonFiniteAndOutOfRangeCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(null, 75.0, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(11.0, null, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(Double.NaN, 75.0, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(11.0, Double.POSITIVE_INFINITY, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(90.5, 75.0, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(11.0, -180.5, 100));
        assertThrows(IllegalArgumentException.class, () -> FolixRegionGrid.coarsen(11.0, 75.0, 0));
    }

    @Test
    void isValidMatchesWhatCoarsenAccepts() {
        assertTrue(FolixRegionGrid.isValid(0.0, 0.0));
        assertTrue(FolixRegionGrid.isValid(-90.0, 180.0));
        assertFalse(FolixRegionGrid.isValid(null, 1.0));
        assertFalse(FolixRegionGrid.isValid(91.0, 1.0));
        assertFalse(FolixRegionGrid.isValid(1.0, Double.NaN));
    }
}
