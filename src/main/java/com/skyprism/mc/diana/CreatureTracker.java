package com.skyprism.mc.diana;

import com.skyprism.core.diana.MythologicalCreature;
import com.skyprism.core.util.TextClean;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Binds a freshly announced Mythological creature to the entity carrying its nametag, then notices
 * when that entity dies.
 *
 * <h2>Why bother, when the chat line is authoritative?</h2>
 * <p>It is not quite authoritative. Chat tells us a creature <em>spawned</em>, and later that
 * something <em>dropped</em>, but there is no "you killed it" line, and a creature that drops nothing
 * -- which is the common case -- produces no second line at all. Binding the entity is what lets the
 * machine spin the instant the mob dies rather than waiting for loot that may never come, and it is
 * what makes the three-reel "No Drop" result reachable instead of theoretical.
 *
 * <p>The chat path stays the fallback for a creature killed out of view (a party member's Inquisitor,
 * or one that wandered behind a wall), and that fallback lives in {@link DianaController}; this class
 * only reports what it can actually see.
 *
 * <h2>How binding avoids sweeping the world</h2>
 * <p>Two complementary sources, neither of which iterates all loaded entities:
 * <ol>
 *   <li>{@code ClientEntityEvents.ENTITY_LOAD}, forwarded to {@link #onEntityLoad}. This catches the
 *       nametag arriving after the chat line, which is the usual ordering.</li>
 *   <li>A bounded {@link AABB} query around the player, run from {@link #scanNearby}. This catches
 *       the opposite ordering -- the mob was already loaded when the chat line arrived, so no load
 *       event is coming. The query is chunk-indexed and touches only the sections the box overlaps,
 *       which is the whole reason {@code entitiesForRendering()} is never used here.</li>
 * </ol>
 * Both are inert unless a creature is currently expected and the bind window is still open, so the
 * scan cannot run in a loop forever waiting for a mob that already died.
 *
 * <h2>How death is decided</h2>
 * <p>In falling order of confidence: the entity reports {@link Entity#isRemoved()} or stops being
 * alive; a {@link LivingEntity} reports {@link LivingEntity#isDeadOrDying()} or a non-zero
 * {@code deathTime}; or the entity unloads while close enough to the player that a chunk unload is
 * not a plausible explanation.
 *
 * <p>That last condition is the one that needs guarding, and it has three. The distance test rejects
 * the "player walked away and the chunk unloaded" case. The {@value #MIN_BIND_AGE_MILLIS} ms minimum
 * age rejects the load/unload flicker that happens when the server re-sends an entity. And the
 * entity must itself report a death signal, which is what rejects a <em>mass</em> unload: Fabric
 * raises ENTITY_UNLOAD for every entity in the level at the head of the respawn and clear-level
 * packet handlers, so dying or being warped between islands -- both of which Hypixel does with a
 * respawn packet -- would otherwise unload a living creature three blocks from a player who has not
 * moved, satisfy distance and age, and be read as a kill.
 *
 * <p><b>Threading:</b> client thread only.
 */
final class CreatureTracker {

    /** How long after a spawn line this class keeps trying to bind an entity. */
    private static final long BIND_WINDOW_MILLIS = 30_000L;

    /** How long a binding must have stood before an unload is allowed to mean "died". */
    private static final long MIN_BIND_AGE_MILLIS = 250L;

    /** Minimum gap between bounded scans while unbound, so a missing mob cannot busy-query. */
    private static final long SCAN_INTERVAL_MILLIS = 500L;

    /** Beyond this distance an unload is a chunk unload, not a kill. Squared to avoid a sqrt. */
    private static final double DEATH_RADIUS_SQR = 48.0 * 48.0;

    /** Horizontal and vertical extent of the bounded bind scan, in blocks. */
    private static final double SCAN_WIDTH = 48.0;
    private static final double SCAN_HEIGHT = 24.0;

    /** The creature a spawn line announced, or null when nothing is expected. */
    private MythologicalCreature expected;
    private long expectedAt;

    /**
     * The entity whose nametag names {@link #expected}.
     *
     * <p>A strong reference, held only between binding and the kill, and dropped by every exit path
     * including {@link #clear()} on disconnect. Holding an id and re-resolving it through
     * {@code ClientLevel.getEntity} each tick would avoid the reference at the cost of a lookup per
     * tick for the entire life of the fight, which is the wrong trade for an object this small.
     */
    private Entity bound;
    private long boundAt;

    private long nextScanAt;

    /** Set when an unload was judged a kill, so {@link #pollDefeat} can report it on the next tick. */
    private MythologicalCreature unloadDefeat;

    /**
     * Records that a spawn line named a creature, arming binding for the next
     * {@value #BIND_WINDOW_MILLIS} ms.
     *
     * @param creature the creature the server said was dug out, never null
     * @param now      the current time in milliseconds
     */
    void expect(MythologicalCreature creature, long now) {
        this.expected = creature;
        this.expectedAt = now;
        this.bound = null;
        this.boundAt = 0L;
        this.unloadDefeat = null;
        this.nextScanAt = 0L;
    }

    /** The creature currently expected, or null. */
    MythologicalCreature expected() {
        return expected;
    }

    /** Whether an expectation is still live, i.e. worth spending a scan on. */
    boolean expecting(long now) {
        return expected != null && now - expectedAt <= BIND_WINDOW_MILLIS;
    }

    /** Whether an entity is currently bound. */
    boolean bound() {
        return bound != null;
    }

    /** Forgets everything. Called on the gate's closing edge, on disconnect, and once a roll starts. */
    void clear() {
        expected = null;
        expectedAt = 0L;
        bound = null;
        boundAt = 0L;
        unloadDefeat = null;
        nextScanAt = 0L;
    }

    /**
     * Considers a newly loaded entity as the bind target.
     *
     * <p>Ordered cheapest test first: the expectation check is two field reads, and
     * {@link Entity#hasCustomName()} is a single flag, so the string work only happens for entities
     * that actually carry a label.
     *
     * @param entity the entity that just entered the world
     * @param now    the current time in milliseconds
     */
    void onEntityLoad(Entity entity, long now) {
        if (bound != null || entity == null || !expecting(now)) {
            return;
        }
        if (matches(entity, expected)) {
            bound = entity;
            boundAt = now;
        }
    }

    /**
     * Notices the bound entity leaving the world.
     *
     * @param entity   the entity being unloaded
     * @param playerEye the player's position, used to tell a kill from a chunk unload; may be null
     * @param now      the current time in milliseconds
     */
    void onEntityUnload(Entity entity, Vec3 playerEye, long now) {
        if (bound == null || entity != bound) {
            return;
        }
        boolean settled = now - boundAt >= MIN_BIND_AGE_MILLIS;
        boolean nearby = playerEye != null && entity.position().distanceToSqr(playerEye) <= DEATH_RADIUS_SQR;
        // The entity has to carry a death signal of its own. Distance and bind age are not
        // enough, because Fabric fires ENTITY_UNLOAD for *every* entity in the level at the head
        // of ClientPacketListener.handleRespawn and of clearLevel -- so a death, and on Hypixel
        // any proxy warp between islands, unloads the creature while the local player is still
        // standing next to it and the binding is seconds old. Both guards pass, and the machine
        // spins for a kill that never happened and writes it into the tally. During that flood
        // the mob is still alive and not yet removed, so requiring an actual death signal costs
        // nothing on a real kill (a removed entity reports isRemoved) and rejects the whole class
        // of false positive.
        if (settled && nearby && isDead(entity)) {
            unloadDefeat = expected;
        }
        bound = null;
        boundAt = 0L;
    }

    /**
     * Runs one bounded scan for the expected creature, if one is expected, nothing is bound yet, and
     * the scan throttle has elapsed.
     *
     * @param mc  the client, may be null
     * @param now the current time in milliseconds
     */
    void scanNearby(Minecraft mc, long now) {
        if (bound != null || !expecting(now) || now < nextScanAt) {
            return;
        }
        nextScanAt = now + SCAN_INTERVAL_MILLIS;
        if (mc == null || mc.level == null || mc.player == null) {
            return;
        }
        Vec3 eye = mc.player.position();
        AABB box = AABB.ofSize(eye, SCAN_WIDTH, SCAN_HEIGHT, SCAN_WIDTH);
        List<Entity> candidates = mc.level.getEntitiesOfClass(Entity.class, box, Entity::hasCustomName);
        // The nearest match, not the first the chunk query happens to return. The Hub is where
        // Diana is farmed and it is full of other players running the same ritual, so several
        // nametags reading "Minos Champion" inside the scan box is the ordinary case rather than
        // the unlucky one. A creature dug from your own burrow spawns essentially on top of you,
        // so the nearest candidate is the right one and an arbitrary one is a coin flip that
        // costs the player both the spin and the drop when it lands wrong.
        Entity best = null;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Entity candidate = candidates.get(i);
            if (!matches(candidate, expected)) {
                continue;
            }
            double distanceSqr = candidate.position().distanceToSqr(eye);
            if (distanceSqr < bestDistanceSqr) {
                best = candidate;
                bestDistanceSqr = distanceSqr;
            }
        }
        if (best != null) {
            bound = best;
            boundAt = now;
        }
    }

    /**
     * Reports a defeat exactly once, then disarms.
     *
     * <p>First line is a null check on {@link #bound} plus one on the unload flag, so the caller can
     * hang this on every tick: with nothing bound it is two field reads and a return.
     *
     * @param now the current time in milliseconds
     * @return the creature that just died, or null when nothing died
     */
    MythologicalCreature pollDefeat(long now) {
        if (unloadDefeat != null) {
            MythologicalCreature dead = unloadDefeat;
            clear();
            return dead;
        }
        if (bound == null) {
            return null;
        }
        if (now - boundAt < MIN_BIND_AGE_MILLIS) {
            return null;
        }
        if (!isDead(bound)) {
            return null;
        }
        MythologicalCreature dead = expected;
        clear();
        return dead;
    }

    /** Every death signal the two jars agree on, cheapest first. */
    private static boolean isDead(Entity entity) {
        if (entity.isRemoved() || !entity.isAlive()) {
            return true;
        }
        return entity instanceof LivingEntity living && (living.isDeadOrDying() || living.deathTime > 0);
    }

    /**
     * Whether an entity's custom name names this creature.
     *
     * <p>Hypixel labels a mythological mob with its health welded on -- brackets, the name, a health
     * figure and a heart glyph -- so the test is containment of the display name inside the
     * formatting-stripped label, not equality. Formatting has to go first because the name is
     * coloured mid-string.
     *
     * <p>{@link Entity#getCustomName()} is nullable even when {@link Entity#hasCustomName()} was
     * true a moment earlier, since the two are separate reads against live entity data.
     */
    private static boolean matches(Entity entity, MythologicalCreature creature) {
        if (creature == null || !entity.hasCustomName()) {
            return false;
        }
        Component name = entity.getCustomName();
        if (name == null) {
            return false;
        }
        String plain = TextClean.stripFormatting(name.getString());
        return plain != null && plain.contains(creature.displayName());
    }
}
