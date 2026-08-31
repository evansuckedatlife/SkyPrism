package com.skyprism.mc.surfaces;

import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.mc.text.ComponentRewriter;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * A benchmark, not a test. Nothing here is annotated, so the {@code mcTest} task will not
 * run it; it lives in this source set so that it compiles with everything else and cannot
 * quietly rot against an API change.
 *
 * <p>It exists because the numbers quoted in {@link LevelNameMemo}'s javadoc -- identity
 * compare under a nanosecond, {@code Component.equals} around 140 ns on a four-node Hypixel
 * name, a full recolour a couple of microseconds -- are the entire justification for the
 * throttle, and a claim like that should be re-measurable rather than folklore. The
 * behavioural half of what this file used to assert now lives in
 * {@link LevelNameMemoMcTest}; only the timing stayed here, because a wall-clock assertion
 * inside a JUnit run measures the machine it ran on, not the code.</p>
 *
 * <p>Run it against the same classpath the tests use:</p>
 * <pre>
 *   gradlew :26.2:printMcTestClasspath
 *   java -cp "&lt;that classpath&gt;;versions/26.2/build/classes/java/mcTest" \
 *        com.skyprism.mc.surfaces.MemoBench
 * </pre>
 *
 * <p>It is a plain loop-and-clock harness with warm-up rounds, not JMH: good enough to tell
 * one nanosecond from a hundred and forty, which is the only distinction the design turns
 * on. Do not read a ten-percent difference off it.</p>
 */
public final class MemoBench {

    private MemoBench() {
    }

    /** A four-node Hypixel-shaped name, the shape the throttle was measured against. */
    private static MutableComponent hypixelName() {
        MutableComponent name = Component.empty();
        name.append(Component.literal("[451] ").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        name.append(Component.literal("[MVP").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        name.append(Component.literal("++").setStyle(Style.EMPTY.withColor(0xFFAA00)));
        name.append(Component.literal("] Notch").setStyle(Style.EMPTY.withColor(0x55FFFF)));
        return name;
    }

    public static void main(String[] args) {
        Component source = hypixelName();
        LevelPalette palette = LevelPalette.defaults();
        LevelTagLocator locator = LevelTagLocator.standard();
        Object variant = ChatFormatting.RED; // stand-in for the surface's identity key

        LevelNameMemo memo = new LevelNameMemo();
        memo.store(source, variant,
                ComponentRewriter.recolourLevels(source, palette, locator, true, 0L),
                7, 0L, false);

        int sink = 0;

        // The TAB path: the key matches by identity, so this is one reference compare.
        sink += time("HIT identity", 200_000_000L,
                i -> memo.keyMatches(source, variant, 7, 0) ? 1 : 0);

        // The nametag path: a fresh, equal component every call, so Component.equals runs.
        Component fresh = hypixelName();
        sink += time("HIT structural, compare every call", 20_000_000L,
                i -> memo.keyMatches(fresh, variant, 7, 0) ? 1 : 0);
        sink += time("HIT structural, throttled 1-in-16", 20_000_000L,
                i -> memo.keyMatches(fresh, variant, 7, 16) ? 1 : 0);

        // The miss: what the throttle is buying time against.
        sink += time("MISS full recolour", 1_000_000L,
                i -> ComponentRewriter.recolourLevels(source, palette, locator, true, i).hashCode());

        // The off-Hypixel case: a name with no tag must cost only the pre-filter.
        Component plain = Component.literal("Notch");
        sink += time("MISS no tag at all", 1_000_000L,
                i -> ComponentRewriter.recolourLevels(plain, palette, locator, true, i) == plain
                        ? 1 : 0);

        System.out.println("sink=" + sink);
    }

    /** One measurement: three warm-up-and-report rounds, so JIT settling is visible. */
    private static int time(String label, long iterations, Body body) {
        int sink = 0;
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (long i = 0; i < iterations; i++) {
                sink += body.run(i);
            }
            long elapsed = System.nanoTime() - start;
            System.out.printf("%-36s %8.3f ns/op%n", label, elapsed / (double) iterations);
        }
        return sink;
    }

    @FunctionalInterface
    private interface Body {
        int run(long i);
    }
}
