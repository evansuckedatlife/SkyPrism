package com.skyprism.core.loot.containers;

import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.SourceDetector;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The container detectors, in the order they must be registered.
 *
 * <h2>Registration order is behaviour, not tidiness</h2>
 * <p>{@link LootEventBus#onChat} and {@link LootEventBus#onScreenTitle} both return the first event
 * any detector produces, in registration order. Two of these sources deliberately overlap -- a
 * Croesus chest and a Catacombs reward chest are the same GUI and the same broadcast, split apart
 * only by whether the run list was recently open -- so the order below is what decides which of them
 * claims an event, and getting it backwards would give every Croesus chest the wrong caption and the
 * wrong on/off switch.
 *
 * <p>The rule is the same one the shipped banner parser follows when it tries the Diana treasure
 * shapes before the generic banner: <b>specific before general</b>. Croesus is conditional on its
 * window and so goes first; Kuudra owns a disjoint pair of tiers and could go anywhere; the
 * Catacombs chest is the fallback for every tier nobody else claimed. The rest do not overlap each
 * other at all and are ordered only for readability.
 *
 * <h2>What the caller has to supply</h2>
 * <p>One thing: the local player's username. Hypixel broadcasts {@code RARE REWARD!} to the whole
 * party, so without it four other players can spin this machine. The supplier is read at match time
 * rather than captured once, because the name is not known during the seconds after login and a
 * value captured then would be wrong for the rest of the session.
 *
 * <h2>Where a screen title has to come from, verified rather than remembered</h2>
 * <p>Four of these sources read {@link SourceDetector#onScreenTitle}, and nothing in core can supply
 * it. The client-side hook, checked with {@code javap} against both shipped Minecraft jars on
 * 2026-08-30 and found <b>byte-identical on 26.1.2 and 26.2</b>, so it needs no Stonecutter
 * conditional:
 *
 * <pre>
 *   net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
 *       public int getContainerId();
 *       public net.minecraft.world.inventory.MenuType&lt;?&gt; getType();
 *       public net.minecraft.network.chat.Component getTitle();
 *   net.minecraft.client.multiplayer.ClientPacketListener
 *       public void handleOpenScreen(ClientboundOpenScreenPacket);
 * </pre>
 *
 * <p>So: one mixin on {@code ClientPacketListener#handleOpenScreen}, read {@code getTitle()},
 * flatten it, hand the string to {@link LootEventBus#onScreenTitle}. That fires a handful of times a
 * minute at worst, which is why a GUI-armed detector is the cheapest kind in the feature.
 *
 * <p><b>Do not read {@code Minecraft.screen} instead.</b> That field is public on 26.1.2 and not on
 * 26.2 -- {@code IconCapture}'s own javadoc already records the trap -- and reaching for it is
 * exactly how a version conditional gets into a codebase that has none.
 *
 * <p>If nothing ever calls {@code onScreenTitle}, every detector here still works from chat: Croesus
 * simply never arms (its chests are claimed and captioned as in-run Catacombs chests instead), the
 * Experimentation caption loses its game name, and the two chest sources lose only the
 * roll-on-opening behaviour that their shipped policy does not use anyway. Nothing silently stops
 * firing, which is the property that was designed for rather than hoped for.
 */
public final class ContainerDetectors {

    private ContainerDetectors() {
    }

    /**
     * Every container detector, ready to register in the returned order.
     *
     * @param localPlayerName supplies the client's own username; may return null or blank while the
     *                        client is still connecting, which shuts the ownership check rather than
     *                        opening it
     */
    public static List<SourceDetector> all(Supplier<String> localPlayerName) {
        Objects.requireNonNull(localPlayerName, "localPlayerName");
        return List.of(
                // Overlapping pair: Croesus claims only while its run list is fresh, so it must be
                // offered the line first or the Catacombs chest below would take every one of them.
                new CroesusChestDetector(localPlayerName),
                new KuudraRewardChestDetector(localPlayerName),
                new DungeonRewardChestDetector(localPlayerName),

                // Independent of each other and of the above.
                new PowderChestDetector(),
                new StructureLootChestDetector(),
                new CrystalNucleusDetector(),
                new MetalDetectorScavengeDetector(),
                new FossilExcavationDetector(),
                new ExperimentsRewardsDetector());
    }

    /**
     * Registers {@link #all(Supplier)} on {@code bus}, preserving the order.
     *
     * <p>Provided so a caller cannot accidentally reorder them by iterating a set or a map.
     */
    public static void registerAll(LootEventBus bus, Supplier<String> localPlayerName) {
        Objects.requireNonNull(bus, "bus");
        for (SourceDetector detector : all(localPlayerName)) {
            bus.register(detector);
        }
    }
}
