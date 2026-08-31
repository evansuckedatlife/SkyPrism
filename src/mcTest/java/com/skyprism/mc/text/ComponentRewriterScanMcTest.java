package com.skyprism.mc.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.serialization.MapCodec;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reporting half of {@link ComponentRewriter#recolourLevels}, added because two callers were
 * each re-deriving a fact the rewriter had just established.
 *
 * <p>The TAB and nametag surfaces needed the highest level in the component to decide whether the
 * entry animates, and answered it by flattening the result, stripping it and running the locator's
 * regex a second time -- doubling the steady-state cost of exactly the configuration chroma
 * creates. The chat hook did the same thing for a tag count that only {@code /skyprism profile}
 * reads, on every recoloured line, in every session, because profiling is on by default.</p>
 */
@DisplayName("ComponentRewriter's scan report")
final class ComponentRewriterScanMcTest {

    private static final LevelPalette PALETTE = LevelPalette.defaults();
    private static final LevelTagLocator LOCATOR = LevelTagLocator.standard();

    private static Component recolour(Component source, int[] out) {
        return ComponentRewriter.recolourLevels(source, PALETTE, LOCATOR, false, 0L, out);
    }

    @Test
    @DisplayName("reports the tag count and the highest level")
    void reportsCountAndMax() {
        int[] out = new int[2];
        Component source = Component.literal("[12] Steve beat [451] Alex and [7] Bob");

        Component result = recolour(source, out);
        assertEquals(3, out[0], "tag count");
        assertEquals(451, out[1], "highest level");
        assertEquals(source.getString(), result.getString());
    }

    @Test
    @DisplayName("zeroes the report when nothing matched, so no stale value survives")
    void zeroesOnMiss() {
        int[] out = {99, 99};
        Component tagged = Component.literal("[451] Steve");
        recolour(tagged, out);
        assertEquals(1, out[0]);
        assertEquals(451, out[1]);

        Component plain = Component.literal("no bracketed digits here");
        assertSame(plain, recolour(plain, out));
        assertArrayEquals(new int[] {0, 0}, out);

        // A component the pre-filter accepts but the locator rejects: the level is out of range.
        int[] second = {5, 5};
        Component outOfRange = Component.literal("[4200] Steve");
        assertSame(outOfRange, recolour(outOfRange, second));
        assertArrayEquals(new int[] {0, 0}, second);
    }

    @Test
    @DisplayName("a null or short scratch array is tolerated")
    void tolerantOfShortArrays() {
        Component source = Component.literal("[451] Steve");
        // The five-argument form passes null through, which is what every caller that does not
        // want the report uses.
        assertEquals(source.getString(),
                ComponentRewriter.recolourLevels(source, PALETTE, LOCATOR, false, 0L).getString());

        int[] one = new int[1];
        recolour(source, one);
        assertEquals(1, one[0]);

        recolour(source, new int[0]);
        recolour(source, null);
    }

    /**
     * The javadoc promises in bold that this method does not throw, and the chat hook -- unlike the
     * two render surfaces -- has no failure budget behind it, so anything escaping would repeat
     * once per received Hypixel line from inside the client's own message dispatch. The catch used
     * to name only {@code IllegalArgumentException}, which left an
     * {@code IndexOutOfBoundsException} out of TextClean's offset projection, a
     * {@code NullPointerException} out of {@code RunText.flatten}, and anything a foreign component
     * throws mid-walk, all escaping a method documented not to throw.
     */
    @Test
    @DisplayName("a component that throws mid-walk yields the source unchanged")
    void doesNotThrow() {
        Component exploding = MutableComponent.create(new ComponentContents() {
            @Override
            public <T> Optional<T> visit(FormattedText.StyledContentConsumer<T> consumer,
                                         Style style) {
                throw new IndexOutOfBoundsException("not an IllegalArgumentException");
            }

            @Override
            public <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer) {
                throw new IndexOutOfBoundsException("not an IllegalArgumentException");
            }

            @Override
            public MapCodec<? extends ComponentContents> codec() {
                throw new UnsupportedOperationException();
            }
        });

        int[] out = {7, 7};
        assertSame(exploding, recolour(exploding, out));
        assertArrayEquals(new int[] {0, 0}, out);
    }
}
