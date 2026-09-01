# Testing SkyPrism

How to convince yourself this mod works, in the order the checks get slower and more manual.

SkyPrism sits on Hypixel SkyBlock, and the two features it adds only fire under conditions you
cannot summon: a SkyBlock level prefix, and a Diana mayor term that comes round roughly once every
five real days. So the verification is layered — as much as possible is pulled down onto a bare JVM,
the rest is driven by a scripted client with no server at all, and what genuinely cannot be faked is
a short manual checklist at the end.

| Layer | Needs | Speed | Covers |
|---|---|---|---|
| `gradlew test` | a bare JVM | about a second | `com.skyprism.core.**` — every rule, no Minecraft |
| `gradlew :<mc>:mcTest` | Minecraft *classes* | a few seconds | `com.skyprism.mc.**` adapters that wrap `Component`, `Style`, `PlayerInfo` |
| `-Dskyprism.selftest=true` | a booted client | about a minute | every screen the mod draws, photographed |
| `/skyprism simulate`, `/skyprism replay` | a running client | interactive | Diana end to end, off-server |
| the manual checklist | Hypixel, and a Diana term for the last third | days | the things nothing above can reach |

---

## 1. The core suite — `gradlew test`

`com.skyprism.core.**` is Minecraft-free by construction, and the unit tests run on a plain JVM: no
game boot, no Loom runtime, no registries. That is the entire reason the suite is worth running on
every save.

The `test` source set's classpath is the module's real runtime classpath **minus Minecraft** —
`net.minecraft`, `com.mojang`, `org.lwjgl`, `io.netty`, the merged jar, sponge-mixin and MixinExtras
are all subtracted. Everything else stays, so fabric-loader, fabric-api, slf4j-api and Gson are
available and a class that merely implements a loader interface or uses a logger is still testable.

A tripwire inside the `test` task fails the build the moment a Minecraft artifact reappears on that
classpath. It scans the task's *own* classpath property rather than a configuration-time snapshot,
so it cannot be defeated by something that changes the classpath after configuration.

```
gradlew test                      # the active node (26.2)
gradlew testAllVersions           # both nodes
gradlew printTestClasspath        # prove Minecraft is absent
```

At the time of writing that is **665 tests across 24 test source files**, all passing, in about 14
seconds of JUnit wall clock. Take the number from the run, not from this document — it moves.

## 2. The Minecraft-aware suite — `gradlew :<mc>:mcTest`

`src/mcTest/java` is the exact inverse source set: it keeps the whole Minecraft classpath, so a test
there may name `Component`, `Style`, `ChatFormatting` and `PlayerInfo` directly. It has its own
tripwire, asserting Minecraft is *present*.

Ten test classes, around 70 tests, covering the adapters whose behaviour is cross-version sensitive:
`LegacyText`'s legacy-colour table, `ComponentRewriter`, `LevelNameMemo`, and the `ChatRouter`
markers.

Nothing here boots a game. No test calls `Bootstrap.bootStrap()` or touches a registry, so the task
is an ordinary headless JVM fork.

`mcTest` is deliberately **not** a dependency of `check` or `build`. Keeping it out is what keeps
`gradlew test` fast enough to run on every save, so you have to ask for it:

```
gradlew :26.2:mcTest
gradlew :26.1.2:mcTest
gradlew mcTestAllVersions         # both nodes
gradlew printMcTestClasspath
```

Run it on **both** nodes before believing a cross-version claim. That is what
`mcTestAllVersions` exists for: `clean buildAllVersions testAllVersions` used to stay fully green
while the one test that could catch a per-node colour-table drift never executed at all.

### A no-Gradle shortcut

During development it is often faster to compile without going through Gradle at all — and it lets
several agents work in the same tree without fighting over the Gradle lock. Two scratch scripts do
this: one `javac`s all of `src/main/java` against a saved Minecraft classpath, the other compiles
`core` plus its tests on a Minecraft-free classpath and runs the JUnit console launcher over
`com.skyprism.core`. They live outside the repository under a machine-local scratch directory, so
they are a convenience, not an entry point — Gradle remains the definition of a passing build.

---

## 3. The in-client self test

The fastest way to see the whole mod without Hypixel. It boots a real dev client, drives every
screen SkyPrism owns, photographs each one, checks a handful of claims about what it just did, writes
a machine-readable summary and then asks the client to quit.

```
gradlew :26.2:runClient   -Dskyprism.selftest=true
gradlew :26.1.2:runClient -Dskyprism.selftest=true
```

| Property | Meaning |
|---|---|
| `skyprism.selftest` | `true` arms the run. Read once, by `SkyPrismClient`, with `Boolean.getBoolean`. |
| `skyprism.selftest.out` | optional output directory |

Without the property the guard is one `false` test: `SelfTest.arm()` is never called, no class in
`com.skyprism.mc.selftest` is ever loaded, no listener is registered and nothing is allocated. The
cost to a normal player is zero, not "negligible".

A `-D` on the `gradlew` command line sets the property on the **Gradle daemon's** JVM, and Loom's
run tasks are forked `JavaExec`s that do not inherit it. `build.gradle.kts` therefore forwards every
system property in the `skyprism.` namespace -- and only that namespace -- into every run config
(`loom { runs.configureEach { ... } }`). Without that block the commands above boot a client that
sits on the title screen forever with no `self test armed` line in the log, which looks like a hang
and is not one. If a run produces no screenshots, grep the log for `self test armed` first.

Output goes to `-Dskyprism.selftest.out` if set. Otherwise it uses a hard-coded default path baked
into `SelfTest.DEFAULT_OUT_DIR`, and if that directory cannot be created it falls back to
`<gameDirectory>/skyprism-selftest`. **Set the property.** The baked default is a developer's local
scratch path and will not be right on your machine.

### What it captures

| File | What it shows |
|---|---|
| `01-yacl-settings.png` | the YACL settings screen, opened through the identical `ConfigGui.open(null)` call Mod Menu makes |
| `02-level-palette-a.png` | the palette preview, scrolled onto the chroma threshold |
| `03-level-palette-b.png` | the same screen one second later — the band above the threshold has moved, the band below it has not |
| `04-hud-placement.png` | the `/skyprism hud` placement screen, with a roll running behind it |
| `05-slot-spinning.png` | the slot machine mid-spin |
| `06-slot-one-reel-locked.png` | one reel locked |
| `07-slot-all-reels-locked.png` | all reels locked on the real drops, as item sprites with their names beneath |
| `08-jackpot-act-one-spinning.png` | a roll that *will* pay out, still spinning — indistinguishable from an ordinary one, which is the point. The step behind it asserts `jackpot()` is true while `inJackpotSequence()` is false and `jackpotIntroProgress()` is exactly 0, **and that no column has landed**. There used to be a second act-one frame here, `09-jackpot-settled-no-gold.png`, taken after every reel had locked and held; it was a photograph of the stall between the two acts, and a jackpot roll now never reaches `SETTLED` nor lands a column before the prize does, so the frame is gone and its surviving claim moved onto this one |
| `10a-jackpot-intro-early.png` | `JACKPOT_INTRO` a quarter in: the reels have **already** broken loose and the gold has barely started. The reels are unlocked from the first instant of act two, so the wash arrives over a machine that is moving rather than over a still one |
| `10b-jackpot-intro-mid.png` | the same wash half in, the strip somewhere else, still nothing landed |
| `10c-jackpot-intro-late.png` | the same wash nearly complete, reels still turning. Three frames rather than one because a single still of a half-gold machine cannot tell an overlap from a sequence. Each step asserts both halves at once — progress inside its own band, and every column unlocked and already carrying `jackpotSymbol()` — so a frame in which the machine had stopped would fail rather than merely look wrong |
| `11-jackpot-respin.png` | `JACKPOT_SPIN` — all three columns moving again, none landed |
| `12-jackpot-first-match.png` | `JACKPOT_LOCK` with one column landed on the jackpot item |
| `13-jackpot-third-match.png` | the payoff: all three columns landed on the same item |
| `14-jackpot-hold.png` | `JACKPOT_HOLD` — the three of a kind held before the fade |
| `20-pack-before-vanilla.png` | a settled reel drawing the **old** synthesised vanilla stacks: a plain stick, a fallback chest, a golden shovel. None of them carries an `item_model`, so Hypixel's pack has nothing to match on |
| `21-pack-after-hypixel.png` | the same three drops after their `item_model` has been learned — Hypixel's own Control Switch, Daedalus Blade and Ancestral Spade art, drawn by the same `SlotMachineHud.extractRenderState` |
| `22-pack-side-by-side.png` | both stacks per row in one frame, with the id under each. The last row is a control the pack has no art for, so its two columns are meant to match |
| `30-source-slayer-boss.png` | a slayer boss kill: the widget captioned `Voidgloom Seraph IV`, reels on Judgement Core, Coins and Null Atom. The longest subject the feature can produce |
| `31-source-dungeon-chest.png` | a dungeon reward chest: `Obsidian Chest`, reels on Necron's Handle, Essence and the Wither Catalyst. A container, not a kill — the subject is the chest that was opened |
| `32-source-rare-fish.png` | a rare sea creature: `Lord Jawbus`, reels on the Radioactive Vial, Magma Urchin and Lava Shell. The source that is open on every island |
| `recolour-report.txt` | the level recolour end to end, as text: a synthetic component tree pushed through the same two entry points a real chat line takes, printed run by run before and after |
| `selftest-summary.json` | every step, its status and its detail line |

Shots 30–32 exist to prove one thing: that the widget reads as a slot machine for sources that are
not Diana. They are also the only place the item-art table is checked by eye, and it was an eye that
caught the last three missing rows — so `DropSymbolsMcTest` now walks
`SelfTest.demonstrationRolls()` and fails the build if any reel in these frames would draw the
fallback chest, or would draw one sprite twice.

The three screens need no world. The HUD widget normally does, because Minecraft only draws the HUD
while a world is rendering, so `SlotStageScreen` calls the shipped
`SlotMachineHud.extractRenderState` directly instead — shots 05–08 come from the real render path,
not from a stand-in drawing.

#### Hypixel's server resource pack — mandatory, not optional

**The pack is required for the whole run, and a run without it aborts before the first shutter.**
This used to read as a setup note for shots 20–22 alone, and that understatement is how two
releases of screenshots shipped drawing vanilla art: the pack was mounted, every reel item quietly
fell back to its vanilla model because no stack carried a `hypixel_skyblock` `item_model`, and a log
line saying the pack had loaded was mistaken for proof that it was being used. `PackEnforcement`
now runs three `require()` steps ahead of shot 01 — the namespace must be mounted, the index must
hold a real number of item definitions, and a spot check must resolve definition → model → texture
→ baked model — and after the shots it decodes the written PNGs and searches each one for Hypixel's
own texture and for the vanilla texture the drop would have drawn instead. A frame carrying vanilla
art is renamed `REJECTED-*.png` so it cannot be copied into `docs/images/` by anyone who did not
read the summary.

Drop a copy of SkyBlock's server pack into the node's `run/resourcepacks/` and select it in that run
directory's `options.txt`:

```
resourcePacks:["file/hypixel_server_pack.zip"]
```

The pack declares `pack_format` 84, which is **26.1.2's** resource format; 26.2 is format 88 and
will refuse the pack unless it is also listed under `incompatibleResourcePacks`, which is what
vanilla writes once a player has confirmed the "made for a different version" prompt. The reverse
is true too: leave that line populated on 26.1.2 and Minecraft removes the pack from the
incompatibility list on load *and drops the selection with it*, so the pack ends up available but
not applied. So on 26.1.2 `incompatibleResourcePacks` must be empty, and on 26.2 it must name the
pack.

The first step of the section checks the item definition, the model, the texture and the baked item
model for every id it is about to draw, and fails the run if any is missing — a wrong id renders as
an untextured cube, which photographs as a confident-looking success.

These shots prove the **render** half: that a stack carrying an `item_model` present in the pack
draws Hypixel's art, and that the synthesised fallback beside it still draws plain vanilla. They do
not prove the **capture** half. A dev client has no SkyBlock server, so the stacks are constructed
and handed to `DropSymbols.learnFrom` — the same call `IconCapture` makes for a stack matched in the
player's inventory. Everything downstream of that call is genuine; only the stack's arrival is
staged. `drop_item_models.json` is also not written on such a run: the memory is read when the Diana
gate opens, and the gate never opens without Hypixel, so learned rows stay in memory for the
session. Persistence is covered by the `mcTest` suite instead.

### Reading the summary

```json
{
  "schema": "skyprism-selftest/1",
  "mod": "1.0.0+26.2",
  "minecraft": "26.2",
  "durationMillis": 36168,
  "outDir": "...",
  "passed": 25, "failed": 0, "skipped": 1, "ok": true,
  "steps": [
    { "id": "...", "status": "PASS", "detail": "...", "file": "..." }
  ]
}
```

`ok` is the one field to check in a script: it is `failed == 0`. Each step carries an `id`, a
`status` of `PASS`/`FAIL`/`SKIP`, a `detail` line explaining what was actually asserted, and the
`file` it produced if any. The same list is printed to the log at `INFO` under `SkyPrism/selftest`,
one line per step, so a run that could not write the JSON still leaves a record.

The detail lines are the useful part. They are not decoration — they say what was measured, e.g.
*"level 600 went #F547F4 → #47F55D"* for the chroma check, and *"scrolled to level 280, so the grid
straddles the chroma threshold at 300"* for the step that exists because without it the two frames
came out byte-identical while the assertion beneath them still passed.

One step is expected to be **SKIP**: `in-world HUD capture`. Creating a singleplayer world from a
script means `WorldOpenFlows` plus a full `WorldStem`, which is slow, differs between the two nodes,
and can leave a half-written save behind. The render path is covered by shots 05–09 regardless.

The run stages the settings it needs — chroma on from level 300, HUD centred at scale 2, widened reel
timings so each phase can be photographed — through `ConfigManager.refresh()`, which republishes the
palette and every cache **without writing the file**. Running the self test does not touch your
`config.json`; no step calls `save()`.

A tick watchdog ends the run after 240 seconds whatever happens, and the client is stopped the same
way the vanilla quit button does it, so the launcher sees exit code 0.

---

## 4. Exercising Diana off-server

Diana is mayor about one week in five. These two commands drive the feature without waiting.

### `/skyprism simulate <creature> [drops...]`

Starts a roll immediately. `<creature>` accepts the full name with underscores or any of the short
aliases — `inquisitor`, `inq`, `champ`, `lynx`, `bull`, `gaia`, and so on; tab completion lists them
all. Drops are free text separated by commas:

```
/skyprism simulate inquisitor
/skyprism simulate inquisitor Chimera I, 40000 coins, 3x Ancient Claw
```

Leave the drops out and a plausible set is rolled for you. A trailing `coins` is read as a coin
amount, a leading `3x ` as a stack count, and anything else is taken as an item of that name — a
simulator that rejected input would be a worse tool than one that takes you at your word.

`simulate` goes straight into `DianaController.simulate`, which **deliberately does not consult the
gate**. That is the whole point: it has to work while Diana is not mayor and you are not on Hypixel.
The trigger set is not consulted either, so any creature you name will spin.

Two things about jackpots are worth knowing before you use this to test the flourish. The `*` in the
command's own echo comes from `diana.jackpotItems`, but the reels latch their flourish from
`LootDrop.rare()` — the flag `LootParser` sets when Hypixel printed a `RARE DROP!` banner. Drops you
type by hand are always constructed with `rare = false`, so a typed drop list will **not** produce a
flourish however you name the items. Leave the drops off and let `SimulatedLoot` roll a set for you
instead: it draws from `JackpotRule.defaults()` and sets the rare flag, which is what makes the
flourish genuinely fire. It is a roll, not a guarantee — a rare drop appears about one time in three
for a rare creature and one in eight otherwise — so run it a few times.

If the Diana module was never registered, the command falls back to staging the kill as the chat
lines Hypixel would have sent. That path is less direct but exercises the parsers as well, and it
*is* gated, so the reply says so.

### `/skyprism replay <file>`

Feeds a file of raw chat lines through the real pipeline, one line every four ticks (200 ms).

```
/skyprism replay diana-inquisitor.txt
/skyprism replay stop
```

Relative paths resolve against the game directory. The format:

- one raw chat line per file line
- section signs may be literal `§`, written `&a`, or written `§a`
- blank lines are skipped
- a line starting with `#` is a comment, except `#wait 1500`, which inserts a pause in milliseconds
- at most 2000 lines are queued; the rest of the file is ignored with a warning
- starting a replay cancels any replay already running, and says how many lines it dropped

Replay is the honest end-to-end test, because it goes through `ChatRouter.replay` — the same code a
real message takes. That means it **is** subject to the Diana gate (`diana.enabled` and
`DianaGate.isOpen()`), so on a dev client with no server the Diana half will not fire unless you open
the gate. The level recolour half deliberately is **not** gated on `onlyOnSkyBlock` here: replay is
an explicit request to run the pipeline against a fixture, usually from a client that is not on
Hypixel at all, and refusing to colour it there would make the one command written to demonstrate the
feature the one command that cannot.

To force the Hypixel half of the gate open on a dev client:

```
gradlew :26.2:runClient -Dskyprism.forceHypixel=true
```

That seeds `HypixelContext`'s override at class-load. It only forces the *server address* condition;
SkyBlock and the Diana mayor still come from the sidebar and the TAB list, so on a dev client with
no scoreboard the gate stays shut. For a full gate you need a server that produces a SkyBlock sidebar
and a `Mayor Diana` TAB row.

### The other commands

| Command | Use |
|---|---|
| `/skyprism` | status: feature switches, mode, preset, surfaces, chroma, gate state and why it is shut, HUD, config path |
| `/skyprism preview [min] [max]` | full-screen palette grid; the definitive way to see what a palette change did |
| `/skyprism hud` | drag the machine where you want it; saves on close |
| `/skyprism stats` | the session-to-session Diana tally from `config/diana_stats.json` |
| `/skyprism profile` / `on` / `off` / `reset` | per-surface cost: chat, TAB, nametags, HUD, and a total in ms/s against a 60 FPS budget |
| `/skyprism reload` | re-read `config.json` from disk and invalidate every cache |

`/skyprism profile` is the check for "does this cost anything". It reports the TAB memo hit rate and
the total render cost as a share of a 60 FPS frame budget; recording is off by default and `on` makes
the hot paths start timing themselves.

---

## 5. Manual acceptance checklist

Everything above runs without Hypixel. This is what does not. Work through it in order — the level
half is available any day, the Diana half needs a mayor term.

Before you start: build and install the jar — `gradlew buildAllVersions` collects both nodes' jars
into `<root>/build/libs/<mc>/` — alongside Fabric API, which is a hard dependency. Add YACL and Mod
Menu if you want the settings screen: YACL builds it, and Mod Menu's config button is the only way
to open it. There is no keybind and no `/skyprism gui` subcommand. Without YACL the mod runs
normally and says the screen is unavailable rather than failing silently, and you edit
`config/skyprism/config.json` by hand — note that path, you will want it either way.

### A. Level prefix in chat

1. **Join Hypixel and enter SkyBlock.** Run `/skyprism`. Expect `Level colours: on  mode GRADIENT
   preset vanilla_plus` and `surfaces  chat on  tab on  nametags off`.
   *If the status says the config module is unregistered*, the mod half-initialised — check the log
   for a failed subsystem before anything else.
2. **Get a chat line with a level prefix in it.** Any public chat in a Hub works; `[451] Player: hi`.
   Confirm the digits are a smooth gradient shade rather than one of Hypixel's thirteen flat tier
   colours. Two players forty levels apart should be visibly different; two players one level apart
   should be a hair apart.
   *If nothing changes*: check `levels.enabled` and `levels.applyToChat`, then run
   `/skyprism preview 0 600` — if the preview is coloured and chat is not, the problem is the chat
   hook, not the palette.
   *If it changes the wrong text* — a coordinate, another mod's counter — narrow `levels.minLevel`
   and `levels.maxLevel`, and make sure `onlyOnSkyBlock` is on.
3. **Confirm the brackets.** By default the `[` and `]` should keep whatever colour Hypixel drew
   them and only the digits should change. Turn on **Recolour the brackets too** and confirm the
   whole tag takes the colour, then decide which you prefer.

### B. Level prefix in TAB

4. **Open TAB in a busy Hub.** Confirm the same recolouring on the level prefix in the player list.
   *If chat works and TAB does not*: `levels.applyToTabList`, then `/skyprism profile` — if the TAB
   line shows zero rebuilds, the mixin is not being reached; if it shows rebuilds and the colours are
   still Hypixel's, the memo is serving stale entries and `/skyprism reload` should clear them.
5. **Change a setting with TAB open** (the palette preset is the most visible). It should repaint
   without a restart. That is the generation counter doing its job.

### C. Above-head name tags — the unverified one

6. **Look at another player's name tag in the world.** The question to answer is not "does SkyPrism
   recolour it" but **"does Hypixel put the level prefix there at all?"** This is not confirmed. The
   wiki documents the prefix in TAB and chat only, which is why `applyToNameTags` ships `false`.
7. **If you see a `[451]` above a head**, turn on **Nametags above heads** and confirm it recolours
   like the other two surfaces. If it does, that is the first confirmation the feature has, and the
   right follow-up is to flip the shipped default in `SkyPrismConfig.LevelSettings`.
8. **If you never see one**, record that. Leaving the default off is then correct and the hook costs
   one boolean read and one `instanceof` per visible player per frame.
   Either way, write down what you actually saw — this row is the only open question in the level
   feature.

### D. Diana — needs a mayor term

9. **Wait for a Diana mayor term.** Confirm the mod agrees: `/skyprism` should print
   `gate  open, roll …` rather than `closed (…)`. The gate names its own missing conditions, so a
   closed gate tells you which of "not on Hypixel", "not in SkyBlock", "mayor is not Diana" or the
   area whitelist is the problem.
   *If the mayor is Diana and the gate still says otherwise*: the mayor is read off the TAB list,
   not a packet, at most once every 15 seconds. Open TAB, wait 15 s, re-run the command. `Minister
   Diana` is deliberately **not** accepted — a minister does not grant the Ritual.
10. **Go to a Diana island and equip a Griffin Pet with an Ancestral Spade.** Dig a burrow.
11. **Kill the creature that spawns.** With the shipped trigger set only Minos Inquisitor, King
    Minos and Manticore spin the reels, so for a first test either tick more creatures in the Diana
    category or expect the common ones to be silent.
    *If the kill is not detected at all*: check `/skyprism stats` — if kills is climbing but rolls
    is not, the creature is not in your trigger set; if kills is not climbing either, the spawn line
    is not matching and `/skyprism profile` plus the log is the next stop.
12. **Confirm the machine spins and locks onto the real drops.** The reels should show the items you
    actually received, not a random selection. Compare against the drop lines in chat.
    *If the reels lock onto "No Drop"*: the drops landed outside `lootWindowMillis`. Raise it from
    3000 and try again.
    *If the reels show items from an unrelated kill*: fill in the **Islands** whitelist. The loot
    parser recognises Hypixel's banners server-wide, so a slayer or dungeon `RARE DROP!` inside a
    stale spawn's lifetime can otherwise be credited to Diana.
13. **Get a jackpot.** The flourish fires on any drop Hypixel announced with a `RARE DROP!` (or
    louder) banner, so on a good burrow chain it should happen fairly soon. Confirm the extra spin,
    the pulsing border and the sound. Note that the **Jackpot items** list in the settings screen
    does *not* control this — see the note in [CONFIG.md](CONFIG.md#jackpot-items). If you want that
    list to be what decides, that is a code change, not a config change.
14. **Turn on "Hide the drop lines in chat"** and do it again. The drop lines the reels captured
    should disappear from chat; lines that never reached a reel should still show.
15. **Run `/skyprism profile`** after a session. Confirm the total sits at a small fraction of a
    60 FPS budget and that the HUD line shows frames skipped while idle, not drawn.

### E. Both Minecraft versions

16. Repeat A, B and D on the other node. There are zero Stonecutter conditionals — both versions
    compile from byte-identical source and all four mixins apply unchanged on both — so a difference
    here is a real finding and worth a bug report rather than a shrug.

### What "done" looks like

Rows 1–5 and 9–15 should all pass. Rows 6–8 have no expected answer yet; the deliverable there is an
observation, not a tick.
