package com.skyprism.mc.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.core.text.RunText;
import com.skyprism.core.text.StyledRun;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * The Component-level half of the level-prefix recolour, which the core suite cannot reach.
 *
 * <p>{@code com.skyprism.core.text.RunRewriterTest} already proves the span algebra over plain
 * {@code StyledRun<String>} values, and it must keep doing so on a bare JVM. What it cannot
 * prove is the thing that actually breaks in the game: that a real
 * {@link net.minecraft.network.chat.Component} tree survives the round trip through
 * {@link ComponentRewriter} with its {@link HoverEvent}, {@link ClickEvent} and insertion
 * intact. Hypixel hangs all three off the player-name node in chat and in TAB, and losing
 * them is invisible to whoever wrote the mod, because the colours still look right.</p>
 *
 * <p>Grown from the ad-hoc {@code CRHarness} main() written while the rewriter was being
 * built. Needs Minecraft <em>classes</em> only: no bootstrap, no registries, no game.</p>
 */
@DisplayName("ComponentRewriter, against real Component trees")
final class ComponentRewriterMcTest {

    private static final LevelPalette PALETTE = LevelPalette.defaults();
    private static final LevelTagLocator LOCATOR = LevelTagLocator.standard();

    /**
     * A chat line shaped like Hypixel's: a bold root, a coloured level prefix, a player name
     * carrying every interactive style at once, an emblem, and a body holding a second
     * bracketed number so the locator has something it must reject.
     */
    private static Component hypixelChatLine() {
        MutableComponent tree = Component.literal("").setStyle(Style.EMPTY.withBold(true));
        tree.append(Component.literal("[451] ").setStyle(Style.EMPTY.withColor(0xAA00AA)));
        tree.append(Component.literal("Notch").setStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/profile Notch"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("hi")))
                .withInsertion("Notch")
                .withItalic(true)));
        tree.append(Component.literal(" ❈").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        tree.append(Component.literal(": hello [12] world"));
        return tree;
    }

    /** The colour of the first run whose text equals {@code text}, or empty when there is none. */
    private static Optional<Integer> colourOfRun(Component component, String text) {
        for (StyledRun<Style> run : ComponentRewriter.toRuns(component)) {
            if (run.text().equals(text)) {
                return Optional.ofNullable(run.style().getColor()).map(c -> c.getValue());
            }
        }
        return Optional.empty();
    }

    /** The style of the first run whose text equals {@code text}, or null. */
    private static Style styleOfRun(Component component, String text) {
        for (StyledRun<Style> run : ComponentRewriter.toRuns(component)) {
            if (run.text().equals(text)) {
                return run.style();
            }
        }
        return null;
    }

    @Nested
    @DisplayName("run decomposition")
    final class Runs {

        @Test
        @DisplayName("flattening the runs reproduces getString() exactly")
        void flattenMatchesGetString() {
            Component tree = hypixelChatLine();
            assertEquals(tree.getString(), RunText.flatten(ComponentRewriter.toRuns(tree)));
        }

        @Test
        @DisplayName("fromRuns(toRuns(x)) round-trips the text")
        void roundTripsText() {
            Component tree = hypixelChatLine();
            List<StyledRun<Style>> runs = ComponentRewriter.toRuns(tree);
            assertEquals(tree.getString(), ComponentRewriter.fromRuns(runs).getString());
        }

        @Test
        @DisplayName("toRuns(null) is empty and fromRuns(empty) is the empty component")
        void nullAndEmpty() {
            assertTrue(ComponentRewriter.toRuns(null).isEmpty());
            assertTrue(ComponentRewriter.fromRuns(List.of()).getString().isEmpty());
        }
    }

    @Nested
    @DisplayName("digits-only recolour")
    final class DigitsOnly {

        @Test
        @DisplayName("keeps every character of the line")
        void preservesText() {
            Component tree = hypixelChatLine();
            Component out = ComponentRewriter.recolourLevels(tree, PALETTE, LOCATOR, false, 0L);
            assertEquals(tree.getString(), out.getString());
            assertNotSame(tree, out, "a line carrying a tag must come back as a new instance");
        }

        @Test
        @DisplayName("recolours both level numbers and leaves the brackets Hypixel's colour")
        void recoloursDigitsNotBrackets() {
            Component out = ComponentRewriter.recolourLevels(
                    hypixelChatLine(), PALETTE, LOCATOR, false, 0L);
            assertEquals(Optional.of(PALETTE.colorFor(451, 0L)), colourOfRun(out, "451"));
            assertEquals(Optional.of(PALETTE.colorFor(12, 0L)), colourOfRun(out, "12"));
            assertEquals(Optional.of(0xAA00AA), colourOfRun(out, "["),
                    "the bracket run must keep the colour Hypixel gave it");
        }

        /** The whole reason this class does not flatten to a string and re-emit one literal. */
        @Test
        @DisplayName("hover, click, insertion and italic all survive on the player name")
        void preservesInteractiveStyles() {
            Component out = ComponentRewriter.recolourLevels(
                    hypixelChatLine(), PALETTE, LOCATOR, false, 0L);
            Style name = styleOfRun(out, "Notch");
            assertNotNull(name, "the player-name run went missing");
            assertTrue(name.getHoverEvent() instanceof HoverEvent.ShowText, "hover event lost");
            assertTrue(name.getClickEvent() instanceof ClickEvent.RunCommand rc
                    && rc.command().equals("/profile Notch"), "click event lost");
            assertEquals("Notch", name.getInsertion(), "insertion lost");
            assertTrue(name.isItalic(), "italic lost");
        }
    }

    @Nested
    @DisplayName("whole-tag recolour")
    final class WholeTag {

        @Test
        @DisplayName("emits the brackets and digits as one recoloured run")
        void wholeTagIsOneRun() {
            Component out = ComponentRewriter.recolourLevels(
                    hypixelChatLine(), PALETTE, LOCATOR, true, 0L);
            assertEquals(Optional.of(PALETTE.colorFor(451, 0L)), colourOfRun(out, "[451]"));
        }

        @Test
        @DisplayName("keeps every character of the line")
        void preservesText() {
            Component tree = hypixelChatLine();
            Component out = ComponentRewriter.recolourLevels(tree, PALETTE, LOCATOR, true, 0L);
            assertEquals(tree.getString(), out.getString());
        }
    }

    /**
     * The identity contract the TAB memoiser is built on: when nothing matched, the caller
     * gets the very same object back and can detect the no-op with {@code ==}.
     */
    @Nested
    @DisplayName("a no-op returns the identical instance")
    final class NoOp {

        private void assertNoOp(String text) {
            Component in = Component.literal(text);
            assertSame(in, ComponentRewriter.recolourLevels(in, PALETTE, LOCATOR, true, 0L),
                    "\"" + text + "\" should not have been copied");
        }

        @Test
        @DisplayName("a line with no bracketed digits at all")
        void noTag() {
            assertNoOp("Nothing here at all");
        }

        @Test
        @DisplayName("ranks and ratios are not level tags")
        void ranksAndRatios() {
            assertNoOp("[MVP+] Notch: hi [6/8] [Lv100]");
        }

        @Test
        @DisplayName("a number past the top of the level range")
        void outOfRange() {
            assertNoOp("[123456] hello");
        }

        @Test
        @DisplayName("null in, null out")
        void nullInput() {
            assertNull(ComponentRewriter.recolourLevels(null, PALETTE, LOCATOR, true, 0L));
        }
    }

    @Nested
    @DisplayName("the cheap pre-filter")
    final class PreFilter {

        @Test
        @DisplayName("accepts a literal holding a tag, rejects one that does not")
        void plainLiterals() {
            assertTrue(ComponentRewriter.mightContainLevelTag(Component.literal("[451] x")));
            assertFalse(ComponentRewriter.mightContainLevelTag(Component.literal("hello world")));
        }

        @Test
        @DisplayName("rejects a rank prefix and an emblem-only name")
        void rejectsLookalikes() {
            assertFalse(ComponentRewriter.mightContainLevelTag(Component.literal("[MVP+] Notch")));
            assertFalse(ComponentRewriter.mightContainLevelTag(
                    Component.literal("Notch ❈❈")));
        }

        @Test
        @DisplayName("rejects the empty component")
        void rejectsEmpty() {
            assertFalse(ComponentRewriter.mightContainLevelTag(Component.empty()));
        }

        /**
         * The false negative that would matter most: Hypixel is free to split {@code [451]}
         * across siblings, and a filter that only looked inside one literal would miss it.
         */
        @Test
        @DisplayName("sees a tag split across four siblings, and recolours it")
        void tagSplitAcrossSiblings() {
            MutableComponent split = Component.empty();
            split.append(Component.literal("["));
            split.append(Component.literal("45"));
            split.append(Component.literal("1"));
            split.append(Component.literal("] Notch"));

            assertTrue(ComponentRewriter.mightContainLevelTag(split), "false negative");
            Component out = ComponentRewriter.recolourLevels(split, PALETTE, LOCATOR, false, 0L);
            assertNotSame(split, out);
            assertEquals("[451] Notch", out.getString());
        }
    }

    @Nested
    @DisplayName("awkward text")
    final class Awkward {

        /** Hypixel sometimes sends legacy section codes as literal characters inside the text. */
        @Test
        @DisplayName("a line whose text contains literal section codes")
        void legacyCodesInText() {
            Component legacy = Component.literal("§a[§b451§a] §7Notch");
            assertTrue(ComponentRewriter.mightContainLevelTag(legacy));

            Component out = ComponentRewriter.recolourLevels(legacy, PALETTE, LOCATOR, false, 0L);
            assertNotSame(legacy, out);
            assertEquals(legacy.getString(), out.getString());

            boolean digitsRecoloured = ComponentRewriter.toRuns(out).stream().anyMatch(r ->
                    r.text().contains("451")
                            && r.style().getColor() != null
                            && r.style().getColor().getValue() == PALETTE.colorFor(451, 0L));
            assertTrue(digitsRecoloured, "the 451 digits were not the recoloured run");
        }

        /** Surrogate pairs either side of a tag: span offsets are in chars, not code points. */
        @Test
        @DisplayName("astral characters either side of the tag do not shift the span")
        void astralNeighbours() {
            Component astral = Component.literal("𝐀 [451] 😀 tail");
            Component out = ComponentRewriter.recolourLevels(astral, PALETTE, LOCATOR, true, 0L);
            assertNotSame(astral, out);
            assertEquals(astral.getString(), out.getString());
        }

        @Test
        @DisplayName("two tags with no gap between them")
        void adjacentTags() {
            Component adjacent = Component.literal("[1][2] x");
            Component out = ComponentRewriter.recolourLevels(adjacent, PALETTE, LOCATOR, true, 0L);
            assertEquals("[1][2] x", out.getString());
            assertEquals(Optional.of(PALETTE.colorFor(1, 0L)), colourOfRun(out, "[1]"));
            assertEquals(Optional.of(PALETTE.colorFor(2, 0L)), colourOfRun(out, "[2]"));
        }
    }
}
