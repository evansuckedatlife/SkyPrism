# Changelog

All notable changes to SkyPrism are recorded here. Versions follow the
`<mod version>+<minecraft version>` scheme used by the jar names, so `1.0.0` below ships as
`skyprism-1.0.0+26.1.2.jar` and `skyprism-1.0.0+26.2.jar`.

## 1.0.1 — 2026-08-31

### Reel filler now matches the source that is rolling

The spinning reels used one static list of drop names on every roll of every source. It was written
when this was a Diana-only mod and never generalised, so a fishing roll scrolled Daedalus Sticks and a
slayer roll scrolled Griffin Feathers. It also carried `Control Switch`, a Crystal Hollows mining item
that has never been Diana loot — which is what made the problem visible on a Minos Champion roll.

Filler is now resolved per source from the loot registry and cached once, so the strip tells you what
kind of machine you are looking at. A test walks all 64 sources and fails if a strip borrows another
source's loot, or contains a name with no sprite row — the gap that let the unmapped Control Switch
through unnoticed.

### Chroma is off until level 600

Was 400. The animated shimmer now marks the very top of the ladder rather than being common. A value
already stored in a config file wins over the new default.

Two bugs surfaced by re-shooting the screenshots:

- `SelfTest` pinned `chromaMinLevel` to a literal `300` inside a block documented as using shipped
  defaults, so every published palette screenshot showed a threshold no player has ever had.
- `/skyprism preview` hardcoded `0..600`, so against the new default the one screen built to
  demonstrate chroma opened with none of it in frame. The range is derived from the threshold now.

The palette screenshot in the README is shot at GUI scale 2 and shows 546 levels instead of 99.

### The slot machine now covers the whole game

The HUD slot machine is no longer a Diana feature. It spins for chance-based events across
SkyBlock — **64 sources, 51 with a working detector today.**

- Slayers (all six bosses, five tiers, plus minibosses), Catacombs bosses including Master
  Mode, Kuudra, the seven Ender Dragons, the Endstone Protector, Arachne, the Crimson Isle
  minibosses, and the universal rare-drop and pet-drop banners.
- Dungeon, Kuudra and Croesus reward chests; Crystal Hollows powder, structure, Nucleus and
  Divan sources; fossil excavation; the Experimentation Table.
- Fishing: rare sea creatures, trophy fish, the Golden Fish, treasure catches. Galatea tree
  gifts and phantoms. Garden crops, pests, Crop Fever and visitors. Mining procs and goblin
  raids. Trevor the Trapper.
- Seasonal and Rift content: Hoppity, the Chocolate Factory, Winter gifts, the Great Spook,
  the Year of the Pig, the Carnival, Split or Steal, Motes orbs, vermin vacuuming.

**Every source has a roll policy, and the shipped default was chosen per source.** Rolling
on every common drop would be unusable — an hour of fishing would spin the reels hundreds of
times — so a source is `ALWAYS`, `ON_RARE_BANNER`, `ON_JACKPOT_ITEM_ONLY` or `NEVER`.
Inherently rare things (a Minos Inquisitor, an Obsidian chest, a Gold trophy fish) default
to `ALWAYS`; high-frequency ones (ordinary sea creatures, Pristine procs, Compact procs)
default to `NEVER` but are listed so they can be switched on. All of it is per-source
configurable, grouped by category in the settings screen.

- The caption strip under the reels now names whatever produced the loot — "Voidgloom
  Seraph IV", "Obsidian Chest", "Lord Jawbus" — instead of a Mythological creature.
- A configurable minimum interval stops a burst of luck from restarting the reels mid-spin.
- `/skyprism simulate` covers every source; `/skyprism sources` lists each one with its
  policy, its gate verbatim, and whether that gate is currently open.
- Config migrates v3 → v4, preserving every choice an existing Diana player had made.

### Architecture

- New `com.skyprism.core.loot` package: one gated event bus, a small `SourceDetector`
  interface, and a registry carrying each source's default policy, gate, chat markers and
  captured sample lines. Not 25 always-on detectors.
- **Diana did not change.** It remains owned end to end by `DianaController`, is deliberately
  not registered on the bus, and outranks it: a bus event arriving while a Diana roll is on
  screen is refused. Every pre-existing Diana test passes unmodified — none was removed,
  renamed or relaxed, and the 15 added to `LootParserTest` are all new — and the settled Diana
  HUD frame `07-slot-all-reels-locked.png` is byte-identical to the pre-refactor capture.

### Fixed

- **A single markerless detector was switching the chat pre-filter off for every other
  detector**, across all of SkyBlock — `SeaCreatureDetector` declares no markers (its ninety
  announcements share no literal) and is armed on every island. Ordinary chat cost
  2,566–3,331 ns per line instead of 703. The bus now skips only the detectors that promised
  markers, preserving registration order exactly.

### Also fixed

- **The reels drew three identical chests for anything outside Diana.** The item-art table
  covered Diana's loot only, so a slayer roll showed two identical chests and a fishing roll
  showed three — which reads as the machine having failed and empties the three-of-a-kind
  flourish of meaning. `assets/skyprism/drop_symbols.json` now carries 354 rows over 476 drop
  names onto 236 distinct vanilla items, 65 glinted. Every id was checked to exist on both
  26.1.2 and 26.2. Same-sprite collisions inside one source's payout are now a build failure,
  not a hope.
- **The six GUI-triggered sources could never fire from a GUI.** `LootEventBus.onScreenTitle`
  and its detectors existed, but nothing ever called them, so Croesus, the two reward chests,
  the Experimentation Table, the Witches Stew and Split or Steal only ever fired from their
  chat halves — and `CROESUS_CHEST` never armed at all. A new
  `ClientPacketListenerOpenScreenMixin` feeds every container title into `ScreenTitleFeed`,
  which is fused: it catches everything, gives up after three failures, and costs one integer
  compare when no source is armed. Five of the six are newly reachable on shipped defaults
  (the Witches Stew ships `NEVER`).
- **Four defects in `core/diana/LootParser`**, which feeds the reels: the bracketed "went to
  sacks" banner captured the item name as a bare `(`; the two-space `VERY RARE DROP!` and
  `CRAZY RARE DROP!` forms did not match at all; the `§r` required after the banner is absent
  on Garden pest drops; a trailing `§8xN` count was lost. Fixed test-first with 15 new cases.
  Diana never exercised any of them and did not move.

### Magic Find on the jackpot reveal

Hypixel appends the Magic Find a rare drop was rolled at to its own banner — `(+240% ★ Magic
Find!)` — and the machine used to throw it away. It is now carried from the parser to the
screen.

- The jackpot reveal captions the prize with the figure, in the caption strip's right-hand
  gutter beside `JACKPOT`, fading in behind the third match.
- **When the server reported no Magic Find, nothing is drawn.** Not `+0%`: an unreported stat
  and a stat of zero are different facts, and a player who knows their own number can tell
  which one a widget is claiming.
- **The panel does not move either way.** The gutter is space the strip already had, so a roll
  that reports the stat and a roll that does not are drawn into pixel-identical boxes — 190×70
  both, asserted by the self test, which now photographs the two side by side
  (`14-jackpot-hold.png`, `15-jackpot-hold-no-magic-find.png`).
- The mod draws its own star, U+2605, not Hypixel's. Both of Hypixel's icons (U+E01A today,
  U+272F before it) were checked against `assets/minecraft/font/include/default.json` in both
  merged jars and neither is in any provider's char list — drawing the captured codepoint would
  put an empty box on the reveal for anyone without Hypixel's resource pack.
- The percent sign is echoed, never assumed. Hypixel emits both `+208%` and `+208`, and
  appending a sign it did not send would be inventing a unit.

### One banner parser, not four

The rare-drop banner corpus was encoded in four separate files, and they had already drifted —
which is the mechanism behind "some drops parse and some do not". It is encoded once now, in
`core/diana/LootParser`; `RareDropBanner`, `BannerLines` and `MobRareDropDetector` are adapters
over it. `docs/CHAT-PATTERNS.md` §3.10 records what each copy did differently.

Five shapes were being read wrongly or not at all, all found by running the former parsers side
by side over one line set:

- **Garden Crop Fever put an English sentence on a reel.** `RARE DROP! You dropped 48x
  Enchanted Melon Slice!` came back with the item name `You dropped 48x Enchanted Melon Slice!`
  — matching no jackpot entry ever, and recorded in the stats file. It now reads as
  `Enchanted Melon Slice` × 48.
- **`UNCOMMON DROP!` armed a detector nothing could then decompose**, so the roll settled on
  "No Drop" — a feature that looks like it works. It is in the vocabulary now, and flagged not
  rare.
- **Every aqua-coloured drop swallowed its own Magic Find bracket into its item name.** The old
  guard rested on "no rarity is aqua"; DIVINE rarity is aqua.
- **A bracketed line whose pattern failed for any reason fell through as an item named `(`** —
  which no jackpot list can match and the stats file keeps forever. It is refused outright now.
  The catch-all detector still spins the machine for such a line, captioned with the source's
  own name: a rare drop that produces nothing at all is worse than one with a generic caption.
- **An item name carrying a second formatting code** (`§r§6§lDivan's Alloy`, or an obfuscated
  run) was a silent miss, because the colour group admitted exactly one code.

Also: a completely unformatted `RARE DROP! …` is now refused by every caller, not just by the
Diana parser — two of the copies wrote that leading run with a `*` while their javadoc claimed
the anchoring argument verbatim. And `LootMachine.promote` rebuilt a promoted drop by hand,
which discarded the Magic Find of exactly the drops a jackpot reveal is about; it calls
`LootDrop.asRare()` now.

**Diana did not move.** Every pre-existing test in the repository passes unmodified — the 94
added cases are all in two new files, `BannerCorpusTest` and `LootDropTest` — and the settled
HUD frame `07-slot-all-reels-locked.png` is byte-identical to the pre-change capture.

### Ownership: one rule, and it now knows your name

Deciding whether a drop line was yours or a party member's was a substring guess, written twice
— four needles in `LootParser.isThirdPartyLine`, five in `CombatChatGuards` — and the two had
already drifted. Both were over-eager in the same direction: ` found a ` sits inside the
player's own "You found a …", and Hypixel writes "*yourOwnName* has obtained …" for a Crystal
Hollows pickup, so the machine silently declined loot the player had actually received. Nothing
looks more like a broken feature than one that quietly does nothing.

There is one implementation now, `core/loot/LineOwnership`, and both old front doors are
one-line delegates to it. A line is somebody else's only when it carries a colon (player-typed),
or a tell that names no actor, or a named third-person verb whose **actor** — rank prefix
stripped by the same `ContainerText.playerName` the chest broadcasts use — is not the local
player, compared as a whole token, case-insensitively. `You found a …` is the player's by
construction and needs no name at all.

The name comes from `Minecraft.getInstance().getUser().getName()`, `javap`-verified identical
on 26.1.2 and 26.2 — no Stonecutter conditional, and `Minecraft.user` is a final field set in
the constructor, so the name exists before the title screen rather than after a world join.

**An unknown name still refuses**, deliberately and permanently: refusing a line you did own
costs one spin, accepting one you did not puts five party members' Chimeras on your screen.
Second-person lines are exempt because they need no name to be certain of.

Also fixed while integrating: the fast-path needle array was a third hand-written copy of the
other two, and a needle missing from it would have been skipped rather than checked — the
fail-*open* direction. It is derived from the other two arrays now and pinned by a test.

Scope, measured over every chat-shaped string in the tree rather than argued: this changes the
verdict on no line containing `DROP!`, which is the only family the Diana parser and the
combat catch-all can reach. `docs/CHAT-PATTERNS.md` §3.11 has the whole rule and its attack
table.

### Known limitations

- **The Crystal Hollows chest lines this was expected to unblock were never gated by it.**
  `PowderChestDetector` keys on `CHEST LOCKPICKED` and `StructureLootChestDetector` on
  `LOOT CHEST COLLECTED`, and neither consults the ownership check. The over-eager needles
  actually reached the banner family and the combat guards. A Crystal Hollows chest that does
  not spin is a separate problem, still unlooked-at.
- `for assisting ` is refused even though it is second person and the shards do reach the
  player's inventory. Harmless today — `COMBAT_SHARD` and `SLAYER_MINIBOSS` both ship `NEVER`
  and neither detector consults the check — and the fix, if either arms, is to read the actor
  after the needle rather than drop it.
- No non-Diana trigger has been observed on a live server by this project — chat patterns and
  the six GUI screen titles alike were copied from SkyHanni and Skyblocker. About twenty
  patterns are inferred or widened rather than copied verbatim, each listed in
  `docs/CHAT-PATTERNS.md` §6.3. A wrong trigger fails silently.
- 12 sources have no detector and are simulate-only.
- The item art is a stand-in chosen for how it reads at 16x16, not Hypixel's own art, until an
  `item_model` has been captured from a live session. Coverage is only partly provable: the
  test can walk the 44 of 64 sources that declare a jackpot list (207 names) plus the five
  reels the self test photographs. Trophy fish outside the seven the registry names still draw
  the chest, harmlessly — a trophy catch is one fish at a time.
- Area-gated sources are permanently shut (nothing reads Hypixel's graph area yet) and
  seasonal sources are armed year-round (no season token).
- `ScreenTitleFeed` reaches the machine's bus by reflection, because `LootMachine` has no
  public screen-title route. The seam to retire it (`bind()`) is in place and the field name is
  pinned by a test.
- **No Magic Find suffix has been read off a live server by this project.** All five forms come
  from SkyHanni's and Skyblocker's own regression corpora. The dungeon form —
  `(+240% Magic Find!)`, no icon, exclamation inside the bracket — rests on a single source
  whose own regex there is malformed, so it is the one most worth confirming in a Catacombs run.
- **The exact wording of the Crystal Hollows self-pickup line is still inferred.** The shape is
  taken from SkyHanni's own regression samples (`oBlazin§r§f §r§ehas obtained §r§a§r§7[Lvl 1]
  §r§6Bal§r§e!`), not from a capture on this account, so the ownership rule is exercised
  against that *shape* rather than a verbatim line.
- Sack auto-pickups still cannot be read at all: the item names live in component hover text,
  which the chat path flattens away. That needs a Component-level reader, not a pattern.
- The colour codes on the Crop Fever sentence rows are reconstructed, not captured — SkyHanni
  matches that line colour-stripped. The wording is verbatim, and nothing in the pattern or the
  tests depends on the colours.

## 1.0.0 — 2026-08-29

Initial release. A client-side Fabric mod for Hypixel SkyBlock, built for Minecraft 26.1.2
and 26.2 from one shared source tree.

### Level prefix colours

- Recolours the `[451]` SkyBlock level prefix Hypixel prints in front of a player's name.
- Three modes: **Gradient**, which blends between colour stops in Oklab so a red-to-green
  ramp passes through clean yellows rather than muddy browns; **Brackets**, which keeps hard
  tiers you define yourself; and **Vanilla**, which reproduces Hypixel's own thirteen tiers
  exactly.
- Eleven gradient presets and two bracket presets, all editable, plus a `custom` slot that
  stops tracking a preset. The default is `spectrum`, an even hue sweep across the whole level
  range, so two players forty levels apart are obviously different colours; `vanilla_plus` is
  still there for anyone who wants Hypixel's own thirteen tiers, smoothed.
- Brackets take the level colour by default, so the whole `[451]` tag is one colour. Turn
  `levels.recolourBrackets` off for Hypixel's dim-bracket styling.
- Optional animated chroma above a level threshold you choose, with configurable speed,
  saturation, lightness and a refresh-rate cap.
- Three independent surfaces: chat, the TAB list and nametags above heads. Nametags are off
  by default.
- Scoped to SkyBlock by default, because nothing about the token `[451]` distinguishes a
  level tag from a bracketed number in someone's message.
- The rewrite preserves the surrounding component's hover text, click actions and
  shift-click insertions, and leaves the flattened text byte-identical. Only colours change.
- Configurable detection range, so a short bracketed number in chat can be excluded.
- `/skyprism preview [min] [max]` draws the palette across a level range, using the same
  palette object chat and TAB colour with.

### Diana slot machine

- A HUD slot machine that spins when a chosen Mythological creature dies during a Diana
  mayor term and locks its reels onto the drops parsed out of chat.
- Reels lock one after another. Captured drops are ranked most interesting first, so with
  more drops than reels the rarest are the ones shown; with fewer, the ranking cycles.
- A jackpot celebration when the server prints a rare-drop banner on a captured drop, and it
  is a second act rather than a change to the first. The ordinary roll plays out untouched --
  the reels lock on the real drops and settle with no gold anywhere -- and only then does the
  second act open, with all three reels breaking loose and the gold washing in over them at the
  same instant. They keep turning under the finished wash and land one at a time on the same
  item: three of a kind, held, then faded. Paced by `diana.jackpotIntroMillis`, `jackpotSpinMillis`,
  `jackpotLockStaggerMillis` and `jackpotHoldMillis`, none of which costs anything on a kill
  that does not pay out.
- Reels draw real Minecraft item sprites, with the drop's name in small text underneath
  (`hud.showDropNames`, on by default). The mapping lives in
  `assets/skyprism/drop_symbols.json`, so a renamed or newly added Hypixel drop is a data fix
  rather than a recompile.
- Each window is lit from the inside as it lands -- a warm wash behind the sprite, a glint
  crossing it, embers rising off it and a shockwave ring on the instant it locks -- under a
  `JACKPOT` headline drawn half again the size of the drop names with a bloom behind it, and
  a harder kick on the third and final match. Every part of it is derived from the frame's own
  clock, so it looks the same at 30 fps as at 240.
- Every name on the machine is drawn at one type size, chosen as the smallest fit anything
  currently on screen needs, so a spinning reel does not change size mid-scroll and the three
  columns never disagree. Names are fitted inside their windows rather than against the
  frame, and the longest of them -- `Dwarf Turtle Shelmet` -- is shown in full.
- All twelve Mythological creatures are individually switchable as triggers. Minos
  Inquisitor, King Minos and Manticore start on.
- Configurable reel count, spin length, lock stagger, settle, fade, loot window, and the four
  durations of the jackpot celebration.
- Optional island whitelist, so a rare drop from unrelated content landing shortly after a
  burrow is not credited to Diana.
- Optional suppression of the drop lines in chat once the reels have captured them (off by
  default).
- Anchor, position, scale, backdrop and backdrop opacity for the HUD, editable by dragging
  in `/skyprism hud`.
- Reel-tick and jackpot sounds, using vanilla sound events, with their own volume.
- Kills, rolls, jackpots and per-item totals tallied to `config/diana_stats.json` and
  readable with `/skyprism stats`.

### Configuration

- A four-tab YACL settings screen (Levels, Diana, HUD, Sounds), reachable through Mod Menu.
  Every option carries its own tooltip.
- YACL and Mod Menu are both optional. Without YACL the mod loads and runs normally with no
  settings screen; every `dev.isxander` import is quarantined in one package-private class
  so a missing library cannot cause a linkage failure.
- `config/skyprism/config.json` is safe to hand-edit. Values are clamped into range rather
  than rejected, an unparseable file is recovered from rather than overwritten, older
  schemas are migrated forward one version at a time, and a file written by a newer build is
  left alone with a warning instead of being downgraded.
- `/skyprism reload` re-reads the file from disk.

### Commands

`/skyprism` (status), `preview`, `hud`, `simulate`, `replay`, `replay stop`, `stats`,
`profile` (`reset` / `on` / `off`) and `reload`. All client-side; nothing is sent to the
server.

`/skyprism simulate <creature> [drops…]` fakes a kill through the real Diana controller, so
the machine can be seen and tuned without waiting for a Diana mayor term.
`/skyprism replay <file>` pushes raw chat lines from a text file through the mod's own
handlers.

### Performance

- No threads, no executors, no timers, no sockets and no network access anywhere in the mod.
- The Diana feature is gated on being on Hypixel, in SkyBlock, with Diana as mayor, on an
  allowed island. While the gate is shut the chat and render listeners are not subscribed
  at all.
- The facts behind that gate are read on a throttle: the server address on the connection
  edge, the sidebar at most every 2 seconds, the mayor at most every 15 seconds.
- TAB and nametag rewrites are memoised on the `PlayerInfo` and player entity respectively,
  so the steady-state cost is a cache hit rather than a rebuild. Chat is rewritten once per
  message.
- The HUD element early-outs before computing anything when no roll is running.
- `/skyprism profile` reports all of it, counting skipped work alongside done work.

### Build

- One source tree, two Minecraft versions, via Stonecutter 0.9.7 — with **zero** version
  conditionals. Both nodes compile from byte-identical Java source and all four mixins apply
  unchanged on both.
- Requires JDK 25; no machine-specific JDK path is committed anywhere.
- `gradlew test` runs on a bare JVM with Minecraft removed from the classpath, enforced by a
  tripwire that fails the build if a Minecraft artifact reappears. `gradlew mcTest` is the
  exact inverse and asserts Minecraft is present.
- `runClient -Dskyprism.selftest=true` boots the client, drives every screen the mod owns,
  photographs them and writes a machine-readable summary.

### Known limitations at 1.0.0

- **Not yet verified against the live Hypixel server.** The Diana chat patterns are copied
  character for character from SkyHanni's `GriffinBurrowHelper` (re-read against its `beta`
  branch on 2026-08-28) and tested here only against synthetic lines and fixtures. Mayor and
  island detection scrape display text from TAB and the sidebar and have never seen the real
  thing.
- **The Jackpot items setting has no effect on the live flourish.** The roll decides a
  jackpot from the server's rare-drop banner; the configured list is read only by
  `/skyprism simulate`.
- **No in-game way to open the settings screen without Mod Menu.** There is no
  `/skyprism gui` subcommand and no settings keybind.
- Minecraft 26.1.2 and 26.2 only.
