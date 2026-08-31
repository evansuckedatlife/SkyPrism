package com.skyprism.core.loot;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The seeded table of every {@link LootSource}: caption, shipped {@link RollPolicy}, gate, chat
 * markers, real captured trigger lines and jackpot list.
 *
 * <h2>Where this data came from</h2>
 * <p>Every sample line in this file is a string read out of a reference mod's source or its own
 * captured regression corpus, transcribed verbatim with section signs written as escapes. Nothing
 * here was written from memory, and where the research found no verified signal for a source, the
 * source is still listed -- with an honest policy and a note saying so -- rather than given an
 * invented pattern. An invented pattern is worse than an absent one: it ships a feature that looks
 * finished and silently never fires.
 *
 * <h2>The invariants, which are enforced rather than described</h2>
 * <ul>
 *   <li>Every {@link LootSource} constant has exactly one entry. Missing one is a test failure, not
 *       a null at runtime.</li>
 *   <li>A source may not default to {@link RollPolicy#ON_RARE_BANNER} unless it actually emits a
 *       rare banner, which is checked in {@link LootSourceInfo}'s constructor. The Ender Dragon is
 *       why: it has a kill line but drops its loot as armour stands with nothing in chat, so that
 *       policy there would be a detector that can never fire.</li>
 *   <li>A source may not default to {@link RollPolicy#ON_JACKPOT_ITEM_ONLY} with an empty jackpot
 *       list, for the same reason.</li>
 *   <li>Every declared chat marker must appear verbatim in at least one sample, and every sample
 *       must contain at least one marker. That is the pre-filter contract, and
 *       {@code LootEventBusPreFilterTest} additionally drives the real bus with these samples so
 *       the guarantee is end to end rather than on paper.</li>
 * </ul>
 *
 * <h2>Two sources deliberately carry no markers</h2>
 * <p>The sea creature announcements share no literal at all -- "A Squid appeared.", "What is this
 * creature!?", "The Loch Emperor arises from the depths." -- so there is nothing to pre-filter on.
 * Both reference mods detect them by exact equality against a fixed table, which is one hash lookup
 * and cheaper than any regex. Declaring no markers means "offer me every line", which is the safe
 * direction, and the note on those two entries says so out loud rather than pretending a marker
 * list exists.
 */
public final class LootSourceRegistry {

    private static final Map<LootSource, LootSourceInfo> INFOS = build();

    private LootSourceRegistry() {
    }

    /** The entry for {@code source}; never null, because every constant is present. */
    public static LootSourceInfo info(LootSource source) {
        Objects.requireNonNull(source, "source");
        LootSourceInfo info = INFOS.get(source);
        if (info == null) {
            throw new IllegalStateException("no registry entry for " + source);
        }
        return info;
    }

    /** The caption for {@code source}, e.g. "Slayer Boss". */
    public static String displayName(LootSource source) {
        return info(source).displayName();
    }

    /** The shipped default policy for {@code source}. */
    public static RollPolicy defaultPolicy(LootSource source) {
        return info(source).defaultPolicy();
    }

    /** The gate {@code source} sits behind. */
    public static SourceGate gate(LootSource source) {
        return info(source).gate();
    }

    /** Every entry, in {@link LootSource} declaration order. */
    public static Collection<LootSourceInfo> all() {
        return INFOS.values();
    }

    /** Whether {@code ctx} lets {@code source} fire at all. */
    public static boolean gateOpen(LootSource source, GameContext ctx) {
        return info(source).gate().isOpen(ctx);
    }

    private static Map<LootSource, LootSourceInfo> build() {
        Map<LootSource, LootSourceInfo> map = new EnumMap<>(LootSource.class);
        for (LootSourceInfo info : entries()) {
            if (map.put(info.source(), info) != null) {
                throw new IllegalStateException("duplicate registry entry for " + info.source());
            }
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private static List<LootSourceInfo> entries() {
        return List.of(

                // ======================================================= Diana, the shipped path

                LootSourceInfo.builder(LootSource.DIANA_MYTHOLOGICAL, "Mythological Ritual")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.mayor("Diana"))
                        .triggers(TriggerKind.ENTITY)
                        .rareBanner()
                        .markers("You dug out")
                        .samples(
                                "§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!",
                                "§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!")
                        .jackpot("Mythological Dye", "Myth the Fish", "Minos Relic",
                                "Braided Griffin Feather", "Daedalus Stick", "Crochet Tiger Plushie",
                                "Shimmering Wool", "Manti-core", "Washed-up Souvenir", "Cretan Urn",
                                "Hilt of Revelations", "Brain Food", "Antique Remedies",
                                "Dwarf Turtle Shelmet", "Fateful Stinger", "Chimera I", "Chimera",
                                "Crown of Greed")
                        .note("The shipped path and the only one verified on the live server. The roll "
                                + "fires on the bound creature being defeated, which is an entity "
                                + "event with no chat line; the treasure-dig lines above are loot, "
                                + "not the trigger. Its behaviour is frozen: this entry describes it, "
                                + "it does not drive it.")
                        .build(),

                // ======================================================= combat

                LootSourceInfo.builder(LootSource.SLAYER_BOSS, "Slayer Boss")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.anywhere())
                        .rareBanner()
                        .markers("SLAYER BOSS SLAIN!", "SLAYER QUEST COMPLETE!")
                        .samples(
                                "  §r§6§lNICE! SLAYER BOSS SLAIN!",
                                "  §r§a§lSLAYER QUEST COMPLETE!")
                        .jackpot("Judgement Core", "Enchant Rune I", "Void Conqueror Enderman Skin",
                                "Handy Blood Chalice", "Etherwarp Merger", "Sinful Dice",
                                "Pocket Espresso Machine", "Warden Heart", "Scythe Blade",
                                "Shredded Sinew", "Severed Hand", "Beheaded Horror", "Snake Rune I",
                                "Wilson's Engineering Plans", "Subzero Inverter",
                                "High Class Archfiend Dice", "Fiery Burst Rune I", "Byzantium Dye",
                                "Matcha Dye", "Brick Red Dye", "Celeste Dye", "Flame Dye")
                        .note("The closest analogue to the shipped Diana behaviour: a deliberate, "
                                + "discrete kill the player is waiting on, at Diana's own cadence. "
                                + "ON_RARE_BANNER would gut it, because the whole point of a slayer "
                                + "run is the moment of truth on a boss that usually drops nothing. "
                                + "The right knob for a T1 farmer is a minimum-tier floor, not a "
                                + "weaker policy. The true gate is 'the scoreboard has a Slayer "
                                + "Quest section', which is tighter than any island test and which "
                                + "GameContext cannot yet express.")
                        .build(),

                LootSourceInfo.builder(LootSource.SLAYER_MINIBOSS, "Slayer Miniboss")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.anywhere())
                        .rareBanner()
                        .markers("SLAYER MINI-BOSS", "assisting on a slayer miniboss")
                        .samples(
                                "  SLAYER MINI-BOSS",
                                "§eYou received kill credit for assisting on a slayer miniboss!")
                        .note("Same island, same quest, opposite cadence to SLAYER_BOSS: minibosses "
                                + "die several times a minute during the grind phase and rolling on "
                                + "each would make the widget a strobe. Shipped off, switchable on, "
                                + "with ON_RARE_BANNER the sensible second choice for anyone who "
                                + "specifically farms them.")
                        .build(),

                LootSourceInfo.builder(LootSource.MOB_RARE_DROP, "Rare Mob Drop")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.anywhere())
                        .rareBanner()
                        .markers("DROP!")
                        .samples(
                                "§6§lRARE DROP! §r§9Judgement Core §r§b(+§r§b168% §r§b✯ Magic Find§r§b)",
                                "§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) (+123% ✯ Magic Find)",
                                "§5§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) (+123% ✯ Magic Find)")
                        .jackpot("Summoning Eye", "Judgement Core", "Sorrow", "Hazmat Enderman",
                                "Pocket Espresso Machine")
                        .note("The catch-all that makes the feature SkyBlock-wide without a detector "
                                + "per mob. Deliberately always-on, which is affordable only because "
                                + "it is one regex behind a single indexOf of \"DROP!\" -- that one "
                                + "test is the entire per-line cost on the chat that is not a drop. "
                                + "ON_RARE_BANNER is definitionally the only sane value here: the "
                                + "banner is the trigger, so ALWAYS would either mean the same thing "
                                + "or invite someone to widen the regex.")
                        .build(),

                LootSourceInfo.builder(LootSource.PET_DROP, "Pet Drop")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.anywhere())
                        .rareBanner()
                        .markers("PET DROP!")
                        .samples(
                                "§6§lPET DROP! §r§5Baby Yeti §r§b(+§r§b168% §r§b✯ Magic Find§r§b)",
                                "§6§lPET DROP! §r§6Rat")
                        .jackpot("Golden Dragon", "Ender Dragon", "Tiger", "Griffin", "Baby Yeti",
                                "Bal")
                        .note("A pet drop is rare by construction, so the banner is already the "
                                + "rarity gate and ALWAYS carries no spam risk. Reserve the "
                                + "three-of-a-kind for the LEGENDARY and MYTHIC rarity colours "
                                + "rather than for named pets, so it stays correct as Hypixel adds "
                                + "them; the names below are a starting list, not the rule.")
                        .build(),

                LootSourceInfo.builder(LootSource.DUNGEON_BOSS, "Dungeon Boss")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.dungeon())
                        .rareBanner()
                        .markers("Defeated ")
                        .samples("                    ☠ Defeated Necron in 5m 43s")
                        .jackpot("Necron Dye", "Livid Dye")
                        .note("One roll per run, a run being three to ten minutes of committed play. "
                                + "The caption should carry the floor and the clear time so it reads "
                                + "as a run summary rather than a slot pull. The chests are the "
                                + "chest sources' territory; this one must not also roll on them or "
                                + "every run double-fires.")
                        .build(),

                LootSourceInfo.builder(LootSource.DUNGEON_RUN_COMPLETE, "Dungeon Run Complete")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.dungeon())
                        .rareBanner()
                        .markers("The Catacombs - ")
                        .samples("                Master Mode The Catacombs - Floor VII")
                        .note("The same event as DUNGEON_BOSS arriving a few lines later, so shipping "
                                + "both armed means two rolls per run. The constant exists so a "
                                + "player can swap which of the pair fires -- the summary arrives "
                                + "after the essence lines and so has more to lock onto -- but "
                                + "exactly one of the two should ever be live.")
                        .build(),

                LootSourceInfo.builder(LootSource.KUUDRA_COMPLETE, "Kuudra")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Kuudra"))
                        .markers("KUUDRA DOWN!")
                        .samples("§c§l                    §r§6§lKUUDRA DOWN!")
                        .jackpot("Tentacle Dye", "Infernal Kuudra Core", "Kraken Shard",
                                "Heavy Pearl")
                        .note("Two to five minutes of committed group play that costs a key: the "
                                + "definition of an event worth a roll, with no frequency risk. The "
                                + "payout arrives through the Free and Paid chest GUIs afterwards, "
                                + "which KUUDRA_REWARD_CHEST owns; this source rolls on the banner "
                                + "and captions the tier.")
                        .build(),

                LootSourceInfo.builder(LootSource.ENDER_DRAGON, "Ender Dragon")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.area("The End", "Dragon's Nest"))
                        .markers("DRAGON DOWN!")
                        .samples("§f                      §r§6§lPROTECTOR DRAGON DOWN!")
                        .jackpot("Pearlescent Dye", "Aspect of the Dragons", "Ender Dragon",
                                "Superior Dragon Fragment")
                        .note("Deliberately NOT ON_RARE_BANNER: dragon loot spawns as floating armour "
                                + "stands and is never announced in chat, so that policy would be a "
                                + "detector that silently never fires. Either something reads armour "
                                + "stands in the seconds after the banner, or this ships as a "
                                + "caption-only roll. Do not paper over it.")
                        .build(),

                LootSourceInfo.builder(LootSource.ENDSTONE_PROTECTOR, "Endstone Protector")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("The End"))
                        .markers("ENDSTONE PROTECTOR DOWN!")
                        .samples("§f                    §r§6§lENDSTONE PROTECTOR DOWN!")
                        .note("Rarer than the dragons and it takes a hundred zealot kills to summon, "
                                + "so unambiguously earned. Same armour-stand loot caveat as "
                                + "ENDER_DRAGON: caption-only unless the world is read.")
                        .build(),

                LootSourceInfo.builder(LootSource.CRIMSON_MINIBOSS, "Crimson Isle Miniboss")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Crimson Isle"))
                        .rareBanner()
                        .markers(" DOWN!")
                        .samples(
                                "§f                      §r§6§lASHFANG DOWN!",
                                "§f                      §r§6§lBARBARIAN DUKE X DOWN!")
                        .jackpot("Ashfang Helmet", "Ashfang Gloves", "Ashfang Cloak",
                                "Soul Esperance", "Mageblood Necklace", "Fel Pearl")
                        .note("A two-minute respawn floor means the worst case is a roll every two "
                                + "minutes, and only while camping one spawn -- well inside Diana's "
                                + "cadence. The five names are a closed set, which is what keeps the "
                                + "anchored match safe: never accept an arbitrary name out of a "
                                + "DOWN! banner, or a party message becomes a remote control for "
                                + "someone else's HUD.")
                        .build(),

                LootSourceInfo.builder(LootSource.VANQUISHER, "Vanquisher")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.island("Crimson Isle"))
                        .rareBanner()
                        .markers("DROP!", "A Vanquisher is spawning nearby!")
                        .samples(
                                "A Vanquisher is spawning nearby!",
                                "§6§lRARE DROP! §r§9Vanquisher Loot §r§b(+123% ✯ Magic Find)")
                        .note("No kill line could be verified -- the reference mod uses entity "
                                + "despawn instead, which is strong evidence none exists. Rolling on "
                                + "the spawn broadcast would be wrong: it fires for everyone in the "
                                + "lobby, including players nowhere near it. So the drop banner "
                                + "carries it, and the constant stays so an entity-side detector can "
                                + "later slot in behind the same source.")
                        .build(),

                LootSourceInfo.builder(LootSource.ARACHNE, "Arachne")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Spider's Den"))
                        .rareBanner()
                        .markers("ARACHNE DOWN!")
                        .samples("§f                              §r§6§lARACHNE DOWN!")
                        .jackpot("Arachne's Calling", "Arachne's Keeper Fragment",
                                "Travel Scroll to Spider's Den Top of Nest", "Bite Rune I")
                        .note("Needs crystals to summon and the fight is communal and infrequent: an "
                                + "unambiguous event.")
                        .build(),

                LootSourceInfo.builder(LootSource.BROODMOTHER, "Broodmother")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.island("Spider's Den"))
                        .rareBanner()
                        .markers("DROP!")
                        .samples("§6§lRARE DROP! §r§9Spider Catalyst §r§b(+123% ✯ Magic Find)")
                        .note("No Hypixel chat line for the Broodmother's death exists in either "
                                + "reference mod -- both track it through the tab-list stage widget "
                                + "-- and an invented regex here would look like a working feature "
                                + "that never fires. The universal banner carries it; promote to "
                                + "ALWAYS the day a kill line is confirmed.")
                        .build(),

                LootSourceInfo.builder(LootSource.GHOST_MIST, "Ghost")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.area("Dwarven Mines", "The Mist"))
                        .rareBanner()
                        .markers("DROP!", "materialized")
                        .samples(
                                "RARE DROP! Sorrow (+123% ✯ Magic Find)",
                                "The ghost's death materialized 1,000,000 coins from the mists!")
                        .jackpot("Sorrow", "Bag of Cash", "Ghost Cutlass", "Plasma", "Volta",
                                "Ectoplasm")
                        .note("Ghosts die every few seconds in a real grind, so ALWAYS is out; the "
                                + "banner is already the correct rarity filter. Model the Bag of Cash "
                                + "line the way Diana's coin payout is modelled -- an item named "
                                + "Coins with a count -- and give it the flourish.")
                        .build(),

                LootSourceInfo.builder(LootSource.DRACONIC_SACRIFICE, "Draconic Sacrifice")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("The End"))
                        .markers("BONUS LOOT!")
                        .samples("§c§lBONUS LOOT! §r§eYou also received §r§817x §r§5Wise Dragon Fragment §r§efrom your sacrifice!")
                        .jackpot("Superior Dragon Fragment", "Ender Dragon")
                        .note("Trigger on BONUS LOOT, never on SACRIFICE. Mass-sacrificing a stash "
                                + "emits dozens of SACRIFICE lines in seconds; the bonus IS the "
                                + "chance, so firing solely on it gives an already-correct rate with "
                                + "no policy machinery at all. This is the general template: pick "
                                + "the line that is the lottery, not the line that surrounds it.")
                        .build(),

                LootSourceInfo.builder(LootSource.ENDER_NODE, "Ender Node")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.island("The End"))
                        .markers("ENDER NODE!")
                        .samples("§5§lENDER NODE! §r§fYou found §r§8§r§aEnchanted Obsidian§r§f!")
                        .jackpot("Ender Gauntlet", "Ender Helmet", "Ender Chestplate",
                                "Ender Leggings", "Ender Boots", "End Stone Shulker")
                        .note("Node mining prints this line constantly and the overwhelming majority "
                                + "are Enchanted Obsidian or Ender Pearls; only the Ender armour is "
                                + "worth a spin. Mining-shaped content on a combat island, so it has "
                                + "exactly one owner.")
                        .build(),

                LootSourceInfo.builder(LootSource.REINDRAKE, "Reindrake")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.season("the Season of Jerry"))
                        .rareBanner()
                        .markers("from the depths!", "DROP!")
                        .samples(
                                "WOAH! [VIP] Georeek summoned a Reindrake from the depths!",
                                "§6§lRARE DROP! §r§9Reindrake Fragment §r§b(+123% ✯ Magic Find)")
                        .jackpot("Reindrake Fragment", "Frozen Blaze Helmet")
                        .note("The only verified line is a lobby-wide summon broadcast that fires "
                                + "whether or not you participate, so triggering on it would spin "
                                + "the widget for bystanders. The drop banner carries it until a "
                                + "kill line is verified.")
                        .build(),

                LootSourceInfo.builder(LootSource.PRIMAL_FEAR, "Primal Fear")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.season("the Great Spook"))
                        .rareBanner()
                        .markers("Primal Fear", "DROP!")
                        .samples(
                                "§5§lFEAR. §r§eA §r§dPrimal Fear §r§ehas been summoned!",
                                "§6§lRARE DROP! §r§9Ephemeral Gratitude §r§b(+123% ✯ Magic Find)")
                        .jackpot("Ephemeral Gratitude", "Bone Dye")
                        .note("The summon line is verified; the defeat line is not, and the summon "
                                + "fires for other people's fears too. Banner-gating is the honest "
                                + "default. Gated on the Great Spook, so it is shut for eleven "
                                + "months of the year.")
                        .build(),

                LootSourceInfo.builder(LootSource.HEADLESS_HORSEMAN, "Headless Horseman")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.season("the Spooky Festival"))
                        .rareBanner()
                        .markers("DROP!")
                        .samples("§6§lRARE DROP! §r§9Horseman's Horse §r§b(+123% ✯ Magic Find)")
                        .note("Listed for completeness only. Neither reference mod carries a spawn or "
                                + "kill line for it -- it is known solely as a damage-indicator boss "
                                + "type -- so no trigger regex was written. The universal banner is "
                                + "the only honest signal.")
                        .build(),

                LootSourceInfo.builder(LootSource.RIFT_BOSS, "Rift Boss")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.rift())
                        .rareBanner()
                        .markers("is growing into", "DROP!")
                        .samples(
                                "§aBac §r§eis growing into §r§aBact§r§e!",
                                "§6§lRARE DROP! §r§9Rift Loot §r§b(+123% ✯ Magic Find)")
                        .note("Bacte announces its growth phases; Leech Supreme and Sun Gecko are "
                                + "detected purely from entity names, and no kill line exists for any "
                                + "of the three. The banner is the only honest signal, and the Rift's "
                                + "economy is Motes, so ordinary banners are rare there anyway.")
                        .build(),

                LootSourceInfo.builder(LootSource.TREVOR_TRAPPER, "Trevor the Trapper")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.island("The Farming Islands"))
                        .rareBanner()
                        .markers("Return to the Trapper soon", "DROP!")
                        .samples(
                                "Return to the Trapper soon to get a new animal to hunt!",
                                "§6§lRARE DROP! §r§9Hunter Ring §r§b(+123% ✯ Magic Find)")
                        .jackpot("Hunter Ring", "Hunter Talisman", "Hunter Knife",
                                "Skin of the Wolf", "Trapper's Ring")
                        .note("A hunt completes every one to three minutes and the reward is usually "
                                + "mundane; the rarity tier is what matters and the banner encodes "
                                + "it. Narrowing the detector to Endangered and Elusive assignments "
                                + "would make ALWAYS defensible, since the rarity is in the "
                                + "assignment line.")
                        .build(),

                LootSourceInfo.builder(LootSource.COMBAT_SHARD, "Combat Shard Drop")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.anywhere())
                        .markers("Shard")
                        .samples(
                                "You caught a Birries Shard!",
                                "LOOT SHARE You received 2 Mossybit Shards for assisting FallenYeti!",
                                "FUSION! You obtained Bolt Shard x2! NEW!",
                                "You sent a Voracious Spider Shard to your Hunting Box.")
                        .note("Shards drop many times a minute from ordinary mobs since the Hunting "
                                + "update, so ALWAYS is out; there is no rarity banner on these "
                                + "lines either, so ON_RARE_BANNER would never fire. The one signal "
                                + "actually in the text is the trailing \" NEW!\" on a first-ever "
                                + "shard, which is a per-line flag the drop model does not yet carry "
                                + "-- so this ships off rather than shipping a rule it cannot "
                                + "honour. It also overlaps the hunting content, which needs a "
                                + "single owner before anything here arms.")
                        .build(),

                // ======================================================= containers and GUIs

                LootSourceInfo.builder(LootSource.DUNGEON_REWARD_CHEST, "Catacombs Reward Chest")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.screen("reward chest"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .rareBanner()
                        .markers("RARE REWARD!")
                        .samples("§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Recombobulator 3000 §r§ein their Obsidian Chest§r§e!")
                        .jackpot("Recombobulator 3000", "Necron's Handle", "Shadow Warp",
                                "Implosion", "Wither Shield", "Dark Claymore", "Fifth Master Star",
                                "Judgement Core", "Necron Dye", "Livid Dye")
                        .note("A Catacombs grinder opens ten to twenty chests an hour, so ALWAYS "
                                + "would spin on every Wood chest of Enchanted Bread -- and worse, "
                                + "the GUI shows the contents BEFORE you pay, so a roll on opening "
                                + "re-reveals loot the player has already read. The RARE REWARD "
                                + "broadcast is Hypixel's own flag and names the item and the tier. "
                                + "It also fires for every party member, so a detector MUST compare "
                                + "the captured name against the local player.")
                        .build(),

                LootSourceInfo.builder(LootSource.KUUDRA_REWARD_CHEST, "Kuudra Chest")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.screen("Free or Paid Chest"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .rareBanner()
                        .markers("RARE REWARD!")
                        .samples("§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Kraken Shard §r§ein their Paid Chest§r§e!")
                        .jackpot("Kraken Shard", "Apex Dragon Shard", "Heavy Pearl",
                                "Infernal Kuudra Core")
                        .note("A fast T5 team takes two chests per run at twenty to thirty an hour, "
                                + "squarely in the maddening range for ALWAYS. Flagged uncertainty: "
                                + "the RARE REWARD broadcast is only confirmed for an Obsidian "
                                + "Chest, and its pattern would match \"Paid\" but has not been seen "
                                + "doing so. If it turns out not to fire for Kuudra, the fallback is "
                                + "ALWAYS on the Paid chest only -- not a quieter policy, which "
                                + "would leave a source that never rolls. Accept all four title "
                                + "spellings: Hypixel duplicates the word Chest.")
                        .build(),

                LootSourceInfo.builder(LootSource.CROESUS_CHEST, "Croesus Chest")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.screen("Croesus"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .rareBanner()
                        .markers("RARE REWARD!")
                        .samples("§6§lRARE REWARD! §r§bLeebys §r§efound a §r§6Necron's Handle §r§ein their Bedrock Chest§r§e!")
                        .jackpot("Obsidian Chest", "Bedrock Chest")
                        .note("Deliberately a separate source from DUNGEON_REWARD_CHEST even though "
                                + "the per-chest GUI is identical: clearing a backlog at Croesus "
                                + "opens fifteen chests in ninety seconds, which is a completely "
                                + "different pacing problem from one at the end of a run, and the "
                                + "player needs to silence that session without silencing in-run "
                                + "chests. The tier itself is the lottery here.")
                        .build(),

                LootSourceInfo.builder(LootSource.POWDER_CHEST, "Crystal Hollows Treasure Chest")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.island("Crystal Hollows"))
                        .markers("CHEST LOCKPICKED")
                        .samples("  §r§6§lCHEST LOCKPICKED")
                        .jackpot("Pickonimbus 2000", "Jungle Heart", "Prehistoric Egg",
                                "Red Goblin Egg", "Blue Goblin Egg", "Flawless Ruby Gemstone",
                                "Flawless Amethyst Gemstone", "Flawless Jade Gemstone",
                                "Flawless Amber Gemstone", "Flawless Sapphire Gemstone",
                                "Flawless Topaz Gemstone", "Flawless Jasper Gemstone", "FTX 3070",
                                "Synthetic Heart", "Control Switch", "Robotron Reflector",
                                "Electron Transmitter", "Superlite Motor")
                        .note("Thirty to a hundred an hour while powder grinding, and there is NO "
                                + "rare banner anywhere in the reward block -- so ON_RARE_BANNER "
                                + "here would be a feature that silently never runs. The block gives "
                                + "clean exact item names, which makes a jackpot list both easy and "
                                + "correct. The reward block itself is shared with LOOT_CHEST, "
                                + "GLACITE_CORPSE, FOSSIL_EXCAVATION and the Nucleus: one reader, "
                                + "five sources.")
                        .build(),

                LootSourceInfo.builder(LootSource.LOOT_CHEST, "Structure Loot Chest")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.islands("Crystal Hollows", "Mineshaft"))
                        .markers("LOOT CHEST COLLECTED")
                        .samples("  §r§5§lLOOT CHEST COLLECTED")
                        .jackpot("Pickonimbus 2000", "Jungle Heart", "Prehistoric Egg",
                                "Red Goblin Egg", "Blue Goblin Egg")
                        .note("Five to ten an hour, each a deliberate detour to a named structure "
                                + "with a fat loot table: the pacing the machine was built for. The "
                                + "area name captions it for free. A player key-spamming the Jungle "
                                + "Temple, which refills on every key, will want "
                                + "ON_JACKPOT_ITEM_ONLY instead.")
                        .build(),

                LootSourceInfo.builder(LootSource.CRYSTAL_NUCLEUS_RUN, "Crystal Nucleus Run")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Crystal Hollows"))
                        .markers("Nucleus Vault", "CRYSTAL FOUND")
                        .samples(
                                "§7Pick it up near the §r§5Nucleus Vault§r§7!",
                                "§f    §r§5§l✦ CRYSTAL FOUND §r§7(1§r§7/5§r§7)")
                        .jackpot("Jade Crystal", "Amethyst Crystal", "Amber Crystal",
                                "Sapphire Crystal", "Topaz Crystal")
                        .note("A full run is thirty to sixty minutes and happens at most twice an "
                                + "hour; nothing in the game deserves the machine more. Roll on the "
                                + "completion only -- the five CRYSTAL FOUND lines are progress "
                                + "markers, and firing on each would devalue the finish. Use the "
                                + "five crystals as the reel symbols.")
                        .build(),

                LootSourceInfo.builder(LootSource.METAL_DETECTOR_SCAVENGE, "Metal Detector Find")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.island("Crystal Hollows"))
                        .markers("Metal Detector")
                        .samples("§aYou found §r§cScavenged Diamond Axe §r§awith your §r§cMetal Detector§r§a!")
                        .jackpot("Scavenged Golden Hammer", "Scavenged Diamond Axe",
                                "Scavenged Emerald Hammer", "Scavenged Lapis Sword",
                                "Pickonimbus 2000")
                        .note("The line fires on every dig including plain Rough gemstones -- dozens "
                                + "per Divan visit -- so ALWAYS is unusable, but the four Scavenged "
                                + "tools are the whole point of the area at 18% a chest, which paces "
                                + "a celebration every five or six digs. \"Scavenged Lapis Sword\" is "
                                + "the one name not seen quoted anywhere; verify it before trusting "
                                + "that entry.")
                        .build(),

                LootSourceInfo.builder(LootSource.GLACITE_CORPSE, "Glacite Corpse")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Mineshaft"))
                        .markers("CORPSE LOOT!")
                        .samples("  §r§b§l§r§9§lLAPIS §r§b§lCORPSE LOOT!")
                        .jackpot("Ascension Rope", "Fine Onyx Gemstone", "Flawless Onyx Gemstone",
                                "Pickonimbus 2000", "Vanguard Helmet", "Armor of Yog Helmet",
                                "Mineral Helmet", "Lapis Armor Helmet")
                        .note("Structurally identical to a Diana burrow: a key-gated, deliberate, "
                                + "discrete opening with a randomised payout, minutes apart. The "
                                + "header names the corpse type, which is the caption. The four "
                                + "types differ enormously in stakes -- Lapis is free, Vanguard "
                                + "costs a Skeleton Key -- so a per-subject override is the natural "
                                + "refinement once one exists.")
                        .build(),

                LootSourceInfo.builder(LootSource.FOSSIL_EXCAVATION, "Fossil Excavation")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.islands("Dwarven Mines", "Mineshaft"))
                        .markers("EXCAVATION COMPLETE", "You didn't find anything")
                        .samples(
                                "  §r§6§lEXCAVATION COMPLETE",
                                "§cYou didn't find anything. Maybe next time!")
                        .note("Each excavation costs a Suspicious Scrap and a minute of the tile "
                                + "minigame, so twenty an hour is the ceiling and the gamble is one "
                                + "the player has already paid for. The empty-result line matters: "
                                + "settle the reels on No Drop rather than suppressing the spin, "
                                + "because the near miss is the texture. No jackpot list here on "
                                + "purpose -- no quoted loot table was found, and inventing names is "
                                + "exactly the silent-never-fires failure.")
                        .build(),

                LootSourceInfo.builder(LootSource.SUSPICIOUS_SCRAP, "Suspicious Scrap")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.islands("Dwarven Mines", "Mineshaft"))
                        .markers("EXCAVATOR!")
                        .samples("§6§lEXCAVATOR! §r§fYou found a §r§9Suspicious Scrap§r§f!")
                        .jackpot("Suspicious Scrap")
                        .note("Rare enough to be a genuine beat and it is one cheap anchored line -- "
                                + "and it is the currency FOSSIL_EXCAVATION gambles, so the two make "
                                + "a pair. Caveat: only one symbol is available, so three matching "
                                + "reels is guaranteed and the jackpot is unconditional; give it a "
                                + "one-reel treatment or accept that.")
                        .build(),

                LootSourceInfo.builder(LootSource.GLACITE_MINESHAFT_PORTAL, "Glacite Mineshaft Portal")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.islands("Dwarven Mines", "Crystal Hollows"))
                        .markers("Glacite Mineshaft")
                        .samples("§5§lWOW! §r§aYou found a §r§bGlacite Mineshaft §r§aportal!")
                        .jackpot("Glacite Mineshaft")
                        .note("Genuinely rare -- it has a pity counter -- and it is the entry point "
                                + "to the corpse loop, so the celebration lands at the right moment. "
                                + "But it produces NO drop, so the reels have exactly one symbol and "
                                + "the three-of-a-kind is meaningless. Honest options: fire the "
                                + "flourish and the title without a reel spin, or leave it out and "
                                + "keep the machine strictly about loot.")
                        .build(),

                LootSourceInfo.builder(LootSource.EXPERIMENTS_REWARDS, "Experimentation Table")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.screen("Experimentation Table"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .markers("rewards!")
                        .samples("§eYou claimed the §r§dMetaphysical §r§erewards!")
                        .note("One claim per experiment, an experiment is one to three minutes, and "
                                + "it only happens while the player is deliberately sat at the "
                                + "table. ALWAYS over ON_JACKPOT_ITEM_ONLY deliberately: the "
                                + "ultra-rare books are read from GUI lore, not from any name list, "
                                + "so a hard-coded jackpot list here would be invented -- and an "
                                + "invented list is worse than no list. The GUI title arms and "
                                + "disarms it, which costs nothing.")
                        .build(),

                LootSourceInfo.builder(LootSource.WINTER_GIFT, "Season of Jerry Gift")
                        .policy(RollPolicy.ON_RARE_BANNER)
                        .gate(SourceGate.season("the Season of Jerry"))
                        .rareBanner()
                        .markers("gift with")
                        .samples(
                                "§e§lSWEET! §r§5Snow Suit Helmet §r§egift with §r§aGrazma§r§e!",
                                "§9§lRARE! §r§6+20,000 Coins §r§egift with §r§aGrazma§r§e!")
                        .jackpot("Cryopowder Shard", "Snow Suit Helmet", "Snow Suit Chestplate",
                                "Snow Suit Leggings", "Snow Suit Boots", "North Star")
                        .note("The best-behaved source found anywhere: Hypixel prints the rarity word "
                                + "itself, so the policy maps onto SWEET, SANTA TIER and PARTY TIER "
                                + "with no item list at all and nothing that can drift when the loot "
                                + "table changes. COMMON is the overwhelming majority of a gifting "
                                + "session, which is why ALWAYS would be pure noise.")
                        .build(),

                LootSourceInfo.builder(LootSource.FROZEN_TREASURE, "Frozen Treasure")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.seasonOnIsland("the Season of Jerry", "Jerry's Workshop"))
                        .markers("FROZEN TREASURE!")
                        .samples(
                                "FROZEN TREASURE! You found Glacial Talisman!",
                                "FROZEN TREASURE! You found Packed Ice!")
                        .jackpot("Glacial Talisman", "Glacial Fragment", "Frozen Bait",
                                "Einary's Red Hoodie", "Enchanted Packed Ice", "Red Gift")
                        .note("Several a minute while ice mining, hundreds an hour, so this would be "
                                + "the single worst offender armed on ALWAYS. The item list is small, "
                                + "closed and fully verified, which makes a jackpot list exactly "
                                + "right here: Packed Ice, Enchanted Ice and Ice Bait are the water, "
                                + "everything else is the prize.")
                        .build(),

                LootSourceInfo.builder(LootSource.SPOOKY_CHEST, "Trick or Treat Chest")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.season("the Spooky Festival"))
                        .markers("has appeared!")
                        .samples("§6§lSPOOKY! §r§7A §r§6Trick or Treat Chest §r§7has appeared!")
                        .note("Shipped disabled and it should stay that way until someone captures a "
                                + "loot line live. The appearance broadcast says a chest spawned "
                                + "somewhere on the island -- not that you opened one, not what was "
                                + "in it, possibly opened by someone else. Rolling on it would be a "
                                + "reel that lies. If a rare item does come out, the universal banner "
                                + "already covers it.")
                        .build(),

                // ======================================================= gathering

                LootSourceInfo.builder(LootSource.FISHING_RARE_SEA_CREATURE, "Rare Sea Creature")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.anywhere())
                        .triggers(TriggerKind.CHAT)
                        .samples(
                                "§9You have angered a legendary creature... §r§bLord Jawbus §r§9has arrived.",
                                "§9The sky darkens and the air thickens. The end times are upon us: Ragnarok is here.",
                                "§9What is this creature!?",
                                "§9You hear a massive rumble as Thunder emerges.")
                        .jackpot("Titanoboa Shed", "Radioactive Vial", "Lord Jawbus", "Thunder",
                                "Flying Fish", "Magma Lord Fragment", "Reindrake Fragment",
                                "Plhlegblast Pearl", "Shark Tooth Necklace", "Sea Emperor Fragment")
                        .note("The fishing analogue of a Minos Inquisitor: the corpus already did the "
                                + "rarity filtering with its own rare flag, and these are minutes to "
                                + "hours apart. NO MARKERS ON PURPOSE -- the announcements share no "
                                + "literal whatsoever (\"What is this creature!?\" does not even name "
                                + "the creature), so there is nothing to pre-filter on. Both "
                                + "reference mods match them by exact equality against a fixed table, "
                                + "which is one hash lookup and cheaper than any regex; declaring no "
                                + "markers means the bus offers every line, which is the safe "
                                + "direction and the honest one. Note Hypixel has two spellings of "
                                + "the Titanoboa line; accept both or a mythic is missed.")
                        .build(),

                LootSourceInfo.builder(LootSource.FISHING_SEA_CREATURE, "Sea Creature")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.anywhere())
                        .triggers(TriggerKind.CHAT)
                        .samples(
                                "§9A Squid appeared.",
                                "§9You caught a Sea Walker.",
                                "§9The Rider of the Deep has emerged.")
                        .note("The highest-frequency event in the entire feature: a geared player "
                                + "with a hotspot hooks one every two to five seconds, and a double "
                                + "hook prints two. ALWAYS here would be a bug, not a preference. "
                                + "Shipped off; ON_RARE_BANNER is the sensible value for anyone who "
                                + "arms it, and the banner path is already covered by MOB_RARE_DROP. "
                                + "Markerless for the same reason as the rare variant, which is a "
                                + "second reason not to arm it lightly. Also: Baby Magma Slug "
                                + "announces nothing at all and can never be detected from chat.")
                        .build(),

                LootSourceInfo.builder(LootSource.FISHING_TROPHY_FISH_RARE, "Trophy Fish (Gold/Diamond)")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Crimson Isle"))
                        .rareBanner()
                        .markers("TROPHY FISH!")
                        .samples("§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§9Lavahorse §r§6§lGOLD§r§f!")
                        .jackpot("Golden Fish", "Vanille", "Mana Ray", "Karate Fish", "Soul Fish",
                                "Moldfin", "Skeleton Fish")
                        .note("Splitting the tier at the detector rather than at the policy is what "
                                + "makes ALWAYS safe: this constant fires only on the 2% and 0.2% "
                                + "tiers, which are exactly the ones a player screenshots. The best "
                                + "gathering source in the game for a slot machine, because Hypixel "
                                + "hands you a discrete named tier in the trigger line itself. The "
                                + "name capture can contain an obfuscation code -- an undiscovered "
                                + "trophy fish prints scrambled.")
                        .build(),

                LootSourceInfo.builder(LootSource.FISHING_TROPHY_FISH, "Trophy Fish (Bronze/Silver)")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.island("Crimson Isle"))
                        .rareBanner()
                        .markers("TROPHY FISH!")
                        .samples("§6 §r§6§lTROPHY FISH! §r§fYou caught a §r§6Golden Fish §r§7§lSILVER§r§f!")
                        .note("Bronze is 100% of trophy catches and Silver 25%, arriving every few "
                                + "seconds while lava fishing. No configuration of a slot machine "
                                + "survives that. The constant exists so the config screen can show "
                                + "it and a curious player can turn it on.")
                        .build(),

                LootSourceInfo.builder(LootSource.FISHING_GOLDEN_FISH, "Golden Fish")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Crimson Isle"))
                        .markers("Golden Fish")
                        .samples(
                                "§9You spot a §r§6Golden Fish §r§9surface from beneath the lava!",
                                "§9The §r§6Golden Fish §r§9is weak!")
                        .jackpot("Golden Fish")
                        .note("The rarest trophy fish in the game; it surfaces after eight to twelve "
                                + "minutes of continuous lava fishing and despawns if not hooked. "
                                + "Firing on the \"is weak!\" line instead of the spawn puts the spin "
                                + "where the catch is actually imminent, which may read better.")
                        .build(),

                LootSourceInfo.builder(LootSource.FISHING_TREASURE, "Treasure Catch")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.anywhere())
                        .markers("CATCH!")
                        .samples(
                                " GOOD CATCH! You caught 36,064 Coins!",
                                "§6 §r§6§lGREAT CATCH! §r§fYou caught a §r§7[Lvl 1] §r§aSquid§r§f!")
                        .jackpot("Squid", "Megalodon", "Flying Fish", "Blue Whale", "Deep Sea Orb",
                                "Water Snake Shard", "Giant Water Bug Shard")
                        .note("Treasure Chance reaches the fifties with good gear, so a GOOD CATCH "
                                + "lands on a large minority of catches and most of them are coins "
                                + "or bait. The pet form and the shard form are the ones worth "
                                + "celebrating and both are matchable by name. Restricting the "
                                + "detector to the OUTSTANDING tier and defaulting ALWAYS would be "
                                + "the cleaner design, but no cited rate for that tier could be "
                                + "found, so it is not shipped as a guess.")
                        .build(),

                LootSourceInfo.builder(LootSource.FORAGING_TREE_BONUS_GIFT, "Tree Bonus Gift")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Galatea"))
                        .markers("BONUS GIFT")
                        .samples("                                §r§d§lBONUS GIFT")
                        .jackpot("Tree the Fish", "Chameleon", "Deep Root", "Foraging Wisdom Booster",
                                "Sweep Booster")
                        .note("The right answer to the user's own headline example, and the single "
                                + "most future-proof rule in the whole feature: Hypixel prints the "
                                + "drop odds ON THE LINE (\"Tree the Fish (0.05%)\"), so the jackpot "
                                + "decision can be a numeric threshold on a captured group rather "
                                + "than a hard-coded item list -- the one rule here that cannot go "
                                + "stale when Hypixel adds an item. The sub-block only appears when "
                                + "a bonus actually rolled, so ALWAYS is already filtered.")
                        .build(),

                LootSourceInfo.builder(LootSource.FORAGING_TREE_GIFT, "Tree Gift")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.island("Galatea"))
                        .markers("TREE GIFT")
                        .samples("                                §r§9§lTREE GIFT")
                        .jackpot("Vinesap", "Tender Wood", "Signal Enhancer")
                        .note("One gift per tree the player contributed to, so every thirty to ninety "
                                + "seconds -- the foraging equivalent of spinning on every fish -- "
                                + "and the base contents are guaranteed filler. Let "
                                + "FORAGING_TREE_BONUS_GIFT carry the celebration. The item "
                                + "breakdown lives in the line's hover component, not its text, so "
                                + "read it lazily and only once the jackpot check has a reason to "
                                + "look.")
                        .build(),

                LootSourceInfo.builder(LootSource.FORAGING_TREE_PHANTOM, "Tree Phantom")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Galatea"))
                        .markers("fell from the Tree!")
                        .samples("§r§7A §r§dPhanpyre §r§7fell from the Tree!")
                        .jackpot("Dreadwing", "Grizzly Bear", "Puck")
                        .note("Rare, discrete, named, and it spawns a mob that then drops shards -- a "
                                + "natural double beat for the widget. A handful an hour at most.")
                        .build(),

                LootSourceInfo.builder(LootSource.GARDEN_VERY_RARE_CROP, "Very Rare Crop")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Garden"))
                        .rareBanner()
                        .markers("VERY RARE CROP!")
                        .samples("VERY RARE CROP! Burrowing Spores")
                        .jackpot("Burrowing Spores")
                        .note("Hypixel promoted these to their own banner word precisely because they "
                                + "are rarer than the RARE CROP tier; taking the server at its word "
                                + "is both correct and free. Only one item was verified using this "
                                + "tier, so the tier's full membership is unknown.")
                        .build(),

                LootSourceInfo.builder(LootSource.GARDEN_RARE_CROP, "Rare Crop")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.island("Garden"))
                        .rareBanner()
                        .markers("RARE CROP!")
                        .samples(
                                "RARE CROP! Cropie (+97)",
                                "RARE CROP! Seasoning (+115) (automatically donated)")
                        .jackpot("Helianthus", "Fermento", "Warty", "Rarefinder Chip")
                        .note("With full Fermento or Helianthus armour these fire several times a "
                                + "minute, and Cropie and Squash are near-continuous. The name list "
                                + "splits cleanly by value and the item is captured in the trigger "
                                + "line, so name matching needs no extra parsing. The trailing "
                                + "bracket is farming fortune, not a count.")
                        .build(),

                LootSourceInfo.builder(LootSource.GARDEN_PEST_DROP, "Pest Rare Drop")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Garden"))
                        .rareBanner()
                        .markers("DROP!")
                        .samples(
                                "§6§lRARE DROP! §9Mutant Nether Wart §8x9 §e(§e+134)",
                                "§6§lRARE DROP! §r§aNot Just a Pest Vinyl §r§6(Cocoaleech)")
                        .jackpot("Slug", "Wings of Harmony Vinyl", "Not Just a Pest Vinyl",
                                "DynaMITES Vinyl", "Mutant Nether Wart", "Pesterminator",
                                "Ultimate Sunset")
                        .note("Pre-filtered by the server -- the line only appears for RARE and PET "
                                + "drops -- genuinely rare, and it covers the whole Vinyl system. "
                                + "One of the few gathering sources where ALWAYS needs no argument. "
                                + "Two drops print no message at all and can only be seen by an "
                                + "inventory diff, so do not expect a line for them. Note this "
                                + "shape omits the reset code after the banner and puts the count "
                                + "in a trailing run, which the shipped banner regex does not "
                                + "currently accept.")
                        .build(),

                LootSourceInfo.builder(LootSource.GARDEN_CROP_FEVER, "Crop Fever")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Garden"))
                        .markers("CROP FEVER")
                        .samples("WOAH! You caught a case of the CROP FEVER for 60 seconds!")
                        .note("Roll on the fever STARTING -- a sixty-second buff, occasional, worth "
                                + "celebrating -- and feed the drop lines inside the window into the "
                                + "loot window rather than treating each as its own roll. The window "
                                + "is also the only place in the game using the banner word PRAY TO "
                                + "RNGESUS DROP.")
                        .build(),

                LootSourceInfo.builder(LootSource.GARDEN_VISITOR_RARE, "Legendary Visitor")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Garden"))
                        .markers("OFFER ACCEPTED")
                        .samples("§6§lOFFER ACCEPTED §8with §6Sirius §8(§6§lLEGENDARY§8)")
                        .jackpot("Space Helmet", "Jungle Key", "Flowering Bouquet",
                                "Overgrown Grass", "Green Bandana", "Music Rune")
                        .note("ALWAYS, but the detector must do the rarity filter and emit only for "
                                + "LEGENDARY, MYTHIC and SPECIAL: a Garden main accepts visitors "
                                + "constantly, and rolling on every handover would be the most "
                                + "repetitive thing in the game. Honest limitation -- the reward "
                                + "itself is NOT in chat, only in the visitor GUI's item lore, so a "
                                + "chat-only implementation knows THAT a rare visitor was served but "
                                + "not what it gave; lock the reels on the visitor's own name and "
                                + "rarity.")
                        .build(),

                LootSourceInfo.builder(LootSource.MINING_PRISTINE_GEMSTONE, "Pristine Gemstone")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.island("Crystal Hollows"))
                        .markers("PRISTINE!")
                        .samples("§d§lPRISTINE! §r§fYou found §r§a☘ Flawed Jade Gemstone §r§8x20§r§f!")
                        .note("The mining twin of the ordinary sea creature: several times a minute "
                                + "with the perk maxed, valuable in aggregate and worthless as an "
                                + "individual moment. Shipped off. Note the line only ever carries "
                                + "the Flawed tier, so a jackpot list would have nothing to match "
                                + "and ON_JACKPOT_ITEM_ONLY is not the fallback it looks like.")
                        .build(),

                LootSourceInfo.builder(LootSource.MINING_COMPACT, "Compact Proc")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.islands("Dwarven Mines", "Crystal Hollows"))
                        .markers("COMPACT!")
                        .samples("COMPACT! You found an Enchanted Hard Stone!")
                        .note("Listed only so the enumeration is honest and the config screen can "
                                + "show it greyed out. It fires continuously while mining stone; "
                                + "arming it would spin the reels faster than they can settle.")
                        .build(),

                LootSourceInfo.builder(LootSource.MINING_GOBLIN_RAID, "Golden Goblin")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.island("Dwarven Mines"))
                        .markers("Goblin")
                        .samples(
                                "§6A Golden Goblin has spawned!",
                                "§6A §r§bDiamond Goblin §r§6has spawned!")
                        .jackpot("Golden Goblin Egg", "Diamond Goblin Egg")
                        .note("Minutes to hours apart, and rare enough that the reference mod gives "
                                + "it a screen title and a sound of its own. The kill's drops arrive "
                                + "on the standard banner, so this line opens the loot window and "
                                + "the shared parser covers the payoff.")
                        .build(),

                // ======================================================= events and seasonal

                LootSourceInfo.builder(LootSource.HOPPITY_MEAL_EGG, "Chocolate Meal Egg")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.season("Hoppity's Hunt"))
                        .markers("HOPPITY'S HUNT")
                        .samples("§d§lHOPPITY'S HUNT §r§dYou found a §r§9Chocolate Lunch Egg §r§don a ledge next to the stairs up§r§d!")
                        .jackpot("El Dorado", "Rabbit the Fish", "Fish the Rabbit", "Solomon")
                        .note("Three meal eggs per SkyBlock day per island, roughly twenty real "
                                + "minutes apart, each a discrete low-frequency reward with a "
                                + "guaranteed rabbit inside: the same cadence as a Diana burrow. "
                                + "Nothing here fires often enough to become noise.")
                        .build(),

                LootSourceInfo.builder(LootSource.HOPPITY_RABBIT, "Hoppity Rabbit")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.season("Hoppity's Hunt"))
                        .rareBanner()
                        .markers("HOPPITY'S HUNT")
                        .samples("§D§LHOPPITY'S HUNT §7You found §6Solomon §7(§6§LLEGENDARY§7)!")
                        .jackpot("El Dorado", "Rabbit the Fish", "Fish the Rabbit", "Solomon")
                        .note("The rarity is handed to us inside the line, so the jackpot keys on the "
                                + "rarity group rather than a name list and cannot drift. WATCH THE "
                                + "CASE: Hypixel really sends UPPER-CASE formatting codes on this "
                                + "one line, and any hand-rolled code stripper that assumes "
                                + "lower-case gets a feature that never fires.")
                        .build(),

                LootSourceInfo.builder(LootSource.CHOCOLATE_FACTORY_STRAY, "Chocolate Factory Stray")
                        .policy(RollPolicy.ON_JACKPOT_ITEM_ONLY)
                        .gate(SourceGate.screen("Chocolate Factory"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .markers("stray", "CAUGHT!")
                        .samples(
                                "§7You caught a stray §6§lGolden Rabbit§7! §7You gained §6+13,566,571 Chocolate§7!",
                                "§6El Dorado §d§lCAUGHT!")
                        .jackpot("Golden Rabbit", "El Dorado", "Fish the Rabbit", "Side Dish")
                        .note("The clearest case anywhere for not defaulting to ALWAYS: an active "
                                + "chocolate player sees several strays a minute and a session would "
                                + "spin the reels hundreds of times. Golden Rabbit, El Dorado and "
                                + "Fish the Rabbit are the only strays anyone celebrates and all "
                                + "three are named in the line, so the jackpot list does all the "
                                + "work. Note the Factory runs year-round, not only during the hunt, "
                                + "so the GUI title is the gate rather than the season.")
                        .build(),

                LootSourceInfo.builder(LootSource.YEAR_OF_THE_PIG_ORB, "Shiny Orb")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.season("the Year of the Pig"))
                        .markers("SHINY!")
                        .samples("SHINY! You extracted Shiny Token and +1,000,000 Coins from the piglet's orb!")
                        .jackpot("Shiny Token")
                        .note("A once-per-SkyBlock-century event with a discrete self-announcing "
                                + "reward, so ALWAYS carries no risk at all. Worth stating plainly "
                                + "because it is easy to get wrong: SHINY! is NOT a member of the "
                                + "rare-drop banner family. Every occurrence anywhere belongs to "
                                + "this event, so building a general SHINY branch into the shared "
                                + "parser would be building a branch for one centennial source.")
                        .build(),

                LootSourceInfo.builder(LootSource.YEAR_OF_THE_WITCH_STEW, "Witches Stew")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.screen("Witches Stew"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .note("Shipped off because it is not established that the stew is random "
                                + "rather than a fixed menu. The GUI title and its item lines are "
                                + "verified; the mechanic is not. A slot machine on a fixed menu "
                                + "would be celebrating a purchase.")
                        .build(),

                LootSourceInfo.builder(LootSource.RIFT_UBIK_SPLIT_OR_STEAL, "Split or Steal")
                        .policy(RollPolicy.ALWAYS)
                        .gate(SourceGate.screen("Split or Steal"))
                        .triggers(TriggerKind.SCREEN_TITLE)
                        .markers("SPLIT!")
                        .samples("SPLIT! You need to wait 4h 12m before you can play again.")
                        .note("A literal gamble on a multi-hour cooldown: thematically the most "
                                + "perfect trigger in the game for a slot machine. The honest "
                                + "caveat is that no win or lose line could be verified -- only the "
                                + "cooldown -- and the reward is Motes, so say so in the caption "
                                + "rather than implying an item.")
                        .build(),

                LootSourceInfo.builder(LootSource.RIFT_MOTES_ORB, "Motes Orb")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.rift())
                        .markers("ORB!")
                        .samples("§5§lORB! §r§dPicked up §r§5+12 Motes§r§d.")
                        .note("Enumerated so nobody adds it thinking ORB! is a rare banner: it is the "
                                + "Rift's routine currency pickup, the direct equivalent of walking "
                                + "over coins, and orbs drop from nearly everything there.")
                        .build(),

                LootSourceInfo.builder(LootSource.RIFT_VERMIN_VACUUM, "Rift Vermin")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.rift())
                        .markers("vacuumed")
                        .samples("§eYou vacuumed a §r§aSilverfish§r§e!")
                        .note("Listed for completeness because it is the only gathering-shaped "
                                + "announcement anywhere in the Rift, and it is not chance-based at "
                                + "all. Related negative result worth recording: Rift fishing does "
                                + "not exist as a chat-detectable loot source, and no constant was "
                                + "created for it.")
                        .build(),

                LootSourceInfo.builder(LootSource.CARNIVAL_FRUIT_DIGGING, "Carnival Fruit Digging")
                        .policy(RollPolicy.NEVER)
                        .gate(SourceGate.area("Hub", "Carnival"))
                        .markers("TREASURE!")
                        .samples("TREASURE! There is a Dragonfruit nearby.")
                        .jackpot("Dragonfruit")
                        .note("Barely a lottery: the board is solvable and it pays Carnival Tokens "
                                + "rather than items, so there is almost nothing for a reel to land "
                                + "on. The Dragonfruit is the one genuinely rare reveal, which is "
                                + "why the constant exists at all; shipped off and opt-in.")
                        .build());
    }
}
