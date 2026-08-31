package com.skyprism.core.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bare-JVM tests for {@link BracketTable}. */
class BracketTableTest {

    private static BracketTable threeTiers() {
        return BracketTable.of(0, 0xAAAAAA, 40, 0xFFFFFF, 80, 0xFFFF55);
    }

    @Test
    @DisplayName("a level exactly on a boundary takes that bracket's colour")
    void exactBoundaries() {
        var t = threeTiers();
        assertEquals(0xAAAAAA, t.colorAt(0));
        assertEquals(0xFFFFFF, t.colorAt(40));
        assertEquals(0xFFFF55, t.colorAt(80));
    }

    @Test
    @DisplayName("one below a boundary still belongs to the previous bracket")
    void oneBelowEachBoundary() {
        var t = threeTiers();
        assertEquals(0xAAAAAA, t.colorAt(39));
        assertEquals(0xFFFFFF, t.colorAt(79));
    }

    @Test
    @DisplayName("one above a boundary is still that same bracket")
    void oneAboveEachBoundary() {
        var t = threeTiers();
        assertEquals(0xAAAAAA, t.colorAt(1));
        assertEquals(0xFFFFFF, t.colorAt(41));
        assertEquals(0xFFFF55, t.colorAt(81));
    }

    @Test
    @DisplayName("above the top bracket the top colour holds forever")
    void aboveTheTopBracket() {
        var t = threeTiers();
        assertEquals(0xFFFF55, t.colorAt(10_000));
        assertEquals(0xFFFF55, t.colorAt(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("below the lowest bracket clamps to the lowest colour instead of failing")
    void belowTheLowestBracket() {
        var t = BracketTable.of(10, 0x111111, 20, 0x222222);
        assertEquals(0x111111, t.colorAt(9));
        assertEquals(0x111111, t.colorAt(0));
        assertEquals(0x111111, t.colorAt(-500));
        assertEquals(0x111111, t.colorAt(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("a single-bracket table is a constant")
    void singleBracketIsConstant() {
        var t = BracketTable.of(100, 0x00FF00);
        assertEquals(0x00FF00, t.colorAt(0));
        assertEquals(0x00FF00, t.colorAt(100));
        assertEquals(0x00FF00, t.colorAt(999));
    }

    @Test
    @DisplayName("brackets supplied out of order behave identically to sorted ones")
    void bracketsAreSortedInternally() {
        var jumbled = BracketTable.of(80, 0xFFFF55, 0, 0xAAAAAA, 40, 0xFFFFFF);
        assertEquals(List.of(0, 40, 80),
            jumbled.brackets().stream().map(BracketTable.Bracket::minLevel).toList());
        for (int level = -5; level <= 120; level++) {
            assertEquals(threeTiers().colorAt(level), jumbled.colorAt(level), "mismatch at " + level);
        }
    }

    @Test
    @DisplayName("duplicate minLevels are rejected")
    void duplicateMinLevelsRejected() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> BracketTable.of(0, 0xAAAAAA, 40, 0xFFFFFF, 40, 0x000000));
        assertTrue(ex.getMessage().contains("40"), ex.getMessage());
    }

    @Test
    @DisplayName("an empty or null bracket list is rejected")
    void emptyAndNullRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BracketTable(List.of()));
        assertThrows(NullPointerException.class, () -> new BracketTable(null));
        assertThrows(NullPointerException.class,
            () -> new BracketTable(Arrays.asList(new BracketTable.Bracket(0, 1), null)));
        assertThrows(IllegalArgumentException.class, () -> BracketTable.of(0, 1, 2));
    }

    @Test
    @DisplayName("brackets() is immutable and the input list is copied")
    void bracketsAreDefensivelyCopied() {
        var input = new ArrayList<BracketTable.Bracket>();
        input.add(new BracketTable.Bracket(40, 0xFFFFFF));
        input.add(new BracketTable.Bracket(0, 0xAAAAAA));
        var t = new BracketTable(input);

        input.clear();
        assertEquals(2, t.brackets().size());
        assertThrows(UnsupportedOperationException.class,
            () -> t.brackets().add(new BracketTable.Bracket(5, 0)));
    }

    @Test
    @DisplayName("a bracket discards alpha or stray high bits")
    void bracketMasksHighBits() {
        assertEquals(0x00AA00, new BracketTable.Bracket(0, 0xFF_00AA00).rgb());
    }

    @Test
    @DisplayName("the colour only ever changes at a declared boundary")
    void changesOnlyAtBoundaries() {
        var t = threeTiers();
        for (int level = 1; level <= 200; level++) {
            if (level != 40 && level != 80) {
                assertEquals(t.colorAt(level - 1), t.colorAt(level), "unexpected change at " + level);
            }
        }
    }
}
