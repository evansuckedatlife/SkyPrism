package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Bare-JVM tests for {@link DianaGate}.
 *
 * <p>The interesting property is not the boolean algebra of {@link DianaGate#isOpen()} -- it is
 * that {@link DianaGate#consumeChanged()} fires exactly once per <em>openness</em> transition and
 * never for an input change that left openness alone. Callers register and unregister listeners on
 * that signal, so a spurious edge means churning subscriptions every time the player walks between
 * two allowed islands, and a missing edge means the feature silently never attaches.
 */
class DianaGateTest {

    /** Written as an escape, matching TextCleanTest, so no source-encoding setting can change it. */
    private static final String S = "\u00A7";

    private DianaGate gate;

    @BeforeEach
    void setUp() {
        gate = new DianaGate();
    }

    /** Sets the three session flags that, with the default any-area whitelist, open the gate. */
    private void openIt() {
        gate.setOnHypixel(true);
        gate.setInSkyBlock(true);
        gate.setMayorDiana(true);
    }

    @Nested
    @DisplayName("openness")
    class Openness {

        @Test
        @DisplayName("a fresh gate is closed and has no edge to report")
        void freshGateIsClosed() {
            assertFalse(gate.isOpen());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("all three session conditions are required, and the last one opens it")
        void allThreeConditionsRequired() {
            gate.setOnHypixel(true);
            assertFalse(gate.isOpen());
            gate.setInSkyBlock(true);
            assertFalse(gate.isOpen());
            gate.setMayorDiana(true);
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("dropping any single condition closes it again")
        void anyConditionCloses() {
            openIt();
            gate.setOnHypixel(false);
            assertFalse(gate.isOpen());

            gate.setOnHypixel(true);
            assertTrue(gate.isOpen());
            gate.setInSkyBlock(false);
            assertFalse(gate.isOpen());

            gate.setInSkyBlock(true);
            assertTrue(gate.isOpen());
            gate.setMayorDiana(false);
            assertFalse(gate.isOpen());
        }
    }

    @Nested
    @DisplayName("edge signalling")
    class Edges {

        @Test
        @DisplayName("consumeChanged reports an opening edge exactly once")
        void openingEdgeFiresOnce() {
            openIt();
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged(), "the edge is consumed, not sticky");
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("consumeChanged reports a closing edge exactly once")
        void closingEdgeFiresOnce() {
            openIt();
            gate.consumeChanged();

            gate.setMayorDiana(false);
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("setting a flag to the value it already holds raises nothing")
        void noOpSetRaisesNothing() {
            openIt();
            gate.consumeChanged();

            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            gate.setMayorDiana(true);
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged(), "a no-op set must not churn listener registration");
        }

        @Test
        @DisplayName("a change that leaves the gate closed raises nothing")
        void changeWhileStillClosedRaisesNothing() {
            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            assertFalse(gate.isOpen());
            assertFalse(gate.consumeChanged(), "closed to closed is not an edge");
        }

        @Test
        @DisplayName("walking between two allowed areas raises nothing")
        void walkingBetweenAllowedAreasRaisesNothing() {
            gate.setAllowedAreas(Set.of("Hub", "Crimson Isle"));
            gate.setArea("Hub");
            openIt();
            assertTrue(gate.isOpen());
            assertTrue(gate.consumeChanged());

            gate.setArea("Crimson Isle");
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged(), "still open, so nothing to re-register");
        }

        @Test
        @DisplayName("several input changes that net out to one transition still fire only once")
        void severalChangesOneEdge() {
            openIt();
            assertTrue(gate.consumeChanged());

            gate.setOnHypixel(false);
            gate.setInSkyBlock(false);
            gate.setMayorDiana(false);
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged(), "one closing edge");
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("an open/close pair nobody observed still reports at most one edge")
        void unconsumedReversalIsReportedOnce() {
            openIt();
            gate.setMayorDiana(false);
            // The gate opened and closed again between two polls. The flag is a "something moved,
            // go and look" signal rather than a queue, so the caller is told once and re-reads
            // isOpen() to find out what it actually is -- here, still closed.
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }
    }

    @Nested
    @DisplayName("areas")
    class Areas {

        @Test
        @DisplayName("an empty whitelist means any area, including an unknown one")
        void emptyWhitelistMeansAnyArea() {
            openIt();
            assertTrue(gate.isOpen(), "the default, unconfigured gate must work everywhere");

            gate.setArea("Hub");
            assertTrue(gate.isOpen());
            gate.setArea("The Catacombs");
            assertTrue(gate.isOpen());
            gate.setArea(null);
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("an explicitly emptied whitelist also means any area")
        void explicitlyEmptiedWhitelistMeansAnyArea() {
            gate.setAllowedAreas(Set.of("Hub"));
            gate.setArea("Crimson Isle");
            openIt();
            assertFalse(gate.isOpen());

            gate.setAllowedAreas(Set.of());
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("a null whitelist is treated as empty, i.e. any area")
        void nullWhitelistMeansAnyArea() {
            gate.setAllowedAreas(Set.of("Hub"));
            gate.setArea("Crimson Isle");
            openIt();
            assertFalse(gate.isOpen());

            gate.setAllowedAreas(null);
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("with a whitelist, only a listed area opens the gate")
        void whitelistRestricts() {
            gate.setAllowedAreas(Set.of("Hub", "Crimson Isle"));
            openIt();
            assertFalse(gate.isOpen(), "unknown area under a whitelist stays closed");

            gate.setArea("Hub");
            assertTrue(gate.isOpen());
            gate.setArea("The End");
            assertFalse(gate.isOpen());
            gate.setArea("Crimson Isle");
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("an unknown area is closed under a whitelist, because it is not a match")
        void unknownAreaClosedUnderWhitelist() {
            gate.setAllowedAreas(Set.of("Hub"));
            gate.setArea("Hub");
            openIt();
            assertTrue(gate.isOpen());

            gate.setArea(null);
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged());

            gate.setArea("   ");
            assertFalse(gate.isOpen(), "blank is unknown too");
            assertFalse(gate.consumeChanged(), "unknown to unknown is not an edge");
        }

        @Test
        @DisplayName("areas match through formatting codes, casing and stray spacing")
        void areaMatchingIsNormalised() {
            gate.setAllowedAreas(Set.of("crimson isle"));
            openIt();

            gate.setArea(S + "6Crimson " + S + "rIsle");
            assertTrue(gate.isOpen(), "formatting codes must not defeat the match");
            gate.setArea("  CRIMSON   ISLE  ");
            assertTrue(gate.isOpen(), "casing and spacing must not defeat it either");
        }

        @Test
        @DisplayName("whitelist entries are normalised too, and blank entries are discarded")
        void whitelistEntriesAreNormalised() {
            var configured = new LinkedHashSet<String>();
            configured.add("  " + S + "aHub  ");
            configured.add("   ");
            configured.add("");
            gate.setAllowedAreas(configured);
            openIt();

            gate.setArea("hub");
            assertTrue(gate.isOpen());
            gate.setArea("Crimson Isle");
            assertFalse(gate.isOpen(), "a blank config row must not match everything");
        }

        @Test
        @DisplayName("narrowing the whitelist to exclude the current area closes the gate, once")
        void narrowingWhitelistCloses() {
            gate.setArea("Hub");
            openIt();
            assertTrue(gate.isOpen());
            gate.consumeChanged();

            gate.setAllowedAreas(Set.of("Crimson Isle"));
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("setting the same whitelist again raises nothing, whatever order it arrives in")
        void sameWhitelistIsANoOp() {
            gate.setArea("Hub");
            gate.setAllowedAreas(Set.of("Hub", "Crimson Isle"));
            openIt();
            gate.consumeChanged();

            gate.setAllowedAreas(Set.of("Crimson Isle", "Hub"));
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged());

            gate.setAllowedAreas(Set.of(S + "eHUB", "  crimson isle "));
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged(), "normalisation makes these the same whitelist");
        }

        @Test
        @DisplayName("the caller's set is copied, so mutating it afterwards cannot reopen the gate")
        void whitelistIsCopied() {
            var mutable = new LinkedHashSet<String>();
            mutable.add("Hub");
            gate.setAllowedAreas(mutable);
            gate.setArea("Crimson Isle");
            openIt();
            assertFalse(gate.isOpen());

            mutable.add("Crimson Isle");
            assertFalse(gate.isOpen(), "the gate must not see a whitelist edit it was never told about");
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("reset closes an open gate and reports the closing edge")
        void resetClosesAndFires() {
            openIt();
            gate.consumeChanged();

            gate.reset();
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("resetting an already-closed gate raises nothing")
        void resetWhileClosedRaisesNothing() {
            gate.setOnHypixel(true);
            gate.reset();
            assertFalse(gate.isOpen());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("reset clears the session state but keeps the whitelist, which is configuration")
        void resetKeepsTheWhitelist() {
            gate.setAllowedAreas(Set.of("Hub"));
            gate.setArea("Hub");
            openIt();
            assertTrue(gate.isOpen());

            gate.reset();
            assertFalse(gate.isOpen());

            // rejoining: the session flags must be re-supplied, and the area was forgotten,
            // so the whitelist still holds the gate shut until the area is known again
            openIt();
            assertFalse(gate.isOpen(), "reset forgot which area we were in");
            gate.setArea("Hub");
            assertTrue(gate.isOpen(), "and the configured whitelist survived to match it");
        }
    }

    // ------------------------------------------------------------------ hostile inputs

    /**
     * Inputs a config file or a scoreboard can genuinely produce and that a naive implementation
     * would throw on or silently mismatch: nulls inside the configured set, supplementary code
     * points, the exotic spaces Hypixel mixes into its strings, and absurd lengths.
     */
    @Nested
    @DisplayName("hostile inputs")
    class Hostile {

        @Test
        @DisplayName("a whitelist carrying nulls, blanks and code-only entries is accepted, not thrown at")
        void nullEntriesInWhitelist() {
            var messy = new java.util.HashSet<String>();
            messy.add("Hub");
            messy.add(null);
            messy.add("");
            messy.add("   ");
            messy.add(S + "a" + S + "l");
            gate.setAllowedAreas(messy);
            openIt();

            gate.setArea("Hub");
            assertTrue(gate.isOpen());
            gate.setArea("Crimson Isle");
            assertFalse(gate.isOpen(), "the discarded blank entries must not match everything");
        }

        @Test
        @DisplayName("supplementary code points and exotic spaces normalise on both sides")
        void unicodeAreas() {
            gate.setAllowedAreas(Set.of("\uD83D\uDC0D den"));
            openIt();

            gate.setArea("\u00A0" + S + "5\uD83D\uDC0D\u2003DEN\u00A0");
            assertTrue(gate.isOpen(), "nbsp, em space, casing and formatting codes must all normalise away");
            gate.consumeChanged();

            gate.setArea("  \uD83D\uDC0D   Den  ");
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged(), "a different spelling of the same area is not an edge");
        }

        @Test
        @DisplayName("an emoji area name survives stripping intact rather than losing a surrogate")
        void surrogatePairSurvives() {
            gate.setAllowedAreas(Set.of("\uD83D\uDC0D den"));
            openIt();

            // The pair must come through whole: half a surrogate would compare unequal to itself.
            gate.setArea(S + "b\uD83D\uDC0D " + S + "aden");
            assertTrue(gate.isOpen());

            // And a section sign in front of an emoji is content, not a code, per TextClean's
            // documented policy -- so it makes a genuinely different area name, not a match.
            gate.setArea(S + "\uD83D\uDC0D den");
            assertFalse(gate.isOpen(), "a lone section sign is text and must stay in the name");
        }

        @Test
        @DisplayName("a hundred-thousand-character area name matches or misses exactly, without truncation")
        void veryLongArea() {
            String longName = "a".repeat(100_000);
            gate.setAllowedAreas(Set.of(longName));
            openIt();

            gate.setArea(longName);
            assertTrue(gate.isOpen());
            gate.setArea(longName + "b");
            assertFalse(gate.isOpen());
        }

        @Test
        @DisplayName("repeated null whitelists are a no-op, not a churn of edges")
        void repeatedNullWhitelist() {
            openIt();
            gate.consumeChanged();

            gate.setAllowedAreas(null);
            gate.setAllowedAreas(null);
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("reset is idempotent: three in a row still report one closing edge")
        void resetIdempotent() {
            openIt();
            gate.consumeChanged();

            gate.reset();
            gate.reset();
            gate.reset();
            assertFalse(gate.isOpen());
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("widening the whitelist to include the current area opens it, exactly once")
        void wideningWhitelistOpens() {
            gate.setAllowedAreas(Set.of("Crimson Isle"));
            gate.setArea("Hub");
            openIt();
            assertFalse(gate.isOpen());
            gate.consumeChanged();

            gate.setAllowedAreas(Set.of("Crimson Isle", "Hub"));
            assertTrue(gate.isOpen());
            assertTrue(gate.consumeChanged());
            assertFalse(gate.consumeChanged());
        }

        @Test
        @DisplayName("entries that collapse under normalisation are one whitelist, so re-setting it is quiet")
        void collapsingWhitelist() {
            gate.setArea("Hub");
            openIt();
            gate.setAllowedAreas(Set.of("HUB", "  hub  ", S + "6Hub"));
            assertTrue(gate.isOpen());
            gate.consumeChanged();

            gate.setAllowedAreas(Set.of("hub"));
            assertTrue(gate.isOpen());
            assertFalse(gate.consumeChanged(), "the same single normalised entry is not a change");
        }
    }
}
