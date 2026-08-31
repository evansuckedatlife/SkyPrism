package com.skyprism.mc.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skyprism.mc.symbols.DropSymbols;
import com.skyprism.mc.symbols.ItemRegistryBootstrap;

import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Every name on the spinning reel has a sprite.
 *
 * <p>The reel strip is drawn from {@code SlotMachineHud.FILLER}, a hand-written list of real
 * Diana drop names, and the sprites come from {@code assets/skyprism/drop_symbols.json}. They
 * are two files, edited by different people at different times, and until this test nothing
 * compared them. {@code "Control Switch"} was in the first and not the second, so a fallback
 * chest blurred past on <em>every</em> reel of <em>every</em> roll the mod has ever drawn, and
 * every existing test passed: the symbol suite checked the drops the Diana code can name, the
 * HUD has no test that renders, and the filler list is named nowhere else.</p>
 *
 * <p>This is the cheapest possible guard against that whole class of mistake -- adding a name
 * to the strip without adding a row -- and it costs one loop.</p>
 */
@DisplayName("every filler name on the reel strip has a mapped sprite")
final class SlotMachineFillerMcTest {

    @BeforeAll
    static void bootstrapRegistries() {
        // The same three steps the symbols suite uses, through the same helper: a registry, and
        // then its data components, without which no ItemStack can be constructed at all.
        ItemRegistryBootstrap.ensure();
    }

    @Test
    @DisplayName("the strip is not empty, so a spinning reel has something to show")
    void stripHasContent() {
        assertFalse(SlotMachineHud.fillerNames().isEmpty(),
                "the reel strip has no names, so every spinning reel would be blank");
    }

    @Test
    @DisplayName("no filler name falls through to the fallback icon")
    void everyFillerNameIsMapped() {
        List<String> unmapped = new ArrayList<>();
        for (String name : SlotMachineHud.fillerNames()) {
            if (!DropSymbols.hasMapping(name)) {
                unmapped.add(name);
            }
        }
        assertEquals(List.of(), unmapped,
                "these reel-strip names have no row in drop_symbols.json, so they draw the "
                        + "fallback chest on every spin");
    }

    @Test
    @DisplayName("every filler name resolves to a real, non-empty stack")
    void everyFillerNameDrawsSomething() {
        List<String> empty = new ArrayList<>();
        for (String name : SlotMachineHud.fillerNames()) {
            ItemStack icon = DropSymbols.iconForName(name);
            if (icon == null || icon.isEmpty()) {
                empty.add(name);
            }
        }
        assertEquals(List.of(), empty,
                "these reel-strip names produced no drawable stack, so their window would be "
                        + "empty rather than merely wrong");
    }

    @Test
    @DisplayName("the strip has no duplicate names, so the drum does not stutter")
    void stripHasNoDuplicates() {
        List<String> names = SlotMachineHud.fillerNames();
        assertEquals(names.size(), List.copyOf(new java.util.LinkedHashSet<>(names)).size(),
                "a repeated filler name makes the same sprite appear twice in one turn of the "
                        + "drum, which reads as a stalled reel: " + names);
    }

    @Test
    @DisplayName("the strip is long enough that a reel does not visibly repeat")
    void stripIsLongEnough() {
        // Three windows show three rows at once and the drum turns for over a second, so a
        // strip much shorter than this shows the same item twice on screen at the same time.
        assertTrue(SlotMachineHud.fillerNames().size() >= 6,
                "the reel strip is only " + SlotMachineHud.fillerNames().size() + " long, so a "
                        + "spinning reel repeats itself within one window");
    }
}
