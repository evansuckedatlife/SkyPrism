package com.skyprism.mc.command;

import com.skyprism.core.diana.JackpotRule;
import com.skyprism.core.diana.LootDrop;
import com.skyprism.core.diana.LootParser;
import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.LootSourceInfo;
import com.skyprism.core.loot.LootSourceRegistry;
import com.skyprism.core.util.TextClean;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plausible Mythological Ritual loot, and the raw Hypixel chat lines that would have
 * announced it.
 *
 * <p><b>Why the tables are per-creature.</b> {@code /skyprism simulate manticore} handing
 * out a Minos Relic would be a demo of the slot machine, not of the feature: the whole
 * appeal of the reels is that they lock onto what <em>that</em> creature actually drops.
 * The tables below follow the real drop pools, so a simulated Inquisitor can produce a
 * Chimera and a simulated Minotaur cannot.</p>
 *
 * <p><b>Why a jackpot appears sometimes and not always.</b> The flourish is the part hardest
 * to get right and hardest to see, so a developer running the command a handful of times
 * has to be able to reach it - but a simulator that jackpots every time is useless for
 * judging whether the ordinary case reads well. Rare creatures roll a jackpot roughly one
 * time in three, common ones one in eight, and the jackpot names are taken from
 * {@link JackpotRule#defaults()} so the flourish genuinely fires rather than merely looking
 * like it should.</p>
 *
 * <p>The randomness is a plain {@link Random} with no seed, deliberately: this is a
 * developer toy, and a repeatable sequence would be worse - you would see the same three
 * demonstrations forever.</p>
 *
 * <p><b>Nothing here is a translation key, and nothing here may become one.</b> The item
 * names and chat lines below are reproductions of what Hypixel sends; the whole point of the
 * simulator is that {@link LootParser} sees text it cannot tell from the real thing.
 * Translating them would silently stop exercising the parsers, which is the one job this
 * class has.</p>
 */
public final class SimulatedLoot {

    private SimulatedLoot() {
    }

    private static final Random RANDOM = new Random();

    /**
     * Common rewards shared by the whole ritual pool. Every creature can produce these, and
     * they are what the reels show on an unremarkable kill.
     */
    private static final String[] COMMON = {
        "Ancient Claw",
        "Enchanted Ancient Claw",
        "Griffin Feather",
        "Enchanted Gold Ingot",
        "Enchanted Iron Ingot",
        "Enchanted Redstone Block",
        "Enchanted Diamond",
        "Griffin Feather",
    };

    /** Jackpot-tier rewards, keyed to the creature that can actually drop them. */
    private static final String[] JACKPOT_INQUISITOR = {
        "Chimera I",
        "Daedalus Stick",
        "Crown of Greed",
        "Washed-up Souvenir",
        "Dwarf Turtle Shelmet",
        "Antique Remedies",
    };

    private static final String[] JACKPOT_KING_MINOS = {
        "Minos Relic",
        "Crown of Greed",
        "Dwarf Turtle Shelmet",
        "Washed-up Souvenir",
    };

    private static final String[] JACKPOT_MANTICORE = {
        "Manti-core",
        "Fateful Stinger",
        "Shimmering Wool",
        "Crochet Tiger Plushie",
    };

    private static final String[] JACKPOT_GENERIC = {
        "Crown of Greed",
        "Washed-up Souvenir",
        "Antique Remedies",
        "Dwarf Turtle Shelmet",
        "Cretan Urn",
        "Braided Griffin Feather",
    };

    /**
     * Filler item names for a source that has no jackpot list of its own.
     *
     * <p>Deliberately generic rather than plausible-per-source. Inventing an item name for a
     * content area nobody verified is exactly how a demonstration starts teaching people things
     * that are not true, and the registry already carries the names that <em>were</em> verified.
     * Where it carries none, the honest filler is a word that is obviously filler.</p>
     */
    private static final String[] GENERIC_FILLER = {
        "Enchanted Redstone Block",
        "Enchanted Diamond",
        "Enchanted Gold Ingot",
        "Enchanted Iron Ingot",
    };

    /**
     * A plausible payout for any source in the game, drawn from what the registry actually knows.
     *
     * <p>Every jackpot name comes out of {@link LootSourceRegistry}, which is the same table the
     * {@link com.skyprism.core.loot.RollPolicy#ON_JACKPOT_ITEM_ONLY} decision reads. That is worth
     * more than realism: it means {@code /skyprism simulate} shows a celebration exactly when the
     * live feature would have, so a player using the command to decide whether to arm a source is
     * being shown the truth rather than a dramatisation. Sources whose jackpot list is empty --
     * the ones nobody could verify a loot table for -- get filler and no celebration, which is
     * also the truth about them.</p>
     *
     * <p>Coins are offered only where they are a real payout. A dungeon chest pays coins; a Tree
     * Gift does not, and a reel showing "40,000 Coins" under a Galatea caption would be a small
     * lie that a player would reasonably believe.</p>
     *
     * @param source the source being demonstrated, never null
     * @return one to three drops, jackpot-bearing roughly a third of the time when the source has
     *         a jackpot list at all
     */
    public static List<LootDrop> rollFor(LootSource source) {
        LootSourceInfo info = LootSourceRegistry.info(source);
        List<LootDrop> drops = new ArrayList<>(3);

        if (paysCoins(source) && RANDOM.nextInt(3) != 0) {
            int coins = (1 + RANDOM.nextInt(40)) * 1_000;
            drops.add(new LootDrop(LootParser.COINS_ITEM_NAME, "6", coins, false));
        }

        drops.add(new LootDrop(pick(GENERIC_FILLER), "a", 1 + RANDOM.nextInt(3), false));

        List<String> jackpots = List.copyOf(info.jackpotItems());
        if (!jackpots.isEmpty() && RANDOM.nextInt(3) == 0) {
            drops.add(new LootDrop(jackpots.get(RANDOM.nextInt(jackpots.size())), "5", 1, true));
        }
        return List.copyOf(drops);
    }

    /**
     * Whether a coin line belongs in this source's simulated payout.
     *
     * <p>Kept to the handful of places SkyBlock genuinely hands out coins as loot rather than as
     * a sale: burrow treasure, dungeon and Kuudra chests, winter gifts, the ghost's bag of cash,
     * fishing treasure catches and the Rift's motes.</p>
     */
    private static boolean paysCoins(LootSource source) {
        return switch (source) {
            case DIANA_MYTHOLOGICAL, DUNGEON_REWARD_CHEST, KUUDRA_REWARD_CHEST, CROESUS_CHEST,
                 WINTER_GIFT, GHOST_MIST, FISHING_TREASURE, RIFT_MOTES_ORB,
                 YEAR_OF_THE_PIG_ORB, CHOCOLATE_FACTORY_STRAY -> true;
            default -> false;
        };
    }

    /**
     * Rolls a believable set of drops for one Mythological Ritual kill.
     *
     * <p>Kept separate from {@link #rollFor(LootSource)} rather than folded into it. Diana is the
     * one source whose loot pool differs per <em>subject</em> -- an Inquisitor and a Manticore drop
     * different things -- and the whole appeal of these reels is that they lock onto what that
     * creature actually drops. The general version keys on the source alone, which is right
     * everywhere else and would be a regression here.</p>
     *
     * @param creature the creature that died
     * @return one to three drops, occasionally including a jackpot item, never empty
     */
    public static List<LootDrop> rollFor(MythologicalCreature creature) {
        List<LootDrop> drops = new ArrayList<>(3);

        // Coins are on nearly every kill and give the reels a non-item symbol to show.
        if (RANDOM.nextInt(3) != 0) {
            int coins = (1 + RANDOM.nextInt(40)) * 1_000;
            drops.add(new LootDrop(LootParser.COINS_ITEM_NAME, "6", coins, false));
        }

        drops.add(new LootDrop(pick(COMMON), "a", 1 + RANDOM.nextInt(3), false));

        boolean rare = creature != null && creature.rare();
        int jackpotOdds = rare ? 3 : 8;
        if (RANDOM.nextInt(jackpotOdds) == 0) {
            drops.add(new LootDrop(pick(jackpotPool(creature)), "5", 1, true));
        } else if (RANDOM.nextInt(3) == 0) {
            drops.add(new LootDrop(pick(COMMON), "a", 1, false));
        }

        return List.copyOf(drops);
    }

    private static String[] jackpotPool(MythologicalCreature creature) {
        if (creature == null) {
            return JACKPOT_GENERIC;
        }
        return switch (creature) {
            case MINOS_INQUISITOR -> JACKPOT_INQUISITOR;
            case KING_MINOS -> JACKPOT_KING_MINOS;
            case MANTICORE -> JACKPOT_MANTICORE;
            default -> JACKPOT_GENERIC;
        };
    }

    private static String pick(String[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
    }

    // ======================================================================
    //  Raw chat lines
    // ======================================================================

    /** Hypixel's rotating set of spawn interjections, as accepted by {@code DianaPatterns}. */
    private static final String[] INTERJECTIONS = {
        "Oh", "Uh oh", "Yikes", "Oi", "Good Grief", "Danger", "Woah",
    };

    /**
     * Builds the exact line Hypixel sends when a ritual creature is dug up.
     *
     * <p>The shape is dictated by {@code DianaPatterns.SPAWN}, which is anchored, so this is
     * not a paraphrase - if the format drifts, the simulator stops matching at the same
     * moment the live parser does, which is the correct coupling. That is why this is built
     * from the creature's own {@code displayName()} and {@code colorCode()} rather than a
     * hand-written table.</p>
     *
     * @param creature the creature that spawned
     * @return the raw line, section signs intact
     */
    public static String spawnLine(MythologicalCreature creature) {
        char s = TextClean.SECTION;
        return s + "c" + s + "l" + pick(INTERJECTIONS) + "! "
                + s + "r" + s + "eYou dug out a "
                + s + "r" + s + creature.colorCode() + creature.displayName()
                + s + "r" + s + "e!";
    }

    /**
     * Builds the line that would have announced one drop.
     *
     * <p>Coins take the "Wow!" treasure shape and items take the "RARE DROP!" banner shape,
     * because those are the two forms {@code LootParser} recognises. A simulated line that
     * the real parser cannot read would make {@code /skyprism replay} of a simulated capture
     * silently produce nothing, which is exactly the sort of hidden gap a developer tool
     * must not have.</p>
     *
     * @param drop the reward
     * @return the raw line, section signs intact
     */
    public static String dropLine(LootDrop drop) {
        char s = TextClean.SECTION;
        String color = drop.colorCode() == null || drop.colorCode().isEmpty()
                ? "f" : drop.colorCode().substring(0, 1);

        if (LootParser.COINS_ITEM_NAME.equals(drop.itemName())) {
            return s + "6" + s + "lWow! " + s + "r" + s + "eYou dug out "
                    + s + "r" + s + color + group(drop.count()) + " coins"
                    + s + "r" + s + "e!";
        }

        String name = drop.count() > 1 ? drop.count() + "x " + drop.itemName() : drop.itemName();
        // Both banners are in LootParser's accepted set; the louder one on a rare drop is
        // what Hypixel itself uses and makes a simulated jackpot obvious in the chat log.
        String banner = drop.rare() ? "CRAZY RARE DROP!" : "RARE DROP!";
        return s + "6" + s + "l" + banner + " " + s + "r" + s + color + name
                + s + "r" + s + "7 (+" + (15 + RANDOM.nextInt(60)) + "% Magic Find)";
    }

    /** Thousands separators, because Hypixel writes coin amounts with them. */
    private static String group(int amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount);
    }
}
