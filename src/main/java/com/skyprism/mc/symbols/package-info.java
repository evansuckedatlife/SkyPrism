/**
 * Which Minecraft item sprite stands in for which Diana drop.
 *
 * <p>This is the smallest possible adapter and it is deliberately the only thing in the
 * package. The slot machine's behaviour -- when a reel locks, which drop it locked onto,
 * whether the roll was a jackpot -- is decided in {@code com.skyprism.core.diana} on a bare
 * JVM. Turning "Griffin Feather" into a feather sprite is the one part of that story that
 * cannot be answered without Minecraft's item registry, so it lives here and nowhere else,
 * and {@code com.skyprism.mc.hud} asks it a single question:
 * {@link com.skyprism.mc.symbols.DropSymbols#iconFor}.
 *
 * <p>The table itself is data, not code: {@code assets/skyprism/drop_symbols.json}. Hypixel
 * adds and renames drops on its own schedule, and a wrong entry shows up as a chest rather
 * than as a crash, so the fix should not need a compiler.
 */
package com.skyprism.mc.symbols;
