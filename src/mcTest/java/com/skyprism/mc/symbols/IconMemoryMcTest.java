package com.skyprism.mc.symbols;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.diana.SlotRoll;
import com.skyprism.core.diana.SlotRollConfig;
import com.skyprism.core.util.FixedClock;
import com.skyprism.mc.symbols.DropSymbols.SymbolSource;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The four tiers, in order, on the real registry.
 *
 * <p>The bug this whole feature exists to fix is invisible to any test that does not use the real
 * component system: a synthesised {@code minecraft:stick} and a Hypixel Daedalus Stick are the
 * same {@link net.minecraft.world.item.Item}, and they differ only in a data component that a
 * mocked registry would happily let anyone set on anything. So these tests bootstrap Minecraft the
 * same way {@link DropSymbolsMcTest} does, build stacks the way the server would, and assert on
 * what actually comes back out.
 *
 * <p>Every test resets the module afterwards. {@link DropSymbols}'s tiers are process-global by
 * design -- there is one slot machine and one player -- so a capture left behind here would be a
 * capture the next test class inherits.
 */
@DisplayName("DropSymbols prefers a real stack, then a learned model, then the vanilla lookalike")
final class IconMemoryMcTest {

    /** A real path out of Hypixel's own pack, taken from the shipped server resource pack. */
    private static final String DAEDALUS_MODEL =
            "hypixel_skyblock:item/community_center/mayor/diana/daedalus_stick";

    private static final String CHIMERA_MODEL =
            "hypixel_skyblock:item/community_center/mayor/diana/chimera";

    @BeforeAll
    static void bootstrapRegistries() {
        ItemRegistryBootstrap.ensure();
    }

    @BeforeEach
    void freshModule() {
        DropSymbols.forgetEverythingForTesting();
        IconCapture.resetForTesting();
    }

    @AfterEach
    void leaveNothingBehind() {
        DropSymbols.forgetEverythingForTesting();
        IconCapture.resetForTesting();
    }

    // ======================================================================
    //  Tier order
    // ======================================================================

    @Test
    @DisplayName("with nothing captured or learned, the vanilla lookalike still answers")
    void fallbackIsTheFloor() {
        assertEquals(SymbolSource.FALLBACK, DropSymbols.sourceFor("Daedalus Stick"));
        assertEquals("minecraft:stick", idOf(DropSymbols.iconForName("Daedalus Stick")));
        assertEquals(Identifier.parse("minecraft:stick"),
                DropSymbols.iconForName("Daedalus Stick").get(DataComponents.ITEM_MODEL),
                "the synthesised stack is exactly the bug: its item_model is the vanilla default "
                        + "every item carries, which points at plain stick art, so Hypixel's pack "
                        + "has nothing of its own to answer with");
    }

    @Test
    @DisplayName("a learned model beats the vanilla lookalike")
    void learnedBeatsFallback() {
        DropSymbols.installMemory(memoryWith("daedalus stick", "minecraft:stick", DAEDALUS_MODEL));

        ItemStack icon = DropSymbols.iconForName("Daedalus Stick");
        assertEquals("minecraft:stick", idOf(icon), "the base item is still what Hypixel sent");
        assertEquals(Identifier.parse(DAEDALUS_MODEL), icon.get(DataComponents.ITEM_MODEL),
                "and the component is what makes the pack draw the real art");
        assertEquals(SymbolSource.LEARNED, DropSymbols.sourceFor("Daedalus Stick"));
    }

    @Test
    @DisplayName("a live captured stack beats a learned one")
    void capturedBeatsLearned() {
        DropSymbols.installMemory(memoryWith("daedalus stick", "minecraft:stick", DAEDALUS_MODEL));
        assertEquals(SymbolSource.LEARNED, DropSymbols.sourceFor("Daedalus Stick"));

        ItemStack live = hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL,
                "DAEDALUS_STICK");
        DropSymbols.learnFrom("Daedalus Stick", live);

        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor("Daedalus Stick"));
        ItemStack icon = DropSymbols.iconForName("Daedalus Stick");
        assertEquals(Identifier.parse(DAEDALUS_MODEL), icon.get(DataComponents.ITEM_MODEL));
        assertEquals("Daedalus Stick", icon.getHoverName().getString(),
                "a real capture carries the server's own name too, which a learned stack cannot");
    }

    @Test
    @DisplayName("the captured stack is a copy, so the server rewriting the slot cannot change it")
    void captureKeepsACopy() {
        ItemStack live = hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL, null);
        DropSymbols.learnFrom("Daedalus Stick", live);
        ItemStack kept = DropSymbols.iconForName("Daedalus Stick");

        assertFalse(kept == live, "holding the inventory's own stack would mean the reel drew "
                + "whatever the slot became");
        live.set(DataComponents.ITEM_MODEL, Identifier.parse("minecraft:something_else"));
        assertEquals(Identifier.parse(DAEDALUS_MODEL), kept.get(DataComponents.ITEM_MODEL));
    }

    @Test
    @DisplayName("a captured stack is forced to a count of one, so no stack size covers the sprite")
    void captureNormalisesTheCount() {
        ItemStack live = hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL, null);
        live.setCount(64);
        DropSymbols.learnFrom("Daedalus Stick", live);
        assertEquals(1, DropSymbols.iconForName("Daedalus Stick").getCount());
    }

    @Test
    @DisplayName("a name nothing has ever claimed reaches the shared chest, not an empty stack")
    void unmappedStillDraws() {
        ItemStack icon = DropSymbols.iconForName("Enchanted Wombat of Perpetual Confusion");
        assertFalse(icon.isEmpty());
        assertEquals(SymbolSource.FALLBACK,
                DropSymbols.sourceFor("Enchanted Wombat of Perpetual Confusion"));
        assertSame(icon, DropSymbols.iconForName("Some Other Thing Nobody Mapped"));
    }

    // ======================================================================
    //  Round trip through the file
    // ======================================================================

    @Test
    @DisplayName("what a capture learns survives a restart")
    void learnedMappingRoundTripsThroughTheFile(@TempDir Path dir) {
        Path file = dir.resolve(IconMemory.FILE_NAME);

        // Session one: the drop lands for the first time.
        DropSymbols.installMemory(IconMemory.load(file));
        DropSymbols.learnFrom("Chimera",
                hypixelStack(Items.ENCHANTED_BOOK, "Chimera", CHIMERA_MODEL, "ENCHANTED_BOOK"));
        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor("Chimera"));
        DropSymbols.saveMemory();
        assertTrue(Files.isRegularFile(file), "learning something must actually write it down");

        // Session two: a cold start that has never seen a Chimera.
        DropSymbols.forgetEverythingForTesting();
        assertEquals(SymbolSource.FALLBACK, DropSymbols.sourceFor("Chimera"),
                "sanity: without the file this is the old behaviour");

        DropSymbols.installMemory(IconMemory.load(file));
        assertEquals(SymbolSource.LEARNED, DropSymbols.sourceFor("Chimera"),
                "the whole point: seen once, drawn correctly forever after");
        assertEquals(Identifier.parse(CHIMERA_MODEL),
                DropSymbols.iconForName("Chimera").get(DataComponents.ITEM_MODEL));
        assertEquals("minecraft:enchanted_book", idOf(DropSymbols.iconForName("Chimera")));
    }

    @Test
    @DisplayName("the file records SkyBlock's own id, which is the half Hypixel does not re-word")
    void theSkyblockIdIsPersisted(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(IconMemory.FILE_NAME);
        DropSymbols.installMemory(IconMemory.load(file));
        DropSymbols.learnFrom("Daedalus Stick",
                hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL, "DAEDALUS_STICK"));
        DropSymbols.saveMemory();

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("DAEDALUS_STICK"), () -> "no SkyBlock id in:\n" + written);
        assertTrue(written.contains("\"version\""), "the schema must be versioned so it can change");

        IconMemory reloaded = IconMemory.load(file);
        assertEquals("DAEDALUS_STICK", reloaded.get("daedalus stick").skyblockId());
        assertEquals(DAEDALUS_MODEL, reloaded.get("daedalus stick").modelId());
        assertEquals("minecraft:stick", reloaded.get("daedalus stick").itemId());
    }

    @Test
    @DisplayName("a re-worded drop supersedes the row its old name owned instead of duplicating it")
    void anIdSupersedesTheRowItUsedToLiveUnder() {
        IconMemory store = IconMemory.load(null);
        store.remember("old name", "DAEDALUS_STICK", "minecraft:stick", DAEDALUS_MODEL, 1_000L);
        store.remember("shiny new name", "DAEDALUS_STICK", "minecraft:stick",
                DAEDALUS_MODEL + "_v2", 2_000L);

        assertEquals(1, store.size(), "two rows for one item is exactly the leak the id prevents");
        assertNotNull(store.get("shiny new name"));
        assertNull(store.get("old name"));
    }

    @Test
    @DisplayName("re-seeing a drop that has not changed does not dirty the file")
    void relearningTheSameThingIsFree() {
        IconMemory store = IconMemory.load(null);
        assertTrue(store.remember("chimera", "CHIMERA", "minecraft:enchanted_book",
                CHIMERA_MODEL, 1_000L));
        assertFalse(store.remember("chimera", "CHIMERA", "minecraft:enchanted_book",
                CHIMERA_MODEL, 2_000L), "the tenth Chimera of the evening is not news");
    }

    @Test
    @DisplayName("the map is bounded, and eviction takes the least recently seen")
    void evictionIsBoundedAndLeastRecentlySeen() {
        IconMemory store = IconMemory.load(null);
        for (int i = 0; i < IconMemory.MAX_ENTRIES + 25; i++) {
            // Ascending seenAt, so the earliest names are the stalest.
            store.remember("drop " + i, null, "minecraft:stick", DAEDALUS_MODEL, 1_000L + i);
        }
        assertEquals(IconMemory.MAX_ENTRIES, store.size());
        assertNull(store.get("drop 0"), "the oldest row is the one that should have gone");
        assertNotNull(store.get("drop " + (IconMemory.MAX_ENTRIES + 24)),
                "the row just written is never the one evicted");
    }

    // ======================================================================
    //  Bad files
    // ======================================================================

    @Test
    @DisplayName("a corrupt file degrades to the vanilla lookalike without throwing")
    void corruptFileDegradesToFallback(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(IconMemory.FILE_NAME);
        Files.writeString(file, "{ this is not json at all", StandardCharsets.UTF_8);

        IconMemory store = assertDoesNotThrow(() -> IconMemory.load(file));
        assertEquals(0, store.size());
        assertFalse(store.notes().isEmpty(), "the player deserves to be told why");

        assertDoesNotThrow(() -> DropSymbols.installMemory(store));
        assertEquals(SymbolSource.FALLBACK, DropSymbols.sourceFor("Daedalus Stick"));
        assertFalse(DropSymbols.iconForName("Daedalus Stick").isEmpty());

        assertFalse(Files.exists(file), "the wreckage should have been moved aside");
        try (var siblings = Files.list(dir)) {
            assertTrue(siblings.anyMatch(p -> p.getFileName().toString().contains("corrupt")),
                    "and preserved, not deleted");
        }
    }

    @Test
    @DisplayName("a file from a newer build is left strictly alone")
    void aNewerSchemaIsNotTouched(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(IconMemory.FILE_NAME);
        String original = "{\"version\":" + (IconMemory.SCHEMA_VERSION + 1)
                + ",\"entries\":[{\"name\":\"chimera\",\"item\":\"minecraft:enchanted_book\","
                + "\"model\":\"" + CHIMERA_MODEL + "\"}]}";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        IconMemory store = IconMemory.load(file);
        assertEquals(0, store.size(), "a schema we do not understand is not one we may read");
        assertNull(store.file(), "and not one we may ever write");
        store.remember("chimera", null, "minecraft:enchanted_book", CHIMERA_MODEL, 1L);
        store.save();
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8),
                "launching an old jar once must not cost the player what the new one learned");
    }

    @Test
    @DisplayName("rows naming an item this version does not have are ignored, not fatal")
    void unusableRowsAreSkipped() {
        IconMemory store = IconMemory.load(null);
        store.remember("nonsense", null, "minecraft:definitely_not_an_item", CHIMERA_MODEL, 1L);
        store.remember("also nonsense", null, "not a valid id", "neither is this", 1L);
        store.remember("daedalus stick", null, "minecraft:stick", DAEDALUS_MODEL, 1L);

        assertDoesNotThrow(() -> DropSymbols.installMemory(store));
        assertEquals(SymbolSource.LEARNED, DropSymbols.sourceFor("Daedalus Stick"));
        assertFalse(DropSymbols.hasLearned("nonsense"));
        assertFalse(DropSymbols.hasLearned("also nonsense"));
    }

    @Test
    @DisplayName("a memory with no file never writes, and says so")
    void memoryOnlyStoreNeverWrites() {
        IconMemory store = IconMemory.load(null);
        assertNull(store.file());
        assertFalse(store.notes().isEmpty());
        store.remember("chimera", null, "minecraft:enchanted_book", CHIMERA_MODEL, 1L);
        assertTrue(store.dirty());
        assertDoesNotThrow(store::save);
        assertTrue(store.dirty(), "a save that could not happen must not claim it did");
    }

    // ======================================================================
    //  Nothing is built before it is drawn
    // ======================================================================

    @Test
    @DisplayName("installing a memory builds no ItemStack, however early it runs")
    void installingBuildsNoStack() {
        DropSymbols.installMemory(memoryWith("daedalus stick", "minecraft:stick", DAEDALUS_MODEL));
        assertTrue(DropSymbols.hasLearned("daedalus stick"));
        assertFalse(DropSymbols.learnedStackBuilt("daedalus stick"),
                "an ItemStack built before Minecraft binds its item's components throws, and "
                        + "throwing out of a load here once left the slot machine drawing nothing "
                        + "at all for the rest of the process");

        DropSymbols.iconForName("Daedalus Stick");
        assertTrue(DropSymbols.learnedStackBuilt("daedalus stick"),
                "and it is built on the first draw, not never");
    }

    @Test
    @DisplayName("nothing in the public surface throws on null, blank or garbage")
    void nothingThrows() {
        assertDoesNotThrow(() -> {
            DropSymbols.learnFrom(null, null);
            DropSymbols.learnFrom("Chimera", null);
            DropSymbols.learnFrom("Chimera", ItemStack.EMPTY);
            DropSymbols.learnFrom("", new ItemStack(Items.STICK));
            DropSymbols.learnFrom("   §r  ", new ItemStack(Items.STICK));
            DropSymbols.installMemory(null);
            DropSymbols.maybeSaveMemory(0L);
            DropSymbols.saveMemory();
            DropSymbols.sourceFor(null);
            DropSymbols.sourceFor("");
            DropSymbols.knownNames();
            DropSymbols.capturedCount();
            DropSymbols.learnedCount();
            IconCapture.init();
        });
        assertNotNull(DropSymbols.sourceFor(null));
        assertFalse(DropSymbols.knownNames().isEmpty());
    }

    @Test
    @DisplayName("a stack with no item_model is still captured, but nothing is remembered from it")
    void aStackWithNoModelIsStillWorthKeeping() {
        // A stack whose item_model is its own id, which is the vanilla default every item has.
        ItemStack vanilla = new ItemStack(Items.STICK);
        vanilla.set(DataComponents.CUSTOM_NAME, Component.literal("Daedalus Stick"));
        assertEquals(Identifier.parse("minecraft:stick"),
                vanilla.get(DataComponents.ITEM_MODEL));
        DropSymbols.installMemory(IconMemory.load(null));
        DropSymbols.learnFrom("Daedalus Stick", vanilla);

        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor("Daedalus Stick"));
        assertFalse(DropSymbols.hasLearned("daedalus stick"),
                "there is nothing about a plain vanilla stack worth writing to disk");
        assertEquals(0, DropSymbols.memory().size());
    }

    // ======================================================================
    //  Matching a real stack to a parsed drop
    // ======================================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "Daedalus Stick",
        "daedalus stick",
        "  Daedalus   Stick  ",
        "§aDaedalus §rStick",
        "2x Daedalus Stick",
        "Daedalus Stick x2",
    })
    @DisplayName("a drop name matches its stack through formatting, whitespace and a count")
    void nameMatchingIsForgiving(String parsedName) {
        ItemStack live = hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL, null);
        assertTrue(IconCapture.offerForTesting(List.of(parsedName), live),
                () -> "the pipeline parsed " + readable(parsedName)
                        + " and the stack in the inventory was not matched to it");
        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor(parsedName));
        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor("Daedalus Stick"),
                "however the line was worded, the reel looks the drop up by its plain name");
    }

    @Test
    @DisplayName("SkyBlock's id matches even when the display name has been re-worded")
    void theIdMatchesWhenTheNameDoesNot() {
        ItemStack live = hypixelStack(Items.STICK, "§6Something Else Entirely",
                DAEDALUS_MODEL, "DAEDALUS_STICK");
        assertTrue(IconCapture.offerForTesting(List.of("Daedalus Stick"), live),
                "the id is the half Hypixel does not re-word, and it should be tried first");
        assertEquals(SymbolSource.REAL, DropSymbols.sourceFor("Daedalus Stick"));
    }

    @Test
    @DisplayName("an unrelated item in the inventory is not mistaken for the drop")
    void anUnrelatedStackIsIgnored() {
        ItemStack unrelated = hypixelStack(Items.DIRT, "Dirt", "minecraft:item/dirt", "DIRT");
        assertFalse(IconCapture.offerForTesting(List.of("Daedalus Stick"), unrelated));
        assertEquals(SymbolSource.FALLBACK, DropSymbols.sourceFor("Daedalus Stick"));
    }

    @Test
    @DisplayName("an empty slot is not a capture")
    void emptySlotsAreIgnored() {
        assertFalse(IconCapture.offerForTesting(List.of("Daedalus Stick"), ItemStack.EMPTY));
        assertFalse(IconCapture.offerForTesting(List.of("Daedalus Stick"), null));
    }

    // ======================================================================
    //  What the scan is looking for
    // ======================================================================

    @Test
    @DisplayName("the wanted list survives the roll going idle, so a chest opened after it still counts")
    void wantedNamesOutliveTheRoll() {
        FixedClock clock = new FixedClock(1_000L);
        SlotRoll roll = new SlotRoll(SlotRollConfig.defaults(), clock);
        roll.start(MythologicalCreature.MINOS_INQUISITOR);
        roll.offerDrop(new LootDrop("Daedalus Stick", "6", 1, true));

        IconCapture.refreshWanted(roll, true);
        assertEquals(1, IconCapture.wantedCountForTesting());

        // The reels finish. capturedDrops() answers empty from here on, and rebuilding the list
        // now would throw away the one name the grace period exists to keep looking for.
        clock.advance(60_000L);
        assertFalse(roll.active(), "sanity: the roll really has stopped");
        IconCapture.refreshWanted(roll, false);
        assertEquals(1, IconCapture.wantedCountForTesting(),
                "a drop handed over in a chest GUI after the spin is still that fight's drop");

        // The next fight is a clean slate.
        roll.start(MythologicalCreature.MINOS_CHAMPION);
        IconCapture.refreshWanted(roll, true);
        assertEquals(0, IconCapture.wantedCountForTesting());
    }

    @Test
    @DisplayName("a drop already captured is not looked for again")
    void alreadyCapturedDropsAreNotRescanned() {
        DropSymbols.learnFrom("Daedalus Stick",
                hypixelStack(Items.STICK, "Daedalus Stick", DAEDALUS_MODEL, null));

        FixedClock clock = new FixedClock(1_000L);
        SlotRoll roll = new SlotRoll(SlotRollConfig.defaults(), clock);
        roll.start(MythologicalCreature.MINOS_INQUISITOR);
        roll.offerDrop(new LootDrop("Daedalus Stick", "6", 1, true));
        roll.offerDrop(new LootDrop("Chimera", "5", 1, true));

        IconCapture.refreshWanted(roll, true);
        assertEquals(1, IconCapture.wantedCountForTesting(),
                "the second Inquisitor of the evening should scan for the Chimera and nothing else");
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

    /**
     * A stack shaped the way Hypixel sends one: a custom name, the {@code item_model} component
     * its resource pack keys off, and its own id tucked inside {@code ExtraAttributes}.
     */
    private static ItemStack hypixelStack(net.minecraft.world.item.Item item, String displayName,
            String modelId, String skyblockId) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(displayName));
        stack.set(DataComponents.ITEM_MODEL, Identifier.parse(modelId));
        if (skyblockId != null) {
            CompoundTag extra = new CompoundTag();
            extra.putString("id", skyblockId);
            CompoundTag root = new CompoundTag();
            root.put("ExtraAttributes", extra);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
        return stack;
    }

    private static IconMemory memoryWith(String nameKey, String itemId, String modelId) {
        IconMemory store = IconMemory.load(null);
        store.remember(nameKey, null, itemId, modelId, 1L);
        return store;
    }

    private static String idOf(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString();
    }

    private static String readable(String raw) {
        return "'" + raw.replace('§', '&') + "'";
    }
}
