package com.skyprism.core.loot.containers;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every compiled pattern and literal the container detectors match on, in one place, with its
 * provenance written next to it.
 *
 * <h2>Why one class and not one per detector</h2>
 * <p>Two reasons, both learned from the research rather than invented. First, {@code
 * docs/CHAT-PATTERNS.md} has to list what this feature matches and say honestly which entries were
 * read out of a reference mod and which were widened defensively -- and a document that has to be
 * assembled from nine scattered files drifts from the code within a week. Second, several of these
 * shapes are deliberately near-misses of each other: {@code CHEST LOCKPICKED} and {@code LOOT CHEST
 * COLLECTED} open the identical reward block and differ only in one word, and the {@code RARE
 * REWARD!} broadcast is shared verbatim by three separate sources that tell themselves apart by the
 * chest tier alone. Cross-source false positives are the likeliest bug in the whole feature, and
 * they are much easier to reason about with the patterns sitting next to each other.
 *
 * <h2>Verification status, stated per pattern</h2>
 * <p>Anything marked <b>verbatim</b> was transcribed character for character from a reference mod's
 * own source, in most cases from a pattern that carries its own captured-from-live-chat test string.
 * Anything marked <b>widened</b> is a verbatim shape with a documented relaxation -- an optional
 * leading formatting run, an {@code an?} where the reference had {@code a} -- which can only ever
 * make the pattern match more, never less, and is called out so nobody later mistakes it for
 * something observed. Anything marked <b>inferred</b> is a shape the research could not confirm
 * against a captured line; each of those says so and says what happens if it is wrong.
 *
 * <p>Every pattern is compiled once into a static final field. None of them is ever built per line.
 * The detectors additionally do their own {@code indexOf} on a distinctive literal before touching a
 * {@code Matcher}, because the bus's pre-filter is the <em>union</em> of every open detector's
 * markers -- a line that passed it may well be some other source's line, and allocating a matcher
 * for it would be the cost this design exists to avoid.
 */
public final class ContainerPatterns {

    private ContainerPatterns() {
    }

    // ------------------------------------------------------------------ the RARE REWARD broadcast

    /** The literal every {@code RARE REWARD!} detector rejects a line on before allocating. */
    public static final String RARE_REWARD_MARKER = "RARE REWARD!";

    /**
     * The one universal "a chest gave someone something good" line, matched colourless.
     *
     * <p><b>Widened from verbatim.</b> SkyHanni's coloured form is
     * <code>&#167;6&#167;lRARE REWARD! (.*) &#167;r&#167;efound a (.*) &#167;r&#167;ein their (.*)
     * Chest&#167;r&#167;e!</code> (ChatFilter.kt, with its own captured test line). This is the same
     * sentence with the formatting stripped first, which buys two things worth having. It survives
     * Hypixel moving a colour code inside the player's rank prefix, which is exactly the sort of
     * thing that changes without notice; and anchoring it at {@code ^} makes it unfakeable, because
     * every player-authored line reaching the client carries a name and a colon in front, so a
     * player typing this sentence verbatim in party chat cannot match. That is Skyblocker's
     * {@code (?!.*:)} guard achieved by anchoring instead, which is cheaper and stricter.
     *
     * <p>{@code an?} is a defensive widening: the reference mods only ever quote "found a".
     *
     * <p><b>This line is a broadcast.</b> It fires for every member of the party, naming whoever got
     * the drop. A detector that does not compare the captured name against the local player has
     * built a remote control for its own HUD; see {@link RareRewardBroadcast#isOwnedBy(String)}.
     */
    public static final Pattern RARE_REWARD = Pattern.compile(
            "^RARE REWARD! (?<player>.+?) found an? (?<item>.+?) "
                    + "in their (?<tier>[A-Za-z][A-Za-z ]*?) Chest!$");

    /**
     * The six Catacombs reward-chest tiers, as they appear in the broadcast's tier group.
     *
     * <p>A closed set on purpose. The tier is the only thing that tells a Catacombs chest apart from
     * a Kuudra one in a line that is otherwise identical, so accepting an arbitrary capture would
     * let the two sources fight over one event -- and would let a chest type nobody has seen caption
     * itself as a dungeon chest. It is also why these detectors need no island gate at all: the
     * ownership question is settled by the tier, not by where the player is standing, which is the
     * safer of the two because a gate that never opens is a feature that never fires.
     */
    public static final Set<String> DUNGEON_CHEST_TIERS =
            Set.of("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock");

    /** The two Kuudra chest tiers, closed for the same reason. */
    public static final Set<String> KUUDRA_CHEST_TIERS = Set.of("Free", "Paid");

    // ------------------------------------------------------------------ GUI titles

    /**
     * Every spelling of a Catacombs reward-chest inventory title.
     *
     * <p><b>Verbatim, and the bare forms are not a mistake.</b> Skyblocker carries both {@code "Wood
     * Chest"} and {@code "Wood"} with a source comment saying Hypixel broke the titles, so a
     * detector accepting only the long form misses live chests. The cost of the bare forms is a
     * documented risk rather than a hidden one: six common English nouns matched by exact equality
     * against a whole inventory title. Nothing in SkyBlock is titled exactly {@code "Gold"} that
     * this would want to ignore, but if one ever is, this is the line to change.
     */
    public static final Set<String> DUNGEON_CHEST_TITLES = Set.of(
            "Wood Chest", "Wood",
            "Gold Chest", "Gold",
            "Diamond Chest", "Diamond",
            "Emerald Chest", "Emerald",
            "Obsidian Chest", "Obsidian",
            "Bedrock Chest", "Bedrock");

    /**
     * Every spelling of a Kuudra chest inventory title.
     *
     * <p><b>Verbatim.</b> Hypixel duplicates the word "Chest" in the inventory name but not in the
     * item stack name, which is a server bug both reference mods work around rather than fix --
     * SkyHanni's regex carries all four spellings as its own test cases and its display code does a
     * literal {@code replace("Chest Chest", "Chest")}. Accept all four.
     */
    public static final Set<String> KUUDRA_CHEST_TITLES =
            Set.of("Free Chest", "Free Chest Chest", "Paid Chest", "Paid Chest Chest");

    /**
     * The Croesus run-list inventory title.
     *
     * <p><b>Verbatim</b> from SkyHanni's CroesusChestTracker, page number and all.
     */
    public static final Pattern CROESUS_TITLE = Pattern.compile("(?:\\(\\d+/\\d+\\) )?Croesus");

    /**
     * The Experimentation Table's inventory titles, including the three minigames.
     *
     * <p><b>Verbatim</b> from SkyHanni's CompactExperimentRewards. The {@code ➜} is the arrow
     * Hypixel uses in "Superpairs ➜ Stakes"; it is written as an escape rather than pasted so
     * a file re-encoding cannot silently break it.
     *
     * <p>Both capture groups are optional by construction -- exactly one participates in any match
     * -- so a caller reads {@code game} for a minigame screen and gets null on the table itself,
     * which is the honest answer rather than a fabricated game name.
     */
    public static final Pattern EXPERIMENT_TITLE = Pattern.compile(
            "(?<game>Superpairs|Chronomatron|Ultrasequencer) "
                    + "(?:\\(.+\\)|➜ Stakes|Rewards)"
                    + "|(?<table>Experimentation Table)");

    // ------------------------------------------------------------------ the shared reward block

    /**
     * The header of a lockpicked Crystal Hollows treasure chest.
     *
     * <p><b>Verbatim</b> from SkyHanni's PowderMiningChatFilter, which carries this exact string as
     * its own captured test line. The {@code .*} on both ends is SkyHanni's, and the fixed
     * <code>&#167;r&#167;6&#167;l</code> run in the middle is what makes it unfakeable: a player
     * cannot put a section sign into chat, so no amount of typing "CHEST LOCKPICKED" matches.
     */
    public static final Pattern CHEST_LOCKPICKED =
            Pattern.compile(".*§r§6§lCHEST LOCKPICKED.*");

    /**
     * The header of a fixed-structure loot chest -- Jungle Temple, Mines of Divan, Fairy Grotto,
     * Lost Precursor City, the Goblin Queen's Den, and the Glacite Mineshaft.
     *
     * <p><b>Verbatim</b> from the same file. It differs from {@link #CHEST_LOCKPICKED} in the colour
     * code as well as in the words, so the two can never both match one line.
     *
     * <p><b>Inferred, and flagged:</b> the research could not find a source proving that the Jungle
     * Temple's key-opened chests use this header rather than a third one. If they use something else
     * this detector simply never fires for them -- a missing roll, not a wrong one.
     */
    public static final Pattern LOOT_CHEST_COLLECTED =
            Pattern.compile(".*§r§5§lLOOT CHEST COLLECTED.*");

    /**
     * The header of a completed fossil excavation.
     *
     * <p><b>Verbatim</b> from SkyHanni's FossilExcavatorApi, two leading spaces and optional
     * trailing space included.
     */
    public static final Pattern EXCAVATION_COMPLETE =
            Pattern.compile(" {2}§r§6§lEXCAVATION COMPLETE ?");

    /**
     * The line Hypixel prints when an excavation found nothing at all.
     *
     * <p><b>Widened from verbatim:</b> the reference form is the flat literal
     * <code>&#167;cYou didn't find anything. Maybe next time!</code>; the leading
     * {@code (?:§.)*} admits an outer style run in front of it. Worth detecting rather than
     * ignoring: an excavation that paid nothing is still a resolved gamble, and settling the reels
     * on "No Drop" is the honest outcome -- suppressing the spin would quietly hide the near miss,
     * which is most of the texture.
     */
    public static final Pattern EXCAVATION_EMPTY = Pattern.compile(
            "(?:§.)*You didn't find anything\\. Maybe next time!");

    /**
     * The opening and closing rule of a container reward block: exactly 64 identical
     * {@code ▬}, behind a bold colour run.
     *
     * <p><b>Verbatim</b> in shape from five separate SkyHanni features. The colour varies with the
     * source and this pattern deliberately does not pin it -- {@code §e} and {@code §d}
     * for chests, {@code §a} for corpse loot and fossil excavation, {@code §3} for a
     * finished Nucleus run, {@code §5} for a crystal found. See {@link RewardBlock} for why
     * this is exposed but not consumed by any detector in this package.
     */
    public static final Pattern BLOCK_EDGE =
            Pattern.compile("(?:§.)*§l▬{64}");

    /**
     * One reward line inside a container block: four leading spaces, a reset, the item, and an
     * optional {@code x<count>} tail.
     *
     * <p><b>Verbatim.</b> The four-space indent is load-bearing and is the cheapest possible
     * rejection for every other line in chat.
     */
    public static final Pattern BLOCK_ITEM = Pattern.compile(
            " {4}§r(?<item>.+?)(?: §r§8x(?<amount>[\\d,]+))?");

    // ------------------------------------------------------------------ single-line sources

    /**
     * The Crystal Nucleus run-completed tail.
     *
     * <p><b>Widened from verbatim.</b> SkyHanni's line is
     * <code>&#167;7Pick it up near the &#167;r&#167;5Nucleus Vault&#167;r&#167;7!</code>; the
     * leading {@code (?:§.|\s)*+} admits the indent and outer style run the rest of that block
     * carries. The quantifier is possessive so the alternation cannot backtrack -- this runs on a
     * chat line whose length the server chooses, and a backtracking alternation there is the shape
     * that turns a 200,000-character line into a frozen client. {@code LootParser} has the same note
     * for the same reason.
     */
    public static final Pattern NUCLEUS_RUN_COMPLETE = Pattern.compile(
            "(?:§.|\\s)*+Pick it up near the §r§5Nucleus Vault§r§7!");

    /**
     * A crystal placed during a Nucleus run, carrying its own progress count.
     *
     * <p><b>Verbatim</b> from SkyHanni's CrystalNucleusChatFilter. Deliberately not a trigger: these
     * five lines are progress markers, and rolling on each would fire five times per run and
     * devalue the finish. They are read only so the completion can be captioned with the count.
     */
    public static final Pattern CRYSTAL_FOUND = Pattern.compile(
            "§f *§r§5§l✦ CRYSTAL FOUND "
                    + "§r§7\\((?<count>\\d)§r§7/5§r§7\\)");

    /**
     * A Metal Detector dig in the Mines of Divan.
     *
     * <p><b>Verbatim</b> from SkyHanni's CrystalNucleusChatFilter, which carries captured lines for
     * both the tool form and the gemstone-with-count form. The count sits <em>inside</em> the loot
     * group, which is why {@link ContainerText#itemCaption(String)} exists.
     */
    public static final Pattern METAL_DETECTOR = Pattern.compile(
            "§aYou found §r(?<loot>.*) §r§awith your "
                    + "§r§cMetal Detector§r§a!");

    /**
     * The Experimentation Table's reward-claim line, whose colour group names the experiment tier.
     *
     * <p><b>Widened from verbatim:</b> SkyHanni's form is
     * <code>&#167;eYou claimed the &#167;r&#167;.\S+ &#167;r&#167;erewards!</code>; the leading
     * {@code (?:§.)*} admits an outer style run, and the tier is captured rather than discarded
     * so the caption can read "Ultrasequencer (Metaphysical)" instead of a bare game name.
     */
    public static final Pattern EXPERIMENT_CLAIM = Pattern.compile(
            "(?:§.)*§eYou claimed the §r§.(?<tier>\\S+) "
                    + "§r§erewards!");
}
