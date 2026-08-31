package com.skyprism.core.loot.gathering;

import com.skyprism.core.util.TextClean;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every sea creature Hypixel announces, and the sentence it announces itself with.
 *
 * <h2>Why a table and not a regex</h2>
 * <p>Sea creature catches carry no banner, no rarity word and no shared literal. "A Squid
 * appeared.", "What is this creature!?" and "The Loch Emperor arises from the depths." have nothing
 * in common -- the second one does not even name the creature. There is nothing to anchor a pattern
 * on, which is why both reference mods do exactly what this class does: strip the formatting and
 * compare the whole line for equality against a fixed table. That is one hash lookup, cheaper than
 * any regex, and it is also the only shape that cannot be spoofed. A player typing "A Squid
 * appeared." in chat arrives as "PlayerName: A Squid appeared." and misses by the prefix, where a
 * {@code find()} on the same sentence would have handed someone a remote control for the widget.
 *
 * <h2>The cheap guard in front of the lookup</h2>
 * <p>These two detectors declare no chat markers -- there is no literal to declare -- so the bus
 * offers them every line, including every word of guild chat. {@link #matchRaw(String)} therefore
 * refuses to allocate before it has a reason to: one pass over the raw line finds the last
 * character that is not a formatting code or trailing space, and unless that character is one the
 * corpus actually ends with, and the line is at least as long as the shortest announcement, the
 * line is rejected without a {@code StringBuilder}, a {@code Matcher} or a substring. Both bounds
 * are derived from the table at class-init rather than typed in, so they cannot drift away from it.
 *
 * <h2>Provenance</h2>
 * <p>The complete corpus: 90 creatures across 22 variants, read out of {@code SkyHanni-REPO}
 * {@code constants/SeaCreatures.json} (HEAD 2026-08-30), cross-checked against Skyblocker's
 * independent copy in {@code skyblock/fishing/SeaCreature.java}. The {@code rare} flag is the
 * corpus's own, and it is what splits {@link com.skyprism.core.loot.LootSource#FISHING_RARE_SEA_CREATURE}
 * from {@link com.skyprism.core.loot.LootSource#FISHING_SEA_CREATURE}: the rarity filtering was
 * done upstream by people watching the game, not guessed here.
 *
 * <p>Two entries in the corpus need saying out loud. <b>Baby Magma Slug announces nothing</b> --
 * its {@code chat_message} is the empty string -- so it is absent from this table and can never be
 * detected from chat; it is not an omission. And <b>the Titanoboa has two spellings</b>: the corpus
 * says "Its body stretches", Skyblocker says "It's body stretches". Hypixel corrected the line at
 * some point, so both are registered, because accepting only one would silently drop a mythic on
 * whichever half of the population the server is still sending the other to.
 */
public final class SeaCreatures {

    /** One creature, as the corpus describes it. */
    public record Creature(String name, String rarity, boolean rare) {
    }

    private static final Map<String, Creature> BY_MESSAGE = build();

    /** Length of the shortest announcement, so a shorter raw line cannot possibly be one. */
    private static final int MIN_LENGTH = minLength();

    /** The characters an announcement can end on, as a dense ASCII table. Derived, never typed. */
    private static final boolean[] TERMINALS = terminals();

    private SeaCreatures() {
    }

    /**
     * Looks up a creature by an already-cleaned announcement.
     *
     * @param cleanedLine the line with formatting stripped and whitespace collapsed
     * @return the creature, or empty when the line is not an announcement
     */
    public static Optional<Creature> byMessage(String cleanedLine) {
        return cleanedLine == null ? Optional.empty() : Optional.ofNullable(BY_MESSAGE.get(cleanedLine));
    }

    /**
     * The per-line path: a guard that allocates nothing, then a clean and one hash lookup.
     *
     * @param rawLine the chat line with its formatting codes intact
     * @return the creature this line announces, or null -- null rather than {@code Optional}
     *         because this is the method that runs on every line of guild chat
     */
    public static Creature matchRaw(String rawLine) {
        if (rawLine == null || rawLine.length() < MIN_LENGTH || !endsPlausibly(rawLine)) {
            return null;
        }
        return BY_MESSAGE.get(TextClean.clean(rawLine));
    }

    /** How many announcements are known. Diagnostics and tests. */
    public static int size() {
        return BY_MESSAGE.size();
    }

    /** Every known announcement, in corpus order. Tests and {@code /skyprism} diagnostics. */
    public static Map<String, Creature> byMessage() {
        return BY_MESSAGE;
    }

    /**
     * Whether the last character that is neither a formatting code nor trailing whitespace is one
     * an announcement can end on.
     *
     * <p>Hand-rolled and single-pass on purpose. It is the whole reason a markerless detector is
     * affordable: every ordinary chat line that does not end in a full stop, an exclamation mark or
     * a question mark is rejected here having allocated nothing at all.
     */
    private static boolean endsPlausibly(String raw) {
        char last = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == TextClean.SECTION && i + 1 < raw.length()) {
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            last = c;
        }
        return last < TERMINALS.length && TERMINALS[last];
    }

    private static int minLength() {
        int min = Integer.MAX_VALUE;
        for (String message : BY_MESSAGE.keySet()) {
            min = Math.min(min, message.length());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static boolean[] terminals() {
        boolean[] table = new boolean[128];
        for (String message : BY_MESSAGE.keySet()) {
            char last = message.charAt(message.length() - 1);
            if (last < table.length) {
                table[last] = true;
            }
        }
        return table;
    }

    private static Map<String, Creature> build() {
        Map<String, Creature> map = new LinkedHashMap<>(128);
        Builder add = (name, rarity, rare, message) ->
                map.put(message, new Creature(name, rarity, rare));
        seed(add);
        // The Titanoboa's second spelling. Hypixel corrected "It's" to "Its"; Skyblocker still
        // carries the old wording, which is decent evidence the old one was live for a while.
        map.put("A massive Titanoboa surfaces. It's body stretches as far as the eye can see.",
                new Creature("Titanoboa", "MYTHIC", true));
        return Collections.unmodifiableMap(map);
    }

    @FunctionalInterface
    private interface Builder {
        void add(String name, String rarity, boolean rare, String message);
    }

    private static void seed(Builder add) {
        add.add("Night Squid", "COMMON", false, "Pitch darkness reveals a Night Squid.");
        add.add("Agarimoo", "RARE", false, "Your Chumcap Bucket trembles, it's an Agarimoo.");
        add.add("Carrot King", "RARE", true, "Is this even a fish? It's the Carrot King!");
        add.add("Squid", "COMMON", false, "A Squid appeared.");
        add.add("Sea Walker", "COMMON", false, "You caught a Sea Walker.");
        add.add("Sea Guardian", "COMMON", false, "You stumbled upon a Sea Guardian.");
        add.add("Sea Archer", "UNCOMMON", false, "You reeled in a Sea Archer.");
        add.add("Rider of the Deep", "UNCOMMON", false, "The Rider of the Deep has emerged.");
        add.add("Sea Witch", "UNCOMMON", false, "It looks like you've disrupted the Sea Witch's brewing session. Watch out, she's furious!");
        add.add("Catfish", "RARE", false, "Huh? A Catfish!");
        add.add("Sea Leech", "RARE", false, "Gross! A Sea Leech!");
        add.add("Guardian Defender", "EPIC", false, "You've discovered a Guardian Defender of the sea.");
        add.add("Deep Sea Protector", "EPIC", false, "You have awoken the Deep Sea Protector, prepare for a battle!");
        add.add("Water Hydra", "LEGENDARY", true, "The Water Hydra has come to test your strength.");
        add.add("Frozen Steve", "COMMON", false, "Frozen Steve fell into the pond long ago, never to resurface...until now!");
        add.add("Frosty", "COMMON", false, "It's a snowman! He looks harmless.");
        add.add("Grinch", "UNCOMMON", false, "The Grinch stole Jerry's Gifts...get them back!");
        add.add("Nutcracker", "EPIC", false, "You found a forgotten Nutcracker laying beneath the ice.");
        add.add("Yeti", "LEGENDARY", true, "What is this creature!?");
        add.add("Reindrake", "MYTHIC", true, "A Reindrake forms from the depths.");
        add.add("Scarecrow", "COMMON", false, "Phew! It's only a Scarecrow.");
        add.add("Nightmare", "RARE", false, "You hear trotting from beneath the waves, you caught a Nightmare.");
        add.add("Werewolf", "EPIC", false, "It must be a full moon, a Werewolf appears.");
        add.add("Phantom Fisher", "LEGENDARY", true, "The spirit of a long lost Phantom Fisher has come to haunt you.");
        add.add("Grim Reaper", "LEGENDARY", true, "This can't be! The manifestation of death himself!");
        add.add("Jumpin' Jack", "COMMON", false, "Watch out! It's Jumpin' Jack.");
        add.add("Nurse Shark", "COMMON", false, "A tiny fin emerges from the water, you've caught a Nurse Shark.");
        add.add("Blue Shark", "UNCOMMON", false, "You spot a fin as blue as the water it came from, it's a Blue Shark.");
        add.add("Tiger Shark", "EPIC", false, "A striped beast bounds from the depths, the wild Tiger Shark!");
        add.add("Great White Shark", "LEGENDARY", true, "Hide no longer, a Great White Shark has tracked your scent and thirsts for your blood!");
        add.add("Oasis Sheep", "UNCOMMON", false, "An Oasis Sheep appears from the water.");
        add.add("Oasis Rabbit", "UNCOMMON", false, "An Oasis Rabbit appears from the water.");
        add.add("Small Mithril Grubber", "UNCOMMON", false, "A leech of the mines surfaces... you've caught a Mithril Grubber.");
        add.add("Medium Mithril Grubber", "UNCOMMON", false, "A leech of the mines surfaces... you've caught a Medium Mithril Grubber.");
        add.add("Large Mithril Grubber", "UNCOMMON", false, "A leech of the mines surfaces... you've caught a Large Mithril Grubber.");
        add.add("Bloated Mithril Grubber", "UNCOMMON", false, "A leech of the mines surfaces... you've caught a Bloated Mithril Grubber.");
        add.add("Lava Blaze", "RARE", false, "A Lava Blaze has surfaced from the depths!");
        add.add("Lava Pigman", "RARE", false, "A Lava Pigman arose from the depths!");
        add.add("Flaming Worm", "RARE", false, "A Flaming Worm surfaces from the depths!");
        add.add("Water Worm", "RARE", false, "A Water Worm surfaces!");
        add.add("Poisoned Water Worm", "RARE", false, "A Poisoned Water Worm surfaces!");
        add.add("Abyssal Miner", "LEGENDARY", true, "An Abyssal Miner breaks out of the water!");
        add.add("Moogma", "RARE", false, "You hear a faint Moo from the lava... A Moogma appears.");
        add.add("Magma Slug", "RARE", false, "From beneath the lava appears a Magma Slug.");
        add.add("Pyroclastic Worm", "RARE", false, "You feel the heat radiating as a Pyroclastic Worm surfaces.");
        add.add("Lava Flame", "RARE", false, "A Lava Flame flies out from beneath the lava.");
        add.add("Fire Eel", "RARE", false, "A Fire Eel slithers out from the depths.");
        add.add("Lava Leech", "RARE", false, "A small but fearsome Lava Leech emerges.");
        add.add("Taurus", "RARE", false, "Taurus and his steed emerge.");
        add.add("Thunder", "MYTHIC", true, "You hear a massive rumble as Thunder emerges.");
        add.add("Lord Jawbus", "MYTHIC", true, "You have angered a legendary creature... Lord Jawbus has arrived.");
        add.add("Plhlegblast", "MYTHIC", true, "WOAH! A Plhlegblast appeared.");
        add.add("Trash Gobbler", "COMMON", false, "The Trash Gobbler is hungry for you!");
        add.add("Banshee", "RARE", false, "The desolate wail of a Banshee breaks the silence.");
        add.add("Alligator", "LEGENDARY", true, "A long snout breaks the surface of the water. It's an Alligator!");
        add.add("Dumpster Diver", "UNCOMMON", false, "A Dumpster Diver has emerged from the swamp!");
        add.add("Bayou Sludge", "EPIC", false, "A swampy mass of slime emerges, the Bayou Sludge!");
        add.add("Titanoboa", "MYTHIC", true, "A massive Titanoboa surfaces. Its body stretches as far as the eye can see.");
        add.add("Ragnarok", "MYTHIC", true, "The sky darkens and the air thickens. The end times are upon us: Ragnarok is here.");
        add.add("Volcanic Snail", "UNCOMMON", false, "You feel a burning sensation as you reel in a Volcanic Snail!");
        add.add("Fireproof Witch", "RARE", false, "Trouble's brewing, it's a Fireproof Witch!");
        add.add("Fried Chicken", "COMMON", false, "Smells of burning. Must be a Fried Chicken.");
        add.add("Magma Pillar", "EPIC", false, "A Magma Pillar rises from the lava.");
        add.add("Fiery Scuttler", "LEGENDARY", true, "A Fiery Scuttler inconspicuously waddles up to you, friends in tow.");
        add.add("Wiki Tiki", "MYTHIC", true, "The water bubbles and froths. A massive form emerges- you have disturbed the Wiki Tiki! You shall pay the price.");
        add.add("Blue Ringed Octopus", "LEGENDARY", true, "A garish set of tentacles arise. It's a Blue Ringed Octopus!");
        add.add("Snapping Turtle", "RARE", false, "A Snapping Turtle is coming your way, and it's ANGRY!");
        add.add("Frog Man", "COMMON", false, "Is it a frog? Is it a man? Well, yes, sorta, IT'S FROG MAN!!!!!!");
        add.add("Inkling", "UNCOMMON", false, "You get an inkling that you've caught... an Inkling!");
        add.add("Manta Ray", "EPIC", false, "A majestic creature rises from the water. It's a Manta Ray.");
        add.add("Wetwing", "UNCOMMON", false, "Look! A Wetwing emerges!");
        add.add("Ent", "EPIC", false, "You've hooked an Ent, as ancient as the forest itself.");
        add.add("Tadgang", "RARE", false, "A gang of Liltads!");
        add.add("Bogged", "COMMON", false, "You've hooked a Bogged!");
        add.add("Stridersurfer", "RARE", false, "You caught a Stridersurfer.");
        add.add("The Loch Emperor", "LEGENDARY", true, "The Loch Emperor arises from the depths.");
        add.add("Nessie", "MYTHIC", true, "You've caused a disturbance in the loch. Could it be... Nessie?");
        add.add("Atoll Croaker", "COMMON", false, "An inquisitive Atoll Croaker takes the bait!");
        add.add("Lotus Guardian", "UNCOMMON", false, "A Lotus Guardian emerges, ready to protect the Atoll.");
        add.add("gorF", "RARE", false, "What even is that?! A... gorF?");
        add.add("Drowned Captain", "EPIC", false, "A Drowned Captain takes hold of your bobber!");
        add.add("Puddle Jumper", "LEGENDARY", true, "A Puddle Jumper is preparing for liftoff\u2014cast your rod into it and hold on tight!");
        add.add("Frog Prince", "MYTHIC", true, "Bow down before the Frog Prince... or pay the hefty price!");
        add.add("Haggard", "COMMON", false, "A Haggard stumbles to the shore, ready for a fight!");
        add.add("Brineling", "UNCOMMON", false, "A Brineling interrupts you with a stream of bubbles!");
        add.add("Sprawl", "RARE", false, "A Sprawl emerges from the blue, and it's looking for you!");
        add.add("Torrid", "EPIC", false, "The laughter of a Torrid echoes through the air.");
        add.add("Silkbreeze", "LEGENDARY", true, "Something zips through the air - it's a Silkbreeze!");
        add.add("Giant Isopod", "MYTHIC", true, "A Giant Isopod was dredged up from the depths!");
    }
}
