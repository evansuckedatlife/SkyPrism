package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.SourceDetector;

import java.util.List;
import java.util.Objects;

/**
 * Every gathering detector, in the order they must be registered.
 *
 * <h2>Order is the only thing this class adds, and it is load-bearing</h2>
 * <p>{@link LootEventBus} stops at the first source that claims a line, so registration order
 * decides who wins when two sources can both match. Three pairs in this package genuinely overlap
 * on the raw text, and the order below resolves each of them the same way the shipped banner parser
 * resolves its own: <b>specific before general</b>.
 * <ul>
 *   <li>A "VERY RARE CROP!" line contains "RARE CROP!", so both crop detectors are offered it. The
 *       ordinary one refuses the VERY prefix outright, so the order is belt and braces rather than
 *       load-bearing -- but the belt is cheap and the braces are tested.</li>
 *   <li>A pest drop is a "RARE DROP!" line, which the general MOB_RARE_DROP source (elsewhere) also
 *       claims. Registering the gathering detectors before that one keeps a Garden drop captioned
 *       as a pest drop.</li>
 *   <li>A trapper drop is likewise a rare mob drop; see {@link TrapperDetector} for the trade-off
 *       that ordering buys and what it costs.</li>
 * </ul>
 *
 * <p>The two sea creature detectors are last on purpose. They declare no chat markers, so having
 * either of them open switches the bus's whole pre-filter off; putting them at the end keeps every
 * cheaper detector ahead of the one that has to look at every line.
 *
 * <p>Registering a detector does not arm it. Each source's shipped {@link
 * com.skyprism.core.loot.RollPolicy} decides whether an event it produces can spin the machine, and
 * more than half of these ship on NEVER for the cadence reasons each detector's javadoc records.
 * They are registered anyway so the config screen and {@code /skyprism} can show a player what
 * exists and what turning it on would mean.
 */
public final class GatheringDetectors {

    private GatheringDetectors() {
    }

    /**
     * Every gathering detector, in registration order.
     *
     * <p>A fresh list of fresh instances each call: two of these hold per-session state (the
     * trapper's last completed hunt), so sharing one instance across two buses would share that
     * state too.
     */
    public static List<SourceDetector> all() {
        return List.of(
                // Garden -- the crop banners first, so the narrower VERY tier is tried before the
                // wider one, and both before anything that matches a bare DROP banner.
                RareCropDetector.veryRare(),
                RareCropDetector.rare(),
                new PestDropDetector(),
                new CropFeverDetector(),
                new GardenVisitorDetector(),

                // Foraging -- Galatea tree gifts, which the user named explicitly.
                TreeGiftDetector.bonus(),
                TreeGiftDetector.gift(),
                new TreePhantomDetector(),

                // Mining procs.
                new GoblinRaidDetector(),
                new PristineGemstoneDetector(),
                new CompactProcDetector(),

                // Trapper -- before any general rare-drop source; see TrapperDetector.
                new TrapperDetector(),

                // Fishing -- the banner-shaped ones first.
                TrophyFishDetector.rare(),
                TrophyFishDetector.ordinary(),
                new GoldenFishDetector(),
                new TreasureCatchDetector(),

                // Markerless, and therefore last: having either of these open unfilters the bus.
                SeaCreatureDetector.rare(),
                SeaCreatureDetector.ordinary());
    }

    /** Registers {@link #all()} on {@code bus}, in order. */
    public static void registerAll(LootEventBus bus) {
        Objects.requireNonNull(bus, "bus");
        for (SourceDetector detector : all()) {
            bus.register(detector);
        }
    }
}
