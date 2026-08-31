package com.skyprism.core.loot.events;

import com.skyprism.core.loot.GameContext;
import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.SourceDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that matters most: no detector may claim another source's line.
 *
 * <p>Cross-source false positives are the likeliest bug in this whole feature, and they are close to
 * invisible from the inside -- the machine still spins, it just spins for the wrong reason and the
 * caption lies. The suite therefore takes every real captured line in the package and feeds it to
 * <em>every</em> detector, asserting that exactly the intended one claims it.
 *
 * <p>Two collisions this pins in particular, because they are real and both were designed around:
 * <ul>
 *   <li>{@code TREASURE!} is a substring of {@code FROZEN TREASURE!}, so the Carnival and the
 *       Jerry's Workshop ice line reach each other's detectors through the bus's shared marker set.
 *       Anchoring closes it.</li>
 *   <li>{@code You dug out} appears in both the Diana creature spawn and the Diana treasure payout.
 *       The first is the shipped path's entity trigger and the second is this package's; matching
 *       the spawn line here would start a roll for a creature still alive, which is a visible
 *       regression on the one path that must not regress.</li>
 * </ul>
 *
 * <p>Section signs are {@code \u00A7} escapes so the file's encoding cannot change what is tested.
 */
@DisplayName("events detectors: exactly one source may claim any given line")
class EventDetectorCrossTalkTest {

    private static final long NOW = 5_000L;

    /**
     * Every gate forced open, so this suite tests the patterns rather than the gates.
     *
     * <p>Deliberate: a shut gate would hide a cross-source match instead of exposing it, and the
     * gates are covered separately.
     */
    private static final GameContext EVERYWHERE =
            new GameContext(true, true, "Hub", "Carnival", "Diana", true, true);

    /** One line, and the single source that is allowed to claim it. */
    private record Case(String line, LootSource owner) {
    }

    private static List<Case> corpus() {
        return List.of(
                new Case("\u00A7d\u00A7lHOPPITY'S HUNT \u00A7r\u00A7dYou found a \u00A7r\u00A79Chocolate Lunch Egg "
                        + "\u00A7r\u00A7don a ledge\u00A7r\u00A7d!", LootSource.HOPPITY_MEAL_EGG),
                new Case("\u00A7D\u00A7LHOPPITY'S HUNT \u00A77You found \u00A76Solomon \u00A77(\u00A76\u00A7LLEGENDARY\u00A77)!",
                        LootSource.HOPPITY_RABBIT),
                new Case("\u00A77You caught a stray \u00A79Fish the Rabbit\u00A77!",
                        LootSource.CHOCOLATE_FACTORY_STRAY),
                new Case("\u00A76El Dorado \u00A7d\u00A7lCAUGHT!", LootSource.CHOCOLATE_FACTORY_STRAY),
                new Case("\u00A7e\u00A7lSWEET! \u00A7r\u00A75Snow Suit Helmet \u00A7r\u00A7egift with \u00A7r\u00A7aGrazma\u00A7r\u00A7e!",
                        LootSource.WINTER_GIFT),
                new Case("\u00A76\u00A7lSPOOKY! \u00A7r\u00A77A \u00A7r\u00A76Trick or Treat Chest \u00A7r\u00A77has appeared!",
                        LootSource.SPOOKY_CHEST),
                new Case("SHINY! You extracted Shiny Token and +1,000,000 Coins "
                        + "from the piglet's orb!", LootSource.YEAR_OF_THE_PIG_ORB),
                new Case("\u00A75\u00A7lORB! \u00A7r\u00A7dPicked up \u00A7r\u00A75+12 Motes\u00A7r\u00A7d.", LootSource.RIFT_MOTES_ORB),
                new Case("\u00A7eYou vacuumed a \u00A7r\u00A7aSilverfish\u00A7r\u00A7e!", LootSource.RIFT_VERMIN_VACUUM),
                new Case("TREASURE! There is a Dragonfruit nearby.",
                        LootSource.CARNIVAL_FRUIT_DIGGING),
                new Case("\u00A76\u00A7lPET DROP! \u00A7r\u00A75Baby Yeti \u00A7r\u00A7b(+123% ✯ Magic Find)",
                        LootSource.PET_DROP),
                new Case("\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)",
                        LootSource.MOB_RARE_DROP),
                new Case("\u00A7b\u00A7lRARE DROP! \u00A7r\u00A77(\u00A7r\u00A7f\u00A7r\u00A79Revenant Viscera\u00A7r\u00A77) (+123% ✯ Magic Find)",
                        LootSource.MOB_RARE_DROP));
    }

    /** Lines that belong to other agents' sources, or to nobody, and must be claimed by no one. */
    private static List<String> foreignLines() {
        return List.of(
                // The Jerry's Workshop ice line: shares "TREASURE!" with the Carnival.
                "FROZEN TREASURE! You found Glacial Talisman!",
                "COMPACT! You found an Enchanted Ice!",
                // The Diana creature spawn: shares "You dug out" with the treasure payout.
                "\u00A7c\u00A7lOh! \u00A7r\u00A7eYou dug out a \u00A7r\u00A7cMinos Inquisitor\u00A7r\u00A7e!",
                "\u00A7eYou dug out a Griffin Burrow! \u00A7r\u00A77(2/4)",
                // Other agents' territory.
                "\u00A76 \u00A7r\u00A76\u00A7lTROPHY FISH! \u00A7r\u00A7fYou caught a \u00A7r\u00A79Lavahorse \u00A7r\u00A76\u00A7lGOLD\u00A7r\u00A7f!",
                "\u00A76\u00A7lRARE CROP! \u00A7aCane Knot \u00A7e(\u00A7e+139.5)",
                "\u00A7d\u00A7lPRISTINE! \u00A7r\u00A7fYou found \u00A7r\u00A7a☘ Flawed Jade Gemstone \u00A7r\u00A78x20\u00A7r\u00A7f!",
                "  \u00A7r\u00A7b\u00A7l\u00A7r\u00A79\u00A7lLAPIS \u00A7r\u00A7b\u00A7lCORPSE LOOT!",
                "  \u00A7r\u00A76\u00A7lNICE! SLAYER BOSS SLAIN!",
                "\u00A76\u00A7lRARE REWARD! \u00A7r\u00A7bLeebys \u00A7r\u00A7efound a \u00A7r\u00A76Recombobulator 3000 "
                        + "\u00A7r\u00A7ein their Obsidian Chest\u00A7r\u00A7e!",
                "\u00A76\u00A7lEXCAVATOR! \u00A7r\u00A7fYou found a \u00A7r\u00A79Suspicious Scrap\u00A7r\u00A7f!",
                // Ordinary chat, including a player quoting a banner at somebody.
                "\u00A79Party \u00A78> \u00A7bSteve\u00A7f: \u00A7rRARE DROP! Crown of Greed",
                "\u00A7b[MVP\u00A7r\u00A76+\u00A7r\u00A7b] Notch\u00A7f: \u00A7rI found a stray cat",
                "\u00A7aYou are now in a party with 3 players.",
                "\u00A7aSteve \u00A7r\u00A7ehas obtained \u00A7r\u00A7a\u00A7r\u00A79Judgement Core\u00A7r\u00A7e!");
    }

    private static List<SourceDetector> allDetectors() {
        List<SourceDetector> all = new ArrayList<>(EventDetectors.inOrder());
        all.add(new BurrowTreasureDetector());
        return all;
    }

    @Test
    @DisplayName("each captured line is claimed by its own source and by no other")
    void exactlyOneClaimant() {
        List<String> problems = new ArrayList<>();
        for (Case sample : corpus()) {
            for (SourceDetector detector : allDetectors()) {
                Optional<LootEvent> event = detector.onChat(sample.line(), NOW);
                boolean shouldMatch = detector.source() == sample.owner();
                if (event.isPresent() != shouldMatch) {
                    problems.add((shouldMatch ? "MISSED by " : "STOLEN by ")
                            + detector.source() + " :: " + sample.line());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("lines belonging to other areas, and ordinary chat, are claimed by nobody here")
    void foreignLinesAreLeftAlone() {
        List<String> problems = new ArrayList<>();
        for (String line : foreignLines()) {
            for (SourceDetector detector : allDetectors()) {
                if (detector.onChat(line, NOW).isPresent()) {
                    problems.add(detector.source() + " claimed a line it does not own: " + line);
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("the treasure detector takes the treasure payout and never the creature spawn")
    void burrowTreasureIsNotTheCreatureSpawn() {
        BurrowTreasureDetector detector = new BurrowTreasureDetector();

        Optional<LootEvent> item = detector.onChat(
                "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather\u00A7r\u00A7e!", NOW);
        assertTrue(item.isPresent());
        assertEquals("Treasure Burrow", item.get().subject());
        assertEquals(LootSource.DIANA_MYTHOLOGICAL, item.get().source());

        assertTrue(detector.onChat("\u00A76\u00A7lWow! \u00A7r\u00A7eYou dug out \u00A7r\u00A762,500 coins\u00A7r\u00A7e!", NOW)
                .isPresent(), "a coin payout is a treasure burrow too");

        // The line that must never match: a creature is now alive, and the shipped Diana path owns
        // the roll for it. Matching here would spin the machine before the fight.
        assertTrue(detector.onChat("\u00A7c\u00A7lOh! \u00A7r\u00A7eYou dug out a \u00A7r\u00A7cMinos Inquisitor\u00A7r\u00A7e!", NOW)
                .isEmpty());
        assertTrue(detector.onChat("\u00A7eYou dug out a Griffin Burrow! \u00A7r\u00A77(2/4)", NOW).isEmpty());
    }

    @Test
    @DisplayName("on the real bus, registration order gives the specific source the line")
    void busOrderPrefersTheSpecificSource() {
        LootEventBus bus = new LootEventBus();
        EventDetectors.registerAll(bus);
        bus.updateContext(EVERYWHERE);

        assertEquals(LootSource.PET_DROP,
                claim(bus, "\u00A76\u00A7lPET DROP! \u00A7r\u00A75Baby Yeti \u00A7r\u00A7b(+123% ✯ Magic Find)"),
                "the catch-all must not swallow a pet drop");
        assertEquals(LootSource.MOB_RARE_DROP,
                claim(bus, "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)"));

        // Once a Reindrake is summoned, the next banner is its own rather than the catch-all's.
        bus.onChat("WOAH! [VIP] Georeek summoned a Reindrake from the depths!", NOW);
        assertEquals(LootSource.REINDRAKE,
                claim(bus, "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Reindrake Fragment \u00A7r\u00A7b(+123% ✯ Magic Find)"));
        // ...and the one after it falls back, because the window is single-shot.
        assertEquals(LootSource.MOB_RARE_DROP,
                claim(bus, "\u00A76\u00A7lRARE DROP! \u00A7r\u00A79Judgement Core \u00A7r\u00A7b(+123% ✯ Magic Find)"));
    }

    @Test
    @DisplayName("the catch-all is registered last, which is what makes the order work at all")
    void catchAllIsLast() {
        List<SourceDetector> order = EventDetectors.inOrder();
        assertEquals(LootSource.MOB_RARE_DROP, order.get(order.size() - 1).source());
    }

    @Test
    @DisplayName("registering twice is refused, so a duplicate source cannot go unnoticed")
    void registrationIsExclusive() {
        LootEventBus bus = new LootEventBus();
        EventDetectors.registerAll(bus);
        assertEquals(EventDetectors.inOrder().size(), bus.registeredCount());
        assertThrows(IllegalArgumentException.class, () -> EventDetectors.registerAll(bus));
    }

    @Test
    @DisplayName("the burrow-treasure detector is opt-in, because it and Diana share one source")
    void burrowTreasureIsNotInTheDefaultSet() {
        // Shipping it in registerAll would make a bus that also carries the shipped DianaLootSource
        // throw on wiring, or -- worse, if somebody caught that -- would silently replace the
        // creature path. Making it an explicit call keeps the choice deliberate.
        for (SourceDetector detector : EventDetectors.inOrder()) {
            assertTrue(detector.source() != LootSource.DIANA_MYTHOLOGICAL,
                    "the default set must not claim Diana's source");
        }
        LootEventBus bus = new LootEventBus();
        EventDetectors.registerAll(bus);
        EventDetectors.registerBurrowTreasure(bus);
        bus.updateContext(EVERYWHERE);
        assertEquals(LootSource.DIANA_MYTHOLOGICAL,
                claim(bus, "\u00A76\u00A7lRARE DROP! \u00A7r\u00A7eYou dug out a \u00A7r\u00A79Griffin Feather\u00A7r\u00A7e!"));
    }

    @Test
    @DisplayName("every detector's own registry samples reach it through the real pre-filter")
    void samplesSurviveThePreFilter() {
        // The pre-filter is derived from the registry's markers, so a detector whose markers do not
        // cover its own trigger lines is a feature that passes its unit test and never fires. This
        // drives the real bus with the real markers rather than asserting the invariant in a
        // comment.
        List<String> swallowed = new ArrayList<>();
        for (SourceDetector detector : allDetectors()) {
            if (!detector.readsChat()) {
                continue;
            }
            LootEventBus bus = new LootEventBus();
            bus.register(detector);
            bus.updateContext(EVERYWHERE);
            for (String sample : detector.triggerSamples()) {
                if (!bus.passesPreFilter(sample)) {
                    swallowed.add(detector.source() + " :: " + sample);
                }
            }
        }
        assertTrue(swallowed.isEmpty(), String.join("\n", swallowed));
    }

    private static LootSource claim(LootEventBus bus, String line) {
        Optional<LootEvent> event = bus.onChat(line, NOW);
        assertTrue(event.isPresent(), "nothing claimed: " + line);
        return event.get().source();
    }
}
