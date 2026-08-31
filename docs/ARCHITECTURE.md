# SkyPrism architecture

How the mod is put together, and why it is put together that way.

SkyPrism does two things. It repaints the SkyBlock level prefix Hypixel prints in front of
a player's name — `[451]` — in chat, in the TAB list and on above-head nametags. And it
runs a HUD slot machine that spins when a chosen Diana creature dies and locks its reels
onto the drops the player actually received.

Neither feature is large. What shapes the codebase is that both of them read strings a
third party controls, run on threads that must not stall, and have to compile unchanged
against two Minecraft versions.

---

## 1. The core/adapter split

Every class in SkyPrism sits on one side of a line.

**`com.skyprism.core.**` contains no reference to Minecraft.** Not a `Component`, not a
`Style`, not a `ChatFormatting`. It works in `String`, `int`, `long`, a small `StyledRun<S>`
generic over whatever the caller's style type happens to be, and records of its own. It is
where the colour maths lives, where the regexes live, where the slot-machine state machine
lives, and where the config schema lives.

**`com.skyprism.mc.**` is the adapter layer.** It names Minecraft types, translates them
into core types, calls into the core, and translates the answer back. It contains as little
decision-making as it can get away with.

**`com.skyprism.mixin.**` holds four mixins**, and they are thinner still: check a flag,
fetch a cache cell, hand the work to `com.skyprism.mc.surfaces`, set a return value.

### What the rule buys

The whole of `com.skyprism.core` compiles and runs on a bare JVM. The core suite —
**665 tests across 100 containers** at the time of writing — runs in seconds, with no
Minecraft jar on the classpath, no `Bootstrap.bootStrap()`, no registries and no game
window. (Take the count and the timing from an actual run; both move. See
[TESTING.md](TESTING.md).) That is what makes it possible to write a test that says
"level 451 under this twelve-stop ramp is `#9BF547`" or "this hostile chat line must not
match the spawn pattern" and get the answer back before you have finished reading the
assertion.

The second thing it buys is that the interesting logic is testable *at all*. `SlotRoll` is
a state machine over a `Clock` interface; `FixedClock` lets a test step it to the
millisecond and assert exactly which reel has locked. If `SlotRoll` held a `Minecraft`
reference, none of that would exist.

### What keeps the rule true

Three mechanisms, and none of them is "remember to".

1. **`gradlew test` runs on a classpath with Minecraft subtracted.** `build.gradle.kts`
   takes the real runtime classpath and removes `net.minecraft`, `com.mojang`, `org.lwjgl`
   and `io.netty` plus the `minecraft-merged` / `sponge-mixin` / `mixinextras` file
   artifacts, keeping everything a Minecraft-free class can legitimately need
   (fabric-loader, slf4j, gson, guava). A `doFirst` tripwire scans the `Test` task's own
   `classpath` property and fails the build the moment a Minecraft marker jar reappears.
2. **`corecheck.sh` compiles `src/main/java/com/skyprism/core` on its own**, against a
   Minecraft-free classpath. If anything under `core/` ever grows a Minecraft import, that
   compile fails before any test runs.
3. **A second, opt-in source set for the other side.** `src/mcTest` is the exact inverse:
   it keeps the whole Minecraft classpath, with its own tripwire that fails if Minecraft
   *disappears*. It is deliberately not a dependency of `check`, so the fast suite stays
   fast, and it is the only automated coverage of the adapters whose behaviour is
   cross-version sensitive.

One wrinkle worth knowing: `DianaLineFilter` lives in `com.skyprism.mc.chat` but has no
Minecraft imports and no static initialisation that reaches any. That is deliberate — it
lets `DianaMarkerContractMcTest` load and call it directly, without dragging in
`ChatRouter`'s class-init (which reaches `ConfigManager`, `Metrics` and the Diana
controller).

---

## 2. Module map

| Package | Job |
| --- | --- |
| `com.skyprism` | `SkyPrismClient`, the Fabric `ClientModInitializer`; starts each subsystem inside its own try/catch so one failure degrades rather than crashes. |
| `com.skyprism.core.config` | The settings schema (`SkyPrismConfig`), JSON load/save that never destroys a file it cannot read (`ConfigCodec`), schema upgrades applied to the raw JSON tree before binding (`ConfigMigrations`), and `HudAnchor`. |
| `com.skyprism.core.level` | Colour. `Oklab` conversion and mixing, `GradientRamp` (interpolated stops), `BracketTable` (step tiers), `ChromaClock` (time → HSL hue), `PalettePresets` (the five shipped ramps and two tables), and `LevelPalette`, which folds mode + chroma threshold into one `colorFor(level, millis)`. |
| `com.skyprism.core.level` (parsing) | `LevelTagLocator` finds `[451]` in plain text and returns `LevelTag` records carrying both the whole-tag span and the digits-only span. The highest-risk parser in the mod. |
| `com.skyprism.core.text` | Style-agnostic run surgery. `StyledRun<S>` is a `(text, style)` pair; `Span` is a half-open range with an `int` payload; `RunText` flattens and measures; `RunRewriter.restyle` splits runs at span boundaries and applies a restyling function, without ever touching the text. |
| `com.skyprism.core.util` | `Clock`/`SystemClock`/`FixedClock`, `TextClean` (strips `§` codes, optionally producing a stripped→source index map), `TimeFormat`. |
| `com.skyprism.core.diana` | The Diana feature's brain. `DianaPatterns` (four anchored Hypixel regexes), `MythologicalCreature` (the twelve creatures), `LootParser` → `LootDrop`, `JackpotRule`, `DianaGate`, and the reel engine: `SlotRoll`, `SlotRollConfig`, `RollState`, `Reel`. |
| `com.skyprism.mc.text` | `ComponentRewriter` — the only class that turns a Minecraft `Component` into core runs, drives the recolour, and rebuilds a `Component`. `LegacyText` — `Component` ⇄ `§`-coded legacy string. |
| `com.skyprism.mc.surfaces` | `LevelSurfaces`, the shared entry point for the TAB and nametag hooks: memoisation, chroma frame quantisation, per-surface circuit breakers. `LevelNameMemo` is the cache cell; `LevelNameMemoHolder` is the interface two mixins implement to expose one. |
| `com.skyprism.mc.chat` | `ChatHooks` (the only class here that names Fabric), `ChatRouter` (the two callbacks plus a testable `replay` seam), `DianaChatBridge` (an interface so the Diana feed can be swapped in tests), `DianaLineFilter` (the substring pre-reject). |
| `com.skyprism.mc.diana` | `DianaController` — the state machine that decides when to spin. `HypixelContext` — reads server address, sidebar and TAB to answer the gate's four questions. `CreatureTracker` — binds to the spawned entity and notices its death. `DianaStats` — the on-disk tally. |
| `com.skyprism.mc.hud` | `SlotMachineHud`, a Fabric `HudElement` attached before the vanilla chat element. Owns geometry, the jackpot flourish and the sounds. |
| `com.skyprism.mc.config` | `ConfigManager`, the singleton that owns the loaded config, the derived `LevelPalette` and `LevelTagLocator`, and a monotonic `generation` counter every cache keys on. |
| `com.skyprism.mc.command` | The `/skyprism` tree and everything it needs: `SkyPrismServices` (a registry seam so commands never name other modules directly), `DefaultBindings` (the one file that does), `Feedback` (output house style), `Metrics` (performance counters behind `/skyprism profile`), `Palettes`, `SimulatedLoot`, `ChatPipeline`, `ClientScheduler`, and two screens — `LevelPreviewScreen` and `HudPlacementScreen`. |
| `com.skyprism.mc.gui` | `ConfigGui`, the `Screen`-typed facade; `SkyPrismConfigScreen`, the package-private class that is the only place in the mod permitted to import `dev.isxander`; `ModMenuIntegration`. |
| `com.skyprism.mc.selftest` | The in-client camera: `SelfTest` drives a real client through every screen, `Shots` captures the framebuffer, `SlotStageScreen` draws the real HUD without a world, `RecolourProbe` pushes a synthetic Hypixel-shaped component through the shipped recolour path and writes down what happened. Loaded only when `-Dskyprism.selftest=true` is set. |
| `com.skyprism.mixin` | The four mixins, and nothing else. |

---

## 3. Feature 1 — the level prefix, end to end

### Three entry points, one pipeline

```
  chat        ClientReceiveMessageEvents.MODIFY_GAME
              -> ChatRouter.modifyGameMessage(Component, boolean overlay)
                    no memo: a chat line is seen once

  TAB         PlayerTabOverlayMixin  @Inject(RETURN) getNameForDisplay(PlayerInfo)
              -> LevelSurfaces.tabDisplayName(memo, source, gameMode, decorated)
                    memo lives on the PlayerInfo   (PlayerInfoMixin)

  nametag     EntityRendererNameTagMixin  @Inject(RETURN) getNameTag(Entity)
              -> LevelSurfaces.nameTag(memo, nameTag)
                    memo lives on the AbstractClientPlayer (AbstractClientPlayerMixin)

                              all three converge on
                                       |
                                       v
                    ComponentRewriter.recolourLevels(...)
```

### Inside `ComponentRewriter.recolourLevels`

```
  Component source
        |
        |  mightContainLevelTag(source)        <- cheap reject, no regex, no allocation
        |    a single PlainTextContents with no siblings is scanned straight out of its
        |    String; anything else costs one small TagScanner. Looks only for
        |    '[' digits ']' , skipping over § codes so they cannot break a digit run.
        |
        |  -- false --> return source (by identity)
        v
  toRuns(source)                     Component.visit -> List<StyledRun<Style>>
        |
  RunText.flatten(runs)              -> one flat String
        |
        |  does the flat text contain § ?
        |     no  -> tags = locator.find(flat)                 (the common case; free)
        |     yes -> stripped = TextClean.stripFormattingWithOffsets(flat)
        |            tags    = locator.find(stripped.stripped())
        v
  LevelTagLocator.find(plain)        -> List<LevelTag>{ start,end, level, digitsStart,digitsEnd }
        |
        |  -- empty --> return source (by identity)
        v
  build Span list
        from/to = recolourBrackets ? tag.start()/end()  :  tag.digitsStart()/digitsEnd()
        projected back through StripResult when one was built
        payload = tag.level();   maxLevel tracked for the caller
        |
        v
  RunRewriter.restyle(runs, spans, (style, span) ->
          style.withColor( LevelPalette.colorFor(span.payload(), nowMillis) ))
        |
        |  splits runs at span boundaries, refuses overlapping spans, refuses a
        |  boundary that would split a surrogate pair, and never edits any text
        v
  fromRuns(restyled)                 merges adjacent same-identity runs, rebuilds a Component
```

Three properties fall out of this shape, and all three are asserted by `RecolourProbe`
inside a real client — it writes `recolour-report.txt` into the self-test output directory
(`-Dskyprism.selftest.out`, defaulting to a `skyprism-selftest` folder), listing every run's
colour and style before and after:

- **The flattened text is byte-identical before and after.** `RunRewriter` only ever splits
  runs and replaces styles.
- **Hover, click and insertion events survive.** The restyler starts from the run's existing
  `Style` and calls `withColor`, so every other field is carried through.
- **Nothing but bracketed digits is touched.** See the emblem caveat in
  [CHAT-PATTERNS.md](CHAT-PATTERNS.md).

`recolourLevels` is documented as never throwing, and it means it: the whole body,
pre-filter included, sits in a `catch (RuntimeException | LinkageError)` that returns the
original component. It has to, because the chat hook has no failure budget behind it — an
escape would land inside Fabric's message dispatch and repeat on every line Hypixel sends.

### Where the memoisation sits

Chat has none, and needs none: a chat line arrives once and is never re-rendered.

TAB and nametags are per-frame. `PlayerTabOverlay.getNameForDisplay` is called once per
listed player per frame — eighty times a frame on a full Hypixel lobby. So the result is
memoised on the entry itself, via `LevelNameMemo` fields injected by `PlayerInfoMixin` and
`AbstractClientPlayerMixin` and reached through the `LevelNameMemoHolder` interface. A cast,
not a hash lookup, on a path that runs a hundred-odd times a frame.

The cache key is a triple:

| Part | TAB | Nametag |
| --- | --- | --- |
| source component | `info.getTabListDisplayName()` — keeps its identity until the server sends another player-info packet | the returned nametag component |
| variant | `info.getGameMode()` — vanilla italicises spectators, so the decoration depends on it | `null` |
| generation | `ConfigManager.generation()`, bumped on every settings change | same |

`LevelNameMemo.keyMatches` tries reference identity first. On an identity miss the TAB
surface falls straight through to `equals` (its key is exact, so there is nothing to trade
away); the nametag surface only pays for `equals` on every sixteenth draw
(`LevelSurfaces.NAME_TAG_REVALIDATE_DRAWS`).

Attaching the cache to `PlayerInfo` also solves invalidation for free: the client drops a
`PlayerInfo` when the player leaves and builds a new one when they return, so a stale entry
cannot outlive the thing it describes and nothing ever has to sweep.

Two more details of the memo path are worth knowing:

- **"No change" is cached too.** `recolourLevels` returns its argument by identity when it
  matched nothing. `LevelSurfaces` stores that outcome as a `null` value, so a name with no
  level tag — the common case off Hypixel — is not rescanned every frame. Both mixins treat
  a `null` return as "leave vanilla alone".
- **Chroma is quantised, not per-frame.** When the palette reports the component's highest
  tag as chromatic, the cached value is recomputed only when the quantised clock advances:
  `interval = max(4, 1000 / chromaUpdateHz)` (33 ms at the default 30 Hz), and
  `quantised = now - floorMod(now, interval)`. Every surface in a frame therefore asks the
  palette for the same timestamp, so the TAB list and the nametag above the same player
  cannot drift out of phase. Whether a component *is* chromatic is decided from
  `SCAN[1]` — the highest level the rewriter just matched — rather than by a second full
  scan of a component whose tags were located a line earlier.

### Failure posture

Each render surface has its own `Breaker` in `LevelSurfaces`, with a budget of
`FAILURE_BUDGET = 8`. The first failure logs a warning; the eighth disables that surface and
logs an error naming the fix ("change any SkyPrism setting to try again"). A generation bump
re-arms it. The catch is deliberately `Throwable`, because a `LinkageError` from a
half-loaded class would otherwise escape into the render loop and the right response is
identical either way: leave the server's own colours on screen.

`ChatRouter.modifyGameMessage` has the same posture without the budget — it logs once and
returns the message unchanged forever after.

---

## 4. Feature 2 — the Diana slot machine, end to end

```
 ClientReceiveMessageEvents.ALLOW_GAME
   |
   |  two listeners are registered (see "two doors" below); both reach the same method
   |
   |  1. gate.isOpen()?  ------------------------ no --> return true. Done. One field read.
   |  2. diana.enabled?  ------------------------ no --> return true.
   |  3. DianaLineFilter.mightMatterToDiana(message.getString())
   |        four String.contains on text the caller already had
   |                          ---------------- no --> return true.
   |  4. LegacyText.toLegacy(message)
   |        Component -> "§6§lRARE DROP! §r§9Griffin Feather§r§e!"
   v
 DianaController.handleLine(legacy, now)
   |
   |  same line inside 50 ms? -> drop it (the two doors would otherwise double-count)
   |
   +-- DianaPatterns.matchSpawn(raw) -----------> MythologicalCreature
   |        pendingCreature = it;  pendingAt = now
   |        CreatureTracker.expect(creature, now)   + one immediate nearby scan
   |
   +-- DianaPatterns.INQUISITOR_SHARE (only when diana.onlyMyBurrows is off)
   |        treated as a Minos Inquisitor spawn
   |
   +-- LootParser.parse(raw) -------------------> List<LootDrop>
            if no roll is running:
                needs a pendingCreature within SPAWN_TTL_MILLIS (5 min)
                that creature must be in diana.triggers
                -> beginRoll(creature, now)
            then, while now <= lootDeadline:
                SlotRoll.offerDrop(drop)  and  DianaStats.recordDrop(drop)
```

### Binding to the entity, and noticing it die

The spawn line names the creature; it does not tell you which entity it is. `CreatureTracker`
finds it two ways, and never by iterating the world:

- `ClientEntityEvents.ENTITY_LOAD` — if the loading entity's custom name contains the
  expected display name, bind to it.
- A throttled query on the client tick — at most once every 500 ms, `getEntitiesOfClass`
  over a 48 × 24 × 48 box around the player, filtered to entities with a custom name, taking
  the **nearest** match rather than the first. The Hub is full of other players running the
  same ritual, so several nametags reading "Minos Champion" inside the box is the ordinary
  case.

Defeat is polled on `END_CLIENT_TICK`. A bound entity counts as dead when it reports
`isRemoved()`, is not alive, or is a `LivingEntity` that is dying. `ENTITY_UNLOAD` can also
report a defeat, but only with all three of: the binding is at least 250 ms old, the entity
is within 48 blocks, **and** the entity carries a death signal of its own. That third
condition is not belt-and-braces — Fabric fires `ENTITY_UNLOAD` for every entity in the
level on respawn and on `clearLevel`, and on Hypixel a proxy warp between islands is a
respawn packet. Without it, every warp spun the machine for a kill that never happened.
`ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` is the other half of that fix: it tears
everything down.

A creature killed out of view never binds at all, which is why the drop line itself can
start a roll, subject to the five-minute spawn TTL.

### The reel engine

`SlotRoll` is pure state over an injected `Clock`. Nothing in it renders, and nothing in it
knows what a frame is.

```
 start(creature)          t0
   |
   |<----- spinMillis ----->|
   |                        |<-- lockStagger -->|<-- lockStagger -->|
   |                        v                   v                   v
   |                     reel 0 locks       reel 1 locks        reel 2 locks
   |                        |                                       |
   |  SPINNING              |        LOCKING                        |   SETTLED
   |                                                                |<- settleMillis ->|
   |                                                                                   |  FADING
   |                                                                                   |<- fade ->|
   |                                                                                              reset()
   |
   |<---------------- lootWindowMillis: offerDrop is accepted -------->|
```

Defaults (`SlotRollConfig.defaults()`): 3 reels, 1200 ms spin, 250 ms stagger, 3000 ms loot
window, 2500 ms settle, 500 ms fade, 900 ms extra spin on a jackpot.

Two things about it are worth calling out:

- **A reel shows only what had arrived by the moment it locked.** `reelsAt(now)` recomputes
  every reel's symbol from the captures whose `atMillis` is at or before that reel's lock
  time. Reels are ranked (rare first, then by descending count, then by arrival order) and
  each reel prefers a symbol not already shown, falling back to `reelIndex % visible`. With
  nothing captured, a locked reel shows `SlotRoll.NO_DROP` ("No Drop").
- **Every arithmetic operation on the timeline is overflow-clamped.** `addClamped` saturates
  instead of wrapping, and window checks are written as `now > start + window` rather than
  `now - start > window`, so a clock far from zero cannot reopen a window that has shut.

### From reels to pixels

`SlotMachineHud` is a Fabric `HudElement`, attached with
`HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ...)`. Its first act each
frame is `roll.activeAt(now)`; when that is false it records a skip in `Metrics` and returns.
All the animation the HUD owns is presentation only — fade alpha, the jackpot breath, the
shine sweep, sparks — driven from the same wall clock, never from state the engine holds.

**One accuracy note on the jackpot.** The flourish is driven by `SlotRoll.jackpot()`, which
becomes true when any captured `LootDrop` has `rare() == true`. On the live path
`LootParser` sets `rare = true` for *every* item it parses and `false` only for coins, so in
practice the flourish fires on any item drop rather than only on the rarest ones.
`JackpotRule` — which does encode a rarity order — is not consumed by the runtime feature
path at all; it is used by `SimulatedLoot` (so `/skyprism simulate` genuinely produces a
recognised drop) and by tests. The `diana.jackpotItems` config list is read by
`SkyPrismConfig.DianaSettings.isJackpot(String)`, which only `SkyPrismCommands` calls, for
its printed output. If the intent is "flourish only for the configured items", that wiring
does not exist yet.

### The drop tables

What a reel *shows* is data, not code: the per-source jackpot lists in
`core/loot/LootSourceRegistry`, the shared filler names in `mc/hud/FillerStrip.GENERIC`, and
the name → vanilla-sprite rows in `assets/skyprism/drop_symbols.json`. A strip is its source's
jackpot list plus `GENERIC`, so those three files are the only thing standing between a player
and loot that does not exist. They were originally assembled by reading wiki pages and were
wrong in ways a SkyBlock player spots on sight. **They are now sourced from the
NotEnoughUpdates item repo for names and from Hypixel's own server resource pack for which
content area an item belongs to; Fandom is blocked and cannot be used.** A fake name is not a
loud failure — it simply never matches, so the source stays silent exactly as if its detector
had never fired. Provenance, the re-verification procedure, the build-time name check and the
list of tables still unverified are in
[CHAT-PATTERNS.md §12](CHAT-PATTERNS.md#12-where-the-drop-tables-come-from-added-2026-08-31);
the sprite-collision rule is §5.1 of the same file. Do not edit these lists from memory.

### Two doors into the chat feed, one path through

Both `DianaController.init()` and `ChatHooks.register()` register an `ALLOW_GAME` listener,
and both funnel into `DianaController.handleLine`. That redundancy is deliberate.
`SkyPrismClient` starts each subsystem inside its own try/catch, so the controller's
listener may never have been registered — a `LinkageError` from an adapter built against two
Minecraft versions is the realistic case. So the chat module's copy is retired **only if the
controller step actually reported success**:

```java
if (started.contains(DIANA_CONTROLLER)) {
    ChatRouter.setDianaFeedEnabled(false);
} else {
    LOGGER.warn("SkyPrism is keeping the chat module's Diana feed: ...");
}
```

A line arriving through both doors inside 50 ms is processed once
(`DUPLICATE_WINDOW_MILLIS`), so a drop can never be offered to the reels or counted in the
stats twice.

Note the asymmetry: the registered listeners check the gate, the public
`onChatMessage(String, long)` does not. The listener's check is what buys the zero-cost
property; the public method is the injection point for `/skyprism simulate`, `/skyprism
replay` and tests, where insisting on a live Hypixel connection would make the feature
untestable.

### Chat suppression

With `diana.suppressDropChatLines` on, a drop line the machine captured is hidden from chat
so the reels are the announcement rather than a duplicate of it. The decision is *observed*,
not assumed: `ChatRouter.pump` brackets the feed with `capturedDropCount()` before and after
and only suppresses when the count actually rose. `DianaController.onDrops` adds a second
cap — suppression stops once as many drops have been captured as there are reels, because
`reelsAt` only ever renders `reelCount` symbols and a fourth drop inside a three-reel window
would otherwise be hidden from chat and shown nowhere.

---

## 5. The DianaGate

`DianaGate` is a four-input AND, in the core, with no Minecraft in sight:

```
   onHypixel   AND   inSkyBlock   AND   mayorDiana   AND   areaAllowed()
       |                 |                  |                  |
  server address    sidebar title      TAB election row   sidebar area line
  (connection edge)  (2 s poll)         (15 s poll)        (2 s poll)
                                                                |
                                                     empty whitelist == any area
```

`HypixelContext` supplies all four and is throttled hard, because none of these facts can
change quickly: a server address changes on reconnect, an island on a warp, and a mayor once
every five real days. Between polls, `poll()` returns after comparing two longs. The
expensive read — walking eighty TAB rows for the mayor — happens at most every 15 s, and
only when the sidebar has already confirmed SkyBlock and something still wants the answer.

### What it gates, and why "closed" costs nothing

The gate is read as the first statement of every Diana handler:

| Handler | First line |
| --- | --- |
| `DianaController.allowGameMessage` | `if (!gate.isOpen()) return true;` |
| `ChatRouter.allowGameMessage` | `if (!target.isOpen()) return true;` |
| `ClientEntityEvents.ENTITY_LOAD` | `if (!gate.isOpen()) return;` |
| `ClientEntityEvents.ENTITY_UNLOAD` | `if (!gate.isOpen() \|\| !tracker.bound()) return;` |
| `DianaController.onEndTick` | polls the context, then `if (!gate.isOpen()) return;` |

Nothing downstream of those reads allocates. With Diana out of office, the entire second
feature is one boolean test per event — and the events it is attached to are ones Minecraft
was already firing.

### `consumeChanged()` and why it is an edge of *openness*

Fabric's `Event` has `register` and no `unregister`; a listener attached at mod-init is
attached for the process. So "unregister when the gate closes" cannot be honoured literally.
It is honoured in the only way the API allows: the falling edge actively tears state down —
`hardStop()` drops the bound entity, clears the pending spawn, resets the roll and flushes
stats — and both edges log at INFO with `gate.describe()`, because from the outside "Diana
is not the mayor" and "SkyPrism cannot read the mayor row" are the same symptom with
completely different fixes.

The edge is an edge of `isOpen()`, not of the individual inputs. Walking between two
whitelisted areas changes `setArea` but not `isOpen()`, and must not churn anything. Setting
a field to the value it already holds raises no edge either.

One polarity choice is load-bearing: **an empty allowed-area set means "any area"**. A
default, unconfigured gate that worked nowhere would be indistinguishable from a broken
feature. With a non-empty whitelist, an unknown (`null`) area is closed — the player asked to
be restricted, and "I do not know where I am" is not a match.

---

## 6. The multi-version story

SkyPrism targets **Minecraft 26.1.2 and 26.2** from one source tree, via
[Stonecutter](https://github.com/stonecutter-versioning/stonecutter) 0.9.7.

Stonecutter registers two *nodes* (`versions/26.1.2/` and `versions/26.2/`), each holding
nothing but a `gradle.properties`, a `run/` directory and a `build/`. The actual source is
the single shared tree at the repository root, which Stonecutter expands in place for
whichever node is active. `stonecutter.gradle.kts` pins `stonecutter active "26.2"`, and
`org.gradle.parallel` is forced to `false` in `gradle.properties` precisely because the two
nodes share one physical source tree and must never rewrite it concurrently.

### Why there are currently zero conditionals

Verified by grep over `src/main/java`, `src/test/java` and `src/mcTest/java`: not one
`//?` / `//$` Stonecutter comment exists. Both Minecraft versions compile from
byte-identical Java source. Three things make that possible:

1. **26.1+ ships unobfuscated.** Yarn is retired, there is no `mappings()` line, mod
   dependencies use plain `implementation`/`compileOnly` rather than `modImplementation`, and
   the output task is `jar` (there is no `remapJar`). There is no mapping layer to differ.
2. **The per-node differences are dependency coordinates, not code.** Fabric API, YACL and
   ModMenu publish one artifact per Minecraft version, so their versions live in
   `versions/<mc>/gradle.properties` and the shared `build.gradle.kts` reads them. A missing
   `fabric_api_version` fails the build loudly; a missing YACL or ModMenu version simply
   compiles the optional settings screen out of that node's dev runtime.
3. **Every injection point was chosen for being identical on both nodes.** That was a design
   constraint on the mixins, not a happy accident — see §7.

### What to do if a future version forces a difference

In order of preference:

1. **Move the injection point rather than branch it.** This is exactly what
   `EntityRendererNameTagMixin` already does. The nametag component is read in
   `extractRenderState` on 26.1.2 and in the new `extractNameTags(T, S, float)` on 26.2 —
   two different call sites making the *same call*. Injecting into the callee,
   `EntityRenderer.getNameTag(Entity)`, whose erased descriptor is identical on both,
   removes the difference instead of encoding it.
2. **Push the difference into an adapter in `com.skyprism.mc.**`.** The core must stay free
   of it — a conditional in `com.skyprism.core` would mean the core suite no longer
   tests the same code on both nodes, which is the one guarantee the split exists to give.
3. **Only then, a Stonecutter conditional**, kept as small as an expression and never
   spanning a method body. Whoever adds the first one should also add a note here saying why
   options 1 and 2 were unavailable, so the next person does not treat it as precedent.

`mcTestAllVersions` is the command that would catch a silent per-node divergence:
`LegacyText`'s legacy-colour table, `ComponentRewriter`, `LevelNameMemo` and the
`ChatRouter` markers are covered only in `src/mcTest`, and `check` deliberately does not
depend on it.

---

## 7. The four mixins

They exist for one reason: **Fabric publishes no event for the TAB player list and none for
above-head nametags.** Two of the three surfaces feature 1 targets cannot be reached any
other way. Chat, which *does* have an event, is deliberately not mixed into — it goes
through `ClientReceiveMessageEvents.MODIFY_GAME`.

| Mixin | Target | Injection | Why a mixin |
| --- | --- | --- | --- |
| `PlayerTabOverlayMixin` | `PlayerTabOverlay` | `@Inject(at = RETURN, cancellable = true)` on `getNameForDisplay(PlayerInfo)Component` | No Fabric event for TAB entries. |
| `EntityRendererNameTagMixin` | `EntityRenderer` | `@Inject(at = RETURN, cancellable = true)` on `getNameTag(Entity)Component` | No Fabric event for nametags. |
| `PlayerInfoMixin` | `PlayerInfo` | none — adds a `@Unique` field and implements `LevelNameMemoHolder` | You cannot add a field to a foreign class any other way. |
| `AbstractClientPlayerMixin` | `AbstractClientPlayer` | none — same | Same. |

All four are registered in the `client` array of `skyprism.mixins.json`, with
`compatibilityLevel: JAVA_25` and `injectors.defaultRequire: 1`; both behaviour mixins also
carry `require = 1` explicitly, so a target method that stops existing fails loudly when the
mixin is applied at class load, rather than silently doing nothing.

Four design decisions inside them are worth preserving:

**`getNameForDisplay`, not `getTabListDisplayName`.** The latter is the more obvious hook and
the wrong one: it is the raw accessor for a stored field, read by anything that wants a
player's list name, so rewriting it would leak the recolour into unrelated callers and make
the `applyToTabList` toggle a lie. `getNameForDisplay` is the overlay's own method, called
for exactly the entries being drawn.

**`RETURN`, not `HEAD`.** Vanilla's `getNameForDisplay` does more than fetch a name — it
copies the component and italicises spectators. Cancelling at `HEAD` would silently drop
that. Injecting at `RETURN` lets vanilla decorate first and recolours the finished article.

**The two field-only mixins double as the entity filter.** The `instanceof
LevelNameMemoHolder` that `EntityRendererNameTagMixin` performs to fetch a cache cell is
also what narrows a per-entity render hook to players. A named mob or an armour-stand
hologram — and Hypixel builds a great many of both — fails that one type check and is never
scanned. That is what keeps a hook on `EntityRenderer` from becoming a world sweep. The TAB
mixin uses `instanceof` for a different reason: if `PlayerInfoMixin` somehow failed to apply,
a raw cast would throw a `ClassCastException` on every entry of every frame, outside the
reach of `LevelSurfaces`' failure budget.

**The classes stay thin on purpose.** The fast compile check
(`corecheck.sh` / `mccheck.sh`) runs without a Mixin annotation processor, so logic left
inside a mixin is logic nothing checks until the Gradle build. Anything past "check a flag,
fetch a cache cell, set a return value" belongs in `com.skyprism.mc.surfaces`.

One residual risk, stated plainly: a base-class injection cannot catch a subclass that
overrides `getNameTag` without calling `super`. The `package-info.java` records that scanning
all 286 renderer classes in both jars with `javap` turned up exactly one override,
`ItemFrameRenderer`, which names a map or a framed item and never a player. That scan is a
point-in-time result and would need redoing on a version bump.

---

## 8. Cross-cutting conventions

- **Section signs are written as `§` escapes** in `TextClean`, `DianaPatterns` and
  `LootParser`, so the file's encoding can never change what is being matched. (`LegacyText`
  is the exception: it writes a literal `§` and asserts in a static initialiser that
  `ChatFormatting.PREFIX_CODE` still agrees.) `JavaCompile` is pinned to UTF-8 for the same
  reason.
- **All four Diana patterns use `Matcher.matches()`, never `find()`.** Hypixel's lines arrive
  as a whole message; any player can put the same text *inside* a party message. Anchoring is
  the entire defence against a stranger making your HUD spin on demand.
- **Everything user-supplied is clamped, not trusted.** Counter parsing saturates rather than
  throwing; `SlotRollConfig` validates in its compact constructor; `SkyPrismConfig.sanitized()`
  clamps every numeric field before a palette is built from it.
- **Two scratch `int[2]` arrays** — `LevelSurfaces.SCAN` and `ChatRouter.SCAN` — carry the
  rewriter's tag count and highest level back to the caller without allocating. They assume
  the client thread, which is where both callers run.
- **Config changes propagate by generation number, not by invalidation.** `ConfigManager`
  bumps `generation` on every `adopt`, every cache keys on it, and stale entries are simply
  never matched again. Nothing has to walk a cache.
- **Optional dependencies are quarantined by class, not by try/catch.** A missing optional
  library fails at verification, not at a call, so every `dev.isxander` import in the mod
  lives in the single package-private `SkyPrismConfigScreen`, reached only through the
  `Screen`-typed `ConfigGui` facade. `com.skyprism.mc.selftest` uses the same trick against
  the `-Dskyprism.selftest` property: the call sits *inside* the `if`, so the package is
  never loaded for an ordinary player.

---

## 9. Where to look next

- [CHAT-PATTERNS.md](CHAT-PATTERNS.md) — every Hypixel string the mod depends on, quoted
  exactly, with the file and line to fix when one of them changes. This is the mod's most
  fragile surface.
- `README.md` — build and run commands, and the Stonecutter node switching tasks.
- `src/main/java/com/skyprism/mixin/package-info.java` — the house rules for adding a fifth
  mixin.
