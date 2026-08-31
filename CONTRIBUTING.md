# Contributing to SkyPrism

This file is about working on the code. [README.md](README.md) is about using the mod;
it also has a short "Building from source" section, and this document picks up where
that stops.

---

## What you need

**JDK 25, and nothing else pinned to your machine.** Three separate things want it:

| Thing | Where it is declared |
| --- | --- |
| The Gradle daemon's JVM | `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=25`) |
| The Java toolchain used to compile | `java { toolchain { ... } }` in `build.gradle.kts` |
| The mixins and the mod metadata | `compatibilityLevel: JAVA_25` in `skyprism.mixins.json`, `"java": ">=25"` in `fabric.mod.json` |

The Gradle *launcher* can run on an older JVM — the daemon criteria above are what
decides which JVM the build actually happens on. If Gradle cannot find a JDK 25 it
provisions one through the foojay resolver applied in `settings.gradle.kts`.

`org.gradle.java.home` must not go into the tracked `gradle.properties`. An absolute
path to one machine's JDK makes the build unrunnable everywhere else, CI included. If a
machine needs to force a particular JVM, do it outside the repository: `JAVA_HOME`, or
`org.gradle.java.home` in Gradle's user home, or `-D` on the command line.

Gradle 9.5.1 comes from the wrapper. Don't install one.

---

## How the repository is laid out

There is **one** source tree, at the repository root, and it is shared by every
Minecraft version:

```
src/main/java/com/skyprism/
    core/     Minecraft-free. config, diana, level, text, util
    mc/       the Minecraft adapters. chat, command, config, diana, gui, hud,
              selftest, surfaces, text
    mixin/    four client mixins
src/test/java/       the bare-JVM unit suite  (core only, today)
src/mcTest/java/     the Minecraft-aware suite (com.skyprism.mc.** only, today)
versions/26.1.2/     gradle.properties, run/, build/  — no source
versions/26.2/       likewise
```

`versions/<mc>/` holds a node's own dependency versions and its build output, nothing
more. Stonecutter points every node at the root `src/`, which is why `build.gradle.kts`
names the `mcTest` source directories against `rootProject` rather than `$projectDir` —
`versions/26.2/src/mcTest/java` does not exist and never will.

Per-node settings live in `versions/<mc>/gradle.properties` (`fabric_api_version`,
`yacl_version`, `modmenu_version`); everything shared lives in the root
`gradle.properties` (Loom, Fabric Loader, JUnit, mod id and version). A missing
`fabric_api_version` fails the build with an explicit message. A missing `yacl_version`
or `modmenu_version` does not — the mod is designed to run without either library, so
forgetting one produces a jar that builds, ships, and quietly has no settings screen.

---

## The everyday loop

```sh
gradlew test            # the fast suite, on every node
gradlew :26.2:test      # the fast suite, once
```

`gradlew test` needs no Minecraft, no game boot and no Loom runtime. That is the point
of it, and it is why `check` deliberately does **not** depend on `mcTest`.

Before you call a change done:

```sh
gradlew buildAllVersions      # both jars; `build` depends on `check`, so this
                              # also runs the fast suite on both nodes
gradlew mcTestAllVersions     # the Minecraft-aware suite on both nodes
```

`mcTestAllVersions` is the one people forget. It is the only automated coverage of the
classes whose behaviour is cross-version sensitive — `LegacyText`'s legacy-colour
table, `ComponentRewriter`, `LevelNameMemo`, the `ChatRouter` markers — and because
`mcTest` is not part of `check`, a full `clean buildAllVersions` can be green while none
of it has run.

Anything that needs a *booted game* rather than Minecraft classes belongs in the
in-client self test, in `com.skyprism.mc.selftest`. It is gated behind the
`skyprism.selftest` system property and writes its captures and a
`selftest-summary.json` next to them; `skyprism.selftest.out` overrides the directory.
README.md has the invocation.

---

## `core` is Minecraft-free, and what actually enforces that

`com.skyprism.core.**` must not import `net.minecraft` or `net.fabricmc`. It currently
does not — the whole feature logic (palettes, Oklab, the Diana patterns, the loot
parser, the roll state machine, the config codec) is plain Java over plain strings, and
that is what lets 665 unit tests run in about a second without a game.

Be clear about how strongly this is enforced, because it is easy to over-trust:

- **The unit-test classpath has Minecraft subtracted from it.** `sourceSets.test` is
  assigned the module's real runtime classpath *minus* `net.minecraft`, `com.mojang`,
  `org.lwjgl`, `io.netty`, the merged jar, sponge-mixin and MixinExtras. Everything else
  — fabric-loader, fabric-api, slf4j, gson, guava — stays, so a class that merely
  implements a loader interface or logs can still be unit tested.
- **A tripwire in `tasks.test` fails the build if Minecraft ever comes back.** It scans
  the `Test` task's own `classpath` property, not a configuration-time snapshot, so a
  Loom upgrade or an IDE plugin that reassigned the classpath is caught rather than
  silently turning the fast suite into the slow one.
- **There is no static check that a `core` class does not import `net.minecraft`.**
  `sourceSets.test.compileClasspath` includes `sourceSets.main.output` — compiled
  classes, not sources — so a `core` class with a Minecraft import compiles fine and
  only fails with `NoClassDefFoundError` when a test loads it. In practice that is
  enough, because the core is well covered, but a core class no test touches would slip
  through. If you add one, add tests for it.

`gradlew printTestClasspath` prints the classpath directly if you want to see it.

The mirror rule holds for `mcTest`: that source set keeps the whole Minecraft classpath
and has its own tripwire asserting Minecraft is *present*, so a Loom change that stopped
supplying it fails with one clear message instead of a pile of `NoClassDefFoundError`s
inside individual tests.

Where the line falls in practice: if you can express something over strings, integers
and `java.time`, it goes in `core` and gets unit tests. If it needs a `Component`, a
`PlayerInfo`, a `Minecraft` instance or a Fabric event, it goes in `mc` and gets an
`mcTest` — or a self-test step, if it needs pixels.

---

## Switching Minecraft nodes

Stonecutter expands one node's sources into `src/` at a time. The switch tasks have
spaces in their names, so quote them:

```sh
gradlew "Set active project to 26.1.2"
gradlew "Set active project to 26.2"
gradlew "Reset active project"       # back to the committed state (26.2)
```

`vcsVersion = "26.2"` in `settings.gradle.kts` is what "the committed state" means.
**Run `"Reset active project"` before you commit.** Committing while 26.1.2 is expanded
commits that node's expansion of the tree.

`gradlew stonecutterIdea` generates IntelliJ run configurations for these.

The aggregate tasks (`buildAllVersions`, `testAllVersions`, `mcTestAllVersions`, defined
in `stonecutter.gradle.kts`) move between nodes themselves. A direct `gradlew :26.1.2:test`
compiles whatever is currently expanded in `src/`. Today that is harmless — see the next
section: there are no conditionals, so both expansions are byte-identical — but it stops
being harmless the moment that changes.

`org.gradle.parallel=false` in `gradle.properties` is load-bearing and not a
conservative guess: the nodes genuinely share one physical source tree that Stonecutter
rewrites in place, so configuring or executing two of them at once would let two nodes
read and rewrite the same files. Do not turn it back on.

---

## Zero Stonecutter conditionals

There are currently **no** Stonecutter conditionals anywhere in the tree. Both Minecraft
versions compile from byte-identical Java source and all four mixins
(`PlayerTabOverlayMixin`, `PlayerInfoMixin`, `AbstractClientPlayerMixin`,
`EntityRendererNameTagMixin`) apply unchanged on both. This is a design goal, not luck:
where 26.1.2 and 26.2 diverge (`ChatFormatting`'s accessors, `Font.drawInBatch`,
`Minecraft.setScreen`, `PlayerTeam.getColor`) the mod uses the members both jars agree
on.

Try hard to keep it. A conditional costs more than it looks like it does: the two
expansions stop being identical, so every "does this compile" question becomes two
questions, `:26.1.2:test` starts depending on which node is expanded, and diffs get
harder to read.

If a divergence really is unavoidable, the syntax is:

```java
//? if <26.2 {
/*doTheOldThing();
*///?} else
doTheNewThing();
```

Minecraft 26.1+ ships unobfuscated, so **verify an API against the jar rather than from
memory** before you write against it:

```sh
javap -cp <merged-minecraft-jar> net.minecraft.network.chat.Component
```

The merged jars sit under `.gradle/loom-cache/minecraftMaven/` once a node has been
built. There is no `mappings()` line, mod dependencies use plain `implementation` /
`compileOnly` rather than `modImplementation`, and `jar` (not `remapJar`) produces the
output.

---

## Adding or changing a Hypixel pattern

This is the part of the codebase most likely to need touching, and the part where a
mistake is silent rather than loud. Read this section before you edit a regex.

### Where the patterns live

| What | File |
| --- | --- |
| The four server line shapes that drive Diana | `core/diana/DianaPatterns.java` — `SPAWN`, `BURROW_DUG`, `TREASURE_DUG`, `INQUISITOR_SHARE` |
| Decomposing a reward into a `LootDrop` | `core/diana/LootParser.java` — `TREASURE_ITEM`, `TREASURE_COINS`, `BANNER_DROP`, `LEADING_COUNT` (all private) |
| Creature names, rare flag, colour code, command aliases | `core/diana/MythologicalCreature.java` |
| The `[451]` level tag itself | `core/level/LevelTagLocator.java` — `TAG` |
| Scraping the sidebar and TAB for server, island and mayor | `mc/diana/HypixelContext.java` — this one is Minecraft-side, and feeds `core/diana/DianaGate` |

### The rules that are not negotiable

**Write every section sign in a pattern as a `\u00A7` escape**, never as a literal `§`.
The platform default encoding on a Windows box is not UTF-8, and a literal would let the
file's encoding decide what is being matched. (`JavaCompile` does set `-encoding UTF-8`
for exactly this reason, but the escape costs nothing and does not depend on that staying
true.) `TextClean` follows the same rule.

**Match with `Matcher.matches()`, never `find()`.** Hypixel's lines arrive as a whole
chat message; any player can put the same text *inside* a party message. Anchoring is
the only thing stopping someone making another player's HUD spin on demand. This applies
to new patterns too.

**Copy from SkyHanni rather than improving.** The Diana patterns are reproduced character
for character from SkyHanni's `GriffinBurrowHelper`, and the creature constants from its
`constants/events/Diana.json`. Those are maintained against the live server by a large
user base; an obvious tidy-up here is far more likely to be a regression than a fix. If
Hypixel changes a message, re-copy. Note that `wiki.hypixel.net` shut down in July 2026 —
use `hypixelskyblock.minecraft.wiki` for mob and drop facts. Record the date you
re-checked a source in the javadoc, as the existing patterns do.

### The step everyone forgets

`mc/chat/DianaLineFilter.MARKERS` is a list of four literal substrings
(`"dug out"`, `"DROP!"`, `"burrow chain"`, `"has spawned near"`). A chat line containing
none of them is rejected **before** any pattern sees it. That filter is a real
optimisation — `ALLOW_GAME` fires for every system message Hypixel sends, and four
`indexOf` calls are far cheaper than a component walk plus seven anchored regexes — but
it inverts the usual safety of a filter: a new pattern whose text shares none of those
substrings is not slow, or wrong, or loud. It simply never runs. Nothing throws and
nothing logs.

So, when you add a pattern:

1. Add it to `DianaPatterns` (or `LootParser`).
2. Check whether an existing marker already covers a line it matches. If not, add one to
   `DianaLineFilter.MARKERS`, as plain uninterrupted text — a marker with a colour code
   spliced through the middle of it is found in neither the raw form nor the plain one.
   `SHORTEST_MARKER` is computed rather than written down, so a short marker is safe to
   add.
3. Add a matching sample line to `DianaMarkerContractMcTest.SAMPLES`, keyed by field
   name, and a unit test for the pattern itself in `src/test`.

Step 3 is partly enforced. `DianaMarkerContractMcTest` discovers `DianaPatterns`' public
static `Pattern` fields by reflection, so a new one fails the test on the day it is added
unless you record a sample. It then asserts the sample really matches its own pattern —
so you cannot "fix" the failure by inventing a line containing "dug out" — and that the
sample survives the filter in both its raw section-coded and its plain form.

Two limits on that guard, both worth knowing:

- **`LootParser`'s patterns are private, so reflection does not see them.** Add a shape
  there and nothing checks it against the filter. Do that by hand.
- **The test lives in `mcTest`**, so it runs under `mcTestAllVersions` or
  `:<mc>:mcTest` — not under `gradlew test`, and not under `build`.

### Testing a change to a pattern

Nothing in this repository can talk to Hypixel, and the mod has never been run against
the live server (README.md's "Limitations" section is explicit about that). Three things
substitute:

- **`gradlew test`** against fixture strings — the cheapest, and where most pattern
  coverage belongs.
- **`/skyprism replay <file>`** in a dev client, which pushes raw chat lines from a text
  file through the mod's real handlers. Section signs may be written literally, as `&a`
  or as `§a`; `#` starts a comment and `#wait 1500` inserts a pause.
- **`/skyprism simulate <creature> [drops…]`** to drive a kill and a roll through the
  real Diana controller.

`HypixelContext` will not believe a dev client is Hypixel, so the gate stays closed. Two
escape hatches exist: the `skyprism.forceHypixel` system property, read once at class
load, and `HypixelContext.setHypixelOverride(Boolean)`.

---

## Before you push

- `gradlew buildAllVersions` and `gradlew mcTestAllVersions` are both green.
- `gradlew "Reset active project"` has been run, so the tree is back on 26.2.
- No new Stonecutter conditional, or a note in the pull request explaining why one was
  unavoidable.
- No `net.minecraft` or `net.fabricmc` import under `com.skyprism.core`.
- No absolute paths from your machine in any tracked file.
- New behaviour has a test in the source set that matches what it needs: `src/test` for
  anything expressible without Minecraft, `src/mcTest` for anything that needs Minecraft
  classes, the self test for anything that needs a booted game.

CI (`.github/workflows/build.yml`) runs the same two Gradle invocations across both nodes
and uploads each version's jar. It does not publish anywhere and needs no secrets. It has
not yet run on GitHub Actions, so treat its first run as the thing that verifies it.

---

## Licence

MIT — see [LICENSE](LICENSE). Contributions are accepted under the same licence.
