package com.skyprism.mc.command;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.skyprism.core.config.HudAnchor;
import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.LevelColorMode;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.util.TextClean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The three pieces of the command and screen layer that are pure logic but live behind a
 * Minecraft import, so the bare-JVM suite cannot see them.
 *
 * <p>Grown from the ad-hoc {@code cmdtest/CmdProbe2} main().</p>
 */
@DisplayName("Command-layer surfaces")
final class CommandSurfaceMcTest {

    private static final String S = String.valueOf(TextClean.SECTION);

    /**
     * {@code /skyprism replay} takes a raw Hypixel line typed by a human, and a human cannot
     * type a section sign. The ampersand convention is therefore load-bearing, and its edges
     * are where it goes wrong: a doubled ampersand must escape itself, and an ampersand in
     * front of something that is not a colour code must survive as an ampersand.
     */
    @Nested
    @DisplayName("ChatPipeline.unescape")
    final class Unescape {

        @Test
        @DisplayName("turns ampersand codes into section codes")
        void ampersandCodes() {
            assertEquals(S + "6" + S + "lRARE DROP! " + S + "r" + S + "9Crown",
                    ChatPipeline.unescape("&6&lRARE DROP! &r&9Crown"));
        }

        @Test
        @DisplayName("a section sign already in the input is left alone")
        void literalSectionPreserved() {
            assertEquals(S + "cLiteral", ChatPipeline.unescape(S + "cLiteral"));
            assertEquals(S + "c" + S + "lOh!", ChatPipeline.unescape("§c§lOh!"));
        }

        @Test
        @DisplayName("a doubled ampersand escapes itself")
        void doubledAmpersand() {
            assertEquals("Tom & Jerry", ChatPipeline.unescape("Tom && Jerry"));
        }

        /**
         * {@code a} really is a legacy colour code, so "Q&A" cannot be special-cased away;
         * the convention's cost is that this one reads oddly and is meant to.
         */
        @Test
        @DisplayName("an ampersand before a real code is a code, before anything else is text")
        void codeVersusText() {
            assertEquals("Q" + S + "A time", ChatPipeline.unescape("Q&A time"));
            assertEquals("Q&Z time", ChatPipeline.unescape("Q&Z time"));
        }

        @Test
        @DisplayName("null and a trailing ampersand are tolerated")
        void degenerateInput() {
            assertTrue(ChatPipeline.unescape(null).isEmpty());
            assertEquals("&", ChatPipeline.unescape("&"));
        }
    }

    /**
     * {@link Palettes} is built straight from user-editable config, including a chroma speed
     * the config screen lets someone type. It is called on the render thread, so throwing is
     * not an option however nonsensical the numbers are.
     */
    @Nested
    @DisplayName("Palettes tolerate any configuration")
    final class PalettesAreTotal {

        @ParameterizedTest
        @EnumSource(LevelColorMode.class)
        @DisplayName("no mode, chroma setting or speed makes palette construction throw")
        void everyModeAndSpeed(LevelColorMode mode) {
            double[] speeds = {0.35, 0.0, -1.0, 1e9, Double.NaN};
            for (boolean chroma : new boolean[] {false, true}) {
                for (double cps : speeds) {
                    SkyPrismConfig config = SkyPrismConfig.defaults();
                    config.levels.mode = mode;
                    config.levels.chromaEnabled = chroma;
                    config.levels.chromaCyclesPerSecond = cps;

                    assertDoesNotThrow(() -> {
                        LevelPalette palette = Palettes.fromConfig(config.levels);
                        palette.colorFor(0, 0L);
                        palette.colorFor(451, System.currentTimeMillis());
                        palette.colorFor(999, 1L);
                    }, () -> mode + " chroma=" + chroma + " cps=" + cps);
                }
            }
        }

        @Test
        @DisplayName("null settings yield a usable palette rather than an exception")
        void nullSettings() {
            assertNotNull(Palettes.fromConfig(null));
        }

        @Test
        @DisplayName("quantise snaps the clock to the configured update interval")
        void quantiseSnapsToTheInterval() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.levels.chromaUpdateHz = 30;
            long now = 1_700_000_123_456L;
            long step = 1_000L / 30L;

            long quantised = Palettes.quantise(now, config.levels);
            assertAll(
                    () -> assertEquals(0, quantised % step, "not on a step boundary"),
                    () -> assertTrue(quantised <= now, "quantise moved the clock forward"),
                    () -> assertTrue(quantised > now - step, "quantise lost more than one step"));
        }

        @Test
        @DisplayName("a zero update rate does not divide by zero")
        void quantiseWithZeroHz() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.levels.chromaUpdateHz = 0;
            assertTrue(Palettes.quantise(12_345L, config.levels) >= 0);
        }
    }

    /**
     * The HUD placement screen stores a position as one fraction of the screen and turns it
     * back into pixels through {@link HudAnchor#topLeft}. Dragging the widget runs that
     * conversion in reverse, so the two have to be exact inverses or the box creeps a pixel
     * every time it is picked up.
     */
    @Nested
    @DisplayName("HudAnchor placement is invertible")
    final class AnchorRoundTrip {

        @ParameterizedTest
        @EnumSource(HudAnchor.class)
        @DisplayName("topLeft and the drag-handler's inverse agree at every screen size")
        void inverseHolds(HudAnchor anchor) {
            int[][] screens = {{1920, 1080}, {854, 480}, {3840, 2160}};
            double[][] positions = {{0.0, 0.0}, {0.5, 0.25}, {1.0, 1.0}, {0.37, 0.91}};
            int boxWidth = 190;
            int boxHeight = 46;

            for (int[] screen : screens) {
                for (double[] position : positions) {
                    double[] topLeft = anchor.topLeft(
                            screen[0], screen[1], boxWidth, boxHeight, position[0], position[1]);
                    double x = (topLeft[0] + boxWidth * anchor.xFraction()) / screen[0];
                    double y = (topLeft[1] + boxHeight * anchor.yFraction()) / screen[1];

                    assertEquals(position[0], x, 1e-9,
                            () -> anchor + " x at " + screen[0] + "x" + screen[1]);
                    assertEquals(position[1], y, 1e-9,
                            () -> anchor + " y at " + screen[0] + "x" + screen[1]);
                }
            }
        }
    }
}
