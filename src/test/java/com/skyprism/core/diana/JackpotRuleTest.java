package com.skyprism.core.diana;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link JackpotRule}, including the researched default list. */
class JackpotRuleTest {

    private static LootDrop drop(String name) {
        return new LootDrop(name, "5", 1, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mythological Dye",
            "Myth the Fish",
            "Minos Relic",
            "Braided Griffin Feather",
            "Daedalus Stick",
            "Crochet Tiger Plushie",
            "Shimmering Wool",
            "Manti-core",
            "Washed-up Souvenir",
            "Cretan Urn",
            "Hilt of Revelations",
            "Brain Food",
            "Antique Remedies",
            "Dwarf Turtle Shelmet",
            "Fateful Stinger",
            "Chimera",
            "Crown of Greed",
    })
    @DisplayName("every researched jackpot drop is celebrated by the defaults")
    void defaultsCelebrateTheRareDrops(String itemName) {
        assertTrue(JackpotRule.defaults().isJackpot(drop(itemName)), itemName);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Griffin Feather",
            "Coins",
            "Mythos Fragment",
            "Ancient Claw",
            "Enchanted Ancient Claw",
            "Enchanted Gold Ingot",
            "Enchanted Book",
    })
    @DisplayName("the routine Diana drops are not celebrated -- a flourish every burrow is no flourish")
    void defaultsIgnoreTheCommonDrops(String itemName) {
        assertFalse(JackpotRule.defaults().isJackpot(drop(itemName)), itemName);
    }

    @Test
    @DisplayName("matching ignores case and stray whitespace")
    void matchingIsCaseAndWhitespaceInsensitive() {
        JackpotRule rule = JackpotRule.defaults();
        assertTrue(rule.isJackpot(drop("crown of greed")));
        assertTrue(rule.isJackpot(drop("CROWN OF GREED")));
        assertTrue(rule.isJackpot(drop("Crown  of   Greed")));
        assertTrue(rule.isJackpot(drop("  Crown of Greed  ")));
    }

    @Test
    @DisplayName("a custom list replaces the defaults entirely, and is itself case-insensitive")
    void customListReplacesDefaults() {
        JackpotRule rule = new JackpotRule(Set.of("dwarf turtle SHELMET", "Griffin Feather"));
        assertTrue(rule.isJackpot(drop("Dwarf Turtle Shelmet")));
        assertTrue(rule.isJackpot(drop("Griffin Feather")), "the player is allowed to want this");
        assertFalse(rule.isJackpot(drop("Crown of Greed")), "not on their list any more");
    }

    @Test
    @DisplayName("an empty list means never flourish")
    void emptyListNeverFlourishes() {
        JackpotRule rule = new JackpotRule(Set.of());
        assertFalse(rule.isJackpot(drop("Mythological Dye")));
        assertEquals(Optional.empty(), rule.bestJackpot(List.of(drop("Mythological Dye"))));
    }

    @Test
    @DisplayName("null drops and a null name set are handled without throwing the wrong thing")
    void nullHandling() {
        assertFalse(JackpotRule.defaults().isJackpot(null));
        assertEquals(Optional.empty(), JackpotRule.defaults().bestJackpot(null));
        assertEquals(Optional.empty(), JackpotRule.defaults().bestJackpot(List.of()));
        assertThrows(NullPointerException.class, () -> new JackpotRule(null));
    }

    @Test
    @DisplayName("bestJackpot picks the rarest drop, not the first one seen")
    void bestJackpotPicksTheRarest() {
        JackpotRule rule = JackpotRule.defaults();
        List<LootDrop> haul = List.of(
                drop("Crown of Greed"),
                drop("Shimmering Wool"),
                drop("Mythological Dye"));
        assertEquals("Mythological Dye", rule.bestJackpot(haul).orElseThrow().itemName());
    }

    @Test
    @DisplayName("bestJackpot ignores drops that are not jackpots at all")
    void bestJackpotSkipsNonJackpots() {
        JackpotRule rule = JackpotRule.defaults();
        List<LootDrop> haul = List.of(
                new LootDrop("Coins", "6", 25000, false),
                drop("Griffin Feather"),
                drop("Minos Relic"));
        assertEquals("Minos Relic", rule.bestJackpot(haul).orElseThrow().itemName());
    }

    @Test
    @DisplayName("bestJackpot returns empty when nothing in the haul qualifies")
    void bestJackpotEmptyWhenNothingQualifies() {
        JackpotRule rule = JackpotRule.defaults();
        List<LootDrop> haul = List.of(drop("Griffin Feather"), new LootDrop("Coins", "6", 100, false));
        assertEquals(Optional.empty(), rule.bestJackpot(haul));
    }

    @Test
    @DisplayName("among unranked names the server's own rare banner breaks the tie")
    void unrankedNamesFallBackToTheServerFlag() {
        JackpotRule rule = new JackpotRule(Set.of("Homemade Trophy", "Borrowed Trophy"));
        List<LootDrop> haul = List.of(
                new LootDrop("Homemade Trophy", "7", 1, false),
                new LootDrop("Borrowed Trophy", "7", 1, true));
        assertEquals("Borrowed Trophy", rule.bestJackpot(haul).orElseThrow().itemName());
    }

    @Test
    @DisplayName("both spellings of the Inquisitor's book are recognised")
    void chimeraSpellings() {
        JackpotRule rule = JackpotRule.defaults();
        assertTrue(rule.isJackpot(drop("Chimera")));
        assertTrue(rule.isJackpot(drop("Chimera I")));
    }

    @Test
    @DisplayName("itemNames exposes the active list in normalised form")
    void itemNamesIsExposed() {
        JackpotRule rule = new JackpotRule(Set.of("Crown of Greed", "  Minos Relic  "));
        assertEquals(Set.of("crown of greed", "minos relic"), rule.itemNames());
    }

    // ------------------------------------------------------- hostile input

    @Test
    @DisplayName("the exposed name set cannot be mutated by a caller")
    void itemNamesIsUnmodifiable() {
        Set<String> names = JackpotRule.defaults().itemNames();
        assertThrows(UnsupportedOperationException.class, () -> names.add("Griffin Feather"));
        assertThrows(UnsupportedOperationException.class, () -> names.remove("crown of greed"));
    }

    /**
     * A config screen will hand this constructor the same mutable set it goes on editing.
     * The rule has to have taken a copy, or a half-finished edit silently changes what the
     * HUD celebrates mid-run.
     */
    @Test
    @DisplayName("the configured set is copied, not aliased")
    void configuredSetIsCopied() {
        Set<String> editable = new HashSet<>(Set.of("Crown of Greed"));
        JackpotRule rule = new JackpotRule(editable);

        editable.clear();
        editable.add("Griffin Feather");

        assertTrue(rule.isJackpot(drop("Crown of Greed")), "the rule kept its own copy");
        assertFalse(rule.isJackpot(drop("Griffin Feather")), "later edits must not leak in");
    }

    @Test
    @DisplayName("null and blank entries in the configured set are dropped, not stored")
    void nullAndBlankEntriesAreDropped() {
        Set<String> messy = new HashSet<>(Arrays.asList("Crown of Greed", null, "   ", "", "\u00A7d"));
        JackpotRule rule = new JackpotRule(messy);
        assertEquals(Set.of("crown of greed"), rule.itemNames());
    }

    @Test
    @DisplayName("a null element in the haul is skipped rather than thrown on")
    void nullElementInTheHaulIsSkipped() {
        List<LootDrop> haul = Arrays.asList(null, drop("Minos Relic"), null);
        assertEquals("Minos Relic",
                JackpotRule.defaults().bestJackpot(haul).orElseThrow().itemName());
    }

    @Test
    @DisplayName("an item name that still carries its colour codes still matches")
    void stillFormattedNamesMatch() {
        assertTrue(JackpotRule.defaults()
                .isJackpot(new LootDrop("\u00A7dMythological \u00A7dDye", "d", 1, true)),
                "normalisation runs through TextClean, so codes cannot hide a jackpot");
    }

    /**
     * {@code String.toLowerCase()} without a locale turns "I" into a dotless i under a
     * Turkish default locale, which would stop "Chimera I" matching itself. Both the
     * constructor and the rank table have to pin {@link java.util.Locale#ROOT}.
     */
    @Test
    @DisplayName("matching does not depend on the default locale")
    void matchingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            JackpotRule rule = JackpotRule.defaults();
            assertTrue(rule.isJackpot(drop("CHIMERA I")));
            assertTrue(rule.isJackpot(drop("Hilt of Revelations")));
            assertEquals("Mythological Dye",
                    rule.bestJackpot(List.of(drop("Crown of Greed"), drop("Mythological Dye")))
                            .orElseThrow().itemName());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("a haul of duplicates of the same jackpot picks one and does not throw")
    void duplicateDropsAreHandled() {
        JackpotRule rule = JackpotRule.defaults();
        List<LootDrop> haul = List.of(drop("Minos Relic"), drop("Minos Relic"), drop("Minos Relic"));
        assertEquals(Optional.of(drop("Minos Relic")), rule.bestJackpot(haul));
    }

    @Test
    @DisplayName("a parsed drop line flows straight into the rule")
    void endToEndFromAChatLine() {
        String line = "\u00A76\u00A7lRARE DROP! \u00A7r\u00A75Crown of Greed";
        List<LootDrop> drops = new LootParser().parse(line);
        assertEquals(Optional.of(new LootDrop("Crown of Greed", "5", 1, true)),
                JackpotRule.defaults().bestJackpot(drops));
    }
}
