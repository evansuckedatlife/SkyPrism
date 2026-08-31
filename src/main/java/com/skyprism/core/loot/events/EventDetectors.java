package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.SourceDetector;

import java.util.List;
import java.util.Objects;

/**
 * The events, festivals, Rift and universal-banner detectors, in the order they must be registered.
 *
 * <h2>Registration order is behaviour, not tidiness</h2>
 * <p>{@link LootEventBus} dispatches in registration order and the first event wins, which is what
 * stops one chat line spinning the machine twice. Sources genuinely overlap: a Reindrake's loot is
 * also a rare mob drop, a Primal Fear's loot is also a rare mob drop, and a pet dropped by either is
 * also a pet drop. So the order below runs from most specific to most general and ends with
 * {@link GenericRareDropDetector}, which claims any banner nobody else wanted. Moving that one
 * earlier would make it swallow every specific source in the mod -- the single easiest way to break
 * this feature, and one that fails quietly.
 *
 * <p>Within the specific group the order is: sources with a trigger line of their own first (they
 * cannot collide with anything), then pets, then the two summon-window bosses. Pets go ahead of the
 * window bosses so that a pet dropped during a Reindrake fight is still captioned as a pet drop --
 * the more informative of the two answers. Both window detectors also refuse pet lines outright, so
 * the split survives somebody re-ordering this list.
 *
 * <h2>What is not here, and why</h2>
 * <p>{@link BurrowTreasureDetector} speaks for the same {@code LootSource} as the shipped
 * {@code DianaLootSource}, and the bus rejects two detectors for one source. They are alternatives,
 * not companions, so registering the treasure detector is an explicit call --
 * {@link #registerBurrowTreasure(LootEventBus)} -- rather than something this method can do behind a
 * caller's back and break Diana with.
 */
public final class EventDetectors {

    private EventDetectors() {
    }

    /**
     * Every detector this package owns except the burrow-treasure one, most specific first.
     *
     * <p>Fresh instances each call: two of these hold a summon window and a third holds a cooldown
     * timestamp, so sharing them between buses would let one bus's state leak into another's.
     */
    public static List<SourceDetector> inOrder() {
        return List.of(
                // Distinctive trigger lines: these cannot collide with anything else.
                new HoppityEggDetector(),
                new HoppityRabbitDetector(),
                new ChocolateFactoryStrayDetector(),
                new WinterGiftDetector(),
                new SpookyChestDetector(),
                new YearOfThePigOrbDetector(),
                new WitchesStewDetector(),
                new SplitOrStealDetector(),
                new MotesOrbDetector(),
                new RiftVerminDetector(),
                new CarnivalFruitDiggingDetector(),

                // Banner claimants, narrowest first.
                new PetDropDetector(),
                new PrimalFearDetector(),
                new ReindrakeDetector(),

                // The catch-all. Always last.
                new GenericRareDropDetector());
    }

    /**
     * Registers {@link #inOrder()} on {@code bus}.
     *
     * @throws IllegalArgumentException if the bus already has a detector for one of these sources,
     *                                  which the bus itself raises and which is worth surfacing
     *                                  rather than swallowing: a duplicate means two things are
     *                                  claiming one source and one of them would be unreachable
     */
    public static void registerAll(LootEventBus bus) {
        Objects.requireNonNull(bus, "bus");
        for (SourceDetector detector : inOrder()) {
            bus.register(detector);
        }
    }

    /**
     * Registers the Mythological treasure-burrow detector, which is mutually exclusive with the
     * shipped {@code DianaLootSource}.
     *
     * <p>Call this on a bus that does <em>not</em> carry {@code DianaLootSource}: the creature-kill
     * half of Diana lives in the shipped controller and is not driven from this bus at all, so a
     * general bus that wants treasure burrows to spin the machine registers this one and leaves the
     * creature path exactly where it already works.
     */
    public static void registerBurrowTreasure(LootEventBus bus) {
        Objects.requireNonNull(bus, "bus");
        bus.register(new BurrowTreasureDetector());
    }
}
