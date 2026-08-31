package com.skyprism.mc.hud;

import com.skyprism.core.loot.LootSource;

/**
 * The handful of families the widget's caption and the command tree group {@link LootSource} into.
 *
 * <h2>Why a presentation-layer enum rather than a field on {@code LootSource}</h2>
 *
 * <p>Nothing in {@code com.skyprism.core.loot} needs to know that a slayer boss and a Crimson
 * miniboss look alike; the detectors, the gates and the policies all treat every constant
 * identically and are better for it. What needs the grouping is the two places a human reads the
 * list -- the caption strip, which has one line and must hint at where a roll came from without
 * spending a second row on it, and {@code /skyprism sources}, which is unreadable as sixty-four
 * ungrouped rows. Both of those are presentation, so the grouping lives with them.</p>
 *
 * <h2>The colour is the whole hint</h2>
 *
 * <p>The brief's constraint on the caption is that its <em>geometry</em> does not move: same strip,
 * same single centred line, same height. That rules out printing the category as extra text, so the
 * category is carried by the caption's colour instead. A player who has seen two rolls learns that
 * gold is something they opened and aqua is something they mined without ever being told, and a
 * player who has not is no worse off than they were when every caption was the same colour.</p>
 *
 * <p>The codes are legacy formatting characters rather than RGB so the caption goes through
 * {@code SlotMachineHud}'s existing {@code rgbOf} table -- the same one Diana's creature colours
 * already use -- rather than introducing a second colour path that could drift from it.</p>
 *
 * <h2>Diana is deliberately absent from the colour rule</h2>
 *
 * <p>{@link LootSource#DIANA_MYTHOLOGICAL} maps to {@link #MYTHOLOGY}, but the widget never asks
 * this enum for its colour: a Diana roll carries a live {@code MythologicalCreature}, and that
 * creature's own colour code is what the shipped, live-verified caption draws. Keeping that branch
 * first in {@code drawCaption} is what makes "Diana must not regress" true of the pixels and not
 * only of the timings. The colour here is the fallback for a Diana <em>event</em> with no creature
 * attached, which is only reachable from {@code /skyprism simulate diana_mythological}.</p>
 */
public enum SourceCategory {

    /** The Mythological Ritual: the shipped feature, and the one path verified on the live server. */
    MYTHOLOGY("Mythology", 'd'),

    /** Slayers, bosses, minibosses, and the universal rare-drop and pet banners. */
    COMBAT("Combat", 'c'),

    /** Catacombs and Kuudra: runs, their bosses, and the chests they pay out into. */
    DUNGEONS("Dungeons", '5'),

    /** Anything the player opens: chests, reward blocks, corpses, excavations, experiments. */
    CONTAINERS("Containers", '6'),

    /** Mining: powder, gemstones, mineshafts, the Nucleus, and the Dwarven procs. */
    MINING("Mining", 'b'),

    /** Rod and lava rod: sea creatures, trophy fish, treasure catches. */
    FISHING("Fishing", '9'),

    /** Galatea's trees and the Garden: gifts, crops, pests, visitors. */
    FORAGING("Foraging", 'a'),

    /** Seasonal and calendar content: Hoppity, Jerry, the Spook, the Carnival, the centennials. */
    EVENTS("Events", 'd'),

    /** The Rift, which has its own economy and its own pace. */
    RIFT("Rift", '3');

    private final String displayName;
    private final String colorCode;

    SourceCategory(String displayName, char colorCode) {
        this.displayName = displayName;
        this.colorCode = String.valueOf(colorCode);
    }

    /** The family's name, as {@code /skyprism sources} groups by. */
    public String displayName() {
        return displayName;
    }

    /**
     * The legacy formatting code the caption is tinted with.
     *
     * @return a one-character string, always a valid {@code 0-9a-f} code, so
     *         {@code SlotMachineHud.rgbOf} can never fall through to its default because of this
     */
    public String colorCode() {
        return colorCode;
    }

    /**
     * The family a source belongs to.
     *
     * <p>An exhaustive {@code switch} over the enum with no {@code default}: adding a
     * {@link LootSource} constant should fail to compile here rather than quietly land in a
     * catch-all family, because a source in the wrong family is a caption in the wrong colour and
     * a row under the wrong heading, neither of which anyone would report as a bug.</p>
     *
     * @param source the source, never null
     * @return its family
     */
    public static SourceCategory of(LootSource source) {
        return switch (source) {
            case DIANA_MYTHOLOGICAL -> MYTHOLOGY;

            case SLAYER_BOSS, SLAYER_MINIBOSS, MOB_RARE_DROP, PET_DROP, ENDER_DRAGON,
                 ENDSTONE_PROTECTOR, CRIMSON_MINIBOSS, VANQUISHER, ARACHNE, BROODMOTHER,
                 GHOST_MIST, DRACONIC_SACRIFICE, ENDER_NODE, HEADLESS_HORSEMAN, RIFT_BOSS,
                 TREVOR_TRAPPER, COMBAT_SHARD -> COMBAT;

            case DUNGEON_BOSS, DUNGEON_RUN_COMPLETE, KUUDRA_COMPLETE, DUNGEON_REWARD_CHEST,
                 KUUDRA_REWARD_CHEST, CROESUS_CHEST -> DUNGEONS;

            case POWDER_CHEST, LOOT_CHEST, CRYSTAL_NUCLEUS_RUN, METAL_DETECTOR_SCAVENGE,
                 GLACITE_CORPSE, FOSSIL_EXCAVATION, SUSPICIOUS_SCRAP, GLACITE_MINESHAFT_PORTAL,
                 MINING_PRISTINE_GEMSTONE, MINING_COMPACT, MINING_GOBLIN_RAID -> MINING;

            case EXPERIMENTS_REWARDS -> CONTAINERS;

            case FISHING_RARE_SEA_CREATURE, FISHING_SEA_CREATURE, FISHING_TROPHY_FISH_RARE,
                 FISHING_TROPHY_FISH, FISHING_GOLDEN_FISH, FISHING_TREASURE -> FISHING;

            case FORAGING_TREE_BONUS_GIFT, FORAGING_TREE_GIFT, FORAGING_TREE_PHANTOM,
                 GARDEN_VERY_RARE_CROP, GARDEN_RARE_CROP, GARDEN_PEST_DROP, GARDEN_CROP_FEVER,
                 GARDEN_VISITOR_RARE -> FORAGING;

            case WINTER_GIFT, FROZEN_TREASURE, SPOOKY_CHEST, REINDRAKE, PRIMAL_FEAR,
                 HOPPITY_MEAL_EGG, HOPPITY_RABBIT, CHOCOLATE_FACTORY_STRAY, YEAR_OF_THE_PIG_ORB,
                 YEAR_OF_THE_WITCH_STEW, CARNIVAL_FRUIT_DIGGING -> EVENTS;

            case RIFT_UBIK_SPLIT_OR_STEAL, RIFT_MOTES_ORB, RIFT_VERMIN_VACUUM -> RIFT;
        };
    }
}
