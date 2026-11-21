package dev.briefestboxer.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AimDestinationTest {
    @AfterEach
    void reset() {
        AimDestination.clear();
    }

    @Test
    void containsPointsWithinRadiusInThreeDimensions() {
        AimDestination.Target target = AimDestination.set(10.5, 64.0, -3.5, 2.0);
        assertTrue(target.contains(10.5, 64.0, -3.5));
        assertTrue(target.contains(12.0, 64.0, -3.5));
        assertTrue(target.contains(10.5, 65.9, -3.5));
        assertFalse(target.contains(12.6, 64.0, -3.5));
        assertFalse(target.contains(10.5, 66.1, -3.5));
    }

    @Test
    void clampsRadiusAndRejectsNonFiniteCoordinates() {
        assertEquals(AimDestination.MIN_RADIUS, AimDestination.set(0, 0, 0, 0.0).radius);
        assertEquals(AimDestination.MAX_RADIUS, AimDestination.set(0, 0, 0, 1000.0).radius);
        assertEquals(AimDestination.DEFAULT_RADIUS, AimDestination.clampRadius(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> AimDestination.set(Double.NaN, 0, 0, 1));
    }

    @Test
    void blockAreaCoversWholeBlocksBetweenCornersInclusive() {
        // Corners in any order, as /fill takes them.
        AimDestination.Target area = AimDestination.Target.blockArea(5, 64, 10, 2, 64, 8);
        assertTrue(area.box);
        assertEquals(2.0, area.minX);
        assertEquals(6.0, area.maxX);
        assertEquals(64.0, area.minY);
        assertEquals(65.0, area.maxY);
        assertTrue(area.contains(2.0, 64.0, 8.0));
        assertTrue(area.contains(5.9, 64.0, 10.9));
        assertFalse(area.contains(6.1, 64.0, 9.0));
        assertFalse(area.contains(4.0, 63.9, 9.0));
    }

    @Test
    void distanceIsZeroInsideAndGrowsOutside() {
        AimDestination.Target area = AimDestination.Target.box(0, 0, 0, 4, 1, 4);
        assertEquals(0.0, area.distance(2, 0.5, 2));
        assertEquals(3.0, area.distance(7, 0.5, 2), 1e-9);
        assertEquals(5.0, area.distance(7, 0.5, 8), 1e-9);
        AimDestination.Target point = new AimDestination.Target(0, 0, 0, 1.0);
        assertEquals(0.0, point.distance(0.5, 0, 0));
        assertEquals(2.0, point.distance(3, 0, 0), 1e-9);
    }

    @Test
    void rejectsOversizedAreas() {
        assertThrows(IllegalArgumentException.class,
                () -> AimDestination.Target.box(0, 0, 0, AimDestination.MAX_AREA_SIZE + 1, 1, 1));
    }

    @Test
    void clearRemovesTheDestination() {
        AimDestination.set(1, 2, 3, 1);
        assertNotNull(AimDestination.current());
        AimDestination.clear();
        assertNull(AimDestination.current());
    }
}
