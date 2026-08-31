package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;

import java.util.Optional;

/**
 * A sea creature surfaced.
 *
 * <p>Two instances of one class, because it is one table read twice. {@link #rare()} speaks for
 * {@link LootSource#FISHING_RARE_SEA_CREATURE} and fires only on the 23 creatures the corpus itself
 * flags rare; {@link #ordinary()} speaks for {@link LootSource#FISHING_SEA_CREATURE} and fires only
 * on the other 66. The split is exclusive by construction -- both read {@code creature.rare()} and
 * take opposite branches -- so one catch can never spin the machine twice, whichever order the two
 * are registered in.
 *
 * <h2>The defaults, and why they are opposite</h2>
 * <p><b>Rare: ALWAYS.</b> This is the fishing analogue of a Minos Inquisitor. Lord Jawbus, Thunder,
 * Ragnarok, the Wiki Tiki, Nessie and the Frog Prince are minutes to hours apart, and they are
 * exactly the catches the reference mods already fire a screen title and a sound for. The rarity
 * classification was done upstream by the corpus, so arming this costs nothing in noise.
 *
 * <p><b>Ordinary: NEVER.</b> This is the highest-frequency event in the entire feature. A geared
 * player with a hotspot hooks one every two to five seconds and a Double Hook prints two
 * announcements back to back -- several hundred an hour. {@code ALWAYS} here would not be a
 * preference, it would be a bug: the reels would never finish settling. The constant exists so the
 * config screen can list it and so a player who wants it can have it, and the honest second choice
 * for anyone who arms it is {@code ON_RARE_BANNER}, which spins only when a drop from the kill
 * carried a server banner.
 *
 * <h2>Cost</h2>
 * <p>Neither instance declares a chat marker, because the announcements share no literal to declare
 * -- see {@link SeaCreatures}. That means the bus hands them every line, so the whole per-line cost
 * has to live in {@link SeaCreatures#matchRaw(String)}, where it is a length test and one pass that
 * allocates nothing before the line looks like a sentence at all.
 */
public final class SeaCreatureDetector extends RegistryDetector {

    private final boolean wantRare;

    private SeaCreatureDetector(LootSource source, boolean wantRare) {
        super(source);
        this.wantRare = wantRare;
    }

    /** The 23 corpus-flagged rare creatures: Jawbus, Thunder, Ragnarok, Nessie and friends. */
    public static SeaCreatureDetector rare() {
        return new SeaCreatureDetector(LootSource.FISHING_RARE_SEA_CREATURE, true);
    }

    /** Everything else -- the firehose. Shipped on NEVER; see the class notes. */
    public static SeaCreatureDetector ordinary() {
        return new SeaCreatureDetector(LootSource.FISHING_SEA_CREATURE, false);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        SeaCreatures.Creature creature = SeaCreatures.matchRaw(rawLine);
        if (creature == null || creature.rare() != wantRare) {
            return Optional.empty();
        }
        return Optional.of(event(creature.name(), nowMillis));
    }
}
