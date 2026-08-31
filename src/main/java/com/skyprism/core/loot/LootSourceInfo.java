package com.skyprism.core.loot;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the feature knows about one {@link LootSource}, as data rather than as code.
 *
 * <p>Keeping this in a table instead of scattered across detectors buys three things. The config
 * screen can enumerate every source with its caption, its shipped default and a sentence explaining
 * the gate, without a detector for it having been written yet. A test can assert invariants across
 * all of them at once -- and it does, which is how the never-fires policies stay honest. And a
 * detector author gets the markers and the captured sample lines handed to them, so the pre-filter
 * contract is satisfied by construction rather than by remembering.
 *
 * @param source            the constant this describes
 * @param displayName       the caption, e.g. "Slayer Boss"
 * @param defaultPolicy     the researched shipped default; see {@link RollPolicy}
 * @param gate              the coarse condition under which it may fire at all
 * @param triggers          how it announces itself
 * @param chatMarkers       literal substrings covering every line its detector can match
 * @param triggerSamples    real captured lines, used to prove the pre-filter cannot swallow one
 * @param jackpotItems      drops worth the three-of-a-kind flourish
 * @param emitsRareBanner   whether Hypixel prints a rarity flag this source can key on
 * @param note              one sentence on why the default is what it is
 */
public record LootSourceInfo(LootSource source,
                             String displayName,
                             RollPolicy defaultPolicy,
                             SourceGate gate,
                             Set<TriggerKind> triggers,
                             List<String> chatMarkers,
                             List<String> triggerSamples,
                             Set<String> jackpotItems,
                             boolean emitsRareBanner,
                             String note) {

    public LootSourceInfo {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        Objects.requireNonNull(gate, "gate");
        triggers = Set.copyOf(triggers);
        chatMarkers = List.copyOf(chatMarkers);
        triggerSamples = List.copyOf(triggerSamples);
        jackpotItems = Set.copyOf(jackpotItems);
        note = note == null ? "" : note;
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank for " + source);
        }
        if (triggers.isEmpty()) {
            throw new IllegalArgumentException("no trigger kind declared for " + source);
        }
        if (defaultPolicy == RollPolicy.ON_RARE_BANNER && !emitsRareBanner) {
            // The trap the whole design exists to avoid: a policy that can never be satisfied is a
            // detector that silently never fires, which looks exactly like one that works.
            throw new IllegalArgumentException(
                    source + " defaults to ON_RARE_BANNER but emits no rare banner");
        }
        if (defaultPolicy == RollPolicy.ON_JACKPOT_ITEM_ONLY && jackpotItems.isEmpty()) {
            throw new IllegalArgumentException(
                    source + " defaults to ON_JACKPOT_ITEM_ONLY but has no jackpot items");
        }
    }

    /** Whether this source is read from chat, and therefore owes markers and samples. */
    public boolean chatDriven() {
        return triggers.contains(TriggerKind.CHAT);
    }

    /** Whether this source is armed on a fresh install. */
    public boolean armedByDefault() {
        return defaultPolicy.armed();
    }

    /** A fluent builder; every source in the registry is written through one. */
    public static Builder builder(LootSource source, String displayName) {
        return new Builder(source, displayName);
    }

    /** Mutable while a registry entry is being described, frozen by {@link #build()}. */
    public static final class Builder {

        private final LootSource source;
        private final String displayName;
        private RollPolicy policy = RollPolicy.NEVER;
        private SourceGate gate = SourceGate.anywhere();
        private final Set<TriggerKind> triggers = EnumSet.noneOf(TriggerKind.class);
        private final List<String> markers = new ArrayList<>();
        private final List<String> samples = new ArrayList<>();
        private final Set<String> jackpot = new LinkedHashSet<>();
        private boolean emitsRareBanner;
        private String note = "";

        private Builder(LootSource source, String displayName) {
            this.source = Objects.requireNonNull(source, "source");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
        }

        public Builder policy(RollPolicy value) {
            this.policy = value;
            return this;
        }

        public Builder gate(SourceGate value) {
            this.gate = value;
            return this;
        }

        public Builder triggers(TriggerKind... values) {
            for (TriggerKind kind : values) {
                triggers.add(kind);
            }
            return this;
        }

        public Builder markers(String... values) {
            for (String value : values) {
                markers.add(value);
            }
            triggers.add(TriggerKind.CHAT);
            return this;
        }

        public Builder samples(String... values) {
            for (String value : values) {
                samples.add(value);
            }
            triggers.add(TriggerKind.CHAT);
            return this;
        }

        public Builder jackpot(String... values) {
            for (String value : values) {
                jackpot.add(value);
            }
            return this;
        }

        /** Declares that Hypixel prints a rarity flag on this source, unlocking ON_RARE_BANNER. */
        public Builder rareBanner() {
            this.emitsRareBanner = true;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
            return this;
        }

        public LootSourceInfo build() {
            return new LootSourceInfo(source, displayName, policy, gate, triggers, markers, samples,
                    jackpot, emitsRareBanner, note);
        }
    }
}
