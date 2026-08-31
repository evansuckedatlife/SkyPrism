package com.skyprism.core.loot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every SkyBlock name the mod ships names something that exists.
 *
 * <h2>Why this test is the point of the change it arrived with</h2>
 *
 * <p>A player reported that the slot machine's reels were showing loot that does not belong. They
 * were right, and the audit that followed found the reason: the per-source drop tables had been
 * assembled by reading wikis and inferring, and about a hundred of the 476 names in them were
 * wrong. Some were misspellings ("Enchanted Ancient Claws", "Armor of Yog Helmet" -- an internal id
 * rather than a display name). Some were the wrong kind of thing ("Chimera" is an enchantment,
 * "Lord Jawbus" is a sea creature, "El Dorado" is a rabbit). Some were simply invented: there is no
 * Ashfang armour set, no Soul Esperance, no Reindrake Fragment, no golden goblin egg.</p>
 *
 * <p>Individually correcting those is worth little on its own, because nothing stopped the next
 * hundred. A fake name compiles. It ships. It survives review, because a reviewer cannot hold 7,600
 * SkyBlock item names in their head -- "Chimera" and "Enchanted Ancient Claws" each survived
 * several passes. The only thing that catches it is a machine with the real list, so that is what
 * this is: every name in the jackpot lists, in {@code drop_symbols.json} and in
 * {@code FillerStrip.GENERIC} has to be in a snapshot of the NotEnoughUpdates item database, or on
 * the allowlist below with a written reason.</p>
 *
 * <h2>The allowlist is deliberately small and deliberately awkward to grow</h2>
 *
 * <p>Every entry is a name that is real in the game and absent from an item database, which is a
 * narrow category: currencies, container tiers, a location, and a set of dig-board pieces. Each
 * carries the reason it is not an item. Adding to it should feel like an argument, because the
 * failure mode it guards is exactly "the name looked fine to me".</p>
 *
 * @see SkyBlockNames for where the snapshot comes from and how to regenerate it
 */
@DisplayName("SkyBlock names: nothing the reels can show is invented")
class SkyBlockNameSnapshotTest {

    /**
     * Names that are real in SkyBlock but are not items, and so cannot be in an item database.
     *
     * <p>Keyed by the normalised name, valued by why it is here. The value is not decoration: it is
     * printed in the failure message when a row goes stale, and it is the thing a reviewer reads
     * instead of taking the entry on trust.</p>
     */
    /** One reason, shared by the six Catacombs chest tiers; see {@link #ALLOWED}. */
    private static final String CATACOMBS_CHEST =
            "A Catacombs reward-chest TIER -- Wood through Bedrock, priced by floor and score, from "
                    + "free to two million coins. The player reads these words in the reward screen "
                    + "and in the Croesus GUI, so a sprite for each is worth having, but a container "
                    + "is not its contents: no source lists one as loot any more. CROESUS_CHEST used "
                    + "to have a jackpot list of exactly two of them.";

    /** One reason, shared by the seven Carnival fruits that need it; see {@link #ALLOWED}. */
    private static final String CARNIVAL_FRUIT =
            "One of the eight fruits the Carnival's Fruit Digging board reveals. The wiki lists all "
                    + "eight and Hypixel names them in chat, but they are board pieces rather than "
                    + "stacks, so none of them except Apple is in the item database. Evidence is the "
                    + "wiki, not NEU.";

    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry("coins",
                    "Currency. Hypixel writes it as loot verbatim -- \"You dug out 2,500 coins\" -- "
                            + "and it is a legitimate jackpot outcome on Diana, which is why the "
                            + "gold-nugget sprite exists for it."),
            Map.entry("coin",
                    "The singular, which the same family of lines also produces; it shares the row "
                            + "and the sprite with the plural above and is here for the same reason."),
            Map.entry("wood chest", CATACOMBS_CHEST),
            Map.entry("gold chest", CATACOMBS_CHEST),
            Map.entry("diamond chest", CATACOMBS_CHEST),
            Map.entry("emerald chest", CATACOMBS_CHEST),
            Map.entry("obsidian chest", CATACOMBS_CHEST),
            Map.entry("bedrock chest", CATACOMBS_CHEST),
            Map.entry("glacite mineshaft",
                    "A LOCATION, and the honest payout of GLACITE_MINESHAFT_PORTAL: the thing that "
                            + "happened is that you found one. Kept as a name on purpose so that a "
                            + "later reader does not 'correct' it into an item that was never "
                            + "there."),
            Map.entry("dragonfruit", CARNIVAL_FRUIT),
            Map.entry("mango", CARNIVAL_FRUIT),
            Map.entry("coconut", CARNIVAL_FRUIT),
            Map.entry("cherry", CARNIVAL_FRUIT),
            Map.entry("pomegranate", CARNIVAL_FRUIT),
            Map.entry("durian", CARNIVAL_FRUIT),
            Map.entry("watermelon", CARNIVAL_FRUIT));

    private static final Path ROOT = SkyBlockNames.repoRoot();

    @Nested
    @DisplayName("the check itself")
    class Coverage {

        @Test
        @DisplayName("every name in a jackpot list, a sprite row or the filler top-up is real")
        void nothingIsInvented() {
            Map<String, SkyBlockNames.Known> known = SkyBlockNames.snapshot(ROOT);
            List<String> invented = new ArrayList<>();

            SkyBlockNames.namesInUse(ROOT).forEach((key, use) -> {
                if (SkyBlockNames.resolve(known, key) != null || ALLOWED.containsKey(key)) {
                    return;
                }
                invented.add(use.toString());
            });

            assertTrue(invented.isEmpty(), () -> invented.size()
                    + " name(s) do not exist in SkyBlock. Each is either a misspelling, a mob or "
                    + "enchantment mistaken for an item, or invented outright -- and each would "
                    + "scroll on a reel as loot a player can never get:\n  "
                    + String.join("\n  ", invented)
                    + "\n\nFix the name, or -- only if it is genuinely real and genuinely not an "
                    + "item -- add it to SkyBlockNameSnapshotTest.ALLOWED with the reason. If it IS "
                    + "a real item that the snapshot simply predates, regenerate the snapshot: see "
                    + "SkyBlockNames#main.");
        }

        @Test
        @DisplayName("the check is actually reaching all three name sources")
        void theSourcesAreAllRead() {
            Map<String, SkyBlockNames.Use> used = SkyBlockNames.namesInUse(ROOT);

            List<String> sites = used.values().stream()
                    .flatMap(use -> use.sites().stream())
                    .distinct()
                    .sorted()
                    .toList();
            assertTrue(sites.contains("drop_symbols.json"), () -> "sprite rows not read: " + sites);
            assertTrue(sites.contains("FillerStrip.GENERIC"),
                    () -> "the generic filler top-up not read: " + sites);
            assertTrue(sites.stream().anyMatch(site -> site.equals("DIANA_MYTHOLOGICAL")),
                    () -> "jackpot lists not read: " + sites);

            // A floor rather than an exact count, so adding a drop is not a test edit; low enough
            // that it can only fail if a whole source stopped being read.
            assertTrue(used.size() > 400,
                    () -> "only " + used.size() + " names were collected, which means one of the "
                            + "three sources stopped being read rather than that the mod shrank");
        }
    }

    @Nested
    @DisplayName("the snapshot and the allowlist stay honest")
    class Hygiene {

        @Test
        @DisplayName("every snapshot row is still used, so the file cannot silently rot")
        void theSnapshotCarriesNoDeadRows() {
            Map<String, SkyBlockNames.Known> known = SkyBlockNames.snapshot(ROOT);
            Map<String, SkyBlockNames.Use> used = SkyBlockNames.namesInUse(ROOT);

            TreeSet<String> reached = new TreeSet<>();
            used.keySet().forEach(inUse -> {
                SkyBlockNames.Known hit = SkyBlockNames.resolve(known, inUse);
                if (hit != null) {
                    reached.add(hit.name());
                }
            });
            List<String> orphans = known.values().stream()
                    .filter(row -> !reached.contains(row.name()))
                    .map(row -> row.name() + " [" + row.internalName() + "]")
                    .sorted()
                    .toList();

            assertTrue(orphans.isEmpty(), () -> "the snapshot holds " + orphans.size()
                    + " row(s) no name in the mod reaches. It is meant to be the names actually "
                    + "needed, not a dump of the item database, so a row nobody uses is one more "
                    + "line between a reader and the point: " + orphans);
        }

        @Test
        @DisplayName("no allowlist entry has quietly become a real item")
        void theAllowlistHoldsOnlyRealExceptions() {
            Map<String, SkyBlockNames.Known> known = SkyBlockNames.snapshot(ROOT);
            Map<String, String> redundant = new TreeMap<>();
            ALLOWED.forEach((key, why) -> {
                SkyBlockNames.Known hit = SkyBlockNames.resolve(known, key);
                if (hit != null) {
                    redundant.put(key, hit.internalName());
                }
            });
            assertTrue(redundant.isEmpty(), () -> "these names are on the allowlist as 'not an "
                    + "item', but the snapshot now says otherwise. Drop the allowlist entry: "
                    + redundant);
        }

        @Test
        @DisplayName("no allowlist entry has been left behind by the name that needed it")
        void theAllowlistHasNoUnusedEntries() {
            Map<String, SkyBlockNames.Use> used = SkyBlockNames.namesInUse(ROOT);
            TreeSet<String> unused = new TreeSet<>(ALLOWED.keySet());
            unused.removeAll(used.keySet());
            assertTrue(unused.isEmpty(), () -> "the allowlist excuses names the mod no longer "
                    + "ships. Every entry is a standing exception to the rule this test exists to "
                    + "enforce, so an unused one is a hole left open for nothing: " + unused);
        }

        @Test
        @DisplayName("every allowlist entry says why, in a sentence rather than a word")
        void everyExceptionIsArgued() {
            List<String> thin = ALLOWED.entrySet().stream()
                    .filter(entry -> entry.getValue().length() < 60)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .toList();
            assertTrue(thin.isEmpty(), () -> "an allowlist entry is a claim that a name is real "
                    + "despite being absent from the item database, and the reason is the only "
                    + "evidence a reviewer gets: " + thin);
        }
    }

    @Nested
    @DisplayName("the two findings that started this")
    class Regressions {

        @Test
        @DisplayName("the names the audit proved fake cannot come back")
        void theKnownFakesStayGone() {
            Map<String, SkyBlockNames.Known> known = SkyBlockNames.snapshot(ROOT);
            List<String> resurrected = new ArrayList<>();
            for (String fake : List.of(
                    "Enchanted Ancient Claws", "Ashfang Helmet", "Ashfang Gloves", "Ashfang Cloak",
                    "Soul Esperance", "Mageblood Necklace", "Ghost Cutlass", "Skin of the Wolf",
                    "Trapper's Ring", "Vanguard Helmet", "Reindrake Fragment",
                    "Sea Emperor Fragment", "Plhlegblast Pearl", "Golden Goblin Egg",
                    // Not in this list, though the audit called it fake: Spirit Rune. SPIRIT_RUNE
                    // is a real Sven drop, and the check below is what said so.
                    "Diamond Goblin Egg", "Rib Fossil", "Etherwarp Transmission", "Blaze Catalyst",
                    "Ender Catalyst", "Vampire Catalyst", "Manticore Core",
                    "Manticore Shard", "Gaia Construct Shard", "Minos Champion Shard",
                    "Minos Inquisitor Shard", "Siamese Lynxes Shard", "Stranded Nymph Shard",
                    "Side Dish", "Golden Rabbit", "El Dorado", "Solomon", "Fish the Rabbit")) {
                String key = SkyBlockNames.key(fake);
                // "Enchanted Ancient Claws" is the one that has to be checked without the plural
                // retry: its singular IS real, which is exactly why the plural read as fine.
                boolean plural = fake.equals("Enchanted Ancient Claws");
                boolean live = plural ? known.containsKey(key)
                        : SkyBlockNames.resolve(known, key) != null;
                if (live || ALLOWED.containsKey(key)) {
                    resurrected.add(fake);
                }
            }
            assertTrue(resurrected.isEmpty(), () -> "names the audit proved do not exist are being "
                    + "treated as real again: " + resurrected);
        }

        @Test
        @DisplayName("Chimera is still celebrated, and still an Enchanted Book")
        void chimeraIsHandledRatherThanDeleted() {
            Map<String, SkyBlockNames.Known> known = SkyBlockNames.snapshot(ROOT);
            SkyBlockNames.Known chimera = SkyBlockNames.resolve(known, SkyBlockNames.key("Chimera I"));

            assertFalse(chimera == null,
                    "Chimera I is the Minos Inquisitor's rarest drop and the machine has to keep "
                            + "celebrating it; deleting the name was never the fix for it not being "
                            + "an ordinary item");
            assertEquals("ENCHANT", chimera.kind(),
                    "Chimera is an ultimate enchantment: what drops is an Enchanted Book, which is "
                            + "what the sprite has to draw");
            assertTrue(chimera.internalName().startsWith("ULTIMATE_CHIMERA"),
                    () -> "expected the NEU enchantment id, got " + chimera.internalName());

            assertTrue(LootSourceRegistry.info(LootSource.DIANA_MYTHOLOGICAL).jackpotItems()
                            .contains("Chimera I"),
                    "Diana's jackpot list must still name it, because Hypixel always prints a tier");
            assertFalse(LootSourceRegistry.info(LootSource.DIANA_MYTHOLOGICAL).jackpotItems()
                            .contains("Chimera"),
                    "the bare spelling was a second reel cell for one drop; the sprite table still "
                            + "answers to it, the jackpot list should not repeat it");
        }
    }
}
