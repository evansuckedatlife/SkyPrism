package com.skyprism.core.config;

import com.skyprism.core.loot.LootSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The drawers the settings screen files {@link LootSource} into.
 *
 * <p><b>This is a user-interface concept, not a detection one.</b> {@code LootSource} has sixty-odd
 * constants and every one of them deserves a switch, but sixty-odd switches on one page is a screen
 * nobody will ever scroll to the bottom of. Grouping them by the part of the game they come from is
 * the only division a player already carries in their head: someone who has decided the fishing
 * rolls are too chatty knows to look under Gathering, and does not care that a sea creature and a
 * trophy fish are detected by two completely different mechanisms.
 *
 * <p><b>Why the mapping lives here rather than in {@code LootSourceRegistry}.</b> The registry is
 * the detection side's table -- gates, markers, captured sample lines, the researched default
 * policy -- and it is owned by the code that reads chat. A category is a decision about layout: it
 * changes when the screen changes rather than when Hypixel changes, and it has no business making
 * the detection table larger. Keeping it in {@code core.config} also means the screen can enumerate
 * the drawers without importing anything from the detection side beyond the enum itself.
 *
 * <p><b>{@link #DIANA} is deliberately a category of one.</b> The Mythological Ritual path is the
 * only one verified on the live server, it keeps its own settings group and its own screen tab, and
 * nothing in the general machinery is allowed to switch it off behind the player's back. Giving it
 * a category of its own is what lets every "for each category" loop in the screen and the sanitiser
 * skip it by name rather than by a special case buried in a condition.
 *
 * <p><b>{@link #MISC} should always be empty.</b> It exists so that a {@code LootSource} constant
 * added by someone who did not know this file existed still gets a switch and still appears in the
 * screen, instead of silently becoming unconfigurable. {@code LootConfigTest} asserts it is empty,
 * so the safety net announces itself rather than quietly holding.
 */
public enum LootSourceCategory {

    /** The Mythological Ritual. A category of one, with its own tab and its own master switch. */
    DIANA,

    /** Kills: slayers, bosses, minibosses, and the server-wide rare drop banner. */
    COMBAT,

    /** Things you open: reward chests, corpses, excavations, gifts, experiment tables. */
    CONTAINERS,

    /** Things you gather: fishing, foraging, the Garden, mining procs. */
    GATHERING,

    /** Seasonal and one-off events: Hoppity, the Chocolate Factory, the Rift, the Carnival. */
    EVENTS,

    /**
     * Anything this file has not been told about yet.
     *
     * <p>Not a home for sources that do not fit -- a home for sources nobody has filed. If a
     * constant lands here it is a bug in this class, and the screen renders the drawer anyway so
     * the setting is at least reachable while that bug is outstanding.
     */
    MISC;

    /** A stable, lower-case identifier for config files, commands and translation keys. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this drawer is offered by the general per-category controls.
     *
     * <p>{@link #DIANA} is not: its switch is {@code diana.enabled}, on its own tab, and a
     * "disable this whole category" button reaching into it would be a way to turn the shipped
     * feature off from a screen that never mentions it.
     *
     * @return true for every category the loot screen builds a tab for
     */
    public boolean configurable() {
        return this != DIANA;
    }

    /**
     * Which drawer a source belongs in.
     *
     * @param source any source; null yields {@link #MISC} rather than throwing, because this is
     *               called from screen-building code where a thrown exception would cost the
     *               player the whole settings screen
     * @return the category, or {@link #MISC} for a source nobody has filed
     */
    public static LootSourceCategory of(LootSource source) {
        if (source == null) {
            return MISC;
        }
        LootSourceCategory found = ASSIGNMENTS.get(source);
        return found == null ? MISC : found;
    }

    /**
     * Every source in a drawer, in {@link LootSource} declaration order.
     *
     * <p>Declaration order rather than alphabetical: the enum is already written in a sensible
     * reading order within each section -- slayers before dungeon bosses before the seasonal
     * oddities -- and re-sorting by caption would scatter the pairs that only make sense side by
     * side, such as the two trophy fish tiers.
     *
     * @param category the drawer; null yields an empty list
     * @return an unmodifiable list, possibly empty
     */
    public static List<LootSource> sources(LootSourceCategory category) {
        if (category == null) {
            return List.of();
        }
        return BY_CATEGORY.getOrDefault(category, List.of());
    }

    /**
     * Looks a category up by {@link #id()} or enum name, case-insensitively.
     *
     * @param id the identifier; null or unknown yields empty, because this is fed from a config
     *           file the player can edit by hand
     * @return the category, or empty
     */
    public static Optional<LootSourceCategory> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String key = id.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (LootSourceCategory category : values()) {
            if (category.name().equals(key)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    /**
     * The filing itself.
     *
     * <p>Written out as one line per source rather than derived from the enum's ordinal ranges.
     * Ordinal ranges would be free today and wrong the first time somebody inserts a constant in
     * the middle of a section, and they would be wrong silently -- a Kuudra chest quietly filed
     * under Gathering is exactly the kind of mistake nobody notices until a player asks where a
     * setting went.
     */
    private static final Map<LootSource, LootSourceCategory> ASSIGNMENTS = assign();

    /** The inverse, precomputed once because the screen asks for it per tab, per open. */
    private static final Map<LootSourceCategory, List<LootSource>> BY_CATEGORY = invert();

    private static Map<LootSource, LootSourceCategory> assign() {
        Map<LootSource, LootSourceCategory> map = new EnumMap<>(LootSource.class);

        map.put(LootSource.DIANA_MYTHOLOGICAL, DIANA);

        map.put(LootSource.SLAYER_BOSS, COMBAT);
        map.put(LootSource.SLAYER_MINIBOSS, COMBAT);
        map.put(LootSource.MOB_RARE_DROP, COMBAT);
        map.put(LootSource.PET_DROP, COMBAT);
        map.put(LootSource.DUNGEON_BOSS, COMBAT);
        map.put(LootSource.DUNGEON_RUN_COMPLETE, COMBAT);
        map.put(LootSource.KUUDRA_COMPLETE, COMBAT);
        map.put(LootSource.ENDER_DRAGON, COMBAT);
        map.put(LootSource.ENDSTONE_PROTECTOR, COMBAT);
        map.put(LootSource.CRIMSON_MINIBOSS, COMBAT);
        map.put(LootSource.VANQUISHER, COMBAT);
        map.put(LootSource.ARACHNE, COMBAT);
        map.put(LootSource.BROODMOTHER, COMBAT);
        map.put(LootSource.GHOST_MIST, COMBAT);
        map.put(LootSource.DRACONIC_SACRIFICE, COMBAT);
        map.put(LootSource.ENDER_NODE, COMBAT);
        map.put(LootSource.REINDRAKE, COMBAT);
        map.put(LootSource.PRIMAL_FEAR, COMBAT);
        map.put(LootSource.HEADLESS_HORSEMAN, COMBAT);
        map.put(LootSource.RIFT_BOSS, COMBAT);
        map.put(LootSource.TREVOR_TRAPPER, COMBAT);
        map.put(LootSource.COMBAT_SHARD, COMBAT);

        map.put(LootSource.DUNGEON_REWARD_CHEST, CONTAINERS);
        map.put(LootSource.KUUDRA_REWARD_CHEST, CONTAINERS);
        map.put(LootSource.CROESUS_CHEST, CONTAINERS);
        map.put(LootSource.POWDER_CHEST, CONTAINERS);
        map.put(LootSource.LOOT_CHEST, CONTAINERS);
        map.put(LootSource.CRYSTAL_NUCLEUS_RUN, CONTAINERS);
        map.put(LootSource.METAL_DETECTOR_SCAVENGE, CONTAINERS);
        map.put(LootSource.GLACITE_CORPSE, CONTAINERS);
        map.put(LootSource.FOSSIL_EXCAVATION, CONTAINERS);
        map.put(LootSource.SUSPICIOUS_SCRAP, CONTAINERS);
        map.put(LootSource.GLACITE_MINESHAFT_PORTAL, CONTAINERS);
        map.put(LootSource.EXPERIMENTS_REWARDS, CONTAINERS);
        map.put(LootSource.WINTER_GIFT, CONTAINERS);
        map.put(LootSource.FROZEN_TREASURE, CONTAINERS);
        map.put(LootSource.SPOOKY_CHEST, CONTAINERS);

        map.put(LootSource.FISHING_RARE_SEA_CREATURE, GATHERING);
        map.put(LootSource.FISHING_SEA_CREATURE, GATHERING);
        map.put(LootSource.FISHING_TROPHY_FISH_RARE, GATHERING);
        map.put(LootSource.FISHING_TROPHY_FISH, GATHERING);
        map.put(LootSource.FISHING_GOLDEN_FISH, GATHERING);
        map.put(LootSource.FISHING_TREASURE, GATHERING);
        map.put(LootSource.FORAGING_TREE_BONUS_GIFT, GATHERING);
        map.put(LootSource.FORAGING_TREE_GIFT, GATHERING);
        map.put(LootSource.FORAGING_TREE_PHANTOM, GATHERING);
        map.put(LootSource.GARDEN_VERY_RARE_CROP, GATHERING);
        map.put(LootSource.GARDEN_RARE_CROP, GATHERING);
        map.put(LootSource.GARDEN_PEST_DROP, GATHERING);
        map.put(LootSource.GARDEN_CROP_FEVER, GATHERING);
        map.put(LootSource.GARDEN_VISITOR_RARE, GATHERING);
        map.put(LootSource.MINING_PRISTINE_GEMSTONE, GATHERING);
        map.put(LootSource.MINING_COMPACT, GATHERING);
        map.put(LootSource.MINING_GOBLIN_RAID, GATHERING);

        map.put(LootSource.HOPPITY_MEAL_EGG, EVENTS);
        map.put(LootSource.HOPPITY_RABBIT, EVENTS);
        map.put(LootSource.CHOCOLATE_FACTORY_STRAY, EVENTS);
        map.put(LootSource.YEAR_OF_THE_PIG_ORB, EVENTS);
        map.put(LootSource.YEAR_OF_THE_WITCH_STEW, EVENTS);
        map.put(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, EVENTS);
        map.put(LootSource.RIFT_MOTES_ORB, EVENTS);
        map.put(LootSource.RIFT_VERMIN_VACUUM, EVENTS);
        map.put(LootSource.CARNIVAL_FRUIT_DIGGING, EVENTS);

        return Collections.unmodifiableMap(map);
    }

    private static Map<LootSourceCategory, List<LootSource>> invert() {
        Map<LootSourceCategory, List<LootSource>> building =
                new EnumMap<>(LootSourceCategory.class);
        for (LootSourceCategory category : values()) {
            building.put(category, new ArrayList<>());
        }
        for (LootSource source : LootSource.values()) {
            building.get(of(source)).add(source);
        }
        Map<LootSourceCategory, List<LootSource>> frozen = new EnumMap<>(LootSourceCategory.class);
        for (Map.Entry<LootSourceCategory, List<LootSource>> entry : building.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }
}
