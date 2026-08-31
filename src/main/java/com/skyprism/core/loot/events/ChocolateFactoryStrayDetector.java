package com.skyprism.core.loot.events;

import com.skyprism.core.loot.LootEvent;
import com.skyprism.core.loot.LootSource;
import com.skyprism.core.loot.RegistryDetector;
import com.skyprism.core.util.TextClean;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stray rabbit caught in the Chocolate Factory.
 *
 * <h2>Default policy: ON_JACKPOT_ITEM_ONLY, and this is the clearest such case in the whole mod</h2>
 * <p>An active chocolate player clicks several strays a minute; a session on {@code ALWAYS} would
 * spin the reels hundreds of times and turn the feature from delightful into hostile. But there is
 * no rarity banner anywhere on these lines, so {@code ON_RARE_BANNER} would be a policy that can
 * never be satisfied -- a detector that silently never fires. What the lines <em>do</em> carry is
 * the stray's name, and the only three strays anyone celebrates (Golden Rabbit, El Dorado, Fish the
 * Rabbit) are all named in the text. So the jackpot list does all the work, which is exactly the
 * situation {@code ON_JACKPOT_ITEM_ONLY} exists for.
 *
 * <h2>Gate: the GUI, not the season</h2>
 * <p>The Chocolate Factory runs all year, not only during Hoppity's Hunt, so a seasonal gate would
 * be wrong. The registry gates it on the Factory screen, which is the cheapest gate in the feature:
 * a screen opens a handful of times a minute at worst. This detector deliberately does <b>not</b>
 * require the screen to have been seen before it will match a line, because these lines exist
 * nowhere else in the game and a state machine that has to be armed first is one more way for a
 * feature to silently do nothing. {@link #onScreenTitle(String, long)} therefore returns empty:
 * opening the Factory is not a payout.
 */
public final class ChocolateFactoryStrayDetector extends RegistryDetector {

    /**
     * The descriptive form, which names the stray and what it paid.
     *
     * <p>Verbatim shape from SkyHanni's {@code CFStrayTracker}, whose captured lines include
     * {@code \u00A77You caught a stray \u00A76\u00A7lGolden Rabbit\u00A77! \u00A77You gained \u00A76+13,566,571 Chocolate\u00A77!},
     * {@code \u00A77You caught a stray \u00A79Fish the Rabbit\u00A77!} and the El Dorado variants that continue
     * {@code \u00A77You caught \u00A76El Dorado \u00A77- quite the elusive rabbit!} or "...but he escaped".
     */
    private static final Pattern STRAY_CAUGHT = Pattern.compile(
            "\u00A77You caught a stray (?:\u00A7.)*(?<name>[^\u00A7]+?)\u00A77[!.].*");

    /**
     * The banner form, where the stray's name leads and the bold CAUGHT! follows.
     *
     * <p>SkyHanni's own pattern is {@code ^(?:\u00A7.)*(?<name>.*) \u00A7d\u00A7lCAUGHT!}. The name group is
     * narrowed to exclude section signs here for the same reason as everywhere else in this
     * package: a {@code .*} in a name position happily swallows a colour run and hands the reel a
     * caption with formatting codes baked into it.
     */
    private static final Pattern STRAY_BANNER = Pattern.compile(
            "(?:\u00A7.)*(?<name>[^\u00A7]+?) ?\u00A7d\u00A7lCAUGHT!.*");

    public ChocolateFactoryStrayDetector() {
        super(LootSource.CHOCOLATE_FACTORY_STRAY);
    }

    @Override
    public Optional<LootEvent> onChat(String rawLine, long nowMillis) {
        if (rawLine == null) {
            return Optional.empty();
        }
        Matcher caught = STRAY_CAUGHT.matcher(rawLine);
        if (caught.matches()) {
            return named(caught.group("name"), nowMillis);
        }
        Matcher banner = STRAY_BANNER.matcher(rawLine);
        if (banner.matches()) {
            return named(banner.group("name"), nowMillis);
        }
        return Optional.empty();
    }

    /**
     * Opening the Chocolate Factory is not itself a payout, so this stays empty.
     *
     * <p>Stated rather than left implicit: the registry records a screen trigger for this source
     * because the screen is its gate, and it would be an easy and invisible mistake to read that as
     * "roll when the Factory opens", which would spin the machine every time the player checks their
     * chocolate.
     */
    @Override
    public Optional<LootEvent> onScreenTitle(String title, long nowMillis) {
        return Optional.empty();
    }

    private Optional<LootEvent> named(String rawName, long nowMillis) {
        String name = TextClean.clean(rawName);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(event(name, nowMillis));
    }
}
