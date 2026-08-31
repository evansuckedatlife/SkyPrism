package com.skyprism.mc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.mc.symbols.DropSymbols;
import com.skyprism.mc.symbols.ItemRegistryBootstrap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a spinning reel scrolls belongs to the source that is rolling, and every name on it has a
 * sprite.
 *
 * <h2>The mistake this file was written for, and the bigger one it grew into</h2>
 *
 * <p>The reel strip used to be one static array of Diana drop names in {@code SlotMachineHud}, and
 * the sprites come from {@code assets/skyprism/drop_symbols.json}. They were two files, edited by
 * different people at different times, and until this test nothing compared them:
 * {@code "Control Switch"} was in the first and not the second, so a fallback chest blurred past on
 * <em>every</em> reel of <em>every</em> roll the mod had ever drawn, and every existing test passed.
 * The symbol suite checked the drops the Diana code can name, the HUD has no test that renders, and
 * the filler list was named nowhere else.</p>
 *
 * <p>Adding the row fixed the sprite and left the real bug standing: Control Switch is a Crystal
 * Hollows mining item that has never been Diana loot, and it was scrolling past under a Minos
 * Champion caption because the array was written when Diana was the only source and was never
 * generalised. Sixty-four sources later a fishing roll scrolled Daedalus Sticks and a slayer roll
 * scrolled Griffin Feathers. So the checks below are the same two questions asked of every strip
 * rather than of one -- is every name mapped, and does every name belong to the source whose
 * machine it is on.</p>
 */
@DisplayName("every reel strip belongs to its source and is fully sprite-mapped")
final class SlotMachineFillerMcTest {

    @BeforeAll
    static void bootstrapRegistries() {
        // The same three steps the symbols suite uses, through the same helper: a registry, and
        // then its data components, without which no ItemStack can be constructed at all.
        ItemRegistryBootstrap.ensure();
    }

    // ======================================================================
    //  Every strip can be drawn
    // ======================================================================

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no strip is empty, so a spinning reel always has something to show")
    void stripHasContent(LootSource source) {
        assertFalse(FillerStrip.of(source).nameList().isEmpty(),
                () -> source + "'s reel strip has no names, so every spinning reel would be blank");
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no filler name falls through to the fallback icon")
    void everyFillerNameIsMapped(LootSource source) {
        List<String> unmapped = new ArrayList<>();
        for (String name : FillerStrip.of(source).nameList()) {
            if (!DropSymbols.hasMapping(name)) {
                unmapped.add(name);
            }
        }
        assertEquals(List.of(), unmapped,
                () -> "these names on " + source + "'s reel strip have no row in "
                        + "drop_symbols.json, so they draw the fallback chest on every spin");
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("every filler name resolves to a real, non-empty stack")
    void everyFillerNameDrawsSomething(LootSource source) {
        List<String> empty = new ArrayList<>();
        for (String name : FillerStrip.of(source).nameList()) {
            ItemStack icon = DropSymbols.iconForName(name);
            if (icon == null || icon.isEmpty()) {
                empty.add(name);
            }
        }
        assertEquals(List.of(), empty,
                () -> "these names on " + source + "'s reel strip produced no drawable stack, so "
                        + "their window would be empty rather than merely wrong");
    }

    @Test
    @DisplayName("the no-source strip is mapped too, because a frame can land on one")
    void theNoSourceStripIsMapped() {
        List<String> names = FillerStrip.unknown().nameList();
        assertFalse(names.isEmpty());
        for (String name : names) {
            assertTrue(DropSymbols.hasMapping(name),
                    () -> name + " is on the no-source strip with no sprite of its own");
        }
    }

    @Test
    @DisplayName("the generic top-up is fully mapped, because most strips lean on it")
    void theGenericTopUpIsMapped() {
        List<String> unmapped = new ArrayList<>();
        for (String name : FillerStrip.genericTopUp()) {
            if (!DropSymbols.hasMapping(name)) {
                unmapped.add(name);
            }
        }
        assertEquals(List.of(), unmapped,
                "the generic top-up pads the strip of every source whose loot table nobody could "
                        + "verify, so an unmapped name here is a fallback chest on dozens of "
                        + "machines rather than on one");
    }

    // ======================================================================
    //  Every strip reads as motion
    // ======================================================================

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no strip repeats a name, so the drum does not stutter")
    void stripHasNoDuplicates(LootSource source) {
        List<String> names = FillerStrip.of(source).nameList();
        Set<String> distinct = new LinkedHashSet<>();
        for (String name : names) {
            distinct.add(name.toLowerCase(Locale.ROOT));
        }
        assertEquals(names.size(), distinct.size(),
                () -> "a repeated name on " + source + "'s strip makes the same sprite appear "
                        + "twice in one turn of the drum, which reads as a stalled reel: " + names);
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("no strip puts two of its names on one picture")
    void stripHasNoRepeatedSprites(LootSource source) {
        assertNoSpriteRepeats(FillerStrip.of(source).nameList(), source + "'s reel strip");
    }

    @Test
    @DisplayName("the generic top-up alone never puts two names on one picture")
    void theGenericTopUpHasNoRepeatedSprites() {
        // Half the sources in the game show nothing else, so a clash inside this list is a clash
        // on half the machines.
        assertNoSpriteRepeats(FillerStrip.genericTopUp(), "the generic top-up");
    }

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("every strip is long enough that a reel does not visibly repeat")
    void stripIsLongEnough(LootSource source) {
        // Nine cells are on screen at once across the three windows, so a strip shorter than ten
        // shows the same item twice on screen at the same time. Thin sources reach it by topping
        // up from the generic list rather than by repeating what little they have.
        int length = FillerStrip.of(source).nameList().size();
        assertTrue(length >= FillerStrip.MIN_LENGTH,
                () -> source + "'s reel strip is only " + length + " long, so a spinning reel "
                        + "repeats itself within one window");
    }

    // ======================================================================
    //  Every strip belongs to its own source
    // ======================================================================

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("a strip carries only this source's own loot, or honest generic material")
    void stripCarriesNothingBorrowed(LootSource source) {
        // The whole defect, stated as an invariant. A name on a machine is either something the
        // registry says that source can actually pay, or one of the enchanted materials the top-up
        // uses precisely because they claim nothing about where a roll came from. Anything else is
        // another source's loot table leaking in, which is what "a fishing reel scrolling Daedalus
        // Sticks" was.
        Set<String> allowed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        allowed.addAll(LootSourceRegistry.info(source).jackpotItems());
        allowed.addAll(FillerStrip.genericTopUp());

        List<String> borrowed = new ArrayList<>();
        for (String name : FillerStrip.of(source).nameList()) {
            if (!allowed.contains(name)) {
                borrowed.add(name);
            }
        }
        assertEquals(List.of(), borrowed,
                () -> source + "'s reel strip scrolls loot that source cannot drop: " + borrowed
                        + ". A player reads the strip as what this machine pays.");
    }

    @Test
    @DisplayName("Diana no longer scrolls a Crystal Hollows mining item")
    void dianaHasNoControlSwitch() {
        // The line the user actually reported: a Minos Champion roll with a Control Switch
        // scrolling past it. It is real loot, and it belongs to the Powder Chest, which is where
        // it now is and where it now stays.
        List<String> diana = FillerStrip.of(LootSource.DIANA_MYTHOLOGICAL).nameList();
        assertFalse(diana.contains("Control Switch"),
                () -> "Control Switch is a Crystal Hollows drop and has never been Diana loot: "
                        + diana);
        assertTrue(FillerStrip.of(LootSource.POWDER_CHEST).nameList().contains("Control Switch"),
                "Control Switch belongs on the Powder Chest's strip, not nowhere");
    }

    @Test
    @DisplayName("the strips genuinely differ, so the reel says which machine this is")
    void stripsAreNotAllTheSame() {
        // A regression that would pass every check above: resolving every source to one shared
        // list again. Sources with a verified loot table must not look alike.
        List<String> diana = FillerStrip.of(LootSource.DIANA_MYTHOLOGICAL).nameList();
        List<String> fishing = FillerStrip.of(LootSource.FISHING_RARE_SEA_CREATURE).nameList();
        List<String> slayer = FillerStrip.of(LootSource.SLAYER_BOSS).nameList();

        assertFalse(fishing.contains("Daedalus Stick"),
                () -> "a fishing reel is scrolling a Diana drop: " + fishing);
        assertFalse(slayer.contains("Griffin Feather"),
                () -> "a slayer reel is scrolling a Diana drop: " + slayer);
        assertFalse(diana.contains("Lord Jawbus"),
                () -> "a Diana reel is scrolling a sea creature: " + diana);

        assertTrue(diana.contains("Minos Relic"), () -> "Diana's own loot is missing: " + diana);
        assertTrue(fishing.contains("Lord Jawbus"),
                () -> "the rare sea creature strip has no sea creature on it: " + fishing);
        assertTrue(slayer.contains("Judgement Core"),
                () -> "the slayer strip has no slayer drop on it: " + slayer);
    }

    // ======================================================================
    //  Resolution is cached, because the spinning path may not allocate
    // ======================================================================

    @ParameterizedTest
    @EnumSource(LootSource.class)
    @DisplayName("a source's strip is resolved once and handed back, never rebuilt")
    void stripsAreCached(LootSource source) {
        FillerStrip first = FillerStrip.of(source);
        assertSame(first, FillerStrip.of(source),
                () -> "a fresh FillerStrip per lookup would be an allocation on the frame that "
                        + "draws " + source);
        assertSame(first.names(), FillerStrip.of(source).names(),
                () -> "the names of " + source + " are being rebuilt per lookup");
        assertSame(source, first.source());
    }

    @Test
    @DisplayName("the sprite array is allocated once and refreshed in place")
    void iconsAreRefreshedInPlace() {
        FillerStrip strip = FillerStrip.of(LootSource.DIANA_MYTHOLOGICAL);
        ItemStack[] first = strip.icons(1_000L);
        assertEquals(strip.nameList().size(), first.length);
        assertSame(first, strip.icons(1_100L), "a lookup inside the refresh window re-resolved");
        // Past the refresh window the contents are looked up again -- DropSymbols learns art off
        // real drops -- but into the array that already exists, so a refresh allocates nothing.
        assertSame(first, strip.icons(9_000L),
                "the refresh replaced the array instead of rewriting it, which is an allocation "
                        + "on the spinning path every half second");
    }

    @Test
    @DisplayName("the measured type size is remembered per strip, not per machine")
    void labelScaleIsPerStrip() {
        // SlotMachineHud fills this in the first time a source's reel actually spins. Two sources
        // must not share one number: the strips are different lengths of word, and a shared value
        // would change size under the player the first time a different source rolled.
        FillerStrip diana = FillerStrip.of(LootSource.DIANA_MYTHOLOGICAL);
        FillerStrip slayer = FillerStrip.of(LootSource.SLAYER_BOSS);
        diana.labelScale(0.75f);
        try {
            assertEquals(0.75f, diana.labelScale());
            assertEquals(0.0f, slayer.labelScale(),
                    "one strip's measured size leaked into another's");
        } finally {
            diana.labelScale(0.0f);
        }
    }

    // ======================================================================
    //  What /skyprism status reports
    // ======================================================================

    @Test
    @DisplayName("status reports every name the machine can scroll, distinct")
    void allNamesIsTheUnionOfEveryStrip() {
        List<String> all = FillerStrip.allNames();
        assertEquals(all.size(), Set.copyOf(all).size(), "allNames() repeats a name: " + all);

        for (FillerStrip strip : FillerStrip.all()) {
            for (String name : strip.names()) {
                assertTrue(all.contains(name),
                        () -> name + " is on " + strip.source() + "'s strip but is not in the "
                                + "list /skyprism status walks, so nothing reports its sprite");
            }
        }
    }

    @Test
    @DisplayName("every name status reports has a sprite, so the counts mean something")
    void everyReportedNameIsMapped() {
        Set<String> unmapped = new TreeSet<>();
        for (String name : FillerStrip.allNames()) {
            if (!DropSymbols.hasMapping(name)) {
                unmapped.add(name);
            }
        }
        assertTrue(unmapped.isEmpty(),
                () -> "/skyprism status would report these as unmapped, which is the fallback "
                        + "chest on somebody's reel: " + unmapped);
    }

    // ======================================================================
    //  Helpers
    // ======================================================================

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
                () -> what + " shows one picture twice in a single turn of the drum, which reads "
                        + "as a reel that has stalled: " + clashes);
    }

    private static String spriteKey(ItemStack stack) {
        Boolean glint = stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                + (Boolean.TRUE.equals(glint) ? " (glinted)" : "");
    }
}
