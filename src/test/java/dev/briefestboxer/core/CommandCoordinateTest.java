package dev.briefestboxer.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandCoordinateTest {
    @Test
    void parsesAbsoluteAndRelativeForms() {
        CommandCoordinate absolute = CommandCoordinate.parse("12");
        assertFalse(absolute.relative);
        assertEquals(12.0, absolute.value);
        assertTrue(absolute.wholeNumber);

        CommandCoordinate decimal = CommandCoordinate.parse("-3.25");
        assertEquals(-3.25, decimal.value);
        assertFalse(decimal.wholeNumber);

        CommandCoordinate here = CommandCoordinate.parse("~");
        assertTrue(here.relative);
        assertEquals(0.0, here.value);

        assertEquals(-2.5, CommandCoordinate.parse("~-2.5").value);
        assertEquals(4.0, CommandCoordinate.parse("~+4").value);
        assertEquals(0.5, CommandCoordinate.parse(".5").value);
    }

    @Test
    void rejectsNonCoordinates() {
        for (String text : new String[] {"", "~~", "abc", "1.2.3", "^1", "~x", "-"}) {
            assertThrows(IllegalArgumentException.class, () -> CommandCoordinate.parse(text), text);
        }
    }

    @Test
    void resolvesPointsLikeVanilla() {
        // Relative: the exact player position plus the offset.
        assertEquals(10.3, CommandCoordinate.parse("~").resolvePoint(10.3, true), 1e-9);
        assertEquals(12.3, CommandCoordinate.parse("~2").resolvePoint(10.3, true), 1e-9);
        // Whole-number absolute x/z means the block centre; y and decimals are exact.
        assertEquals(12.5, CommandCoordinate.parse("12").resolvePoint(0, true), 1e-9);
        assertEquals(64.0, CommandCoordinate.parse("64").resolvePoint(0, false), 1e-9);
        assertEquals(12.25, CommandCoordinate.parse("12.25").resolvePoint(0, true), 1e-9);
        assertEquals(-7.5, CommandCoordinate.parse("-8").resolvePoint(0, true), 1e-9);
    }

    @Test
    void resolvesBlocksByFlooring() {
        assertEquals(10, CommandCoordinate.parse("~").resolveBlock(10.7));
        assertEquals(-11, CommandCoordinate.parse("~").resolveBlock(-10.2));
        assertEquals(13, CommandCoordinate.parse("~3").resolveBlock(10.7));
        assertEquals(-4, CommandCoordinate.parse("-3.5").resolveBlock(0));
    }
}
