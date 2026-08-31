package com.skyprism.core.loot;

/**
 * When a {@link LootSource} is allowed to spin the machine.
 *
 * <p>This is the single most important design knob in the whole feature, and it exists because
 * SkyBlock's chance-based events do not share a cadence. A Minos Inquisitor, a Kuudra clear and a
 * Glacite corpse are minutes apart and a player is already waiting on each of them; an ordinary sea
 * creature, a Pristine gemstone proc and a Bronze trophy fish arrive several times a <em>second</em>
 * during a grind. Rolling on both kinds with one rule produces either a machine that never fires or
 * a strobe. So every source carries its own policy, and the registry ships a researched default for
 * each (see {@link LootSourceRegistry}).
 *
 * <h2>The rule that picks the default</h2>
 * <p>The distinction that matters is <b>event shaped versus stream shaped</b>, not rarity. An
 * event-shaped source has a discrete, player-initiated completion the player is already watching
 * for, landing somewhere between thirty seconds and ten minutes apart -- which is the band Diana
 * already ships in and the band the player already finds delightful. Those get {@link #ALWAYS}, and
 * the <em>absence</em> of a drop is part of the experience: a slayer boss whose reels stop on "No
 * Drop" is the honest outcome. A stream-shaped source has no completion at all, only a firehose;
 * those get {@link #ON_RARE_BANNER} where Hypixel prints a rarity itself, {@link
 * #ON_JACKPOT_ITEM_ONLY} where it does not, and {@link #NEVER} where even that would strobe.
 *
 * <h2>The trap</h2>
 * <p>Never set {@link #ON_RARE_BANNER} on a source that emits no banner. The Ender Dragon and the
 * Endstone Protector drop their loot as floating armour stands with nothing at all in chat;
 * {@code ON_RARE_BANNER} there is a detector that silently never fires, which is indistinguishable
 * from a working feature and is the worst outcome available. {@link LootSourceRegistry} enforces
 * that as an invariant rather than a convention: a source whose research found no rare banner may
 * not default to this policy.
 */
public enum RollPolicy {

    /**
     * Spin on every trigger. Correct for inherently rare, deliberate, self-initiated events -- a
     * slayer boss, a dungeon clear, a Kuudra run, a Glacite corpse, a Minos Inquisitor.
     */
    ALWAYS,

    /**
     * Spin only when Hypixel itself flagged the loot as rare -- the {@code RARE DROP!} family, or a
     * source that prints a rarity word of its own such as a Winter gift's {@code SANTA TIER!} or a
     * Hoppity rabbit's {@code (LEGENDARY)}. Correct for high-frequency sources where the server has
     * already done the rarity classification for us, and only for those.
     */
    ON_RARE_BANNER,

    /**
     * Spin only when a drop matches the source's jackpot list. Correct for high-frequency sources
     * that carry no rarity banner at all but do name the item in the trigger line -- a Crystal
     * Hollows chest, a Frozen Treasure, a Metal Detector dig, a rare crop.
     */
    ON_JACKPOT_ITEM_ONLY,

    /**
     * Never spin. Shipped for sources that are real, enumerated and switchable, but whose cadence
     * makes them unusable armed -- Pristine gemstone procs, Compact procs, Bronze trophy fish,
     * slayer minibosses -- and for sources whose only verified signal cannot establish that the
     * loot was <em>yours</em>, such as the Trick or Treat chest's island-wide appearance broadcast.
     */
    NEVER;

    /**
     * Whether this policy lets a roll begin, given what is known about the trigger.
     *
     * <p>Both flags are "what the trigger line itself told us", not "what arrived afterwards": the
     * decision has to be made at the moment the event fires, because that is when the reels have to
     * start moving. A source whose loot only arrives later (a slayer boss, a dungeon clear) is
     * exactly the kind that defaults to {@link #ALWAYS}, so it never needs either flag.
     *
     * @param sawRareBanner whether the trigger carried a server rarity flag
     * @param sawJackpotItem whether the trigger named an item on this source's jackpot list
     * @return whether the machine may spin
     */
    public boolean permits(boolean sawRareBanner, boolean sawJackpotItem) {
        return switch (this) {
            case ALWAYS -> true;
            case ON_RARE_BANNER -> sawRareBanner;
            case ON_JACKPOT_ITEM_ONLY -> sawJackpotItem;
            case NEVER -> false;
        };
    }

    /** Whether a source shipped on this policy can ever spin without the player changing config. */
    public boolean armed() {
        return this != NEVER;
    }
}
