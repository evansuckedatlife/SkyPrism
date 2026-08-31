package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.LootDrop.MagicFind;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@link LootDrop} contract, and in particular the two halves of it that are easy to break by
 * accident: "not reported" is not zero, and Magic Find is not part of a drop's identity.
 */
@DisplayName("LootDrop: what dropped, and the stat it was rolled at")
class LootDropTest {

    @Nested
    @DisplayName("absent Magic Find is a distinct fact from a Magic Find of zero")
    class AbsentIsNotZero {

        @Test
        @DisplayName("the four-argument shape reports no Magic Find rather than inventing one")
        void fourArgReportsNothing() {
            LootDrop drop = new LootDrop("Griffin Feather", "9", 1, true);
            assertNull(drop.magicFind());
            assertFalse(drop.magicFindReported());
            assertEquals(Optional.empty(), drop.magicFindIfReported());
            assertNull(drop.magicFindText(),
                    "an absent reading must not render as text; the caller decides how to say "
                            + "'not reported'");
        }

        @Test
        @DisplayName("a reported zero is a real reading and says so")
        void reportedZeroIsReal() {
            LootDrop drop = new LootDrop("Rat", "6", 1, true, new MagicFind(0, true));
            assertTrue(drop.magicFindReported());
            assertEquals(0, drop.magicFind().value());
            assertEquals("+0% Magic Find", drop.magicFindText());
        }

        @Test
        @DisplayName("the two are distinguishable, which is the whole point")
        void theTwoAreDistinguishable() {
            LootDrop absent = new LootDrop("Rat", "6", 1, true);
            LootDrop zero = new LootDrop("Rat", "6", 1, true, new MagicFind(0, true));
            assertFalse(absent.magicFindReported());
            assertTrue(zero.magicFindReported());
            assertNull(absent.magicFindText());
            assertNotNull(zero.magicFindText());
        }

        @Test
        @DisplayName("LootDrop.of reports nothing, because nobody told it anything")
        void ofReportsNothing() {
            assertFalse(LootDrop.of("Chimera").magicFindReported());
        }

        @Test
        @DisplayName("a negative reading is refused rather than clamped into a lie")
        void negativeIsRefused() {
            assertThrows(IllegalArgumentException.class, () -> new MagicFind(-1, true));
        }
    }

    @Nested
    @DisplayName("the percent sign is echoed, never assumed")
    class PercentSign {

        @Test
        @DisplayName("with a percent sign")
        void withSign() {
            assertEquals("+240%", new MagicFind(240, true).format());
            assertEquals("+240% Magic Find", new MagicFind(240, true).describe());
        }

        @Test
        @DisplayName("without one -- Hypixel sends both forms for the same event")
        void withoutSign() {
            assertEquals("+168", new MagicFind(168, false).format());
            assertEquals("+168 Magic Find", new MagicFind(168, false).describe());
        }

        @Test
        @DisplayName("the two forms are not equal, so the sign cannot be silently normalised away")
        void signIsPartOfTheReading() {
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    new MagicFind(168, true), new MagicFind(168, false));
        }

        @Test
        @DisplayName("no private-use glyph is stored -- the words are the durable anchor")
        void noGlyph() {
            String described = new MagicFind(208, true).describe();
            for (int i = 0; i < described.length(); i++) {
                char c = described.charAt(i);
                assertFalse(Character.getType(c) == Character.PRIVATE_USE,
                        "a private-use codepoint reached the reveal text: " + described);
                assertFalse(c == '✯', "the legacy star reached the reveal text: " + described);
            }
        }
    }

    @Nested
    @DisplayName("Magic Find is provenance, not identity")
    class EqualityContract {

        private final LootDrop plain = new LootDrop("Judgement Core", "9", 1, true);
        private final LootDrop rolled =
                new LootDrop("Judgement Core", "9", 1, true, new MagicFind(240, true));

        @Test
        @DisplayName("two drops differing only in Magic Find are the same drop")
        void equalIgnoringMagicFind() {
            assertEquals(plain, rolled);
            assertEquals(rolled, plain);
            assertEquals(plain.hashCode(), rolled.hashCode());
            assertEquals(1, new java.util.HashSet<>(java.util.List.of(plain, rolled)).size(),
                    "a dedupe set must not see one drop as two because the stat differed");
        }

        @Test
        @DisplayName("the four identity components still separate drops")
        void identityStillSeparates() {
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    plain, new LootDrop("Judgement Core", "9", 2, true));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    plain, new LootDrop("Judgement Core", "5", 1, true));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    plain, new LootDrop("Judgement Core", "9", 1, false));
            org.junit.jupiter.api.Assertions.assertNotEquals(
                    plain, new LootDrop("Sorrow", "9", 1, true));
            org.junit.jupiter.api.Assertions.assertNotEquals(plain, "Judgement Core");
            org.junit.jupiter.api.Assertions.assertNotEquals(plain, null);
        }

        @Test
        @DisplayName("toString still shows the stat, so a failed assertion is readable")
        void toStringKeepsTheStat() {
            assertTrue(rolled.toString().contains("240"),
                    "equality ignores the stat, but the message must not: " + rolled);
        }

        @Test
        @DisplayName("a map keyed on drops finds the entry whatever the stat was")
        void mapLookupWorks() {
            Map<LootDrop, String> byDrop = Map.of(plain, "seen");
            assertEquals("seen", byDrop.get(rolled));
        }
    }

    @Nested
    @DisplayName("the derived shapes")
    class Derived {

        @Test
        @DisplayName("withMagicFind attaches, replaces and clears")
        void withMagicFind() {
            LootDrop bare = new LootDrop("Sorrow", "9", 1, true);
            LootDrop rolled = bare.withMagicFind(new MagicFind(97, true));
            assertEquals(new MagicFind(97, true), rolled.magicFind());
            assertEquals(new MagicFind(12, false), rolled.withMagicFind(new MagicFind(12, false))
                    .magicFind());
            assertNull(rolled.withMagicFind(null).magicFind());
            assertEquals("Sorrow", rolled.itemName());
            assertEquals(1, rolled.count());
        }

        @Test
        @DisplayName("asRare promotes without discarding the stat the reveal is about to show")
        void asRareKeepsMagicFind() {
            LootDrop common = new LootDrop("Enchanted Gold", "9", 16, false, new MagicFind(168, true));
            LootDrop promoted = common.asRare();
            assertTrue(promoted.rare());
            assertEquals(new MagicFind(168, true), promoted.magicFind());
            assertEquals(16, promoted.count());
            assertEquals("Enchanted Gold", promoted.itemName());
        }

        @Test
        @DisplayName("asRare on an already-rare drop is the same object")
        void asRareIsIdentityWhenAlreadyRare() {
            LootDrop rare = new LootDrop("Chimera", "6", 1, true);
            assertSame(rare, rare.asRare());
        }
    }

    @Nested
    @DisplayName("the invariants that predate Magic Find still hold")
    class OldInvariants {

        @Test
        @DisplayName("a null name is refused")
        void nullName() {
            assertThrows(NullPointerException.class, () -> new LootDrop(null, "9", 1, true));
        }

        @Test
        @DisplayName("a count below one is refused, with or without a stat")
        void countFloor() {
            assertThrows(IllegalArgumentException.class, () -> new LootDrop("X", "9", 0, true));
            assertThrows(IllegalArgumentException.class,
                    () -> new LootDrop("X", "9", -1, true, new MagicFind(5, true)));
        }

        @Test
        @DisplayName("a null colour is allowed, because uncoloured drops are real")
        void nullColour() {
            assertNull(new LootDrop("Mana Steal I", null, 1, true).colorCode());
        }
    }
}
