package com.skyprism.core.diana;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which drops deserve the slot machine's jackpot flourish.
 *
 * <p><b>Why a rule object and not a flag on {@link LootDrop}.</b> {@code LootDrop.rare()}
 * records what Hypixel <em>said</em>: it is true for anything the server put a RARE DROP!
 * banner on, which on a good Diana run is most of what you pick up. "Jackpot" is a
 * different and entirely local question -- which drops are rare enough that the player
 * wants a screen-shaking celebration -- and it has to stay editable, because what feels
 * like a jackpot changes with the market and with the player. So the two live apart.
 *
 * <h2>Where the default list comes from</h2>
 * Drop rates read from {@code https://hypixelskyblock.minecraft.wiki} on 2026-08-28
 * (Mythological Ritual, plus the individual creature and item pages; the official
 * {@code wiki.hypixel.net} shut down in July 2026). Percentages are for the best tier of
 * each source, so they are the <em>most generous</em> figures -- a real player's odds are
 * worse, which only makes the celebration more deserved.
 *
 * <ul>
 *   <li>Mythological Dye -- 1/50,000 from any creature. The genuine lottery win.</li>
 *   <li>Myth the Fish -- 0.01% from burrow treasure, any spade.</li>
 *   <li>Minos Relic -- 0.02-0.04%, Minos Champion.</li>
 *   <li>Braided Griffin Feather -- 0.03%, burrow treasure, Deific Spade only.</li>
 *   <li>Daedalus Stick -- 0.04-0.08%, Minotaur.</li>
 *   <li>Crochet Tiger Plushie -- 0.05-0.4%, Siamese Lynxes.</li>
 *   <li>Shimmering Wool -- 0.2%, King Minos.</li>
 *   <li>Manti-core -- 0.2%, Manticore.</li>
 *   <li>Washed-up Souvenir -- 0.2-0.5%, Stranded Nymph.</li>
 *   <li>Cretan Urn -- 0.2-0.5%, Cretan Bull.</li>
 *   <li>Hilt of Revelations -- 0.25-2%, Minos Hunter.</li>
 *   <li>Brain Food -- 0.25-0.5%, Sphinx.</li>
 *   <li>Antique Remedies -- 0.35-0.6%, Harpy.</li>
 *   <li>Dwarf Turtle Shelmet -- 0.35-0.6%, Gaia Construct (and 1% from an Inquisitor).</li>
 *   <li>Fateful Stinger -- 0.5%, Manticore.</li>
 *   <li>Chimera -- 1-1.25%, Minos Inquisitor. Announced in chat only as
 *       "Enchanted Book", so both spellings are listed; "Enchanted Book" itself is
 *       deliberately <em>not</em>, because it is far too common server-wide to celebrate.</li>
 *   <li>Crown of Greed -- 2%, King Minos. The most common entry here, kept because it is
 *       the signature King Minos drop and King Minos is itself vanishingly rare.</li>
 * </ul>
 *
 * <p>Deliberately excluded: Griffin Feather (66% of all treasure), Coins, Mythos Fragment,
 * Ancient Claw, Enchanted Ancient Claw and Enchanted Gold Ingot. Celebrating those would
 * make the flourish meaningless within one burrow chain.
 *
 * <p><b>Matching is on the display name, case-insensitively.</b> Item ids would be sturdier
 * but this core never sees one -- it parses chat, where names are all Hypixel gives.
 */
public final class JackpotRule {

    /**
     * Default jackpot names in rarest-first order. The order is not decoration: it doubles
     * as the tie-break for {@link #bestJackpot(List)}, so a single kill that yields both a
     * Crown of Greed and a Mythological Dye flourishes on the Dye. Ties within the list
     * (the four 0.2% entries) resolve by the order written here.
     */
    private static final List<String> DEFAULT_ORDER = List.of(
            "Mythological Dye",
            "Myth the Fish",
            "Minos Relic",
            "Braided Griffin Feather",
            "Daedalus Stick",
            "Crochet Tiger Plushie",
            "Shimmering Wool",
            "Manti-core",
            "Washed-up Souvenir",
            "Cretan Urn",
            "Hilt of Revelations",
            "Brain Food",
            "Antique Remedies",
            "Dwarf Turtle Shelmet",
            "Fateful Stinger",
            "Chimera I",
            "Chimera",
            "Crown of Greed");

    /**
     * Rarity ranking, lowest number = rarest, shared by every instance.
     *
     * <p>It is static rather than per-instance so that a player who edits the jackpot list
     * still gets sensible ordering out of {@link #bestJackpot(List)} for the entries they
     * kept. An item nobody has ranked simply sorts last among jackpots.
     */
    private static final Map<String, Integer> RARITY_RANK = buildRanks();

    private final Set<String> names;

    /**
     * @param jackpotItemNames display names to celebrate; compared case-insensitively and
     *                         with runs of whitespace collapsed. An empty set is legal and
     *                         means "never flourish", which is a reasonable player choice.
     */
    public JackpotRule(Set<String> jackpotItemNames) {
        Objects.requireNonNull(jackpotItemNames, "jackpotItemNames");
        this.names = jackpotItemNames.stream()
                .filter(Objects::nonNull)
                .map(LootParser::normalise)
                .filter(n -> n != null && !n.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The researched default list; see the class javadoc for the rate behind each entry. */
    public static JackpotRule defaults() {
        return new JackpotRule(Set.copyOf(DEFAULT_ORDER));
    }

    /**
     * @param drop the drop to test, may be null
     * @return whether this drop should trigger the jackpot flourish
     */
    public boolean isJackpot(LootDrop drop) {
        if (drop == null) {
            return false;
        }
        String key = LootParser.normalise(drop.itemName());
        return key != null && names.contains(key);
    }

    /**
     * Picks the drop worth building the flourish around when a kill yielded several.
     *
     * <p>Ordering is rarity first (from the researched table), then the server's own rare
     * banner, then the order the drops arrived in. The banner tie-break matters for names
     * the table does not know: a player who adds a new item to their jackpot list gets the
     * server-flagged one ahead of an unflagged one instead of an arbitrary pick.
     *
     * @param drops the drops from one kill, may be null or empty
     * @return the single drop to celebrate, or empty when none of them qualify
     */
    public Optional<LootDrop> bestJackpot(List<LootDrop> drops) {
        if (drops == null || drops.isEmpty()) {
            return Optional.empty();
        }

        LootDrop best = null;
        int bestRank = Integer.MAX_VALUE;
        boolean bestFlagged = false;

        for (LootDrop drop : drops) {
            if (!isJackpot(drop)) {
                continue;
            }
            int rank = RARITY_RANK.getOrDefault(LootParser.normalise(drop.itemName()), Integer.MAX_VALUE);
            if (best == null
                    || rank < bestRank
                    || (rank == bestRank && drop.rare() && !bestFlagged)) {
                best = drop;
                bestRank = rank;
                bestFlagged = drop.rare();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * The names this rule celebrates, normalised to lower case. Exposed so a config screen
     * can show what is active without the caller having to keep its own copy in step.
     */
    public Set<String> itemNames() {
        return names;
    }

    /**
     * Ranks by position in {@link #DEFAULT_ORDER}. {@code Chimera I} and {@code Chimera}
     * sit next to each other so the two spellings of the same book cannot outrank anything
     * they should not.
     */
    private static Map<String, Integer> buildRanks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < DEFAULT_ORDER.size(); i++) {
            ranks.put(DEFAULT_ORDER.get(i).toLowerCase(Locale.ROOT), i);
        }
        return Map.copyOf(ranks);
    }
}
