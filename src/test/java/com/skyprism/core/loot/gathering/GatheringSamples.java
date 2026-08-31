package com.skyprism.core.loot.gathering;

import java.util.List;

/**
 * Real captured Hypixel lines, in one place, so every detector can be shown every other detector's
 * triggers.
 *
 * <p>Cross-source false positives are the likeliest bug in this whole feature: the sources overlap
 * on words ("RARE DROP!" against "RARE CROP!", "TREE GIFT" against "BONUS GIFT", a treasure catch
 * against a trophy fish), and a detector that claims a neighbour's line produces a widget captioned
 * with the wrong activity and a source that appears broken. Keeping the corpus here rather than
 * inside each test is what makes "no detector may claim a line that is not its own" a single
 * assertion instead of a convention.
 *
 * <p>Every line below was read out of a reference mod's own regression corpus. None was invented,
 * except where a comment says otherwise and says why.
 */
final class GatheringSamples {

    private GatheringSamples() {
    }

    // ---------------------------------------------------------------- fishing

    /** SkyHanni TrophyFishMessages.kt. The character after the gold code is U+E02A, the icon. */
    static final String TROPHY_GOLD =
            "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!";

    static final String TROPHY_SILVER =
            "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§6Golden Fish §r§7§lSILVER§r§f!";

    static final String TROPHY_BRONZE =
            "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§5Soul Fish §r§8§lBRONZE§r§f!";

    /**
     * Constructed by analogy, and the only line in this file that is: no captured DIAMOND line was
     * found in either reference mod. The tier word itself is verified -- the wiki documents Diamond
     * at 0.2% -- and the shape is one of the four captured GOLD/SILVER/BRONZE lines with the tier
     * swapped, which is what the pattern reads.
     */
    static final String TROPHY_DIAMOND_BY_ANALOGY =
            "§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§5Vanille §r§b§lDIAMOND§r§f!";


    /** SkyHanni GoldenFishTimer.kt (spawn). */
    static final String GOLDEN_FISH_SPAWN =
            "§9You spot a §r§6Golden Fish §r§9surface from beneath the lava!";

    /** SkyHanni GoldenFishTimer.kt (weak). Deliberately not a trigger; see GoldenFishDetector. */
    static final String GOLDEN_FISH_WEAK = "§9The §r§6Golden Fish §r§9is weak!";

    /** SkyHanni GoldenFishTimer.kt (despawn). */
    static final String GOLDEN_FISH_DESPAWN =
            "§9The §r§6Golden Fish §r§9swims back beneath the lava...";

    /** SkyHanni FishingProfitTracker.kt. The leading character is U+E025, the treasure icon. */
    static final String CATCH_COINS = " GOOD CATCH! You caught 36,064 Coins!";

    /** SkyHanni RareDropMessages.kt (raredrop.pet.fishedmessage). */
    static final String CATCH_PET =
            "§6 §r§6§lGREAT CATCH! §r§fYou caught a §r§7[Lvl 1] §r§aSquid§r§f!";

    /** SkyHanni AttributeShardsData.kt. */
    static final String CATCH_SHARD_STACKED = " GOOD CATCH! You caught Water Snake Shard x3!";

    static final String CATCH_SHARD_SINGLE = " GOOD CATCH! You caught a Water Snake Shard!";

    /** SkyHanni ChatFilter.kt. Note "found" and the full stop. */
    static final String CATCH_BAIT = "§6§lGOOD CATCH! §r§bYou found a §r§fFish Bait§r§b.";

    /**
     * The gold catch with its Trophy Fish Chance glyph replaced by a plain space, standing in for a
     * resource pack that overrides the glyph or a future Hypixel change that moves it -- which has
     * happened before, to Magic Find. It must match exactly as the real line does, which is the
     * whole point of the detectors' icon-agnostic prefixes.
     */
    static final String TROPHY_GOLD_ICON_REPLACED = TROPHY_GOLD.replace('', ' ');

    /** The coin catch with its Treasure Chance glyph replaced the same way, for the same reason. */
    static final String CATCH_COINS_ICON_REPLACED = CATCH_COINS.replace('', ' ');

    // ---------------------------------------------------------------- foraging

    /** SkyHanni ForagingTrackerLegacy.kt (foraging.treegift.header). */
    static final String TREE_GIFT_HEADER = "                                §r§9§lTREE GIFT";

    /** SkyHanni ForagingTrackerLegacy.kt (foraging.treegift.bonus-gift.separator). */
    static final String TREE_BONUS_HEADER = "                                §r§d§lBONUS GIFT";

    static final String TREE_CONTRIBUTION =
            "                 §r§7You helped cut §r§a100% §r§7of the §r§aFig Tree§r§7.";

    static final String TREE_BONUS_COMMON =
            "                          §r§7§r§aStretching Sticks §r§8(§r§a20%§r§8)";

    static final String TREE_BONUS_RARE =
            "                          §r§7§r§cTree the Fish §r§8(§r§a0.05%§r§8)";

    static final String TREE_BONUS_BOOK =
            "          §r§7§r§aEnchanted Book (§r§d§lFirst Impression I§r§a) §r§8(§r§a0.4%§r§8)";

    static final String TREE_PHANTOM = "§r§7A §r§dPhanpyre §r§7fell from the Tree!";

    static final String TREE_PHANTOM_DREADWING = "§r§7A §r§dDreadwing §r§7fell from the Tree!";

    // ---------------------------------------------------------------- garden

    /** SkyHanni RareCropTracker.kt (colourless). */
    static final String RARE_CROP = "RARE CROP! Cropie (+97)";

    static final String RARE_CROP_DONATED = "RARE CROP! Seasoning (+115) (automatically donated)";

    static final String VERY_RARE_CROP = "VERY RARE CROP! Burrowing Spores";

    /**
     * SkyHanni PestProfitTracker.kt. The character before the closing bracket is U+E02B, the
     * Overbloom icon, which is what tells a pest drop apart from every other rare drop.
     */
    static final String PEST_DROP = "§6§lRARE DROP! §9Mutant Nether Wart §8x9 §e(§e+134)";

    static final String PEST_PET_DROP = "§6§lPET DROP! §r§6Slug §e(§e+78)";

    static final String PEST_VINYL = "§6§lRARE DROP! §r§aNot Just a Pest Vinyl §r§6(Cocoaleech)";

    /** SkyHanni CropFeverTracker.kt (colourless). */
    static final String CROP_FEVER_START =
            "WOAH! You caught a case of the CROP FEVER for 60 seconds!";

    static final String CROP_FEVER_END = "GONE! Your CROP FEVER has been cured!";

    static final String CROP_FEVER_DROP = "RARE DROP! You dropped 48x Enchanted Melon Slice!";

    /** SkyHanni GardenVisitorCompactChat.kt (garden.visitor.fullyaccepted). */
    static final String VISITOR_LEGENDARY = "§6§lOFFER ACCEPTED §8with §6Sirius §8(§6§lLEGENDARY§8)";

    static final String VISITOR_SPECIAL = "§6§lOFFER ACCEPTED §8with §cSpaceman §8(§c§lSPECIAL§8)";

    static final String VISITOR_UNCOMMON =
            "§6§lOFFER ACCEPTED §8with §aLibrarian §8(§a§lUNCOMMON§8)";

    // ---------------------------------------------------------------- mining

    /** SkyHanni GemstoneMoneyPerHour.kt (mining.pristine). */
    static final String PRISTINE =
            "§d§lPRISTINE! §r§fYou found §r§a☘ Flawed Jade Gemstone §r§8x20§r§f!";

    /** SkyHanni PowderTracker.kt (mining.compacted.colorless). */
    static final String COMPACT = "COMPACT! You found an Enchanted Hard Stone!";

    /** SkyHanni MiningNotifications.kt (mining.notifications.goblin.*). */
    static final String GOBLIN_GOLDEN = "§6A Golden Goblin has spawned!";

    static final String GOBLIN_DIAMOND = "§6A §r§bDiamond Goblin §r§6has spawned!";

    // ---------------------------------------------------------------- trapper

    /** SkyHanni TrevorFeatures.kt (misc.trevor.mob.died.colorless). */
    static final String TRAPPER_COMPLETE = "Return to the Trapper soon to get a new animal to hunt!";

    static final String TRAPPER_FAILED = "You ran out of time and the animal disappeared!";

    /** The banner a trapper drop actually arrives on; the item is verified trapper loot. */
    static final String TRAPPER_DROP = "§6§lRARE DROP! §r§9Hunter Ring §r§b(+123% ✯ Magic Find)";

    // ---------------------------------------------------------------- not ours

    /** An ordinary rare mob drop -- the general source's line, which nothing here may claim. */
    static final String MOB_RARE_DROP =
            "§6§lRARE DROP! §r§9Dwarf Turtle Shelmet §r§b(+§r§b168% §r§b✯ Magic Find§r§b)";

    /** A player talking. The line every detector has to survive several times a second. */
    static final String PLAYER_CHAT = "§bBob§f: RARE DROP! Cropie (+97) lol";

    /** A Diana treasure dig -- the shipped path, which nothing here may touch. */
    static final String DIANA_TREASURE =
            "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!";

    // ---------------------------------------------------------------- sea creatures

    static final String SEA_RARE_JAWBUS =
            "§9You have angered a legendary creature... §r§bLord Jawbus §r§9has arrived.";

    static final String SEA_ORDINARY_SQUID = "§9A Squid appeared.";

    /** Every line above, for the "no detector may claim what is not its own" sweeps. */
    static final List<String> ALL_LINES = List.of(
            TROPHY_GOLD, TROPHY_SILVER, TROPHY_BRONZE, TROPHY_DIAMOND_BY_ANALOGY,
            GOLDEN_FISH_SPAWN, GOLDEN_FISH_WEAK, GOLDEN_FISH_DESPAWN,
            CATCH_COINS, CATCH_PET, CATCH_SHARD_STACKED, CATCH_SHARD_SINGLE, CATCH_BAIT,
            TREE_GIFT_HEADER, TREE_BONUS_HEADER, TREE_CONTRIBUTION, TREE_BONUS_COMMON,
            TREE_BONUS_RARE, TREE_BONUS_BOOK, TREE_PHANTOM, TREE_PHANTOM_DREADWING,
            RARE_CROP, RARE_CROP_DONATED, VERY_RARE_CROP,
            PEST_DROP, PEST_PET_DROP, PEST_VINYL,
            CROP_FEVER_START, CROP_FEVER_END, CROP_FEVER_DROP,
            VISITOR_LEGENDARY, VISITOR_SPECIAL, VISITOR_UNCOMMON,
            PRISTINE, COMPACT, GOBLIN_GOLDEN, GOBLIN_DIAMOND,
            TRAPPER_COMPLETE, TRAPPER_FAILED, TRAPPER_DROP,
            MOB_RARE_DROP, PLAYER_CHAT, DIANA_TREASURE,
            SEA_RARE_JAWBUS, SEA_ORDINARY_SQUID);
}
