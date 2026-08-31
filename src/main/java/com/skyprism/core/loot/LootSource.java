package com.skyprism.core.loot;

import java.util.Locale;
import java.util.Optional;

/**
 * One constant per chance-based activity in SkyBlock that can spin the machine.
 *
 * <p>The enum carries no data. Everything about a source -- its caption, its default {@link
 * RollPolicy}, what gate it sits behind, which chat substrings can possibly precede it and which
 * drops deserve the celebration -- lives in {@link LootSourceRegistry}, so there is exactly one
 * place to read or change it and no risk of an enum constant and a table drifting apart.
 *
 * <h2>Why there are this many constants and only a handful of detectors</h2>
 * <p>A source is a <em>context</em>, not a listener. Hypixel prints one near-universal rare-drop
 * banner across almost all content, one near-universal boss-down banner covering Arachne, the seven
 * dragons, the Endstone Protector and the five Crimson minibosses, and one container reward block
 * shared by six different openables. What differs per source is which activity produced the line --
 * which is what the widget captions and what the player switches off. So a dozen detectors can serve
 * every constant below, and the size of this enum measures how much the player can configure, not
 * how much code runs per chat line.
 *
 * <h2>Names that look duplicated but are not</h2>
 * <p>{@link #DUNGEON_BOSS} and {@link #DUNGEON_RUN_COMPLETE} are the same run seen a few lines
 * apart, and exactly one of the pair ships armed. {@link #DUNGEON_REWARD_CHEST} and {@link
 * #CROESUS_CHEST} open an identical GUI, but one is a chest at the end of a run and the other is a
 * backlog of fifteen cleared in ninety seconds at the hub, which is a completely different pacing
 * problem and therefore a separate switch. {@link #FISHING_TROPHY_FISH_RARE} and {@link
 * #FISHING_TROPHY_FISH} are one regex whose tier group splits 2.2% of catches from the other 97.8%.
 */
public enum LootSource {

    // ---------------------------------------------------------------- Diana (the shipped path)

    /** Mythological Ritual: a Griffin Burrow creature defeated, or a treasure dug straight out. */
    DIANA_MYTHOLOGICAL,

    // ---------------------------------------------------------------- combat

    /** A slayer quest boss slain. */
    SLAYER_BOSS,
    /** A slayer miniboss during the grind phase. */
    SLAYER_MINIBOSS,
    /** The catch-all: any mob anywhere whose drop carried the server rare-drop banner. */
    MOB_RARE_DROP,
    /** Any PET DROP banner, kept separate so it can be captioned and coloured as a pet. */
    PET_DROP,
    /** A Catacombs boss defeated at the end of a run. */
    DUNGEON_BOSS,
    /** The Catacombs end-of-run summary block; the same run as {@link #DUNGEON_BOSS}. */
    DUNGEON_RUN_COMPLETE,
    /** Kuudra downed. */
    KUUDRA_COMPLETE,
    /** Any of the seven Ender Dragon types downed in the Dragon Nest. */
    ENDER_DRAGON,
    /** The Endstone Protector downed, after a hundred zealot kills filled the summon. */
    ENDSTONE_PROTECTOR,
    /** Bladesoul, Mage Outlaw, Barbarian Duke X, Ashfang or the Magma Boss downed. */
    CRIMSON_MINIBOSS,
    /** A Vanquisher; only its spawn broadcast is verified, so the drop banner carries it. */
    VANQUISHER,
    /** Arachne downed in the Spider Den. */
    ARACHNE,
    /** The Broodmother; no kill line is verified, so the drop banner carries it. */
    BROODMOTHER,
    /** A ghost in the Mist, whose only signal is the drop banner itself. */
    GHOST_MIST,
    /** The bonus half of a draconic sacrifice -- the BONUS LOOT line, never the SACRIFICE line. */
    DRACONIC_SACRIFICE,
    /** An Ender Node broken in The End. */
    ENDER_NODE,
    /** A Reindrake summoned from the depths during the Season of Jerry. */
    REINDRAKE,
    /** A Primal Fear summoned during the Great Spook. */
    PRIMAL_FEAR,
    /** The Headless Horseman; no chat trigger is verified in any reference mod. */
    HEADLESS_HORSEMAN,
    /** Bacte, the Leech Supreme or the Sun Gecko in The Rift; no kill line is verified. */
    RIFT_BOSS,
    /** A Trevor the Trapper hunt completed on the Farming Islands. */
    TREVOR_TRAPPER,
    /** An attribute shard caught, charmed, fused or shared from ordinary combat. */
    COMBAT_SHARD,

    // ---------------------------------------------------------------- containers and GUIs

    /** A Catacombs reward chest opened at the end of a run. */
    DUNGEON_REWARD_CHEST,
    /** A Kuudra Free or Paid chest. */
    KUUDRA_REWARD_CHEST,
    /** A backlog of chests claimed from Croesus in the Dungeon Hub. */
    CROESUS_CHEST,
    /** A Crystal Hollows treasure chest, uncovered while mining and lockpicked. */
    POWDER_CHEST,
    /** A fixed structure loot chest: Jungle Temple, Mines of Divan, Fairy Grotto, Mineshaft. */
    LOOT_CHEST,
    /** A full Crystal Nucleus run, all five crystals placed. */
    CRYSTAL_NUCLEUS_RUN,
    /** A Metal Detector dig in the Mines of Divan. */
    METAL_DETECTOR_SCAVENGE,
    /** A Lapis, Tungsten, Umber or Vanguard corpse looted in a Glacite Mineshaft. */
    GLACITE_CORPSE,
    /** A Fossil Excavator excavation completed. */
    FOSSIL_EXCAVATION,
    /** A Suspicious Scrap found while mining Glacite -- the currency an excavation gambles. */
    SUSPICIOUS_SCRAP,
    /** A Glacite Mineshaft portal found. Rare, but it produces no drop for a reel to land on. */
    GLACITE_MINESHAFT_PORTAL,
    /** A Superpairs, Chronomatron or Ultrasequencer reward claimed at the Experimentation Table. */
    EXPERIMENTS_REWARDS,
    /** A Season of Jerry gift opened. */
    WINTER_GIFT,
    /** A Frozen Treasure found while mining ice in the Glacial Cave. */
    FROZEN_TREASURE,
    /** A Trick or Treat or Party chest; only the island-wide appearance broadcast is verified. */
    SPOOKY_CHEST,

    // ---------------------------------------------------------------- gathering

    /** One of the sea creatures the corpus flags rare -- Jawbus, Thunder, Ragnarok and friends. */
    FISHING_RARE_SEA_CREATURE,
    /** An ordinary sea creature. The highest-frequency event in the entire feature. */
    FISHING_SEA_CREATURE,
    /** A Gold or Diamond trophy fish -- 2% and 0.2% of trophy catches. */
    FISHING_TROPHY_FISH_RARE,
    /** A Bronze or Silver trophy fish. */
    FISHING_TROPHY_FISH,
    /** The Golden Fish surfacing after eight to twelve minutes of lava fishing. */
    FISHING_GOLDEN_FISH,
    /** A GOOD, GREAT or OUTSTANDING treasure catch. */
    FISHING_TREASURE,
    /** The BONUS GIFT sub-block of a Galatea tree gift, whose lines print their own odds. */
    FORAGING_TREE_BONUS_GIFT,
    /** The ordinary contents of a Galatea tree gift. */
    FORAGING_TREE_GIFT,
    /** A phantom falling out of a Galatea tree. */
    FORAGING_TREE_PHANTOM,
    /** A VERY RARE CROP in the Garden. */
    GARDEN_VERY_RARE_CROP,
    /** A RARE CROP in the Garden. */
    GARDEN_RARE_CROP,
    /** A pest rare or pet drop, which Hypixel has already filtered for us. */
    GARDEN_PEST_DROP,
    /** A Crop Fever proc in the Garden. */
    GARDEN_CROP_FEVER,
    /** A Legendary, Mythic or Special garden visitor served on the barn plot. */
    GARDEN_VISITOR_RARE,
    /** A Pristine perk proc while breaking gemstone blocks. */
    MINING_PRISTINE_GEMSTONE,
    /** A Compact enchantment proc while mining stone or ice. */
    MINING_COMPACT,
    /** A Golden or Diamond Goblin spawning in the Dwarven Mines. */
    MINING_GOBLIN_RAID,

    // ---------------------------------------------------------------- events and seasonal

    /** A Hoppity Hunt chocolate meal egg found. */
    HOPPITY_MEAL_EGG,
    /** A Hoppity rabbit revealed, with its rarity printed in the line. */
    HOPPITY_RABBIT,
    /** A stray rabbit clicked in the Chocolate Factory. */
    CHOCOLATE_FACTORY_STRAY,
    /** A Year of the Pig shiny orb extracted. */
    YEAR_OF_THE_PIG_ORB,
    /** A Year of the Witch stew claimed. */
    YEAR_OF_THE_WITCH_STEW,
    /** Ubik Split or Steal in The Rift -- a literal gamble on a multi-hour cooldown. */
    RIFT_UBIK_SPLIT_OR_STEAL,
    /** A Motes orb picked up in The Rift. Routine currency, not a rare drop. */
    RIFT_MOTES_ORB,
    /** Vermin vacuumed in the Rift West Village. Not chance based; enumerated for completeness. */
    RIFT_VERMIN_VACUUM,
    /** Carnival fruit digging. A solvable board paying tokens, so barely a lottery at all. */
    CARNIVAL_FRUIT_DIGGING;

    /** A stable, lower-case identifier for config files and commands, e.g. {@code slayer_boss}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks a source up by {@link #id()} or by enum name, case-insensitively.
     *
     * @param id the identifier; null or unknown yields empty rather than throwing, because this is
     *           fed straight from a config file the player can edit by hand
     * @return the source, or empty
     */
    public static Optional<LootSource> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (LootSource source : values()) {
            if (source.name().equals(key)) {
                return Optional.of(source);
            }
        }
        return Optional.empty();
    }
}
