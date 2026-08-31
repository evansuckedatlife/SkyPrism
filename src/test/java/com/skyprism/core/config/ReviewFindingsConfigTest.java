package com.skyprism.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.DianaGate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The two settings a review turned up as missing: the level feature's server scope, and the island
 * whitelist that three class javadocs described and nothing ever set.
 */
@DisplayName("review findings: the settings")
final class ReviewFindingsConfigTest {

    @Nested
    @DisplayName("levels.onlyOnSkyBlock")
    class OnlyOnSkyBlock {

        /**
         * The default is the fix. A bracketed number is a bracketed number, and off SkyBlock the
         * locator has no way to tell "[451]" from a teammate typing "we need [2] more"; the
         * server check is the only remaining defence and it did not exist.
         */
        @Test
        @DisplayName("defaults to on")
        void defaultsOn() {
            assertTrue(SkyPrismConfig.defaults().levels.onlyOnSkyBlock);
            assertTrue(SkyPrismConfig.defaults().sanitized().levels.onlyOnSkyBlock);
        }

        @Test
        @DisplayName("survives sanitising, copying and a round trip through equality")
        void survives() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.levels.onlyOnSkyBlock = false;

            assertFalse(config.sanitized().levels.onlyOnSkyBlock);
            assertFalse(config.copy().levels.onlyOnSkyBlock);
            assertEquals(config.copy(), config);

            SkyPrismConfig other = config.copy();
            other.levels.onlyOnSkyBlock = true;
            assertNotEquals(other, config);
            assertNotEquals(other.hashCode(), config.hashCode());
        }
    }

    @Nested
    @DisplayName("diana.allowedAreas")
    class AllowedAreas {

        /**
         * Empty means "any island", which is the polarity {@link DianaGate} documents: an
         * unconfigured gate has to work everywhere rather than silently work nowhere.
         */
        @Test
        @DisplayName("defaults to empty, which the gate reads as anywhere")
        void defaultsEmpty() {
            assertTrue(SkyPrismConfig.defaults().diana.allowedAreas.isEmpty());

            DianaGate gate = new DianaGate();
            gate.setAllowedAreas(SkyPrismConfig.defaults().diana.allowedAreas);
            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            gate.setMayorDiana(true);
            gate.setArea("Crimson Isle");
            assertTrue(gate.isOpen());
        }

        @Test
        @DisplayName("entries are cleaned and blanks dropped, like the jackpot list")
        void sanitised() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.diana.allowedAreas = new LinkedHashSet<>(List.of("  §7Hub  ", "   ", "Hub"));

            Set<String> out = config.sanitized().diana.allowedAreas;
            assertEquals(Set.of("Hub"), out);
        }

        @Test
        @DisplayName("a whitelist actually closes the gate on an island that is not on it")
        void restricts() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.diana.allowedAreas = new LinkedHashSet<>(List.of("Hub"));

            DianaGate gate = new DianaGate();
            gate.setAllowedAreas(config.sanitized().diana.allowedAreas);
            gate.setOnHypixel(true);
            gate.setInSkyBlock(true);
            gate.setMayorDiana(true);

            gate.setArea("§7 Hub");
            assertTrue(gate.isOpen());

            gate.setArea("Crimson Isle");
            assertFalse(gate.isOpen());
        }

        @Test
        @DisplayName("survives copying and takes part in equality")
        void survives() {
            SkyPrismConfig config = SkyPrismConfig.defaults();
            config.diana.allowedAreas = new LinkedHashSet<>(List.of("Hub"));

            SkyPrismConfig copy = config.copy();
            assertEquals(Set.of("Hub"), copy.diana.allowedAreas);
            assertEquals(copy, config);

            copy.diana.allowedAreas = new LinkedHashSet<>();
            assertNotEquals(copy, config);
        }
    }
}
