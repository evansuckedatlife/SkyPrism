package com.skyprism.mc.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.skyprism.core.diana.MythologicalCreature;

/**
 * The Diana adapters' package-private seams, which the bare-JVM suite cannot reach.
 *
 * <p>Both classes under test here live in {@code com.skyprism.mc.diana} and are deliberately
 * package-private, so the test shares their package. Neither needs a booted game: the mayor rule is
 * a string predicate and the stats file is ordinary I/O.</p>
 */
@DisplayName("review findings: the Diana adapters")
final class DianaAdapterMcTest {

    @Nested
    @DisplayName("reading the mayor out of a TAB row")
    class MayorRow {

        @Test
        @DisplayName("the two words must be adjacent, not merely in order")
        void adjacency() {
            assertTrue(HypixelContext.namesDianaAsMayor("Mayor Diana"));
            assertTrue(HypixelContext.namesDianaAsMayor("Mayor: Diana"));
            assertTrue(HypixelContext.namesDianaAsMayor("Mayor:Diana"));
            assertTrue(HypixelContext.namesDianaAsMayor("mayor diana"));

            // The row that used to open the gate under somebody else's term. A minister grants a
            // perk, not the Mythological Ritual, so this arms the whole feature on an island where
            // no Griffin burrow can spawn -- and LootParser is not Diana-specific, so any rare drop
            // earned there lands in the tally as Diana loot.
            assertFalse(HypixelContext.namesDianaAsMayor("Mayor Foraging Fortune | Minister Diana"));
            assertFalse(HypixelContext.namesDianaAsMayor("Minister Diana"));

            // A real player row. The mayor loop walks all eighty of them.
            assertFalse(HypixelContext.namesDianaAsMayor("MayorDiana"));
            assertFalse(HypixelContext.namesDianaAsMayor("[MVP+] MayorDianaFan"));

            assertFalse(HypixelContext.namesDianaAsMayor(null));
            assertFalse(HypixelContext.namesDianaAsMayor(""));
            assertFalse(HypixelContext.namesDianaAsMayor("Mayor Cole"));
        }
    }

    @Nested
    @DisplayName("the stats file's first write")
    class Autosave {

        /**
         * The autosave deadline used to start at zero, which every clock reading is already past,
         * so the first write fired on the tick a counter first changed -- and that tick is, by
         * construction, the tick a kill is registered and the slot machine begins its spin. A
         * synchronous createDirectories + writeString + atomic rename on the opening frame of the
         * animation is the worst-placed hitch in the mod.
         */
        @Test
        @DisplayName("is deferred a full interval past the change that dirtied the tally")
        void deferred(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("nested").resolve("diana_stats.json");
            DianaStats stats = DianaStats.load(file);

            stats.recordKill(MythologicalCreature.MINOS_CHAMPION);
            assertTrue(stats.dirty());

            // The tick the kill happened on. Nothing may reach the disk here.
            stats.maybeSave(1_000L);
            assertFalse(Files.exists(file), "the first autosave landed on the kill tick");
            assertTrue(stats.dirty());

            // Still inside the interval.
            stats.maybeSave(30_000L);
            assertFalse(Files.exists(file));

            // Past it.
            stats.maybeSave(70_000L);
            assertTrue(Files.exists(file));
            assertFalse(stats.dirty());
        }

        /** The forced save is unaffected: disconnect and shutdown must never wait. */
        @Test
        @DisplayName("an explicit save still writes immediately")
        void forced(@TempDir Path dir) {
            Path file = dir.resolve("diana_stats.json");
            DianaStats stats = DianaStats.load(file);
            stats.recordKill(MythologicalCreature.MINOS_INQUISITOR);
            stats.save();
            assertTrue(Files.exists(file));
            assertFalse(stats.dirty());
        }

        /** After a write the next change schedules its own interval rather than inheriting one. */
        @Test
        @DisplayName("the deferral re-arms after each write")
        void reArms(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("diana_stats.json");
            DianaStats stats = DianaStats.load(file);

            stats.recordKill(MythologicalCreature.MINOTAUR);
            stats.maybeSave(0L);
            stats.maybeSave(60_000L);
            assertTrue(Files.exists(file));
            long firstWrite = Files.size(file);

            stats.recordKill(MythologicalCreature.MINOTAUR);
            stats.maybeSave(200_000L);
            assertEquals(firstWrite, Files.size(file), "a second change wrote on its own tick");
            assertTrue(stats.dirty());

            stats.maybeSave(261_000L);
            assertFalse(stats.dirty());
        }
    }
}
