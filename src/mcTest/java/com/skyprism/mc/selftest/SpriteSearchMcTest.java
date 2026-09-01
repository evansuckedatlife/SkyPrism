package com.skyprism.mc.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

/**
 * Pins the two pieces of arithmetic the whole capture enforcement rests on.
 *
 * <h2>Why these two and not the self test itself</h2>
 *
 * <p>{@link SelfTest} needs a running client, a window and Hypixel's pack on disk, so it cannot be
 * a unit test and never will be. But everything it concludes about a screenshot passes through
 * exactly two pure functions, and if either of them is wrong the whole audit is a confident-looking
 * lie of precisely the kind it exists to replace:</p>
 *
 * <ul>
 *   <li>{@link SpriteSearch#find} decides whether a texture is in a picture. A false positive
 *       makes every vanilla frame pass; a false negative makes every correct frame fail.</li>
 *   <li>{@link PackAssets#key} decides whether a drop name is joined to a pack item at all. Get it
 *       wrong in one direction and items silently stop being held to the pack; wrong in the other
 *       and a reel is dressed in some other item's art and photographed as proof.</li>
 * </ul>
 *
 * <p>The frames here are synthetic, which is the point: a real screenshot could only ever say
 * "this worked once", while a planted sprite says what the search does at a known scale, at a
 * known offset, and when the texture genuinely is not there.</p>
 */
@DisplayName("the pixel search the capture audit is built on")
final class SpriteSearchMcTest {

    /** A frame the size of a real capture, so the search is exercised at the cost it really pays. */
    private static final int FRAME_W = 1920;
    private static final int FRAME_H = 1080;

    /** A checkerboard backdrop, the same shape SlotStageScreen draws behind the widget. */
    private static SpriteSearch.Pixels backdrop() {
        int[] px = new int[FRAME_W * FRAME_H];
        for (int y = 0; y < FRAME_H; y++) {
            for (int x = 0; x < FRAME_W; x++) {
                px[y * FRAME_W + x] = ((x / 24 + y / 24) % 2 == 0) ? 0xFF10141A : 0xFF161C25;
            }
        }
        return new SpriteSearch.Pixels(px, FRAME_W, FRAME_H);
    }

    /**
     * A 16x16 sprite with a deterministic, distinctive palette and a transparent border.
     *
     * <p>Seeded so the test is repeatable, and deliberately not flat: a single-colour template
     * would be found everywhere and would prove nothing about the search.</p>
     */
    private static SpriteSearch.Pixels sprite(long seed) {
        Random random = new Random(seed);
        int[] px = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                boolean border = x < 3 || y < 3 || x > 12 || y > 12;
                px[y * 16 + x] = border ? 0x00000000 : 0xFF000000 | random.nextInt(0x01000000);
            }
        }
        return new SpriteSearch.Pixels(px, 16, 16);
    }

    /** Paints a template into a frame at a whole magnification, the way the GUI would. */
    private static void plant(SpriteSearch.Pixels frame, SpriteSearch.Pixels tile,
                              int left, int top, int scale) {
        for (int y = 0; y < tile.height(); y++) {
            for (int x = 0; x < tile.width(); x++) {
                int argb = tile.at(x, y);
                if ((argb >>> 24) != 0xFF) {
                    continue;
                }
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        int fx = left + x * scale + dx;
                        int fy = top + y * scale + dy;
                        frame.argb()[fy * frame.width() + fx] = argb;
                    }
                }
            }
        }
    }

    @ParameterizedTest(name = "a sprite drawn at {0}x is located exactly")
    @CsvSource({"1, 600, 400", "2, 337, 291", "3, 1000, 12", "8, 44, 700", "16, 336, 316"})
    @DisplayName("a planted sprite is found at its exact position and magnification")
    void locatesAPlantedSprite(int scale, int left, int top) {
        SpriteSearch.Pixels frame = backdrop();
        SpriteSearch.Pixels tile = sprite(7L);
        plant(frame, tile, left, top, scale);

        SpriteSearch.Result result = SpriteSearch.find(frame, SpriteSearch.template(tile));

        assertEquals(SpriteSearch.Outcome.FOUND, result.outcome(), result.note());
        assertNotNull(result.hit());
        assertEquals(scale, result.hit().scale(), "magnification");
        assertEquals(left, result.hit().x(), "left edge");
        assertEquals(top, result.hit().y(), "top edge");
        // 10x10 of the 16x16 is opaque; the transparent border is deliberately not counted.
        assertEquals(100, result.hit().texels());
    }

    @Test
    @DisplayName("a sprite that is not in the frame is reported absent, not merely unfound")
    void absentSpriteIsAbsent() {
        SpriteSearch.Pixels frame = backdrop();
        plant(frame, sprite(7L), 336, 316, 16);

        SpriteSearch.Result result = SpriteSearch.find(frame, SpriteSearch.template(sprite(99L)));

        assertEquals(SpriteSearch.Outcome.NOT_FOUND, result.outcome(), result.note());
        assertFalse(result.isFound());
    }

    @Test
    @DisplayName("one wrong texel is enough: the match is exact, never approximate")
    void oneWrongTexelIsAMiss() {
        SpriteSearch.Pixels frame = backdrop();
        SpriteSearch.Pixels tile = sprite(7L);
        plant(frame, tile, 336, 316, 16);
        // Repaint one magnified texel. A tolerant match would shrug at 1 pixel in 100 and pass,
        // which is exactly how a "close enough" check lets a wrong sprite through.
        for (int dy = 0; dy < 16; dy++) {
            for (int dx = 0; dx < 16; dx++) {
                frame.argb()[(316 + 5 * 16 + dy) * FRAME_W + (336 + 5 * 16 + dx)] = 0xFF7B2D8E;
            }
        }

        SpriteSearch.Result result = SpriteSearch.find(frame, SpriteSearch.template(tile));

        assertFalse(result.isFound(), "a single altered texel must not still count as this sprite");
    }

    @Test
    @DisplayName("two different sprites in one frame are told apart")
    void twoSpritesAreToldApart() {
        SpriteSearch.Pixels frame = backdrop();
        SpriteSearch.Pixels first = sprite(11L);
        SpriteSearch.Pixels second = sprite(12L);
        plant(frame, first, 336, 316, 16);
        plant(frame, second, 832, 316, 16);

        SpriteSearch.Result a = SpriteSearch.find(frame, SpriteSearch.template(first));
        SpriteSearch.Result b = SpriteSearch.find(frame, SpriteSearch.template(second));

        assertTrue(a.isFound() && b.isFound(), a.note() + " / " + b.note());
        assertEquals(336, a.hit().x());
        assertEquals(832, b.hit().x());
    }

    @Test
    @DisplayName("a texture with almost nothing opaque in it refuses to answer")
    void tooFewTexelsIsRefusedRatherThanGuessed() {
        int[] px = new int[16 * 16];
        px[0] = 0xFFFF0000;
        px[1] = 0xFF00FF00;
        SpriteSearch.Result result = SpriteSearch.find(backdrop(),
                SpriteSearch.template(new SpriteSearch.Pixels(px, 16, 16)));

        assertEquals(SpriteSearch.Outcome.UNUSABLE, result.outcome(), result.note());
        assertFalse(result.isFound());
    }

    @Test
    @DisplayName("a partly transparent texel is skipped rather than blended against a guess")
    void semiTransparentTexelsAreIgnored() {
        SpriteSearch.Pixels tile = sprite(21L);
        // Make one interior texel half-transparent, then plant only the fully opaque ones. What
        // composites behind a translucent texel is whatever the widget drew there, which the
        // search has no way to know -- so it must not be part of the match.
        tile.argb()[8 * 16 + 8] = 0x80FF00FF;
        SpriteSearch.Pixels frame = backdrop();
        plant(frame, tile, 200, 200, 4);

        SpriteSearch.Result result = SpriteSearch.find(frame, SpriteSearch.template(tile));

        assertTrue(result.isFound(), result.note());
        assertEquals(99, result.hit().texels(), "the translucent texel must not be counted");
    }

    @ParameterizedTest(name = "\"{0}\" joins the pack as {1}")
    @CsvSource({
        "Daedalus Blade,        daedalus_blade",
        "daedalus blade,        daedalus_blade",
        "Necron's Handle,       necrons_handle",
        "Washed-up Souvenir,    washedup_souvenir",
        "Manti-core,            manticore",
        "Emperor's Skull,       emperors_skull",
        "Null  Atom,            null_atom",
        "Chimera I,             chimera_i",
        "already_snake_case,    already_snake_case"
    })
    @DisplayName("the display-name to pack-basename join is the one SkyBlock's own ids use")
    void nameJoinMatchesSkyblockInternalIds(String name, String expected) {
        assertEquals(expected, PackAssets.key(name));
    }

    @Test
    @DisplayName("a name that normalises to nothing yields no key rather than a wildcard")
    void emptyNamesDoNotJoinAnything() {
        assertEquals("", PackAssets.key(null));
        assertEquals("", PackAssets.key(""));
        assertEquals("", PackAssets.key("!!!"));
        // Surrounding and doubled spacing collapse rather than producing a leading underscore,
        // which would join nothing at all and would do it silently.
        assertEquals("coins", PackAssets.key("  Coins  "));
        assertEquals("wither_essence", PackAssets.key("§dWither Essence"));
    }
}
