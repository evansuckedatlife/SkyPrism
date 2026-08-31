package com.skyprism.mc.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.BurrowDig;
import com.skyprism.core.diana.DianaPatterns;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.MythologicalCreature;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * The seam between what Hypixel actually sends and what the Minecraft-free Diana parsers
 * expect to read.
 *
 * <p>{@code LootParserTest} and {@code DianaPatternsTest} feed the parsers hand-written
 * section-coded strings, which is the right way to test a parser but proves nothing about
 * where those strings come from. In the live game the input is a styled
 * {@link Component} tree that Hypixel built out of a dozen siblings, and
 * {@link LegacyText#toLegacy(Component)} is the only thing standing between that tree and
 * the regexes. If it emits a reset in the wrong place, or collapses two identically styled
 * siblings differently than expected, every Diana pattern silently stops matching and no
 * Minecraft-free test can see it.
 *
 * <p>So these cases build the tree the way the server would, flatten it, and assert on both
 * the exact raw string and the parse that follows. Grown from the ad-hoc
 * {@code chatprobe/LegacyProbe} main().</p>
 */
@DisplayName("LegacyText, against real styled Component trees")
final class LegacyTextMcTest {

    private static MutableComponent part(String text, ChatFormatting... formats) {
        return Component.literal(text).withStyle(formats);
    }

    /** {@code RARE DROP! You dug out a Griffin Feather!}, as four styled siblings. */
    private static Component treasureItem() {
        return Component.empty()
                .append(part("RARE DROP! ", ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(part("You dug out a ", ChatFormatting.YELLOW))
                .append(part("Griffin Feather", ChatFormatting.BLUE))
                .append(part("!", ChatFormatting.YELLOW));
    }

    /**
     * A banner drop whose magic-find tail is four consecutively identically styled siblings,
     * which is the shape most likely to expose a collapsing bug.
     */
    private static Component bannerDrop() {
        return Component.empty()
                .append(part("RARE DROP! ", ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(part("Dwarf Turtle Shelmet ", ChatFormatting.BLUE))
                .append(part("(+", ChatFormatting.AQUA))
                .append(part("168% ", ChatFormatting.AQUA))
                .append(part("* Magic Find", ChatFormatting.AQUA))
                .append(part(")", ChatFormatting.AQUA));
    }

    private static Component spawnLine() {
        return Component.empty()
                .append(part("Oh! ", ChatFormatting.RED, ChatFormatting.BOLD))
                .append(part("You dug out a ", ChatFormatting.YELLOW))
                .append(part("Minos Inquisitor", ChatFormatting.RED))
                .append(part("!", ChatFormatting.YELLOW));
    }

    private static Component burrowLine() {
        return Component.empty()
                .append(part("You dug out a Griffin Burrow! ", ChatFormatting.YELLOW))
                .append(part("(3/4)", ChatFormatting.GRAY));
    }

    private static Component coinsLine() {
        return Component.empty()
                .append(part("Wow! ", ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(part("You dug out ", ChatFormatting.YELLOW))
                .append(part("2,500 coins", ChatFormatting.GOLD))
                .append(part("!", ChatFormatting.YELLOW));
    }

    @Nested
    @DisplayName("flattening a tree to section codes")
    final class ToLegacy {

        @Test
        @DisplayName("a treasure line")
        void treasure() {
            assertEquals("§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
                    LegacyText.toLegacy(treasureItem()));
        }

        @Test
        @DisplayName("a banner line, including four same-styled siblings in a row")
        void banner() {
            assertEquals("§6§lRARE DROP! §r§9Dwarf Turtle Shelmet §r§b(+§r§b168% "
                            + "§r§b* Magic Find§r§b)",
                    LegacyText.toLegacy(bannerDrop()));
        }

        @Test
        @DisplayName("a burrow line")
        void burrow() {
            assertEquals("§eYou dug out a Griffin Burrow! §r§7(3/4)",
                    LegacyText.toLegacy(burrowLine()));
        }

        /**
         * Hypixel often sends one unstyled literal with the codes already baked into the
         * text. Re-encoding it must be the identity, not a doubling.
         */
        @Test
        @DisplayName("a pre-coded single literal passes through unchanged")
        void preCodedPassthrough() {
            String legacy = "§6§lRARE DROP! §r§9Griffin Feather§r§e!";
            assertEquals(legacy, LegacyText.toLegacy(Component.literal(legacy)));
        }
    }

    @Nested
    @DisplayName("round trip through fromLegacy")
    final class RoundTrip {

        private void assertRoundTrips(Component tree) {
            String raw = LegacyText.toLegacy(tree);
            assertEquals(raw, LegacyText.toLegacy(LegacyText.fromLegacy(raw)));
        }

        @Test
        @DisplayName("treasure, banner and spawn lines all survive")
        void everyShape() {
            assertRoundTrips(treasureItem());
            assertRoundTrips(bannerDrop());
            assertRoundTrips(spawnLine());
        }

        /** A dangling section sign is data, not a truncated code; it must not be swallowed. */
        @Test
        @DisplayName("a lone trailing section sign is kept")
        void loneTrailingSection() {
            assertEquals("hi§", LegacyText.toLegacy(LegacyText.fromLegacy("hi§")));
        }
    }

    /**
     * The point of the whole class: what the flattener emits is what the parsers read.
     */
    @Nested
    @DisplayName("the flattened line is what the Diana parsers accept")
    final class FeedsTheParsers {

        private final LootParser parser = new LootParser();

        @Test
        @DisplayName("treasure item parses to one named drop")
        void treasureParses() {
            List<LootDrop> drops = parser.parse(LegacyText.toLegacy(treasureItem()));
            assertEquals(1, drops.size(), () -> "got " + drops);
            assertEquals("Griffin Feather", drops.get(0).itemName());
        }

        @Test
        @DisplayName("banner drop parses to one named drop, magic-find tail discarded")
        void bannerParses() {
            List<LootDrop> drops = parser.parse(LegacyText.toLegacy(bannerDrop()));
            assertEquals(1, drops.size(), () -> "got " + drops);
            assertEquals("Dwarf Turtle Shelmet", drops.get(0).itemName());
        }

        @Test
        @DisplayName("coins parse with their thousands separator stripped")
        void coinsParse() {
            List<LootDrop> drops = parser.parse(LegacyText.toLegacy(coinsLine()));
            assertEquals(1, drops.size(), () -> "got " + drops);
            assertEquals(2500, drops.get(0).count());
        }

        @Test
        @DisplayName("the spawn line matches its creature")
        void spawnMatches() {
            Optional<MythologicalCreature> match =
                    DianaPatterns.matchSpawn(LegacyText.toLegacy(spawnLine()));
            assertEquals(Optional.of(MythologicalCreature.MINOS_INQUISITOR), match);
        }

        @Test
        @DisplayName("the burrow line matches with its chain position")
        void burrowMatches() {
            Optional<BurrowDig> dig =
                    DianaPatterns.matchBurrowDig(LegacyText.toLegacy(burrowLine()));
            assertTrue(dig.isPresent(), "burrow line stopped matching");
            assertEquals(3, dig.get().current());
            assertEquals(4, dig.get().max());
            assertTrue(!dig.get().chainFinished());
        }
    }
}
