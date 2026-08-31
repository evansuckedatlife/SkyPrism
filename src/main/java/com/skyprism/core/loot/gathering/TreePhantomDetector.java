package com.skyprism.core.loot.gathering;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A phantom falling out of a felled tree on Galatea.
 *
 * <p>ALWAYS, and it needs little defending: it is a bonus outcome of a tree gift, so a handful an
 * hour at most, it names the creature, and the creature then drops shards -- a natural double beat
 * for the widget, where the phantom spins the reels and its shards arrive while they are still
 * moving.
 *
 * <h2>The pattern and the closed set</h2>
 * <p>Verbatim from SkyHanni ForagingTrackerLegacy.kt (foraging.treegift.bonus-gift.phantoms), which
 * carries three captured lines: a section-r section-7 "A ", then the phantom in pink, then "fell
 * from the Tree!". It is used with matches(), so the prefix can only be spaces and formatting codes
 * -- a player quoting the sentence arrives with their name and a colon in front of it and cannot
 * match, which is the same anchoring argument DianaPatterns already makes for boss lines.
 *
 * <p>The name is nonetheless checked against a closed set from SkyHanni-REPO
 * constants/foraging/TreeGiftBonusDrops.json: Phanpyre, Phanflare, Dreadwing, Groundhog, Firefox,
 * Drybark, Puck, Grizzly Bear. A name outside the set is still accepted -- Hypixel adds mobs, and a
 * detector that stops working the day it does is worse than one that captions a new phantom
 * correctly -- but only after it is stripped of formatting and checked to look like a creature
 * name: letters, spaces, apostrophes and hyphens, and short. That keeps a caption drawn into a
 * fixed-width widget from ever being an arbitrary server-controlled string.
 */
public final class TreePhantomDetector extends RegistryDetector {

    /** Captured: "(r)(7)A (r)(d)Phanpyre (r)(7)fell from the Tree!" */
    private static final Pattern PHANTOM = Pattern.compile(
            " *(?:\u00A7.)+A (?:\u00A7.)+(?<phantom>.*) (?:\u00A7.)+fell from the Tree!");

    /** Every phantom the repo knows. Not a whitelist; see the class notes. */
    static final List<String> KNOWN = List.of("Phanpyre", "Phanflare", "Dreadwing", "Groundhog",
            "Firefox", "Drybark", "Puck", "Grizzly Bear");

    /** Longest known name is "Grizzly Bear"; twice that is generous and still bounded. */
    private static final int MAX_NAME_LENGTH = 24;

    public TreePhantomDetector() {
        super(LootSource.FORAGING_TREE_PHANTOM);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null || rawLine.indexOf("fell from the Tree!") < 0) {
            return Optional.empty();
        }
        Matcher matcher = PHANTOM.matcher(rawLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String name = TextClean.clean(matcher.group("phantom"));
        return looksLikeName(name) ? Optional.of(event(name, nowMillis)) : Optional.empty();
    }

    /** Letters, spaces, apostrophes and hyphens only, and short. */
    private static boolean looksLikeName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetter(c) && c != ' ' && c != '\'' && c != '-') {
                return false;
            }
        }
        return true;
    }
}
