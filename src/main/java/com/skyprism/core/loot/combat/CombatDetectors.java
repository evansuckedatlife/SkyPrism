package com.skyprism.core.loot.combat;

import com.skyprism.core.loot.LootEventBus;
import com.skyprism.core.loot.SourceDetector;

import java.util.List;
import java.util.Objects;

/**
 * Builds the combat detectors in the one order that is correct, so the caller cannot get it wrong.
 *
 * <h2>Registration order is behaviour, not style</h2>
 * <p>{@link LootEventBus#onChat(String, long)} returns the <em>first</em> event any open detector
 * produces, because sources genuinely overlap and one line must never spin the machine twice. So
 * order decides which source claims a shared line, exactly the way the shipped banner parser tries
 * the Diana treasure shapes before the generic banner. The rule is <b>specific before general</b>,
 * and the general one in this area is {@link MobRareDropDetector}: an ordinary rare drop has no kill
 * line, so its trigger is a banner every other combat source can also emit. It goes last.
 *
 * <h2>The shared state objects</h2>
 * <p>{@link SlayerQuestState} is shared by the two slayer detectors and is what turns their gate
 * from "in SkyBlock" into "mid-quest", which is shut for every player who is not on a slayer run, on
 * every island. {@link DungeonRunState} is shared by the dungeon pair so the disarmed one still
 * feeds the armed one its floor. Both are handed out by {@link Wiring} so the Minecraft-side
 * sidebar reader has something to report to without reaching inside a detector.
 *
 * <h2>What this does not do</h2>
 * <p>It does not consult the player's config. Every source here ships with a researched default
 * policy in {@code LootSourceRegistry}, and two of them -- {@link SlayerMinibossDetector} and
 * {@link DungeonRunCompleteDetector} -- ship on NEVER. They are still registered, because a
 * registered detector on a NEVER policy costs one substring test and lets the config screen switch
 * it on without restarting anything, and because {@code DungeonRunCompleteDetector} earns its place
 * purely by recording the floor. Deciding whether a produced event may actually roll is the roll's
 * job, not the bus's.
 */
public final class CombatDetectors {

    private CombatDetectors() {
    }

    /**
     * Every combat detector, in registration order, plus the state objects the client must feed.
     *
     * @param detectors  the detectors, ready to hand to {@link LootEventBus#register} in this order
     * @param slayerQuest the sidebar-backed slayer quest state; see {@link SlayerQuestState}
     * @param dungeonRun  the shared Catacombs run state; see {@link DungeonRunState}
     */
    public record Wiring(List<SourceDetector> detectors,
                         SlayerQuestState slayerQuest,
                         DungeonRunState dungeonRun) {

        public Wiring {
            detectors = List.copyOf(detectors);
        }

        /** Registers every detector on {@code bus}, in order. */
        public void registerAll(LootEventBus bus) {
            Objects.requireNonNull(bus, "bus");
            for (SourceDetector detector : detectors) {
                bus.register(detector);
            }
        }
    }

    /** Builds the whole set with fresh state objects. */
    public static Wiring create() {
        SlayerQuestState slayerQuest = new SlayerQuestState();
        DungeonRunState dungeonRun = new DungeonRunState();
        List<SourceDetector> detectors = List.of(
                // Exact literals first: nothing else can match them, and they are the cheapest.
                new SlayerBossDetector(slayerQuest),
                new SlayerMinibossDetector(slayerQuest),

                // The summary header before the defeat line, so the floor is known when the boss
                // is captioned. Hypixel prints them in that order too.
                new DungeonRunCompleteDetector(dungeonRun),
                new DungeonBossDetector(dungeonRun),

                // The boss-down family. Mutually exclusive twice over -- by closed name table and
                // by island gate -- so their relative order is arbitrary and safe.
                new KuudraDetector(),
                new ArachneDetector(),
                new EnderDragonDetector(),
                new EndstoneProtectorDetector(),
                new CrimsonMinibossDetector(),

                // Pets before the generic banner: a pet drop is a rare drop with a better caption.
                new PetDropDetector(),

                // The catch-all, last by construction.
                new MobRareDropDetector());
        return new Wiring(detectors, slayerQuest, dungeonRun);
    }
}
