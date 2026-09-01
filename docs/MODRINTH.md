# The Modrinth listing

Everything needed to create the Modrinth project, ready to paste. Nothing here is read by the build
— this file exists so the listing is written once, reviewed, and kept in the repo next to the thing
it describes, instead of being retyped into a web form.

Three things about it are deliberate:

- **Every image URL is absolute** (`raw.githubusercontent.com/.../main/docs/images/...`). Modrinth
  renders the description on its own domain, so the repo-relative paths the README uses would all
  render as broken images.
- **Every link is absolute too.** The README's five doc links and its `../../releases` link are
  GitHub-relative and would 404 from Modrinth.
- **The two jars are not interchangeable**, and the description says so at the point where it names
  them, because the most likely support question this listing will get is somebody on 26.2 running
  the 26.1.2 jar.

If the palette screenshot is re-shot after the 1.0.3 default change, no URL here changes — the file
name is the same, and `raw.githubusercontent.com` serves whatever is on `main`.

---

## Field: Summary

Modrinth's summary field caps at 256 characters. The current `fabric.mod.json` description — "A
Fabric client mod for Hypixel SkyBlock." — is 44 characters and names neither feature, so it is not
the thing to paste here.

```
Your SkyBlock level tag changes colour every 20 levels instead of every 40, in Hypixel's own palette, with 13 new shades above 480. And every lucky drop in the game — 64 sources — spins a slot machine that lands on the loot you actually got.
```

## Field: Project settings

| Field | Value |
|---|---|
| Name | `SkyPrism` |
| Slug | `skyprism` |
| Project type | Mod |
| Client side | **Required** |
| Server side | **Unsupported** — the mod reads what the server already sent and draws it differently. It never sends anything to Hypixel and no part of it exists on a server. |
| Licence | MIT |
| Source code | `https://github.com/evansuckedatlife/SkyPrism` |
| Issue tracker | `https://github.com/evansuckedatlife/SkyPrism/issues` |
| Wiki / docs | `https://github.com/evansuckedatlife/SkyPrism/tree/main/docs` |
| Categories | Utility, Decoration, Social |
| Loaders | Fabric |
| Game versions | 26.1.2 and 26.2 — one jar each, see below |

## Dependencies

| Mod | Modrinth slug | Type | Why |
|---|---|---|---|
| Fabric API | `fabric-api` | **Required** | Declared in `fabric.mod.json` as `"fabric-api": "*"`; the mod does not load without it. |
| YACL | `yacl` | Optional | The settings screen. Without it the mod loads and runs normally, with no GUI — every `dev.isxander` import is quarantined in one package-private class so a missing library cannot cause a linkage failure. |
| Mod Menu | `modmenu` | Optional | How the settings screen is opened. There is no `/skyprism gui` and no settings keybind, so without Mod Menu the config is a JSON file plus `/skyprism reload`. |

Fabric Loader ≥ 0.19.3 and Java 25 are also required. Neither is a Modrinth dependency; both belong
in the description, and both are in the Install section below.

## Versions to upload

Two jars per release. **They are not interchangeable** — each is compiled against its own Minecraft
version.

| File | Modrinth version number | Game version | Loader |
|---|---|---|---|
| `skyprism-1.0.3+26.1.2.jar` | `1.0.2+26.1.2` | 26.1.2 | Fabric |
| `skyprism-1.0.3+26.2.jar` | `1.0.2+26.2` | 26.2 | Fabric |

Both come out of `./gradlew buildAllVersions`, into `build/libs/<version>/`. Release channel:
**Release**. Changelog for each: the matching section of
[`CHANGELOG.md`](https://github.com/evansuckedatlife/SkyPrism/blob/main/CHANGELOG.md).

## Gallery

Upload these from `docs/images/`, in this order, with these captions. The first is the one to mark
**featured** — it is the only image that shows a feature nobody can get from the title.

| Image | Caption |
|---|---|
| `level-palette.png` | The whole level palette, from `/skyprism preview` |
| `jackpot-magic-find.png` | A jackpot, captioned with the Magic Find it rolled at |
| `slot-spinning.png` | The reels mid-spin |
| `source-slayer.png` | A slayer drop |
| `source-fishing.png` | A rare catch |
| `settings-levels.png` | The settings screen |

---

<!-- ============================================================================ -->
<!-- PASTE EVERYTHING BELOW THIS LINE INTO THE MODRINTH DESCRIPTION FIELD.        -->
<!-- ============================================================================ -->

A Fabric client mod for **Hypixel SkyBlock** that does two things: it gives the SkyBlock level
prefix a colour range worth reading, and it turns every lucky drop in the game into a slot machine.

Built for Minecraft **26.1.2** and **26.2**. Client-side only — it reads what the server already
sent you and draws it differently. It never sends anything to Hypixel.

## 1. Twice as many level colours below 480, and thirteen new ones above it

Hypixel gives your `[451]` level prefix one of **13** colours, changing once every 40 levels. So a
level 200 and a level 239 look identical. And 480 is Hypixel's *last* tier — level 480, level 537
and level 600 are all the same dark red, forever.

SkyPrism raises the resolution without throwing the scheme away.

**Below 480 the colour changes every 20 levels instead of every 40, and it changes to Hypixel's own
colours.** Every other band is a real tier colour on the exact level Hypixel switches at; the band
between two of them is the midpoint of that pair, mixed in a perceptual colour space so it doesn't
go muddy on the way across. The colours you already read stay where they are. There are twice as
many of them.

**Above 480 it changes every 10 levels**, in thirteen colours of SkyPrism's own — rose up through
violet to cyan, alternating vivid and pale so that even ten levels apart is obvious. That is the
stretch Hypixel paints one flat red, so nothing here is being overwritten, and it is where telling
two players apart is worth the most.

Applies in chat, in TAB, and optionally above heads.

![The level palette preview](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/level-palette.png)

That's `/skyprism preview`, showing the live palette. The underline marks where animated chroma
kicks in — it ships off, and starts at level 600 when you switch it on.

Want something else? The mode is a dropdown: **Gradient** gives every single level its own shade
along one of eleven ramps, **Vanilla** reproduces Hypixel's thirteen tiers untouched, and every band
and every stop is editable with a colour picker.

## 2. Lucky drops become a slot machine

When you get something worth getting, a slot machine spins in the corner and its reels land on the
items you **actually** got — read out of chat, not invented.

![The machine mid-spin](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/slot-spinning.png)

If one of them is genuinely rare, the machine doesn't stop there. Gold floods in *while the reels
are still turning*, they break loose and spin up again, and they land one at a time — all three on
the same item. Casino three-of-a-kind.

![A jackpot, with the Magic Find it rolled at](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/jackpot-magic-find.png)

The `✦ +240%` is the Magic Find that earned it. It only shows when Hypixel actually reports one.

**This fires across the whole game, not just one event.** 64 sources: slayer bosses, dungeon chests,
Crystal Hollows chests, rare sea creatures, tree gifts, Diana's mythological creatures, gemstones,
Garden pests, chocolate rabbits, dragons, Kuudra, trapper animals, and more.

![A slayer drop](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/source-slayer.png) ![A rare fish](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/source-fishing.png)

Each source knows how often it fires and behaves accordingly — an Inquisitor spins the machine every
time, ordinary fishing catches don't, or you'd never see anything else. All of it is per-source
configurable.

## Install

1. Minecraft **26.1.2** (or **26.2**) with **Fabric Loader 0.19.3+** and **Java 25**
2. [Fabric API](https://modrinth.com/mod/fabric-api)
3. The jar for **your** Minecraft version — `skyprism-1.0.3+26.1.2.jar` or `skyprism-1.0.3+26.2.jar`
   — into your `mods/` folder. **They are not interchangeable.**
4. *Optional but recommended:* [YACL](https://modrinth.com/mod/yacl) and
   [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen

Without YACL the mod still runs — you just get no settings GUI, and the config is a JSON file plus
`/skyprism reload`.

## Try it in 30 seconds

You don't need a Diana mayor, a dungeon run, or any luck at all:

```
/skyprism simulate inq                  a Minos Inquisitor kill, full roll
/skyprism simulate inq Chimera          ...that pays out, so you see the jackpot
/skyprism simulate slayer_boss          a slayer drop
/skyprism preview                       the level palette
/skyprism hud                           drag the machine wherever you want it
```

## Commands

| Command | What it does |
| --- | --- |
| `/skyprism` | Status: what's on, what's off, where the config lives |
| `/skyprism preview` | The level palette, browsable |
| `/skyprism hud` | Drag the machine into position |
| `/skyprism simulate <source> [drops…]` | Run any source's full pipeline offline |
| `/skyprism sources` | Every source, its policy, and whether its gate is open right now |
| `/skyprism stats` | Kills, rolls, jackpots, drop tallies |
| `/skyprism profile` | Performance counters, so "it's lightweight" is checkable |
| `/skyprism reload` | Re-read the config from disk |

`/skyprism sources` is the one to reach for if something *isn't* firing — it prints each source's
gate so you can see whether the mod thinks you're somewhere else.

## Settings

![The settings screen](https://raw.githubusercontent.com/evansuckedatlife/SkyPrism/main/docs/images/settings-levels.png)

Mod Menu → SkyPrism. Colours (bracket table / gradient / vanilla, presets, chroma, which surfaces),
per-source roll policies grouped by category, HUD position and scale, and sounds. Every option
carries its own tooltip, and
[the full reference is in the repo](https://github.com/evansuckedatlife/SkyPrism/blob/main/docs/CONFIG.md).

## Does it cost FPS?

No, and it's measured rather than asserted. When no roll is on screen the HUD's render method
returns on its first line without allocating. Sources you aren't near aren't just skipped — they
aren't registered at all, so a slayer detector costs literally nothing while you're fishing. TAB
recolouring is memoised per player. There are no threads, no polling loops, and no network calls
anywhere in the mod. `/skyprism profile` shows the real numbers.

## Known limits — please read this bit

**Only the Diana path has been confirmed against the live server.** The other 51 chat detectors were
built by reading two well-established open-source mods' pattern corpora, and their strings have
never been seen arriving from Hypixel by this project.

That matters because **a wrong pattern fails silently**. The gate opens, nothing matches, nothing
gets logged, and it looks exactly like a feature that doesn't work rather than one that's mis-tuned.

So if a source never spins for you:

1. Run `/skyprism sources` and check whether that source's gate is even open
2. If the gate is open but nothing fires, the pattern is likely wrong — **that's a genuinely useful
   bug report.** Copy the exact chat line and
   [open an issue](https://github.com/evansuckedatlife/SkyPrism/issues)
3. `/skyprism simulate <source>` confirms the machine and the HUD are fine independently of
   detection

Also honest:

- **Item art** for non-Diana loot uses vanilla lookalikes. Real Hypixel item art gets learned the
  first time the mod sees a drop in your inventory, then remembered permanently.
- **Six GUI-triggered sources** (Croesus, reward chests, the Experimentation Table, Ubik's game)
  have their screen-title hooks wired but their exact titles unconfirmed.
- **Magic Find doesn't apply to Catacombs chest loot** — that's Hypixel's design, not a bug here. A
  dungeon chest roll showing no `✦` figure is correct.
- Another player's drop being broadcast to you deliberately **won't** spin your machine. If
  ownership can't be established the line is declined, so a missed roll is possible; celebrating
  someone else's Chimera is not.

## Open source

MIT. [Source, docs and issue tracker on GitHub.](https://github.com/evansuckedatlife/SkyPrism)
One source tree builds both Minecraft versions with zero version-conditional code, and 4510 tests
run across the two of them.

Chat patterns were cross-referenced against [SkyHanni](https://github.com/hannibal002/SkyHanni) and
[Skyblocker](https://github.com/SkyblockerMod/Skyblocker), both open source. Not affiliated with
Hypixel or Mojang.
