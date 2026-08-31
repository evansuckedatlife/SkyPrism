package com.skyprism.mc.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyprism.core.diana.JackpotRule;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.mc.command.SimulatedLoot;
import com.skyprism.mc.selftest.SelfTest;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The reels draw item sprites, so the mapping has to be right on the real registry.
 *
 * <p>The failure this suite exists to prevent is specific and ugly: an item id that is close
 * but wrong -- {@code minecraft:turtle_shell} for what vanilla calls
 * {@code minecraft:turtle_helmet}, a colour variant that moved between versions -- renders as
 * a black-and-magenta missing-texture cube in the middle of the widget, and nothing about
 * that is visible until someone kills a Gaia Construct on Hypixel. So every id in the bundled
 * resource is resolved against {@link BuiltInRegistries#ITEM} here, on the same Minecraft the
 * jar is being built for, rather than trusted from a table.
 *
 * <p>The second half is coverage: every drop the mod can name -- Diana's jackpot list, the
 * simulator's tables, the coin symbol, and the jackpot list of all sixty-four registered
 * sources -- must have a row, because those are precisely the drops a player will see on a reel.
 *
 * <p>The third half is the one the captured frames forced. A reel that cannot place a name draws
 * the fallback chest, and for a while almost everything outside Diana did: a slayer roll showed
 * Judgement Core and Null Atom as two identical chests, and a fishing roll showed three. That is
 * worse than an ugly sprite. A slot machine is three pictures that differ and then match, so three
 * identical reels read as the machine having failed, and they empty the three-of-a-kind flourish of
 * meaning because every reel already matches. {@link #oneSourceNeverShowsTheSameSpriteTwice} and
 * {@link #noSimulatedPayoutRepeatsASprite} are what keep that from coming back: within one source's
 * payout, no two rows may share an item id and glint. Two names in the <em>same</em> row are fine
 * and are recognised by identity -- one row builds exactly one shared {@link ItemStack} -- so
 * "Chimera" and "Chimera I" do not count as a clash.
 */
@DisplayName("DropSymbols maps SkyBlock loot to real items on this Minecraft version")
final class DropSymbolsMcTest {

    @BeforeAll
    static void bootstrapRegistries() {
        ItemRegistryBootstrap.ensure();
    }

    // ======================================================================
    //  Every id in the resource is real
    // ======================================================================

    @Test
    @DisplayName("the bundled resource is present and parses")
    void resourceLoads() {
        assertNotNull(resource(), "assets/skyprism/drop_symbols.json is not on the classpath");
    }

    @Test
    @DisplayName("every item id in the resource exists in this Minecraft's item registry")
    void everyIdResolves() {
        List<String> bad = new ArrayList<>();
        for (String id : allIds()) {
            Identifier key = Identifier.tryParse(id);
            if (key == null || BuiltInRegistries.ITEM.getOptional(key).isEmpty()) {
                bad.add(id);
            }
        }
        assertTrue(bad.isEmpty(), () -> "item ids that do not exist on this version: " + bad);
    }

    @Test
    @DisplayName("the resource declares a fallback and it resolves")
    void fallbackIdResolves() {
        JsonObject root = root();
        JsonElement fallback = root.get("fallback");
        assertNotNull(fallback, "the resource has no \"fallback\" id");
        Identifier key = Identifier.tryParse(fallback.getAsString());
        assertNotNull(key, () -> "fallback is not a valid id: " + fallback);
        assertTrue(BuiltInRegistries.ITEM.getOptional(key).isPresent(),
                () -> "fallback item does not exist on this version: " + key);
    }

    @Test
    @DisplayName("no name is claimed by two different rows")
    void noDuplicateNames() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (JsonElement element : root().getAsJsonArray("entries")) {
            for (JsonElement name : element.getAsJsonObject().getAsJsonArray("names")) {
                if (!seen.add(DropSymbols.normalise(name.getAsString()))) {
                    duplicates.add(name.getAsString());
                }
            }
        }
        assertTrue(duplicates.isEmpty(),
                () -> "these names appear in more than one row, so which icon wins is an "
                        + "accident of file order: " + duplicates);
    }

    @Test
    @DisplayName("every row explains its choice")
    void everyRowIsArgued() {
        List<String> unexplained = new ArrayList<>();
        for (JsonElement element : root().getAsJsonArray("entries")) {
            JsonObject row = element.getAsJsonObject();
            JsonElement why = row.get("why");
            if (why == null || why.getAsString().isBlank()) {
                unexplained.add(row.getAsJsonArray("names").get(0).getAsString());
            }
        }
        assertTrue(unexplained.isEmpty(),
                () -> "rows with no \"why\": " + unexplained + ". A substitute sprite that "
                        + "nobody justified is a substitute nobody can review.");
    }

    // ======================================================================
    //  The public API
    // ======================================================================

    @Test
    @DisplayName("a known drop gets its mapped item, not the fallback")
    void knownDropsGetTheirItem() {
        assertEquals("minecraft:feather", idOf(DropSymbols.iconForName("Griffin Feather")));
        assertEquals("minecraft:flint", idOf(DropSymbols.iconForName("Ancient Claw")));
        assertEquals("minecraft:enchanted_book", idOf(DropSymbols.iconForName("Chimera I")));
        assertEquals("minecraft:golden_helmet", idOf(DropSymbols.iconForName("Crown of Greed")));
        assertEquals("minecraft:turtle_helmet",
                idOf(DropSymbols.iconForName("Dwarf Turtle Shelmet")));
        assertEquals("minecraft:gold_nugget",
                idOf(DropSymbols.iconForName(LootParser.COINS_ITEM_NAME)));
    }

    @Test
    @DisplayName("an unknown name falls back to a real, drawable item")
    void unknownFallsBack() {
        ItemStack icon = DropSymbols.iconForName("Enchanted Wombat of Perpetual Confusion");
        assertNotNull(icon);
        assertFalse(icon.isEmpty(), "the fallback must still draw something");
        assertFalse(DropSymbols.hasMapping("Enchanted Wombat of Perpetual Confusion"));
        assertSame(icon, DropSymbols.iconForName("Some Other Thing Nobody Mapped"),
                "every unmapped name should reach the same shared fallback stack");
    }

    @Test
    @DisplayName("null and blank never blow up and never return null")
    void nullSafe() {
        assertNotNull(DropSymbols.iconFor(null));
        assertFalse(DropSymbols.iconFor(null).isEmpty());
        assertNotNull(DropSymbols.iconForName(null));
        assertNotNull(DropSymbols.iconForName(""));
        assertNotNull(DropSymbols.iconForName("   "));
        assertFalse(DropSymbols.hasMapping(null));
        assertFalse(DropSymbols.hasMapping(""));
    }

    @Test
    @DisplayName("iconFor(LootDrop) agrees with iconForName")
    void iconForDropAgrees() {
        LootDrop drop = new LootDrop("Griffin Feather", "9", 3, false);
        assertSame(DropSymbols.iconForName("Griffin Feather"), DropSymbols.iconFor(drop));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Griffin Feather",
        "griffin feather",
        "GRIFFIN FEATHER",
        "  Griffin   Feather  ",
        "§aGriffin §rFeather",
        "§a§lGriffin Feather§r",
    })
    @DisplayName("matching ignores case, whitespace runs and formatting codes")
    void matchingIsForgiving(String name) {
        assertTrue(DropSymbols.hasMapping(name), () -> "did not match: " + readable(name));
        assertEquals("minecraft:feather", idOf(DropSymbols.iconForName(name)));
    }

    @ParameterizedTest
    @CsvSource({
        "'16x Ancient Claw',            minecraft:flint",
        "'32x Ancient Claw',            minecraft:flint",
        "'Ancient Claw x16',            minecraft:flint",
        "'Enchanted Gold Ingot x16',    minecraft:gold_ingot",
        "'16x Enchanted Gold Ingot',    minecraft:gold_ingot",
        "'2x Griffin Feather',          minecraft:feather",
        "'1x Mythological Dye',         minecraft:yellow_dye",
    })
    @DisplayName("a count on either end still finds the same item")
    void countSuffixStillMatches(String name, String expectedId) {
        assertEquals(expectedId, idOf(DropSymbols.iconForName(name)),
                () -> "count-carrying name did not match: " + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Chimera", "Chimera I", "Chimera V", "Chimera VII", "Enchanted Book"})
    @DisplayName("every spelling of the Inquisitor's book is the enchanted book")
    void chimeraSpellings(String name) {
        assertEquals("minecraft:enchanted_book", idOf(DropSymbols.iconForName(name)));
    }

    @Test
    @DisplayName("the parenthetical form Hypixel uses for a book still matches")
    void parentheticalBook() {
        assertEquals("minecraft:enchanted_book",
                idOf(DropSymbols.iconForName("Enchanted Book (Chimera I)")));
    }

    // ======================================================================
    //  Legibility: the pairs that share a sprite must not share a look
    // ======================================================================

    @Test
    @DisplayName("enchanted variants carry a glint so they read apart from the plain drop")
    void enchantedVariantsGlint() {
        assertSameItemDifferentGlint("Ancient Claw", "Enchanted Ancient Claw");
        assertSameItemDifferentGlint("Hilt of Revelations", "Daedalus Stick");
    }

    private static void assertSameItemDifferentGlint(String plain, String enchanted) {
        ItemStack a = DropSymbols.iconForName(plain);
        ItemStack b = DropSymbols.iconForName(enchanted);
        assertEquals(idOf(a), idOf(b), () -> plain + " and " + enchanted + " should share a sprite");
        assertNotSame(a, b, () -> plain + " and " + enchanted + " must be distinct stacks");
        assertFalse(Boolean.TRUE.equals(a.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                () -> plain + " should not glint");
        assertTrue(Boolean.TRUE.equals(b.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                () -> enchanted + " should glint, or it is indistinguishable from " + plain);
    }

    // ======================================================================
    //  Coverage: everything the mod itself can name must have a row
    // ======================================================================

    @Test
    @DisplayName("every default jackpot drop has an icon of its own")
    void everyJackpotDropIsMapped() {
        List<String> missing = new ArrayList<>();
        for (String name : JackpotRule.defaults().itemNames()) {
            if (!DropSymbols.hasMapping(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "jackpot drops with no sprite -- the celebration would land on a chest: "
                        + missing);
    }

    @ParameterizedTest
    @EnumSource(MythologicalCreature.class)
    @DisplayName("every drop the simulator can roll has an icon of its own")
    void everySimulatedDropIsMapped(MythologicalCreature creature) {
        Set<String> missing = new TreeSet<>();
        for (int i = 0; i < 400; i++) {
            for (LootDrop drop : SimulatedLoot.rollFor(creature)) {
                if (!DropSymbols.hasMapping(drop.itemName())) {
                    missing.add(drop.itemName());
                }
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "drops from " + creature.displayName() + " with no sprite: " + missing);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // Burrow treasure, every spade tier.
        "Griffin Feather", "Braided Griffin Feather", "Mythos Fragment", "Myth the Fish", "Coins",
        // The shared creature pool.
        "Ancient Claw", "Enchanted Ancient Claw", "Enchanted Gold Ingot",
        "Mythological Dye",
        // One signature drop per creature, all twelve.
        "Hilt of Revelations", "Crochet Tiger Plushie", "Washed-up Souvenir", "Cretan Urn",
        "Antique Remedies", "Dwarf Turtle Shelmet", "Daedalus Stick", "Minos Relic",
        "Brain Food", "Chimera I", "Fateful Stinger", "Manti-core", "Crown of Greed",
        "Shimmering Wool",
    })
    @DisplayName("every drop a Diana player can actually get is mapped")
    void everyRealDropIsMapped(String name) {
        assertTrue(DropSymbols.hasMapping(name),
                () -> name + " is a real Diana drop with no sprite");
        assertFalse(DropSymbols.iconForName(name).isEmpty());
    }

    // ======================================================================
    //  Coverage across the whole game, not only Diana
    // ======================================================================

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("every jackpot drop of every registered source has a sprite of its own")
    void everyRegisteredJackpotDropIsMapped(LootSource source) {
        Set<String> missing = new TreeSet<>();
        for (String name : LootSourceRegistry.info(source).jackpotItems()) {
            if (!DropSymbols.hasMapping(name)) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(),
                () -> LootSourceRegistry.info(source).displayName() + " can celebrate drops that "
                        + "have no sprite, so the flourish would land on the fallback chest: "
                        + missing);
    }

    @Test
    @DisplayName("nothing the registry can celebrate still draws the fallback chest")
    void theFallbackChestIsNoLongerTheAnswerForRealLoot() {
        Set<String> onTheChest = new TreeSet<>();
        for (LootSource source : LootSource.values()) {
            for (String name : LootSourceRegistry.info(source).jackpotItems()) {
                if ("minecraft:chest".equals(idOf(DropSymbols.iconForName(name)))) {
                    onTheChest.add(name);
                }
            }
        }
        assertTrue(onTheChest.isEmpty(),
                () -> "these drops still resolve to the hard fallback: " + onTheChest
                        + ". The chest is for names nobody has heard of, not for loot the mod "
                        + "itself lists by name.");
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no two drops one source can pay land on the same sprite")
    void oneSourceNeverShowsTheSameSpriteTwice(LootSource source) {
        assertNoSpriteRepeats(LootSourceRegistry.info(source).jackpotItems(),
                LootSourceRegistry.info(source).displayName() + "'s jackpot list");
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no single simulated payout repeats a sprite across its reels")
    void noSimulatedPayoutRepeatsASprite(LootSource source) {
        // The jackpot list is not the whole picture: a real payout mixes the celebrated drop with
        // the generic materials and the coin line, and those go on the other two reels. Rolling the
        // simulator is the cheapest way to test the combination the player actually sees, because
        // it draws from the same registry the live feature does.
        for (int i = 0; i < 200; i++) {
            List<String> names = new ArrayList<>(3);
            for (LootDrop drop : SimulatedLoot.rollFor(source)) {
                names.add(drop.itemName());
            }
            assertNoSpriteRepeats(names, "a single " + source.id() + " payout " + names);
        }
    }

    /**
     * The drop tables the wiki actually publishes, which are wider than any jackpot list.
     *
     * <p>{@link LootSourceRegistry}'s jackpot lists are the drops worth a flourish, not the drops a
     * boss can pay, so testing only those leaves the commonest clash invisible. Every group below
     * is one fight's rare-and-above drops as
     * <a href="https://hypixelskyblock.minecraft.wiki">hypixelskyblock.minecraft.wiki</a> lists them,
     * read on 2026-08-30 and corrected against it again on 2026-08-31, and each caught a real
     * collision when it was first run: Summoning Eye and Etherwarp Merger were both the eye of
     * ender until the Voidgloom row went in, and Spider Catalyst and Tarantula Catalyst shared one
     * row until the Broodfather row went in.
     *
     * <p><b>Six names here were themselves wrong</b>, which is worth recording because a fixture
     * that claims to be transcribed and is not is a worse lie than an untested table. There is no
     * Ender Catalyst, Blaze Catalyst or Wolf Catalyst -- SkyBlock has nine catalysts and none of
     * those three is among them, and the Sven page lists no catalyst at all. "Bundle of Magma" is
     * the same object as the "Bundle of Magma Arrows" listed beside it, and "Grizzly Bait" the same
     * object as "Grizzly Salmon"; each pair was one item under two names, so the tables were also
     * silently asserting that a boss pays a thing twice. And the Glacite corpse table was six
     * eighths wrong -- there is no Vanguard armour, and the Yog, Mineral and Lapis helmets are not
     * corpse loot. The name-snapshot test in {@code core} exists so that the next such name fails
     * the build instead of sitting in a fixture as evidence for itself.
     *
     * <p>Keep these in step with the wiki when Hypixel changes a table. A name that disappears is
     * harmless; a name that appears is the one that needs a row.
     */
    static Stream<Arguments> publishedDropTables() {
        return Stream.of(
                Arguments.of("Voidgloom Seraph", List.of(
                        "Judgement Core", "Null Sphere", "Null Atom", "Twilight Arrow Poison",
                        "Transmission Tuner", "Summoning Eye", "Hazmat Enderman", "Endersnake Rune",
                        "End Rune", "Enchant Rune", "Sinful Dice",
                        "Exceedingly Rare Ender Artifact Upgrade", "Handy Blood Chalice",
                        "Pocket Espresso Machine", "Etherwarp Merger", "End Stone Idol",
                        "Byzantium Dye", "Void Conqueror Enderman Skin")),
                Arguments.of("Revenant Horror", List.of(
                        "Beheaded Horror", "Scythe Blade", "Festering Maggot", "Snake Rune I",
                        "Severed Hand", "Shredded Sinew", "Warden Heart", "Matcha Dye",
                        "Revenant Viscera", "Undead Catalyst", "Enchanted Book")),
                Arguments.of("Tarantula Broodfather", List.of(
                        "Spider Catalyst", "Tarantula Catalyst", "Bite Rune", "Darkness Within Rune",
                        "Enchanted Book", "Fly Swatter", "Tarantula Talisman", "Vial of Venom",
                        "Digested Mosquito", "Paragon Shard", "Tarantula Silk")),
                Arguments.of("Sven Packmaster", List.of(
                        "Spirit Rune I", "Enchanted Book", "Furball", "Red Claw Egg",
                        "Couture Rune I", "Grizzly Salmon", "Overflux Capacitor", "Celeste Dye",
                        "Hamster Wheel", "Wolf Tooth")),
                Arguments.of("Inferno Demonlord", List.of(
                        "Lavatears Rune I", "Wisp's Ice-Flavored Water I Splash Potion",
                        "Bundle of Magma Arrows", "Mana Disintegrator", "Scorched Books",
                        "Inferno Demonlord Shard", "Kelvin Inverter", "Blaze Rod Distillate",
                        "Glowstone Distillate", "Magma Cream Distillate", "Nether Wart Distillate",
                        "Gabagool Distillate", "Scorched Power Crystal", "Flawed Opal Gemstone",
                        "Archfiend Dice", "Fiery Burst Rune I", "High Class Archfiend Dice",
                        "Wilson's Engineering Plans", "Subzero Inverter", "Flame Dye",
                        "Enchanted Book")),
                Arguments.of("Necron's reward chest", List.of(
                        "Recombobulator 3000", "Necron's Handle", "Shadow Warp", "Implosion",
                        "Wither Shield", "Dark Claymore", "Giant's Sword", "Shadow Fury",
                        "Fifth Master Star", "Master Skull - Tier 5", "Wither Catalyst",
                        "Wither Blood", "Necron Dye", "Livid Dye", "Necron's Helmet",
                        "Storm's Helmet", "Goldor's Helmet", "Maxor's Helmet", "Enchanted Book")),
                Arguments.of("Kuudra Paid chest", List.of(
                        "Kraken Shard", "Apex Dragon Shard", "Heavy Pearl", "Infernal Kuudra Core",
                        "Tentacle Dye")),
                Arguments.of("Crystal Hollows treasure chest", List.of(
                        "Pickonimbus 2000", "Jungle Heart", "Prehistoric Egg", "Red Goblin Egg",
                        "Blue Goblin Egg", "Flawless Ruby Gemstone", "Flawless Amethyst Gemstone",
                        "Flawless Jade Gemstone", "Flawless Amber Gemstone",
                        "Flawless Sapphire Gemstone", "Flawless Topaz Gemstone",
                        "Flawless Jasper Gemstone", "FTX 3070", "Synthetic Heart", "Control Switch",
                        "Robotron Reflector", "Electron Transmitter", "Superlite Motor")),
                Arguments.of("Glacite corpse", List.of(
                        "Fine Onyx Gemstone", "Flawless Onyx Gemstone", "Glacite Jewel",
                        "Bejeweled Handle", "Frozen Scute", "Caged Wisp", "Shattered Locket",
                        "Dwarven O's Metallic Minis")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("publishedDropTables")
    @DisplayName("no two drops the wiki lists in one table land on the same sprite")
    void publishedTablesNeverRepeatASprite(String table, List<String> drops) {
        for (String drop : drops) {
            assertTrue(DropSymbols.hasMapping(drop),
                    () -> table + " drops \"" + drop + "\" and nothing here draws it");
        }
        assertNoSpriteRepeats(drops, "the " + table + " drop table");
    }

    /**
     * Fails when two <em>different</em> rows in {@code names} draw the same picture.
     *
     * <p>Sameness is item id plus glint, because that pair is exactly what the player sees. Rows are
     * told apart by object identity rather than by name: {@link DropSymbols} builds one shared stack
     * per row, so two spellings of one drop hand back the same instance and are correctly ignored,
     * while two genuinely different rows that happen to have picked the same item are caught.
     */
    // ======================================================================
    //  The reels the published screenshots actually draw
    // ======================================================================

    /**
     * Covers {@link SelfTest#demonstrationRolls()}, which nothing else here reaches.
     *
     * <p>This is the hole the 2026-08-30 frames fell through. Every other test in this class walks
     * {@link LootSourceRegistry}'s jackpot lists or the wiki snapshot; the self test's own
     * fixtures are a third, separate list of names, and it was the only one no test read. So
     * Essence, Lava Shell and Magma Urchin could be absent from the table with the whole suite
     * green, and the fishing frame shipped showing the Radioactive Vial next to two identical
     * fallback chests -- precisely the failure this file's header promises is tested rather than
     * hoped for. These names are what a reader of the screenshots judges the feature by, so they
     * get the same two guarantees as real loot: a sprite of their own, and no repeat inside one
     * reel.
     */
    @ParameterizedTest
    @MethodSource("demonstrationReels")
    @DisplayName("every reel the self test photographs is mapped, with no repeated sprite")
    void theDemonstrationFramesNeverShowTheFallbackTwice(String frame, List<String> names) {
        Set<String> onTheChest = new TreeSet<>();
        for (String name : names) {
            if ("minecraft:chest".equals(idOf(DropSymbols.iconForName(name)))) {
                onTheChest.add(name);
            }
        }
        assertTrue(onTheChest.isEmpty(),
                () -> frame + ".png would be published with the fallback chest standing in for "
                        + onTheChest + ". A screenshot is the first thing anyone judges this "
                        + "feature by, so a demonstration drop needs a row like any other.");
        assertNoSpriteRepeats(names, frame + ".png's reel");
    }

    static List<Arguments> demonstrationReels() {
        List<Arguments> reels = new ArrayList<>();
        SelfTest.demonstrationRolls().forEach((frame, drops) -> {
            List<String> names = new ArrayList<>();
            for (LootDrop drop : drops) {
                names.add(drop.itemName());
            }
            reels.add(Arguments.of(frame, names));
        });
        reels.sort((a, b) -> String.valueOf(a.get()[0]).compareTo(String.valueOf(b.get()[0])));
        return reels;
    }

    private static void assertNoSpriteRepeats(Iterable<String> names, String what) {
        Map<String, ItemStack> stackBySprite = new HashMap<>();
        Map<String, String> nameBySprite = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        for (String name : names) {
            ItemStack icon = DropSymbols.iconForName(name);
            assertFalse(icon.isEmpty(), () -> name + " has no drawable sprite at all");
            String sprite = spriteKey(icon);
            ItemStack already = stackBySprite.putIfAbsent(sprite, icon);
            String claimant = nameBySprite.putIfAbsent(sprite, name);
            if (already != null && already != icon) {
                clashes.add("\"" + claimant + "\" and \"" + name + "\" both draw " + sprite);
            }
        }
        assertTrue(clashes.isEmpty(),
                () -> what + " puts two different drops on one picture, which is the bug the "
                        + "table exists to prevent -- the reels would look like they had failed "
                        + "and the three-of-a-kind flourish would mean nothing: " + clashes);
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    /** What the player sees: the item, and whether it shimmers. */
    private static String spriteKey(ItemStack stack) {
        Boolean glint = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        return idOf(stack) + (Boolean.TRUE.equals(glint) ? " (glinted)" : "");
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static InputStream resource() {
        return DropSymbols.class.getResourceAsStream(DropSymbols.RESOURCE);
    }

    private static JsonObject root() {
        try (InputStream in = resource()) {
            assertNotNull(in, "resource missing");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("could not read " + DropSymbols.RESOURCE, e);
        }
    }

    private static List<String> allIds() {
        List<String> ids = new ArrayList<>();
        JsonArray entries = root().getAsJsonArray("entries");
        assertNotNull(entries, "the resource has no \"entries\" array");
        for (JsonElement element : entries) {
            ids.add(element.getAsJsonObject().get("item").getAsString());
        }
        return ids;
    }

    /** Section signs are invisible in an assertion message; show them as ampersands. */
    private static String readable(String raw) {
        return raw.replace('§', '&');
    }
}
