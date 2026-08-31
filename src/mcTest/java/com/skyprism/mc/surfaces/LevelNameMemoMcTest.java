package com.skyprism.mc.surfaces;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The TAB and nametag cache key, which is package-private and keyed on a real
 * {@link Component}, so it can only be tested from here.
 *
 * <p>{@link LevelNameMemo#keyMatches} is the whole per-frame cost of the level feature on a
 * busy lobby, and every one of its branches is a decision about correctness traded against
 * that cost. The two that need holding down are the throttle -- a structurally compared key
 * is taken on trust for a few calls, so a renamed player must still be noticed within the
 * configured window -- and the invalidations, since an entry that outlives a settings change
 * shows the old palette until the player leaves.
 *
 * <p>These cases were extracted from the ad-hoc {@code bench/MemoBench} main(). The timing
 * loops that were the rest of that file are deliberately <em>not</em> here: a wall-clock
 * measurement inside a JUnit run tells you about the machine it ran on and would either
 * assert nothing or fail on a loaded CI box. The performance claims in
 * {@code LevelNameMemo}'s javadoc came from that benchmark and should be re-measured with it
 * (or with JMH) when they are in doubt -- not turned into an assertion.</p>
 */
@DisplayName("LevelNameMemo cache key")
final class LevelNameMemoMcTest {

    private static final int GENERATION = 7;

    /** A four-node Hypixel-shaped name: a level tag, a two-colour rank, and the player. */
    private static MutableComponent hypixelName() {
        MutableComponent name = Component.empty();
        name.append(Component.literal("[451] ").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        name.append(Component.literal("[MVP").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        name.append(Component.literal("++").setStyle(Style.EMPTY.withColor(0xFFAA00)));
        name.append(Component.literal("] Notch").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        return name;
    }

    /** Stand-in for the surface's secondary key; TAB passes the player's GameType. */
    private static final Object VARIANT = ChatFormatting.RED;

    private static LevelNameMemo storedFor(Component source) {
        LevelNameMemo memo = new LevelNameMemo();
        memo.store(source, VARIANT, Component.literal("recoloured"), GENERATION, 0L, false);
        return memo;
    }

    @Nested
    @DisplayName("hits")
    final class Hits {

        /** The TAB path: the stored PlayerInfo component keeps its identity between packets. */
        @Test
        @DisplayName("the same instance matches without a structural compare")
        void identityHit() {
            Component source = hypixelName();
            assertTrue(storedFor(source).keyMatches(source, VARIANT, GENERATION, 0));
        }

        /** The nametag path: nothing keeps its identity, so equality has to carry it. */
        @Test
        @DisplayName("a freshly built but equal component matches")
        void structuralHit() {
            assertTrue(storedFor(hypixelName()).keyMatches(hypixelName(), VARIANT, GENERATION, 0));
        }

        @Test
        @DisplayName("a genuinely different name misses")
        void differentNameMisses() {
            assertFalse(storedFor(hypixelName())
                    .keyMatches(Component.literal("[9] Steve"), VARIANT, GENERATION, 0));
        }
    }

    @Nested
    @DisplayName("invalidation")
    final class Invalidation {

        @Test
        @DisplayName("a fresh memo has no answer to give")
        void unstoredMisses() {
            assertFalse(new LevelNameMemo().keyMatches(hypixelName(), VARIANT, GENERATION, 0));
        }

        @Test
        @DisplayName("a settings change bumps the generation and drops every entry")
        void generationBump() {
            Component source = hypixelName();
            assertFalse(storedFor(source).keyMatches(source, VARIANT, GENERATION + 1, 0));
        }

        /** TAB caches under the player's GameType, so leaving spectator must not hit. */
        @Test
        @DisplayName("a different variant key misses even on the identical component")
        void variantChange() {
            Component source = hypixelName();
            assertFalse(storedFor(source)
                    .keyMatches(source, ChatFormatting.BLUE, GENERATION, 0));
        }

        @Test
        @DisplayName("invalidate() forces a recompute and clears the stored answer")
        void explicitInvalidate() {
            Component source = hypixelName();
            LevelNameMemo memo = storedFor(source);
            memo.invalidate();

            assertFalse(memo.keyMatches(source, VARIANT, GENERATION, 0));
            assertNull(memo.value, "invalidate() must release the cached component");
        }
    }

    /**
     * The nametag surface cannot match by identity, so it pays {@code Component.equals} on
     * every frame unless it is throttled. The contract is precise, and the price of getting
     * it wrong is a renamed player who never updates: trust the key for {@code n - 1} calls,
     * compare on the nth.
     */
    @Nested
    @DisplayName("throttled revalidation")
    final class Throttle {

        @Test
        @DisplayName("a stale key is trusted for n-1 calls and caught on the nth")
        void staleKeyIsCaughtOnTheNthCall() {
            LevelNameMemo memo = storedFor(hypixelName());
            Component renamed = Component.literal("[452] Notch");
            int revalidateEvery = 4;

            for (int call = 1; call < revalidateEvery; call++) {
                int c = call;
                assertTrue(memo.keyMatches(renamed, VARIANT, GENERATION, revalidateEvery),
                        () -> "call " + c + " should still be taken on trust");
            }
            assertFalse(memo.keyMatches(renamed, VARIANT, GENERATION, revalidateEvery),
                    "the nth call must run the full compare and notice the rename");
        }

        @Test
        @DisplayName("the counter resets, so the next window is the same length")
        void windowRepeats() {
            LevelNameMemo memo = storedFor(hypixelName());
            Component renamed = Component.literal("[452] Notch");
            int revalidateEvery = 3;

            for (int window = 0; window < 3; window++) {
                for (int call = 1; call < revalidateEvery; call++) {
                    assertTrue(memo.keyMatches(renamed, VARIANT, GENERATION, revalidateEvery));
                }
                assertFalse(memo.keyMatches(renamed, VARIANT, GENERATION, revalidateEvery));
            }
        }

        /** TAB passes zero and gets an exact answer, because identity is free anyway. */
        @Test
        @DisplayName("zero and one both mean compare on every call")
        void unthrottledValues() {
            Component renamed = Component.literal("[452] Notch");
            for (int revalidateEvery : new int[] {0, 1}) {
                LevelNameMemo memo = storedFor(hypixelName());
                assertFalse(memo.keyMatches(renamed, VARIANT, GENERATION, revalidateEvery),
                        "revalidateEvery=" + revalidateEvery + " must not trust a stale key");
            }
        }

        /** Throttling must never suppress the two invalidations that are not about the key. */
        @Test
        @DisplayName("throttling does not protect a stale generation or variant")
        void throttleDoesNotOutrankInvalidation() {
            LevelNameMemo memo = storedFor(hypixelName());
            assertFalse(memo.keyMatches(hypixelName(), VARIANT, GENERATION + 1, 16));
            assertFalse(memo.keyMatches(hypixelName(), ChatFormatting.BLUE, GENERATION, 16));
        }
    }
}
