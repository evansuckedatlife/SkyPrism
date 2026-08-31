package com.skyprism.core.loot;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The coarse condition under which a {@link LootSource} can fire at all, plus a sentence describing
 * it for the config screen and for {@code /skyprism} diagnostics.
 *
 * <h2>Coarsest thing first</h2>
 * <p>Almost every source in the game is shut by one island comparison. The two reference mods prove
 * it: SkyHanni declares an island restriction on nearly every combat handler, and that is exactly
 * the shape wanted here -- {@code CATACOMBS}, {@code KUUDRA}, {@code THE_END}, {@code CRIMSON_ISLE},
 * {@code SPIDER_DEN}, {@code THE_RIFT}, {@code DWARVEN_MINES}, {@code CRYSTAL_HOLLOWS}, {@code
 * GARDEN}, {@code GALATEA} are distinct, so each gate is a string comparison re-evaluated only when
 * the player warps. The handful of sources that are deliberately always-on (a rare mob drop can come
 * from anywhere) pay for it with a substring pre-filter instead, which is the bus's job.
 *
 * <h2>Every gate implies being in SkyBlock</h2>
 * <p>All the factories below fold {@link GameContext#inGame()} in, so no caller can forget it. A
 * lobby is not SkyBlock, and several of these lines -- candy baskets, present chests -- genuinely do
 * fire in the Hypixel main lobby, where spinning a SkyBlock slot machine would be nonsense.
 *
 * <p>Instances are immutable and built once, statically. Evaluating one allocates nothing.
 */
public final class SourceGate {

    private final String description;
    private final Predicate<GameContext> test;

    private SourceGate(String description, Predicate<GameContext> test) {
        this.description = Objects.requireNonNull(description, "description");
        this.test = Objects.requireNonNull(test, "test");
    }

    /** Whether this source may fire in {@code ctx}. Never throws; a null context is closed. */
    public boolean isOpen(GameContext ctx) {
        return ctx != null && ctx.inGame() && test.test(ctx);
    }

    /** A human sentence, e.g. "in SkyBlock, on Crystal Hollows". */
    public String describe() {
        return description;
    }

    @Override
    public String toString() {
        return "SourceGate[" + description + "]";
    }

    // ---------------------------------------------------------------- factories

    /** Open anywhere in SkyBlock. Reserved for the genuinely server-wide banner sources. */
    public static SourceGate anywhere() {
        return new SourceGate("in SkyBlock", ctx -> true);
    }

    /** Open on exactly one island. */
    public static SourceGate island(String island) {
        String name = require(island);
        return new SourceGate("in SkyBlock, on " + name, ctx -> ctx.isIsland(name));
    }

    /** Open on any of several islands, e.g. the two that both report as Glacite mining. */
    public static SourceGate islands(String... islands) {
        Set<String> keys = new LinkedHashSet<>();
        StringBuilder pretty = new StringBuilder();
        for (String island : islands) {
            String name = require(island);
            keys.add(name.toLowerCase(Locale.ROOT));
            if (pretty.length() > 0) {
                pretty.append(" or ");
            }
            pretty.append(name);
        }
        Set<String> frozen = Set.copyOf(keys);
        return new SourceGate("in SkyBlock, on " + pretty,
                ctx -> frozen.contains(ctx.islandKey()));
    }

    /** Open on one island and, within it, one graph area -- the Mist, the Dragon Nest. */
    public static SourceGate area(String island, String area) {
        String islandName = require(island);
        String areaName = require(area);
        return new SourceGate("in SkyBlock, on " + islandName + " in " + areaName,
                ctx -> ctx.isIsland(islandName) && ctx.isArea(areaName));
    }

    /** Open inside a Catacombs run. */
    public static SourceGate dungeon() {
        return new SourceGate("in SkyBlock, inside a dungeon", GameContext::inDungeon);
    }

    /** Open inside The Rift. */
    public static SourceGate rift() {
        return new SourceGate("in SkyBlock, inside The Rift", GameContext::inRift);
    }

    /** Open while a named mayor is in office -- the Diana gate. */
    public static SourceGate mayor(String mayor) {
        String name = require(mayor);
        return new SourceGate("in SkyBlock, while " + name + " is mayor", ctx -> ctx.isMayor(name));
    }

    /**
     * Armed by its own GUI title rather than by anything in the context.
     *
     * <p>The cheapest gate in the feature and the one nobody expects: a screen opens a handful of
     * times a minute at worst, so a detector that arms on its own inventory title costs nothing at
     * all the rest of the time and needs no island check -- the Experimentation Table can sit on a
     * private island, Croesus lives in the Dungeon Hub, a reward chest opens wherever you are. So
     * the context-level gate is simply "in SkyBlock" and the title match does the real filtering,
     * inside the detector, where it is one string comparison on an event that is already rare.
     */
    public static SourceGate screen(String what) {
        String name = require(what);
        return new SourceGate("in SkyBlock, armed by the " + name + " screen", ctx -> true);
    }

    /**
     * A source that really wants a seasonal window the context cannot yet express.
     *
     * <p>Hoppity is one SkyBlock season a year, the Great Spook a fortnight, the Year of the Pig
     * once a century. The right fix is a season token on {@link GameContext} -- the sidebar carries
     * the SkyBlock date and is already polled every couple of seconds, so it costs one extra string
     * test on a poll that happens anyway. Until then this gate is "in SkyBlock": armed year-round,
     * which is safe because every seasonal trigger line is highly distinctive, and merely not free.
     *
     * <p>Deliberately <em>not</em> modelled as permanently shut. A gate that can never open is a
     * detector that silently never fires, which is indistinguishable from a working feature and is
     * the single failure mode this whole design exists to avoid. Paying one substring test out of
     * season is the cheaper mistake.
     */
    public static SourceGate season(String season) {
        String name = require(season);
        return new SourceGate("in SkyBlock (wants: during " + name + ")", ctx -> true);
    }

    /** A season gate that also requires an island, e.g. Frozen Treasure in the Glacial Cave. */
    public static SourceGate seasonOnIsland(String season, String island) {
        String seasonName = require(season);
        String islandName = require(island);
        return new SourceGate("in SkyBlock, on " + islandName + " (wants: during " + seasonName + ")",
                ctx -> ctx.isIsland(islandName));
    }

    private static String require(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("gate name must not be blank");
        }
        return trimmed;
    }
}
