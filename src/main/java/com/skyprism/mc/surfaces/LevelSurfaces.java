package com.skyprism.mc.surfaces;

import com.skyprism.core.config.SkyPrismConfig;
import com.skyprism.core.level.LevelPalette;
import com.skyprism.core.level.LevelTagLocator;
import com.skyprism.mc.command.Metrics;
import com.skyprism.mc.config.ConfigManager;
import com.skyprism.mc.diana.DianaController;
import com.skyprism.mc.text.ComponentRewriter;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All the thinking behind the TAB-list and nametag recolours, kept out of the mixins so it
 * can be read, reviewed and compiled like ordinary code.
 *
 * <p>The mixins are deliberately dumb: check a boolean, fetch a cache cell, call one method
 * here, and set the return value if something comes back. Everything that could be got
 * wrong -- the cache key, the chroma refresh cap, the failure budget -- lives in this file,
 * once, shared by both surfaces.</p>
 *
 * <h2>The shape of a frame</h2>
 * <p>On a full lobby this class is entered around a hundred times per frame. The intended
 * cost of the overwhelmingly common path is: one volatile read of the live config, a
 * handful of field compares in {@link LevelNameMemo#matches}, and a returned reference. No
 * allocation, no regex, no component walk, no map. The expensive path -- flatten the
 * component, locate the tags, splice the styles, rebuild -- runs when the name genuinely
 * changed, when a setting changed, or at the configured chroma rate for animated levels
 * only.</p>
 *
 * <h2>Why the counters are fed from here</h2>
 * <p>{@link Metrics#tabProbe(boolean)} is documented as the load-bearing counter for the TAB
 * performance rule, and for a while nothing called it: both surfaces kept private recompute
 * counters instead, and {@code Metrics.snapshot()} reports a hit rate of exactly 100% when no
 * probe was ever recorded. So the one instrument written to falsify the caching claim could not
 * report a bad number, and a genuinely wrong cache key would have been invisible to the command
 * built to catch it. Every probe is now counted where the decision is actually made, and the two
 * rebuild timers wrap the miss path only.</p>
 *
 * <h2>Why a failure budget instead of a plain try/catch</h2>
 * <p>Both call sites run every frame. A bug that throws would therefore log sixty stack
 * traces a second, which turns a cosmetic problem into an unusable game and an unreadable
 * log. So the first failure on a surface is logged in full, subsequent ones are counted
 * silently, and after {@link #FAILURE_BUDGET} of them the surface switches itself off and
 * says so once. Because the trip is remembered together with the configuration generation
 * that produced it, changing any setting re-arms the surface: a user who mis-edits a
 * palette is not stuck with dead nametags until they restart the game.</p>
 */
public final class LevelSurfaces {

    /** Failures tolerated on one surface before that surface is switched off for the session. */
    public static final int FAILURE_BUDGET = 8;

    /**
     * How many nametag draws a cached answer is trusted for before its key is compared in
     * full.
     *
     * <p>Nametags have no stable key -- {@code Player.getDisplayName()} rebuilds the component
     * from the team prefix and suffix on every call -- so the memo has to compare structurally,
     * which measures around 140 ns for a typical Hypixel name against about 1 ns for the TAB
     * list's identity compare. Sixteen draws is a quarter of a second at 60 fps, comfortably
     * below the point at which anyone notices a nametag changing late, and it turns fifteen
     * frames in sixteen into three field compares.</p>
     */
    public static final int NAME_TAG_REVALIDATE_DRAWS = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("SkyPrism/Surfaces");

    /** Per-surface breaker state. Render thread only, so plain fields are correct and cheapest. */
    private static final Breaker TAB = new Breaker("TAB list");
    private static final Breaker NAME_TAGS = new Breaker("nametags");

    /** Set once the scope check has failed, so a broken gate logs one line rather than a stream. */
    private static boolean scopeFailureLogged;

    /** Recompute counters, for the debug command and for judging whether the cache works. */
    private static long tabRecomputes;
    private static long nameTagRecomputes;

    /**
     * Scratch for {@link ComponentRewriter}'s out-parameters: tag count, then highest level.
     *
     * <p>One array for the life of the process, because both call sites are the render thread and
     * this is the per-entry path. It exists so the chroma decision can be read off the scan the
     * rewriter has just done instead of repeating it -- see {@link #recolour}.
     */
    private static final int[] SCAN = new int[2];

    private LevelSurfaces() {
    }

    /**
     * The TAB hook's first line. Cheap enough to run once per listed player per frame.
     *
     * @return true when the TAB list should be recoloured at all
     */
    public static boolean tabListEnabled() {
        SkyPrismConfig.LevelSettings levels = ConfigManager.get().config().levels;
        return levels.enabled && levels.applyToTabList
                && levelScopeSatisfied(levels) && !TAB.tripped();
    }

    /**
     * The server check, shared by both render surfaces and by the chat hook.
     *
     * <p>A SkyBlock level prefix only exists inside SkyBlock, and the locator has no way to tell
     * one bracketed number from another: off SkyBlock, "we need [2] more" in a Bedwars lobby and a
     * shared coordinate "[500] [70]" match exactly as well as a real tag does. The gate already
     * knows the answer -- it is the SkyBlock half of the four conditions the Diana feature folds
     * together -- so this is one boolean read against a fact refreshed at 0.5 Hz, not a new poll.
     *
     * <p>No cache invalidation is needed when it flips. A surface that answers false here is
     * skipped by its mixin entirely, so vanilla's own name is drawn rather than a stale cached
     * one; and coming back in scope, a memo hit is by construction still the right answer for
     * that component.
     *
     * <p><b>It errs open if the gate cannot be reached at all.</b> The initialiser is built so a
     * subsystem that fails to start leaves the others running -- "a broken Diana HUD must not cost
     * the player their level colours" -- and reading the scope through the Diana module would
     * quietly retract that if a failure here were read as "not on SkyBlock". A repaint somewhere it
     * does not belong is a far smaller problem than the whole feature going dark, and the one-time
     * warning says which it is.
     *
     * @param levels the live level settings; not null
     * @return whether the level recolour is in scope on this server right now
     */
    public static boolean levelScopeSatisfied(SkyPrismConfig.LevelSettings levels) {
        if (!levels.onlyOnSkyBlock) {
            return true;
        }
        try {
            return DianaController.get().gate().inSkyBlock();
        } catch (RuntimeException | LinkageError unavailable) {
            if (!scopeFailureLogged) {
                scopeFailureLogged = true;
                LOGGER.warn("SkyPrism cannot read the SkyBlock gate, so levels.onlyOnSkyBlock "
                        + "cannot be honoured; recolouring everywhere instead of nowhere.",
                        unavailable);
            }
            return true;
        }
    }

    /**
     * The nametag hook's first line.
     *
     * <p>Note on the default: the core ships {@code applyToNameTags} switched <em>off</em>,
     * because whether Hypixel renders the level prefix above a player's head at all is
     * unverified -- the wiki documents only chat and TAB. The hook is built and correct either
     * way, and a player who wants to find out can switch it on; if in-game testing ever shows a
     * tag really does appear there, the honest change is to flip the default on, because the
     * cost when it never matches is one boolean read and one {@code instanceof} per visible
     * player per frame.</p>
     *
     * @return true when nametags should be recoloured at all
     */
    public static boolean nameTagsEnabled() {
        SkyPrismConfig.LevelSettings levels = ConfigManager.get().config().levels;
        return levels.enabled && levels.applyToNameTags
                && levelScopeSatisfied(levels) && !NAME_TAGS.tripped();
    }

    /**
     * Recolours a TAB entry, reusing the previous answer whenever it is still correct.
     *
     * <p>Two different components are involved and the distinction is the whole trick. The
     * <em>key</em> is {@code PlayerInfo.getTabListDisplayName()}, the stored field, which
     * keeps its identity between server updates. The <em>target</em> is what vanilla is
     * about to return: that same component copied, then italicised if the player is
     * spectating. The copy is freshly allocated every frame, so it can never be a cache key,
     * but it is a pure function of the key and the game mode -- which is exactly why the
     * game mode is passed as the variant.</p>
     *
     * @param memo      this player's cache cell, from the {@code PlayerInfo} mixin
     * @param source    the stored tab-list display name; the cache key, not null
     * @param gameMode  the player's game mode, the only thing vanilla's decoration depends on
     * @param decorated the component vanilla is returning; the thing actually recoloured
     * @return the recoloured component, or null to leave vanilla's answer alone
     */
    public static Component tabDisplayName(LevelNameMemo memo, Component source,
                                           Object gameMode, Component decorated) {
        // Revalidate on every call: this key is exact and identity-comparable, so there is
        // nothing to trade away.
        return recolour(memo, source, gameMode, decorated, TAB, 0);
    }

    /**
     * Recolours a nametag, reusing the previous answer whenever it is still correct.
     *
     * <p>Here the source and the target are the same object, because
     * {@code EntityRenderer.getNameTag} returns {@code Entity.getDisplayName()} directly.
     * That component is rebuilt from the team prefix and suffix on every call, so the memo
     * falls back to comparing it structurally -- see {@link LevelNameMemo}.</p>
     *
     * @param memo    this player's cache cell, from the {@code AbstractClientPlayer} mixin
     * @param nameTag the nametag vanilla is returning, not null
     * @return the recoloured nametag, or null to leave vanilla's answer alone
     */
    public static Component nameTag(LevelNameMemo memo, Component nameTag) {
        return recolour(memo, nameTag, null, nameTag, NAME_TAGS, NAME_TAG_REVALIDATE_DRAWS);
    }

    /**
     * How many times each surface has actually rebuilt a component this session.
     *
     * <p>Exposed for the debug command. The number that matters is its <em>rate</em>: on a
     * settled lobby with chroma off it should stop climbing entirely once every entry has
     * been seen once, and with chroma on it should climb at roughly
     * {@code chromaUpdateHz * animatedEntries} per second and no faster.</p>
     *
     * @return a two-element array, TAB recomputes then nametag recomputes
     */
    public static long[] recomputeCounts() {
        return new long[] {tabRecomputes, nameTagRecomputes};
    }

    /**
     * The one place either surface is allowed to do real work.
     *
     * <p>Note where the clock is read: after the key check, and only for an entry the palette
     * actually animates. With chroma off -- the default -- a hit never reads it at all. That
     * is not micro-optimisation for its own sake; {@code System.currentTimeMillis()} measures
     * about 5 ns here, which across a hundred and forty entries a frame is more than the
     * entire rest of the hit path put together.</p>
     *
     * <p>When the clock is read it is quantised down to the chroma interval rather than used
     * raw. Two reasons, both load-bearing: it makes every entry rendered in the same frame
     * agree on the animation phase instead of smearing it across the frame, and it makes the
     * expiry test exact -- an entry stored at a quantised instant expires precisely one
     * interval later, so a shimmering TAB list rebuilds at the configured rate rather than at
     * whatever the frame rate happens to be.</p>
     */
    private static Component recolour(LevelNameMemo memo, Component key, Object variant,
                                      Component target, Breaker breaker, int revalidateEvery) {
        ConfigManager manager = ConfigManager.get();
        int generation = manager.generation();
        breaker.rearmIfConfigChanged(generation);

        boolean keyHit = memo.keyMatches(key, variant, generation, revalidateEvery);
        // Probes are recorded for the TAB surface only: that counter is reported as a TAB hit
        // rate and folding a second surface into it would make the number unreadable rather than
        // more complete. The nametag surface is judged by its own rebuild count and timing.
        boolean measure = Metrics.enabled();
        boolean probe = measure && breaker == TAB;
        if (keyHit && !memo.chromatic) {
            if (probe) {
                Metrics.tabProbe(true);
            }
            return memo.value;
        }

        long interval = manager.chromaFrameIntervalMillis();
        long now = System.currentTimeMillis();
        long quantised = now - Math.floorMod(now, interval);
        if (keyHit && quantised - memo.computedAt < interval) {
            // Still a hit: the memo answered without rebuilding anything. Counting a chroma
            // entry's between-repaint frames as misses would make the hit rate a function of the
            // frame rate rather than of the cache key, which is the one thing it is there to test.
            if (probe) {
                Metrics.tabProbe(true);
            }
            return memo.value;
        }
        if (probe) {
            Metrics.tabProbe(false);
        }

        // Timed on the miss path only. A nanoTime pair around the hit path would itself cost
        // roughly two microseconds per frame across eighty TAB entries -- more than the work it
        // was measuring -- so the instrument would be the overhead it exists to disprove.
        long startedNanos = measure ? System.nanoTime() : 0L;
        try {
            LevelPalette palette = manager.palette();
            LevelTagLocator locator = manager.locator();
            Component result = ComponentRewriter.recolourLevels(
                    target, palette, locator, manager.config().levels.recolourBrackets, quantised,
                    SCAN);

            // recolourLevels returns its argument by identity when nothing matched, which is
            // the cheapest possible "no change" signal and is documented as part of its
            // contract. Cache that outcome too: unmatched names are the common case off
            // Hypixel and must not be rescanned every frame.
            boolean changed = result != target;
            // SCAN[1] is the highest level the rewriter just matched, and isChromatic is a
            // threshold test, so the highest tag decides for the whole component. This used to
            // be a second full scan -- another flatten, another strip, another regex pass -- of
            // a component whose tags had been located a line earlier, which doubled the cost of
            // exactly the configuration chroma creates.
            boolean chromatic = changed && palette.isChromatic(SCAN[1]);

            memo.store(key, variant, changed ? result : null, generation, quantised, chromatic);
            if (breaker == TAB) {
                tabRecomputes++;
                if (measure) {
                    Metrics.tabRewrite(System.nanoTime() - startedNanos);
                }
            } else {
                nameTagRecomputes++;
                if (measure) {
                    Metrics.nameTagRewrite(System.nanoTime() - startedNanos);
                }
            }
            return changed ? result : null;
        } catch (Throwable failure) {
            // Deliberately Throwable: a LinkageError from a half-loaded class would otherwise
            // escape into the render loop, and the right response to any of it is identical --
            // leave the server's own colours on screen and carry on.
            breaker.record(failure, generation);
            // Remember the failure as "no change" so a permanently broken entry is not retried
            // every frame while the budget is being spent.
            memo.store(key, variant, null, generation, quantised, false);
            return null;
        }
    }

    /**
     * One surface's fail-safe: counts failures, logs the first one properly, and gives up on
     * the surface once the budget is gone.
     */
    private static final class Breaker {

        private final String surface;
        private int failures;
        private boolean off;
        private int trippedAtGeneration = -1;

        private Breaker(String surface) {
            this.surface = surface;
        }

        boolean tripped() {
            return off;
        }

        /**
         * Re-arms a tripped surface when the configuration has changed since it tripped.
         *
         * <p>A tripped surface is far more likely to be a bad setting than a bad build -- a
         * palette with no stops, a level range that inverts. Making the user restart the game
         * to test a fix would be a poor trade for a cosmetic feature.</p>
         */
        void rearmIfConfigChanged(int generation) {
            if (off && generation != trippedAtGeneration) {
                off = false;
                failures = 0;
                LOGGER.info("SkyPrism {} recolour re-enabled after a settings change.", surface);
            }
        }

        void record(Throwable failure, int generation) {
            failures++;
            if (failures == 1) {
                LOGGER.warn("SkyPrism {} recolour failed; leaving the server's own colours in place. "
                        + "Further failures will be counted silently.", surface, failure);
            }
            if (failures >= FAILURE_BUDGET && !off) {
                off = true;
                trippedAtGeneration = generation;
                LOGGER.error("SkyPrism {} recolour disabled after {} failures. "
                        + "Change any SkyPrism setting to try again.", surface, failures);
            }
        }
    }
}
