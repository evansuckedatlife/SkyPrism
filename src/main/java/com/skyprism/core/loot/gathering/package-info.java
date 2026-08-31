/**
 * Detectors for the gathering half of SkyBlock: fishing, mining procs, foraging tree gifts, the
 * Garden and Trevor the Trapper.
 *
 * <h2>Why this area is the one where the defaults matter</h2>
 * <p>Every other area of the game hands the machine an event a player is already waiting on -- a
 * slayer boss, a Kuudra clear, a dungeon run. Gathering does not. It is a firehose: a geared angler
 * hooks a sea creature every two to five seconds, a Pristine proc lands several times a minute, a
 * Bronze trophy fish is a hundred percent of trophy catches. Arming those would not be a noisy
 * feature, it would be an unusable one, and the mod would be uninstalled during the first fishing
 * session. So more than half the constants in this package ship on {@link
 * com.skyprism.core.loot.RollPolicy#NEVER} or {@link
 * com.skyprism.core.loot.RollPolicy#ON_JACKPOT_ITEM_ONLY}, and each detector's javadoc states the
 * measured cadence its default was chosen from rather than asserting a preference.
 *
 * <p>The split that decides it is <b>event shaped versus stream shaped</b>. A Golden Fish surfaces
 * once in eight to twelve minutes; a Gold trophy fish is two percent of catches; a tree bonus gift
 * only prints when a bonus actually rolled. Those are events, and they get {@code ALWAYS}. An
 * ordinary sea creature, a Pristine gemstone, a Compact proc and a Bronze trophy fish have no
 * completion at all, and no configuration of a slot machine survives them.
 *
 * <h2>Where the patterns came from</h2>
 * <p>Every pattern in this package was transcribed from a reference mod's source, in most cases
 * sitting beside that mod's own {@code REGEX-TEST} line captured from live chat, and the sea
 * creature table is the complete 90-entry corpus read out of {@code SkyHanni-REPO}'s
 * {@code constants/SeaCreatures.json}. Nothing here was written from memory. Where a pattern is
 * loosened from its source it is loosened in the safe direction -- never anchoring on a private-use
 * icon codepoint, which Hypixel has already moved once -- and the javadoc says so.
 *
 * <h2>Three things this package deliberately does not do</h2>
 * <ul>
 *   <li><b>Rift fishing does not exist.</b> The brief asked for it. A search of all 44 Rift feature
 *       files in SkyHanni and of Skyblocker found no Rift fishing chat line, no Rift sea creature
 *       variant in the 22-variant corpus, and no Rift drop banner: the Rift's gatherables are
 *       deterministic pickups. There is therefore no {@code RiftFishingDetector} here, because an
 *       invented pattern would ship a feature that looks finished and silently never fires.</li>
 *   <li><b>Titanium and Mithril have no chat announcement at all</b>, so no detector can see them
 *       from this bus; both reference mods reach them by diffing sacks and inventories.</li>
 *   <li><b>Jacob's contest rewards are not chance based</b> -- they are decided by bracket
 *       placement -- so a machine spun on them would be celebrating the player's own farming
 *       speed.</li>
 * </ul>
 *
 * <h2>Where the mining boundary runs</h2>
 * <p>The gemstone and Glacite <em>procs</em> are here -- a Pristine proc, a Compact proc, a goblin
 * spawn -- because each is one self-contained line. The mining <em>openables</em> are not: a Glacite
 * corpse, a fossil excavation, a powder chest, a structure loot chest and a metal detector dig all
 * arrive as the same multi-line reward block wrapped in a bar of 64 identical glyphs, and one
 * reader serves all of them. That reader lives with the other container sources, which is why
 * {@code com.skyprism.core.loot.containers} owns them and this package does not. Splitting them by
 * skill rather than by line shape would have meant writing that block reader twice.</p>
 *
 * <h2>The marker contract, and the one place it bites</h2>
 * <p>A detector is only offered lines containing one of its registry {@link
 * com.skyprism.core.loot.LootSourceInfo#chatMarkers() markers}, so every line a detector here can
 * match carries its own marker. That constraint is what shapes the two tree-gift detectors: the
 * block's wrapper line and its indented reward lines carry no marker, so those detectors fire on
 * the header line, which does. {@link com.skyprism.core.loot.gathering.TreeGiftLines} holds the
 * reward-line parsers anyway -- fully tested, ready for the hover reader that would let the caption
 * name the drop -- rather than leaving that research on the floor.
 */
package com.skyprism.core.loot.gathering;
