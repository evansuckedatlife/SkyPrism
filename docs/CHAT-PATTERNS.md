# Hypixel strings SkyPrism depends on

This is the mod's most fragile surface. Everything on this page is a string a third party
controls and can change without telling anyone. When SkyPrism stops working, the cause is
almost always on this page.

The page has three jobs: quote every dependency exactly, say where it came from so it can be
re-verified, and — in [§10](#10-if-hypixel-changes-something) — turn "the feature stopped
working" into a file and a line number in under five minutes.

**Conventions used here.** `§` is U+00A7. In the source it is always written as the escape
`§` (in `DianaPatterns`, `LootParser` and `TextClean`) so the file's encoding can never
change what is being matched; the patterns below are shown with the literal character for
readability, and every one is quoted verbatim otherwise. All line numbers are as of
2026-08-29 and will drift; the constant names will not.

**One structural fact that explains most of the shapes below.** The Diana patterns are not
matched against Minecraft's `Component` tree. `LegacyText.toLegacy` flattens the component
into a `§`-coded string first, and it injects a `§r` in front of *every* run after the first
— deliberately, and even between two runs whose styles are identical, because the drop
banners need exactly that shape. So a component boundary Hypixel happens to place inside an
item name arrives at the parser as `Griffin§r§9Feather`. That is why several item groups
below admit a repeat of the item's *own* colour code by backreference.

---

## 1. The level tag

**What it is.** Hypixel prints the SkyBlock level in front of a player's name as bracketed
digits: `[451]`. It is the entirety of feature 1's input.

**Where it lives.** `core/level/LevelTagLocator.java:92`, the `TAG` constant.

```
(?<![\p{L}\p{N}]{1,2})\[(0|[1-9][0-9]{0,8})\](?![\p{L}\p{N}])
```

The rules it encodes, and what each rejects:

| Rule | Rejects |
| --- | --- |
| Literally `[`, ASCII digits, `]` — nothing else between the brackets | `[MVP+]`, `[VIP]`, `[Lv100]`, `[Healer]`, `[6/8]`, `[451x]`, `[ 451 ]`, `[-5]`, `[]` |
| No leading zeros (`0` alone is fine — level 0 is real and Hypixel colours it grey) | `[0451]` |
| At most 9 digits (`MAX_DIGITS`), the widest run that always fits an `int` | `[99999999999]` — fails to match rather than overflowing |
| Parsed value must fall inside the configured range, standard `0..1000` | `[4200]` |
| The code point either side must be absent or must not be a letter or digit | `x[451]y` |

Two subtleties in that last row. It is a **code point** rule, not a `char` rule: the `{1,2}`
on the lookbehind is load-bearing, because Java sizes a lookbehind in `char`s and a plain
`(?<![\p{L}\p{N}])` would land on the low surrogate of a supplementary-plane letter such as
U+1D400, which is category `Cs` and would silently pass. And a tag ending the string is
accepted — TAB entries and name-only renders legitimately end there.

The range is configurable: `SkyPrismConfig.LevelSettings.minLevel` / `maxLevel`
(`core/config/SkyPrismConfig.java:366` and `:369`), defaulting to `LevelTagLocator.STANDARD_MIN`
(0) and `STANDARD_MAX` (1000). The live cap today is far lower; the headroom costs nothing
and Hypixel raises it over time.

**The bias is deliberate.** A false positive repaints something that is not a level and is
immediately visible as a bug to every player in the lobby. A false negative just leaves the
tag in Hypixel's own colour, which is merely disappointing. Every rule above errs towards
rejecting.

### The emblem caveat

Hypixel awards **prefix emblems** — small diamond-like glyphs, one every ten levels. They
are the thing most likely to be recoloured by accident, and there are two reasons SkyPrism
cannot touch them:

1. **They render to the *right* of the player name**, not next to the tag. The line looks
   like `[451] [MVP+] Notch ❈:`, so an emblem is nowhere near the span being restyled.
2. **They are not bracketed digits**, so `LevelTagLocator` cannot produce a span for one.

There is no emblem-specific code anywhere in the mod, and **there must never be**. If an
emblem ever needs handling, that is a change to the locator's contract, not a special case
bolted onto `ComponentRewriter`. The invariant is asserted in two places: `RecolourProbe`
check 4 (`mc/selftest/RecolourProbe.java:222`), which runs inside a real client using
U+2748 SPARKLE (`❈`) in a colour deliberately unlike every other colour in the line, and
`ComponentRewriterMcTest` ("rejects a rank prefix and an emblem-only name").

Note also that Hypixel mixes **U+00A0 NO-BREAK SPACE** and other exotic whitespace into its
rank and emblem strings. `TextClean.clean` collapses whitespace with
`Pattern.UNICODE_CHARACTER_CLASS` for that reason (`core/util/TextClean.java:37`).

---

## 2. The four Diana chat lines

All four live in `core/diana/DianaPatterns.java`. Every one of them uses
`Matcher.matches()`, never `find()` — the three public helpers in that class enforce it.
That is not stylistic. Hypixel's own lines arrive as a whole chat message, whereas any
player can put the same text *inside* a party message:

```
§9Party §8> Steve§f: §rOh! You dug out a Minos Inquisitor!
```

Anchoring is the entire defence against a stranger making another player's HUD spin on
demand. **It must not be relaxed to `find()`.**

### 2.1 `SPAWN` — a creature crawling out of a burrow

`core/diana/DianaPatterns.java:67`

```
§c§l(?:Oh|Uh oh|Yikes|Oi|Good Grief|Danger|Woah)! §r§eYou dug out (?:an? )?(?:§[a-f0-9r])*(?:an? )?(?<creatureType>[\w\s]+)§r§e!
```

Live example: `§c§lYikes! §r§eYou dug out a §r§2Minotaur§r§e!`

- The exclamation is one of seven Hypixel picks at random, so it carries no information and
  stays a non-capturing alternation.
- `(?:§[a-f0-9r])*` eats the `§r§2` (ordinary) or `§r§c` (rare) colour run before the name.
- **The article is accepted on both sides of that colour run.** SkyHanni's original places it
  before the codes only. If Hypixel writes `You dug out §r§2a Minotaur` instead, the
  one-sided form still *matches* — the optional group matches nothing, `[\w\s]+` swallows
  "a Minotaur" — and then `MythologicalCreature.byDisplayName` finds no such creature. That
  failure would be total, silent and invisible to every synthetic fixture, so both positions
  are accepted and `byDisplayName` strips the article as well
  (`core/diana/MythologicalCreature.java`, `stripArticle`).
- `[\w\s]+` for the name is the part to understand before touching it. Neither `\w` nor `\s`
  matches a section sign, so the greedy run stops cleanly at the closing `§r§e!` while still
  swallowing the internal space of "Minos Inquisitor", "Gaia Construct", "Siamese Lynxes",
  "Stranded Nymph", "Cretan Bull", "Minos Champion", "Minos Hunter" and "King Minos".
  **Narrowing it to `\w+` would silently truncate eight of the twelve creatures to their
  first word.**

**Consumed by** `DianaPatterns.matchSpawn` → `DianaController.handleLine`
(`mc/diana/DianaController.java:336`). An unrecognised creature name degrades to "no roll",
never to a wrong roll.

### 2.2 `BURROW_DUG` — a burrow being dug

`core/diana/DianaPatterns.java:80`

```
§eYou (?<type>finished the Griffin burrow chain!|dug out a Griffin Burrow!) §r§7\((?<current>\d+)/(?<max>\d+)\)
```

Both halves of the alternation keep their trailing `!`, because the chain-finished form and
the ordinary form differ only in that phrase; the `(current/max)` counter is identical in
both. `\d+` is unbounded, so `matchBurrowDig` saturates to `Integer.MAX_VALUE` on a parse
failure rather than throwing out of a chat handler.

**Not currently consumed by any production code.** `DianaPatterns.matchBurrowDig` is called
only from `src/test`, `src/mcTest` and `SimulatedLoot`. The pattern is fully tested and the
`"burrow chain"` marker in `DianaLineFilter` still exists to feed it, but nothing in the
running mod acts on a burrow dig today. Stated here so nobody debugs a burrow-counter HUD
that was never wired up.

### 2.3 `TREASURE_DUG` — a treasure burrow paying out

`core/diana/DianaPatterns.java:92`

```
§6§l(?:RARE DROP!|Wow!) §r§eYou dug out(?: a)? §r§?.+§r§e!
```

The lone `§?` after `§r` is deliberate and is in SkyHanni verbatim: some payout lines colour
the reward and some do not, so the second section sign is optional.

This pattern answers only "is this line a treasure payout". **It is a guard, not a parser**:
`LootParser.parse` uses it at `core/diana/LootParser.java:182` to stop a treasure line that
could not be decomposed from falling through to the mob-drop branch, where it would have
produced an item helpfully named "You dug out a". A missed reel is a nuisance; a reel showing
a fragment of an English sentence is a bug report.

### 2.4 `INQUISITOR_SHARE` — the community waypoint broadcast

`core/diana/DianaPatterns.java:108`

```
(?<party>§9Party §8> )?(?<playerName>.+)§f: §rA MINOS INQUISITOR has spawned near \[(?<area>.*)] at Coords (?<x>[^ ]+) (?<y>[^ ]+) (?<z>[^ ]+)
```

**Unlike the other three, this line is player-authored.** The coordinates are whatever the
sending client typed. Anything built on this pattern must treat the numbers as untrusted and
range-check them. The `party` group is present exactly when the broadcast came through party
chat, which is the only cheap signal that the sender is someone you are actually playing
with; no helper is exposed for it in the core, because that is a decision for whatever
renders a waypoint.

**Consumed by** `DianaController.isSharedInquisitor` (`mc/diana/DianaController.java`), and
only when the player has turned **off** `diana.onlyMyBurrows`. With the default on, this
pattern never fires.

---

## 3. Drop announcements

All in `core/diana/LootParser.java` — **all of them, in that one file**. Until 2026-08-30 the
banner corpus was encoded four times over (see §3.10); it is encoded once now, and every other
caller is an adapter over it.

**Order is load-bearing**: coins, then treasure item, then the treasure guard, then — inside
`matchBanner` — bracketed, then sentence, then plain. A treasure line also satisfies the plain
shape, so trying the plain shape first would name the item "You dug out a"; a bracketed line
also satisfies it, as an item literally named `(`.

| # | Shape | Claimed by |
| --- | --- | --- |
| 3.1 | `§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!` | `TREASURE_ITEM` |
| 3.2 | `§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!` | `TREASURE_COINS` |
| 3.3 | `§6§lRARE DROP! §r§9Judgement Core §r§b(+168% ✯ Magic Find)` | `BANNER_DROP` |
| 3.4 | `§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) (+123% Magic Find)` | `BRACKETED_DROP` |
| 3.5 | `§9§lRARE DROP! §r§aYou dropped 48x Enchanted Melon Slice!` | `SENTENCE_DROP` |
| 3.6 | the `(+240% Magic Find!)` tail on any of 3.3–3.5 | `MAGIC_FIND` |

### 3.1 `TREASURE_ITEM` — an item from a treasure burrow

`core/diana/LootParser.java:71`

```
§6§lRARE DROP! §r§eYou dug out(?: an?)? §r(?:§(?<color>[0-9a-fk-orA-FK-OR]))?(?<item>[^§]++(?:§r§\k<color>[^§]++)*)§r§e!
```

Live example: `§6§lRARE DROP! §r§eYou dug out a §r§9Griffin Feather§r§e!`

The item group crosses a same-colour reset by backreference. With a plain `[^§]+` the match
would fail outright on a name Hypixel split across two component nodes, and `parse` would
then hit the treasure guard and return nothing — a real drop shown on the reel as "No Drop",
with nothing logged.

### 3.2 `TREASURE_COINS` — a coin payout

`core/diana/LootParser.java:87`

```
§6§lWow! §r§eYou dug out §r(?:§(?<color>[0-9a-fk-orA-FK-OR]))?(?<amount>\d[\d,]*) coins§r§e!
```

Live example: `§6§lWow! §r§eYou dug out §r§62,500 coins§r§e!`

Modelled as an item literally named `Coins` (`LootParser.COINS_ITEM_NAME`, line 150) whose
`count` is the amount, so a reel can show it like anything else. Hypixel writes thousands
separators, which is why the group admits commas — but it is `\d[\d,]*` and not `[\d,]+`,
because the looser form matched a line whose amount was a bare `,`, and `parseAmount` then
saturated and put a payout of 2,147,483,647 coins on the reel.

Coins are the one drop shape marked `rare = false`.

### 3.3 `BANNER_DROP` — the plain mob drop announcement

`core/diana/LootParser.java:BANNER_DROP`

```
[ ]*§.(?:[ ]|§.){0,32}(?<banner>VERY RARE DROP!|CRAZY RARE DROP!|INSANE DROP!|UNCOMMON DROP!|RARE DROP!|PET DROP!) {1,2}(?:§r)?(?:§(?<color>[0-9a-fk-orA-FK-OR])(?:§[k-oK-O])*)?(?<item>(?!You )(?!\()[^§\n\r]++(?:§r§\k<color>(?!\(\+)[^§\n\r]++)*+)(?:(?:§r)?§8x(?<count>\d[\d,]{0,12}))?(?<tail>[^\n\r]*)
```

Live example: `§6§lRARE DROP! §r§9Dwarf Turtle Shelmet §r§b(+§r§b168% §r§b* Magic Find§r§b)`
— the trailing bracket is absent when the player has no bonus, which is a different fact from
a bonus of zero. See §3.6.

**The six banner words are exactly:** `RARE DROP!`, `VERY RARE DROP!`, `CRAZY RARE DROP!`,
`INSANE DROP!`, `PET DROP!`, `UNCOMMON DROP!`. Hypixel uses all six for the same line shapes.
`UNCOMMON DROP!` is decomposed but is **not** flagged rare — `LootParser.isRareBanner` is the
one place that distinction lives.

Six things in this pattern are defences, not decoration:

- The leading run **requires at least one formatting code** (`§.` before the optional
  space/code mixture) and **cannot skip over ordinary text**, so a player quoting "RARE DROP!
  Crown of Greed" in party chat does not match — their name sits between the codes and the
  banner. Leading spaces are admitted *before* the first code, because Hypixel really does
  indent some lines, but never *instead* of it.
- ` {1,2}` — `VERY RARE DROP!` and `CRAZY RARE DROP!` are followed by **two** spaces.
- `(?!You )` is the real sentence guard. `isTreasureDig` only catches a treasure line that
  ends exactly on `§r§e!`; give the same line any tail at all and it fell through here as an
  item named "You dug out a". Crop Fever's "You dropped 48x …" was the same failure with a
  different verb, and it was live. No Hypixel item name begins with "You " ("Young Dragon
  Boots" is unaffected — the lookahead requires the space).
- `(?!\()` makes the bracketed shape's failure terminal. A bracketed line satisfies this
  pattern too, as an item literally named `(`, and `BRACKETED_DROP` only shields it while
  *that* pattern succeeds. Truncate the closing bracket, or malform the count run, and the
  reel used to lock onto an open bracket that no jackpot list can match and `DianaStats`
  records forever. Refusing it costs a reel, not a wrong one.
- `(?!\(\+)` on the name's continuation stops an item swallowing its own Magic Find bracket.
  The same-colour backreference was previously the only thing keeping the aqua tail out of the
  name, on the argument that no rarity is aqua. **DIVINE rarity is aqua**, so every DIVINE drop
  welded its stat onto its own item name.
- The item quantifier is possessive and the trailing group does not start with `\s*`. A lazy
  `[^§]+?` followed by `\s*` let the two split a run of spaces every possible way, which is
  quadratic: a 20,000-character line took 2.3 seconds and a 200,000-character one never
  finished — on the chat thread.

### 3.4 `BRACKETED_DROP` — anything that went straight to a sack

`core/diana/LootParser.java:BRACKETED_DROP`

```
<leading codes>(?<banner>…) {1,2}§r§7\((?:§r)?§f(?:§r§7(?<count>\d[\d,]{0,12})x §r§f)?(?:(?:§r)?(?:§(?<color>…)(?:§[k-oK-O])*)?)?(?<item>[^§\n\r]++)§r§7\)(?<tail>[^\n\r]*)
```

Live examples, all captured:

```
§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) (+123% ✯ Magic Find)
§b§lRARE DROP! §r§7(§r§f§r§72x §r§f§r§9Foul Flesh§r§7) (+123% ✯ Magic Find)
§5§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) (+123% ✯ Magic Find)
§9§lVERY RARE DROP!  §r§7(§r§fMana Steal I§r§7) (+123% ✯ Magic Find)
```

Every slayer drop, every combat drop that lands in a sack, runes, distillates, arrow poison.
Tried **before** the plain shape. Three details: the banner colour is `§b`/`§5`/`§9`/`§d` by
tier and content, not `§6`; the colour after the `§f` is optional, because the enchanted-book
form carries none and making it mandatory would drop precisely the VERY RARE tier; the count is
this shape's own `§r§72x §r§f` form, not the plain shape's trailing `§8x9`. The Magic Find tail
here carries **no colour code of its own**, which is why §3.6 anchors on the words.

### 3.5 `SENTENCE_DROP` — Garden Crop Fever

`core/diana/LootParser.java:SENTENCE_DROP`

```
<leading codes>(?<banner>…) {1,2}(?:§r)?(?:§(?<color>…)(?:§[k-oK-O])*)?You dropped (?<count>\d[\d,]{0,12})x (?<item>[^§!\n\r]++)!(?<tail>[^\n\r]*)
```

Captured, colour-stripped (SkyHanni `CropFeverTracker.kt`):

```
RARE DROP! You dropped 48x Enchanted Melon Slice!
UNCOMMON DROP! You dropped 24x Enchanted Melon Slice!
```

**This was a confirmed live corruption in both former parsers**: the item name came back as the
literal string "You dropped 48x Enchanted Melon Slice!" — an English sentence on a reel, matching
no jackpot entry ever, recorded in the stats file. It now decomposes to `Enchanted Melon Slice`
× 48.

The count is *inside* the sentence, which is why neither the leading-multiplier rule nor the
trailing dark-grey rule could ever have found it.

**The wording is verified; the colour codes are not** — SkyHanni matches this line
colour-stripped, so nothing in the pattern depends on them (the reset and the colour run are
both optional). The leading formatting run is still required, which deliberately means the
colour-stripped form is *not* claimed: admitting it would also admit a player typing "RARE DROP!
You dropped 64x Enchanted Diamond!" into chat.

`RareDropBanner.match` filters this shape out for the loot bus, on policy rather than by regex:
a fever window prints many of these in sixty seconds and rolling on each would strobe the
machine. The items still reach the reels through `LootParser.parse`.

### 3.6 `MAGIC_FIND` — the stat the drop was rolled at

`core/diana/LootParser.java:MAGIC_FIND`

```
\((?:§.){0,8}\+(?:§.){0,8}(?<value>\d[\d,]{0,12})(?<pct>%)?(?:§.|[^§()\p{L}\p{N}]){0,64}Magic Find(?:§.){0,8}!?(?:§.){0,8}\)
```

Applied to the `tail` group of an **already-matched banner** and never to a bare chat line.
Result lands on `LootDrop.magicFind()`, and the jackpot reveal draws it (`SlotMachineHud`,
§3.9).

Five forms, all accepted by this one pattern:

| Form | Example | Verified |
| --- | --- | --- |
| Split runs, with `%` | `§r§b(+§r§b168% §r§b<U+E01A> Magic Find§r§b)` | yes — SkyHanni `RareDropMessages.kt` |
| Split runs, **no** `%` | `§r§b(+§r§b168 §r§b✯ Magic Find§r§b)` | yes — pinned twice in SkyHanni, once per form |
| Flat, legacy glyph | `§r§b(+123% ✯ Magic Find)` | yes — this project's own registry samples |
| Bracketed/sack, unstyled | ` (+123% <U+E01A> Magic Find)` | yes — SkyHanni `ChatFilter.kt` |
| **No icon, trailing `!`** | `§r§b(+240% Magic Find!)` | medium — SkyHanni `DungeonChatFilter.kt:136` only |
| Absent entirely | `§6§lPET DROP! §r§6Rat` | yes |

**Where Magic Find actually applies, and where it does not.** Magic Find raises the chance of rare
drops from *mobs* — slayer bosses, Diana's mythological creatures, sea creatures, dragons and
ordinary mob rare drops. It does **not** affect Catacombs chest loot, which is decided by score,
floor and the run's own tables. So the reveal legitimately shows no figure on a dungeon chest roll:
that is Hypixel's behaviour, not a parse failure, and it must not be "fixed".

This corrects an earlier note in this file that called the no-icon variant a *dungeon* shape and
named a Catacombs run as the best way to confirm it. It was filed under `DungeonChatFilter` upstream,
but a Catacombs chest is the one place the suffix has least reason to appear at all. Confirm the
variant somewhere Magic Find is actually in force — a slayer boss is the cheapest — and treat any
Magic Find figure seen on a dungeon chest reward as a finding worth reporting rather than expected.

**Neither icon codepoint appears in the pattern.** Hypixel has already moved the glyph once —
U+272F historically, U+E01A today, and some lines carry none at all — so the only durable anchor
is the literal words "Magic Find". That is the same standing-liability argument
`TrophyFishDetector` and `PestDropDetector` already make, and it is why this variant needs no
special handling: the words carry it whether or not the icon or the exclamation mark are there.

**What it must never claim**, all of which sit in exactly the same position after exactly the
same banner:

```
§6§lPET DROP! §r§5Slug §6(§6+1300☘)             pet luck — gold, shamrock, no stat word
§6§lRARE DROP! §9Mutant Nether Wart §8x9 §e(§e+134)   farming fortune — yellow, bare number
§a§l+5 Kill Combo §r§8+§r§b3% §r§b<icon> Magic Find   a buff grant — no banner, no brackets
```

The last one is why the pattern is only ever offered a matched banner's residue: run free-standing
over chat it would fire on a kill combo and staple 3% onto whatever drop was on screen.

**Absent is not zero.** `LootDrop.magicFind()` is `null` when the server reported nothing and a
`MagicFind(0, …)` only when it really sent `(+0% …)`. `magicFindReported()` is the question to
ask. Showing "+0%" for an unreported stat asserts something the server never said, and a player
who knows their own Magic Find can see the lie.

**The `%` is echoed, never assumed.** Hypixel emits both `+208%` and `+208`; the sign is a
captured field and `MagicFind.format()` reproduces exactly what arrived.

### 3.7 `LEADING_COUNT` — a stacked reward

`core/diana/LootParser.java:LEADING_COUNT`

```
(?<count>\d{1,9})x (?<rest>.+)
```

Hypixel writes stacked rewards as "3x Enchanted Ancient Claw". This splits the multiplier off
into `LootDrop.count` so the reel can label it separately. A line can carry both this and a
trailing `§8xN`; the leading one wins and the two are **never** multiplied together, because
guessing that two independent counts compose is how a wrong number gets onto a reel.

### 3.8 What the parser deliberately does not do

**It does not check that a drop is a Diana drop.** The banner shapes are server-wide, so a
slayer's Judgement Core parses here too. Gating on island, mayor and recency is
`DianaController`'s job. Keeping it out is what lets the parser stay a pure function of one
string and lets the gate be tested on its own.

Note that `LootParser` marks `rare = true` for every item it parses except coins and
`UNCOMMON DROP!`. That flag is what `SlotRoll.jackpot()` reads, so on the live path the
jackpot flourish fires for any item drop. See §5.

**It does not decide ownership itself.** `isThirdPartyLine` is a one-line delegate to
`com.skyprism.core.loot.LineOwnership`, which is described in §3.11. It used to be a
four-needle substring list here and a five-needle one in `CombatChatGuards`; the two had
already drifted, and both refused the player's own loot.

**Shapes deliberately not claimed**, so the boundary is not mistaken for an oversight:

| Shape | Why not |
| --- | --- |
| `[Sacks]` auto-pickup | The item names live in component **hover** text, which `LegacyText.toLegacy` destroys. Needs a Component-level reader, not a regex. |
| Four-space indented reward blocks (essence, powder, gemstones) | `ContainerPatterns.BLOCK_ITEM` models them; nothing feeds them to the reels yet. |
| `FLOOR DROP!` / `CAPTURE!` / `FUSION!` / `CHARM!` / `LOOT SHARE!` attribute shards | Sentence payloads, and SkyHanni matches them colour-stripped — no captured formatting exists to pin. |
| `GOOD CATCH!` / `TROPHY FISH!` / `HOPPITY'S HUNT` | Each has a dedicated detector that captions better than a generic banner could. |
| `RARE REWARD!` (broadcast **and** the indented dungeon-chest self-form) | Never places the item after the banner; adding it to the alternation would capture a player's name as an item. |
| `RARE CROP!` / `VERY RARE CROP!` | One letter from `RARE DROP!`, owned by `RareCropDetector`, and its trailing bracket is farming fortune, not Magic Find. |

### 3.9 Reaching the reveal: what the HUD draws

`SlotRoll.jackpotSymbol()` is the drop all three columns converge on, so it is the only drop
whose figure may be captioned — a roll can capture several and more than one can carry a stat.
`SlotMachineHud.drawMagicFind` puts it in the caption strip's right-hand gutter, right-aligned,
aqua, at prize-name size, fading in over 320 ms behind the third match.

- **When the server reported nothing, nothing is drawn.** Not "+0%", not a placeholder.
- **The panel does not move either way.** The strip has always been the full width of the
  machine with a centred headline; the gutter is dead space on every roll the widget has ever
  drawn. Nothing about `height()`, `width()` or `bandTop` is conditional on the figure. The
  self test asserts the two footprints are equal (`14-jackpot-hold.png` versus
  `15-jackpot-hold-no-magic-find.png`).
- **The star is U+2605, not Hypixel's.** `assets/minecraft/font/include/default.json` was read
  out of both merged jars: neither U+272F nor U+E01A is in any provider's char list, so drawing
  the captured codepoint would put an empty box on the reveal for anyone without Hypixel's
  resource pack. U+2605 is in `minecraft:font/nonlatin_european.png` and that texture is
  byte-identical across 26.1.2 and 26.2. It lives in the lang key
  `skyprism.hud.jackpot.magic_find` = `★ %s`, so a translator whose font lacks it can swap in a
  word.
- **The words "Magic Find" are not on the strip.** Spelling the stat out needs roughly 100px
  against a 47px gutter. On a two-column machine even the star and percentage do not fit and the
  figure is dropped rather than drawn over the headline; the default is three columns.

### 3.10 One corpus, one implementation

Until 2026-08-30 the banner corpus was encoded in **four** places, and the divergence was
measured rather than theorised:

| File | What its copy did differently |
| --- | --- |
| `core/diana/LootParser` | the survivor |
| `core/loot/events/RareDropBanner` | leading run `(?:§.)*` not `+`, so a bare unformatted line matched here and not there; count group `[\d,]{1,13}`, so a comma-only count produced a stack of 2,147,483,647 |
| `core/loot/gathering/BannerLines` | accepted `UNCOMMON DROP!` when neither parser did, so a detector armed on a line nothing could decompose and the roll settled on "No Drop" |
| `core/loot/combat/MobRareDropDetector` | a fourth alternation, missing `UNCOMMON DROP!`, also with a `*` leading run |

All three copies are gone. `RareDropBanner` is now an adapter over `LootParser.matchBanner`
contributing one thing of its own — the policy that sentence drops do not *arm* a source.
`BannerLines` and `MobRareDropDetector` call `LootParser.looksLikeBanner` /
`bannerWordOf`, which share the vocabulary and the anchoring but deliberately **do not**
require a decomposition: a shape the parser cannot yet name should still spin the machine under
the source's own caption rather than be lost. A wrong item name is a bug; a rare drop that
produces nothing at all is the bug players actually report.

`BannerCorpusTest.Unification` pins all of it, including that a bare unformatted line is refused
by every caller and that the catch-all still fires on a truncated bracketed line the parser
refuses.

### 3.11 Ownership — whose drop is this, added 2026-08-30

Hypixel announces other people's loot in the same channel and often in the same sentence shape
as yours. A machine that spins for a party member's Chimera loses its credibility in one line,
so every banner path asks this before it asks anything else.

`com.skyprism.core.loot.LineOwnership` is the **one** implementation.
`LootParser.isThirdPartyLine` and `CombatChatGuards.announcesAnotherPlayer` are one-line
delegates, so the two layers cannot disagree.

**A line is somebody else's when, and only when:**

1. it contains a **colon** — every player-authored line in the game carries `name: ` and no
   loot banner Hypixel prints does. This runs *inside* the rule as well as in front of it in
   `CombatChatGuards.rejects`, because the name comparison in (3) is the one part a hostile
   party member can steer, by typing your own username into a forged banner; or
2. it carries a tell that names **no actor to compare against** — `They also received`,
   `from their sacrifice`, `for assisting `; or
3. it carries a **named third-person verb** — `has obtained`, `found a `, `in their ` — and the
   actor written in front of the *earliest* such verb, rank prefix stripped by
   `ContainerText.playerName`, is not the local player, compared **as a whole token,
   case-insensitively**.

Everything else is the player's own. `You found a …` is theirs by construction: the actor reads
`You`, the server is addressing this client, and no name lookup happens at all. That is the bug
this replaced — the old ` found a ` needle sat inside the player's own line and refused it.

Earliest-verb, whole-token and case-insensitive are each load-bearing:

| Attack | Answer |
| --- | --- |
| `Steve found a Leebys Sword in their Bedrock Chest` | earliest verb is `found a `, so the actor is `Steve`; a name inside the *item* never becomes the actor |
| `Leebys2 has obtained …`, `xLeebys has obtained …` | whole-token compare; a `contains` would hand `Not` every one of `Notch`'s drops |
| `leebys has obtained …` | accepted — Minecraft usernames are unique case-insensitively |
| `Party > Bob: RARE DROP! Leebys has obtained Chimera` | colon, refused before the name is read |
| `[MVP+] Leebys [GUILD] has obtained …` | `playerName` takes the last unbracketed token, so rank and guild tag both fall away |

**The name source.** `SkyPrismClient.localPlayerName()` → `Minecraft.getInstance().getUser()
.getName()`. `javap`-verified byte-identical on **26.1.2 and 26.2** (`Minecraft.getInstance()`,
`Minecraft.getUser()`, `User.getName()`), so no Stonecutter conditional. `Minecraft.user` is a
`private final` field assigned in the constructor from `GameConfig`, so the name exists before
the title screen and long before any chat line — **the unknown-name window is empty in
practice**, not merely short. It is installed once, from `LootMachine.registerDetectors`,
alongside the supplier the chest detectors already take by constructor.

**The deliberate asymmetry: an unknown name refuses.** If the supplier is missing, returns
null, returns blank or throws, every *named* third-person shape is refused exactly as before.
This is not an oversight and must not be "fixed": refusing a line you did own costs one spin in
a window that closes by itself, while accepting one you did not puts five party members' drops
on your screen. Second-person lines are exempt — `You found a …` needs no name to be certain
of, so it is accepted even before the client has one.

**Cost.** The common path is six `indexOf` calls on the raw line, no allocation, and the
supplier is **not read at all** — `LineOwnershipTest.Cost` asserts that by installing a
supplier that throws. Only a line already carrying a tell pays for the strip-and-compare.

**The fast-path list is derived, never written out.** `ALL_TELLS` is
`NAMED_VERBS ++ PRONOUN_TELLS` built at class-load. A needle present in the rule but missing
from the gate would never be looked for, so the line carrying it would be called the local
player's without anybody checking — the fail-*open* direction, and the same drift the four
copies in §3.10 died of. `LineOwnershipTest.OneRule.noNeedleEscapesTheFastPath` pins it.

**Known, and left alone on purpose:** `for assisting ` is second person and the shards really do
reach the player's inventory, so refusing it is a judgement about a share of somebody else's
kill rather than a fact about ownership. It costs nothing today — `COMBAT_SHARD` and
`SLAYER_MINIBOSS` both ship `NEVER` and neither detector consults this class — and if either
ever arms, the fix is to read the actor *after* the needle, not to drop it.

**Scope, measured rather than assumed.** Over every chat-shaped string literal in the tree, this
rule changes the answer only for `You found a …` shapes (now accepted) and the `RARE REWARD!`
family (now name-compared). It changes the verdict on **no** line carrying `DROP!`, which is the
only family `LootParser.matchBanner` and `MobRareDropDetector` can reach — so Diana's proven
path is untouched. The Crystal Hollows *chest* detectors never consulted this guard at all:
`PowderChestDetector` keys on `CHEST LOCKPICKED` and `StructureLootChestDetector` on
`LOOT CHEST COLLECTED`. If a Crystal Hollows chest is not spinning, look there, not here.

---

## 4. Creature names

`core/diana/MythologicalCreature.java:20–32`. The display name is what `SPAWN`'s
`creatureType` group is compared against (case-insensitively, after the article is stripped),
and what `CreatureTracker` looks for inside an entity's custom name.

| Display name | Rare | Colour code | Config aliases |
| --- | --- | --- | --- |
| `Gaia Construct` | no | `2` | gaia, construct |
| `Minotaur` | no | `2` | minotaur, taur |
| `Minos Champion` | no | `2` | champion, champ |
| `Siamese Lynxes` | no | `2` | siamese, lynx, lynxes |
| `Minos Hunter` | no | `2` | hunter |
| `Cretan Bull` | no | `2` | cretan, bull |
| `Harpy` | no | `2` | harpy |
| `Stranded Nymph` | no | `2` | stranded, nymph |
| `Sphinx` | **yes** | `c` | sphinx |
| `Minos Inquisitor` | **yes** | `c` | inquisitor, inq, inquis |
| `King Minos` | **yes** | `c` | king, minos |
| `Manticore` | **yes** | `c` | manticore, manti, core |

The colour code is the one the server paints the creature's name in: `2` (dark green) for
ordinary, `c` (red) for rare. The aliases are for config and `/skyprism simulate` only and
must stay unambiguous across the whole enum.

Default triggers (`MythologicalCreature.defaultTriggers()`): Minos Inquisitor, King Minos,
Manticore.

---

## 5. The jackpot item list

`core/diana/JackpotRule.java:67–85`, in rarity order (rarest first):

Mythological Dye, Myth the Fish, Minos Relic, Braided Griffin Feather, Daedalus Stick,
Crochet Tiger Plushie, Shimmering Wool, Manti-core, Washed-up Souvenir, Cretan Urn, Hilt of
Revelations, Brain Food, Antique Remedies, Dwarf Turtle Shelmet, Fateful Stinger, Chimera I,
Chimera, Crown of Greed.

The shipped user-editable set (`SkyPrismConfig.DianaSettings.jackpotItems`,
`core/config/SkyPrismConfig.java:673`) is a smaller starting point: Daedalus Stick, Crown of
Greed, Minos Relic, Dwarf Turtle Shelmet, Antique Remedies, Washed-up Souvenir.

**Be aware that neither list currently drives the HUD flourish.** `SlotRoll.jackpot()` is
`true` whenever any captured `LootDrop` has `rare() == true`, and `LootParser` sets that on
every non-coin drop. `JackpotRule` is used by `SimulatedLoot` and by tests; the config list
is read only by `SkyPrismCommands` for its printed output. Names in these lists are matched
through `LootParser.normalise` (`TextClean.clean` then lower-case), so spacing and colour
codes do not matter — but if a Hypixel item is *renamed*, the entry here goes stale silently
and it will not change any on-screen behaviour today.

### 5.1 The item-art table — what a reel draws before Hypixel's own art is learned

`assets/skyprism/drop_symbols.json` maps a drop name to the vanilla item whose sprite reads
closest at 16x16. It exists because the reels have three cells and a settled reel draws the
event's own drops: **two drops from one event landing on the same sprite is the bug**, because
three identical chests read as the machine having failed and they empty the three-of-a-kind
flourish of meaning.

Until 2026-08-30 the table covered Diana only, so almost everything else fell to the chest
fallback — a slayer roll drew two identical chests and a fishing roll drew three. It now
carries **354 rows over 476 names, onto 236 distinct vanilla items**, 65 of them glinted so an
`Enchanted X` stays distinct from its plain form.

How a row is chosen, in order: the vanilla texture the wiki shows the drop reusing; failing
that, the closest thing that still reads at 16x16, argued in the row's own `why`; failing that,
a whole family borrows a vanilla family with enough members to keep it apart — runes take
pottery sherds, pets and sea creatures take spawn eggs, vinyls take music discs, gemstones take
the material of their own colour. Every id was checked to exist in **both** 26.1.2 and 26.2 by
dumping `BuiltInRegistries.ITEM.keySet()` on each jar and intersecting; a wrong id is a
missing-texture cube in the middle of the widget.

It is a resource, not code, so a player can drop a corrected copy into a resource pack and a
maintainer can fix a Hypixel rename in a hotfix jar without anyone recompiling.

**What is actually tested, which is narrower than what ships.** `DropSymbolsMcTest` fails the
build when two rows in one source's payout share an item id *and* glint. It can only walk the
**44 of 64 sources that declare a jackpot list** — 207 distinct names — plus the five reels the
self test photographs. The other 20 sources have no verified loot table, so their rows are
argued, not proved.

**Still uncovered, deliberately:**

- Trophy fish beyond the seven the registry celebrates draw the chest. A trophy catch is one
  fish at a time, so it can never produce a repeat, but the sprite is generic.
- Fine and Flawless gemstones of one colour are separated only by the glint. That is correct
  on screen and matches SkyBlock, but it is subtler than a different item; it was the only way
  to fit 12 colours x 2 grades inside vanilla's palette.
- A bare enchantment name (`Smite VI`) has no row. Hypixel sends `Enchanted Book (Smite VI)`,
  which the parenthetical matcher already resolves, so this only bites if a future detector
  strips the wrapper.
- Anything Hypixel adds or renames after the 2026-08-30 wiki read. A name that vanishes is
  harmless; a name that appears needs a row.

**A gap worth remembering, because it shipped once.** The self test's own demonstration reels
are a third list of names that neither the registry nor the wiki snapshot knows about. Three of
them — Essence, Lava Shell, Magma Urchin — were missing from the table while the whole suite
stayed green, and the published fishing frame went out with two identical fallback chests in
it. `SelfTest.demonstrationRolls()` is now exposed and walked by the same test, so the next
missing row fails a build instead of being found by looking at a PNG.

---

## 6. The SkyBlock-wide sources — and how little of this is verified

Sections 1–5 describe the Diana feature, which has been watched working on the live server.
This section describes everything added when the slot machine stopped being a Diana feature,
and it opens with the warning that matters most on the whole page:

> **Almost nothing in this section has ever been seen on a live Hypixel server by this
> project.** The Diana patterns above were built by watching chat and fixing what broke. The
> patterns below were built by reading two other mods' source — SkyHanni and Skyblocker — and
> copying the strings they match. That is good evidence: those mods work, and their authors
> did watch chat. It is not the same as having seen the line arrive. Treat every "verified"
> below as *"verified against a reference mod"*, never as *"observed in play"*, unless the row
> says otherwise.

The practical consequence: **a pattern here that is wrong fails silently.** The gate opens,
the marker never matches, the detector never fires, nothing is logged and nothing looks
broken. It is indistinguishable from standing somewhere the source cannot happen. That is why
§9's marker contract is enforced by a test rather than by review, and why the table below
records provenance per source rather than as a blanket claim.

### 6.1 What ships, and what actually has a detector

64 `LootSource` constants exist. **51 have a detector.** One (`DIANA_MYTHOLOGICAL`) is owned
end to end by `DianaController` and is deliberately not on the bus. **12 have no detector at
all** and are reachable only through `/skyprism simulate`.

Generated from `LootSourceRegistry` itself, so it cannot drift from the code:

| Source | Ships as | Gate | Trigger | Detector? |
| --- | --- | --- | --- | --- |
| `ARACHNE` | ALWAYS | in SkyBlock, on Spider's Den | CHAT | yes |
| `BROODMOTHER` | ON_RARE_BANNER | in SkyBlock, on Spider's Den | CHAT | **none** |
| `CARNIVAL_FRUIT_DIGGING` | NEVER | in SkyBlock, on Hub in Carnival | CHAT | yes |
| `CHOCOLATE_FACTORY_STRAY` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, armed by the Chocolate Factory screen | CHAT + SCREEN_TITLE | yes |
| `COMBAT_SHARD` | NEVER | in SkyBlock | CHAT | **none** |
| `CRIMSON_MINIBOSS` | ALWAYS | in SkyBlock, on Crimson Isle | CHAT | yes |
| `CROESUS_CHEST` | ON_RARE_BANNER | in SkyBlock, armed by the Croesus screen | CHAT + SCREEN_TITLE | yes |
| `CRYSTAL_NUCLEUS_RUN` | ALWAYS | in SkyBlock, on Crystal Hollows | CHAT | yes |
| `DIANA_MYTHOLOGICAL` | ALWAYS | in SkyBlock, while Diana is mayor | CHAT + ENTITY | DianaController |
| `DRACONIC_SACRIFICE` | ALWAYS | in SkyBlock, on The End | CHAT | **none** |
| `DUNGEON_BOSS` | ALWAYS | in SkyBlock, inside a dungeon | CHAT | yes |
| `DUNGEON_REWARD_CHEST` | ON_RARE_BANNER | in SkyBlock, armed by the reward chest screen | CHAT + SCREEN_TITLE | yes |
| `DUNGEON_RUN_COMPLETE` | NEVER | in SkyBlock, inside a dungeon | CHAT | yes |
| `ENDER_DRAGON` | ALWAYS | in SkyBlock, on The End in Dragon's Nest | CHAT | yes |
| `ENDER_NODE` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on The End | CHAT | **none** |
| `ENDSTONE_PROTECTOR` | ALWAYS | in SkyBlock, on The End | CHAT | yes |
| `EXPERIMENTS_REWARDS` | ALWAYS | in SkyBlock, armed by the Experimentation Table screen | CHAT + SCREEN_TITLE | yes |
| `FISHING_GOLDEN_FISH` | ALWAYS | in SkyBlock, on Crimson Isle | CHAT | yes |
| `FISHING_RARE_SEA_CREATURE` | ALWAYS | in SkyBlock | CHAT | yes |
| `FISHING_SEA_CREATURE` | NEVER | in SkyBlock | CHAT | yes |
| `FISHING_TREASURE` | ON_JACKPOT_ITEM_ONLY | in SkyBlock | CHAT | yes |
| `FISHING_TROPHY_FISH` | NEVER | in SkyBlock, on Crimson Isle | CHAT | yes |
| `FISHING_TROPHY_FISH_RARE` | ALWAYS | in SkyBlock, on Crimson Isle | CHAT | yes |
| `FORAGING_TREE_BONUS_GIFT` | ALWAYS | in SkyBlock, on Galatea | CHAT | yes |
| `FORAGING_TREE_GIFT` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on Galatea | CHAT | yes |
| `FORAGING_TREE_PHANTOM` | ALWAYS | in SkyBlock, on Galatea | CHAT | yes |
| `FOSSIL_EXCAVATION` | ALWAYS | in SkyBlock, on Dwarven Mines or Mineshaft | CHAT | yes |
| `FROZEN_TREASURE` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on Jerry's Workshop (wants: during the Season of Jerry) | CHAT | **none** |
| `GARDEN_CROP_FEVER` | ALWAYS | in SkyBlock, on Garden | CHAT | yes |
| `GARDEN_PEST_DROP` | ALWAYS | in SkyBlock, on Garden | CHAT | yes |
| `GARDEN_RARE_CROP` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on Garden | CHAT | yes |
| `GARDEN_VERY_RARE_CROP` | ALWAYS | in SkyBlock, on Garden | CHAT | yes |
| `GARDEN_VISITOR_RARE` | ALWAYS | in SkyBlock, on Garden | CHAT | yes |
| `GHOST_MIST` | ON_RARE_BANNER | in SkyBlock, on Dwarven Mines in The Mist | CHAT | **none** |
| `GLACITE_CORPSE` | ALWAYS | in SkyBlock, on Mineshaft | CHAT | **none** |
| `GLACITE_MINESHAFT_PORTAL` | ALWAYS | in SkyBlock, on Dwarven Mines or Crystal Hollows | CHAT | **none** |
| `HEADLESS_HORSEMAN` | ON_RARE_BANNER | in SkyBlock (wants: during the Spooky Festival) | CHAT | **none** |
| `HOPPITY_MEAL_EGG` | ALWAYS | in SkyBlock (wants: during Hoppity's Hunt) | CHAT | yes |
| `HOPPITY_RABBIT` | ALWAYS | in SkyBlock (wants: during Hoppity's Hunt) | CHAT | yes |
| `KUUDRA_COMPLETE` | ALWAYS | in SkyBlock, on Kuudra | CHAT | yes |
| `KUUDRA_REWARD_CHEST` | ON_RARE_BANNER | in SkyBlock, armed by the Free or Paid Chest screen | CHAT + SCREEN_TITLE | yes |
| `LOOT_CHEST` | ALWAYS | in SkyBlock, on Crystal Hollows or Mineshaft | CHAT | yes |
| `METAL_DETECTOR_SCAVENGE` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on Crystal Hollows | CHAT | yes |
| `MINING_COMPACT` | NEVER | in SkyBlock, on Dwarven Mines or Crystal Hollows | CHAT | yes |
| `MINING_GOBLIN_RAID` | ALWAYS | in SkyBlock, on Dwarven Mines | CHAT | yes |
| `MINING_PRISTINE_GEMSTONE` | NEVER | in SkyBlock, on Crystal Hollows | CHAT | yes |
| `MOB_RARE_DROP` | ON_RARE_BANNER | in SkyBlock | CHAT | yes |
| `PET_DROP` | ALWAYS | in SkyBlock | CHAT | yes |
| `POWDER_CHEST` | ON_JACKPOT_ITEM_ONLY | in SkyBlock, on Crystal Hollows | CHAT | yes |
| `PRIMAL_FEAR` | ON_RARE_BANNER | in SkyBlock (wants: during the Great Spook) | CHAT | yes |
| `REINDRAKE` | ON_RARE_BANNER | in SkyBlock (wants: during the Season of Jerry) | CHAT | yes |
| `RIFT_BOSS` | ON_RARE_BANNER | in SkyBlock, inside The Rift | CHAT | **none** |
| `RIFT_MOTES_ORB` | NEVER | in SkyBlock, inside The Rift | CHAT | yes |
| `RIFT_UBIK_SPLIT_OR_STEAL` | ALWAYS | in SkyBlock, armed by the Split or Steal screen | CHAT + SCREEN_TITLE | yes |
| `RIFT_VERMIN_VACUUM` | NEVER | in SkyBlock, inside The Rift | CHAT | yes |
| `SLAYER_BOSS` | ALWAYS | in SkyBlock | CHAT | yes |
| `SLAYER_MINIBOSS` | NEVER | in SkyBlock | CHAT | yes |
| `SPOOKY_CHEST` | NEVER | in SkyBlock (wants: during the Spooky Festival) | CHAT | yes |
| `SUSPICIOUS_SCRAP` | ALWAYS | in SkyBlock, on Dwarven Mines or Mineshaft | CHAT | **none** |
| `TREVOR_TRAPPER` | ON_RARE_BANNER | in SkyBlock, on The Farming Islands | CHAT | yes |
| `VANQUISHER` | ON_RARE_BANNER | in SkyBlock, on Crimson Isle | CHAT | **none** |
| `WINTER_GIFT` | ON_RARE_BANNER | in SkyBlock (wants: during the Season of Jerry) | CHAT | yes |
| `YEAR_OF_THE_PIG_ORB` | ALWAYS | in SkyBlock (wants: during the Year of the Pig) | CHAT | yes |
| `YEAR_OF_THE_WITCH_STEW` | NEVER | in SkyBlock, armed by the Witches Stew screen | SCREEN_TITLE | yes |

### 6.2 The twelve with no detector, and why

These are not oversights. Most are constants whose *trigger line could not be verified to
exist*, and the project chose an absent detector over an invented regex — because an invented
regex is a feature that looks implemented and never fires.

| Source | Why there is no detector |
| --- | --- |
| `BROODMOTHER` | No death line exists in either reference mod; both track it through the TAB stage widget. |
| `VANQUISHER` | No kill line could be verified; the reference mod uses entity despawn, which is strong evidence none exists. The spawn broadcast is lobby-wide and would fire for players nowhere near it. |
| `HEADLESS_HORSEMAN` | Neither reference mod carries a spawn or kill line; it is known only as a damage-indicator boss type. |
| `RIFT_BOSS` | Bacte announces growth phases only; Leech Supreme and Sun Gecko are detected purely from entity names. No kill line for any of the three. |
| `GHOST_MIST` | Pattern exists, but the gate needs a graph area (`The Mist`) and nothing reads areas yet — see §6.5. |
| `DRACONIC_SACRIFICE` | Pattern known (`BONUS LOOT`), simply not built. |
| `ENDER_NODE` | Pattern known, not built. |
| `COMBAT_SHARD` | Its only rarity tell is a trailing `" NEW!"` on a first-ever shard — a per-line flag `LootDrop` cannot carry. Ships `NEVER` rather than shipping a rule it cannot honour. |
| `GLACITE_CORPSE` | Fully verified header pattern, not built. Ownership fell between the container and gathering packages. |
| `SUSPICIOUS_SCRAP` | Fully verified, not built. Also: only one symbol is available, so three matching reels is guaranteed and the jackpot would be unconditional. |
| `GLACITE_MINESHAFT_PORTAL` | Fully verified, not built. Also produces **no drop**, so the reels have one symbol and three-of-a-kind is meaningless. |
| `FROZEN_TREASURE` | Fully verified, not built. |

The last four are the ones worth building next: their patterns are verified and only the
detector is missing.

### 6.3 Patterns that are NOT verbatim from a reference mod

Every pattern not in this list is copied verbatim from SkyHanni or Skyblocker beside its own
captured sample line. These are the exceptions — the places where a human made a judgement,
which is where a bug will be.

**Invented or inferred (highest risk — these could be simply wrong):**

| Where | What was inferred | If it is wrong |
| --- | --- | --- |
| `TrophyFishDetector` — `GatheringSamples.TROPHY_DIAMOND_BY_ANALOGY` | No captured `DIAMOND` line exists anywhere. The tier word and its 0.2% rate are from the wiki; the surrounding shape is a captured `GOLD` line with the tier swapped. The detector matches the *tier group*, not the name, so it fires correctly if the shape holds. | Diamond trophy fish never roll. |
| `DungeonBossDetector.SIDEBAR_FLOOR` | That Master Mode spells the floor `M7` rather than `F7`. The sidebar shape itself is verified; the letter is not. | Caption degrades to `Necron (F7)`; it does not misfire. The verified summary-header path still yields `Master Mode Floor VII`. |
| `SlayerMinibossDetector.MINIBOSS_PREFIX` | Prefix match on `SLAYER MINI-BOSS` only. Skyblocker matches with `startsWith` and neither reference mod records what follows, so no tail was invented. | Nothing; it is deliberately loose. |
| `ContainerPatterns.LOOT_CHEST_COLLECTED` | That the Jungle Temple's key-opened chests use this header rather than a third one. | Those chests are missed — a missing roll, not a wrong one. |
| `RareRewardBroadcast` for `KUUDRA_REWARD_CHEST` | The `RARE REWARD` broadcast is confirmed only for an Obsidian Chest; that it also fires for Kuudra Free/Paid chests is inferred from SkyHanni's `(.*)` tier group. | `KUUDRA_REWARD_CHEST`'s `ON_RARE_BANNER` can **never** be satisfied. Documented fallback is `ALWAYS` on the Paid chest. |
| `"Scavenged Lapis Sword"` in `METAL_DETECTOR_SCAVENGE`'s jackpot list | The one of four tool names never seen quoted. | One jackpot that never triggers. |
| `ReindrakeDetector`, `PrimalFearDetector` | **Neither has a verified kill line at all** — only the lobby-wide summon broadcast. Both are implemented as "the summon arms a bounded window; the next rare banner inside it is credited". | Known imprecision: neither summon carries ownership, so a bystander's rare drop can be captioned with that boss. |
| `SpookyChestDetector` | Only the appearance broadcast is verified; no loot line exists in either corpus. Ships `NEVER`. | Nothing, by design. |
| `WitchesStewDetector` | The GUI title is verified; whether the stew is random at all is not. Ships `NEVER`. | Nothing, by design. |
| `SplitOrStealDetector` | No win/lose line verified anywhere — only the GUI title and the cooldown refusal. The reward is Motes, not an item. | The caption must say so; the reels have nothing to land on. |
| `CrimsonMinibossDetector` — `"MAGMA BOSS"` | The least corroborated of the five miniboss names. | That one boss silently never rolls; the other four are unaffected. |
| `FOSSIL_EXCAVATION` jackpot list | Empty **on purpose** — no quoted loot table was found. | No fossil ever earns the flourish. |

**Deliberately widened from the verbatim form** (each can only match *more*, never less):

- `BossDownBanner.PATTERN` accepts any interleaving of formatting codes and whitespace before
  the name. The three captured reference forms disagree only on that padding, and SkyPrism's
  own `LegacyText.toLegacy` inserts a `§r` before every styled run — so pinning any one of them
  would have served three bosses and silently missed eleven. The words and the upper-case name
  class are unchanged, the match is anchored with `matches()`, and every caller checks the
  capture against a closed table.
- `RareDropBanner`'s PLAIN branch ends in a permissive `[^\n\r]*` tail; the verbatim SkyHanni
  tails do not match the real Magic Find suffix.
- `RareRewardBroadcast.RARE_REWARD` is matched colourless and anchored at `^`, and accepts
  `found an?` where the reference only quotes `found a`.
- `EXCAVATION_EMPTY`, `NUCLEUS_RUN_COMPLETE` and `EXPERIMENT_CLAIM` each admit an optional
  leading `(?:§.)*` style run.
- Trophy fish, treasure catch and pest drop **no longer anchor on the private-use icon
  codepoints** (U+E02A, U+E025, U+E02B). Hypixel moved Magic Find's glyph once already. Their
  prefixes now admit codes plus characters that cannot spell a player name.
- The pest bracket accepts `+digits` plus at most two trailing characters instead of the literal
  Overbloom glyph — which still rejects the magic-find bracket.
- `CompactProcDetector` captures the material instead of pinning `Enchanted Hard Stone`.
- `ChocolateFactoryStrayDetector` and `HoppityRabbitDetector` narrow SkyHanni's `.*` name groups
  to `[^§]+` so a caption cannot carry formatting codes.
- `WinterGiftDetector` collapses SkyHanni's six separately-verified payload patterns into one
  skeleton.

**Accepted risk, called out where it lives:** `ContainerPatterns.DUNGEON_CHEST_TITLES`
includes the bare forms `Wood`/`Gold`/`Diamond`/`Emerald`/`Obsidian`/`Bedrock`. Skyblocker
carries these with a comment that Hypixel broke the titles, so dropping them would miss live
chests — but they are six common English nouns matched by exact equality against a whole
inventory title.

### 6.4 Things deliberately NOT implemented, with evidence

Recorded so nobody adds an invented regex for them later. All three are in
`core/loot/gathering/package-info.java`.

- **Rift fishing does not exist.** No chat line in 44 SkyHanni rift files, no rift variant in
  the 90-creature corpus.
- **Titanium and Mithril have no chat announcement at all.**
- **Jacob's contest rewards are placement-based, not chance-based.**
- **Baby Magma Slug** is absent from the sea-creature table because its `chat_message` is empty
  in the corpus.
- **The Dark Auction is excluded on purpose**, and so is the Bazaar: the player sees the exact
  item Lucius holds and bids coins against other players, so the outcome is competitive rather
  than random and no drop is generated for a reel to lock onto.

### 6.5 Two gaps that shut gates permanently

Both are cases where a gate's condition is never tested, so the source can never fire. Both
are diagnosable rather than silent — `/skyprism sources` prints the gate verbatim, so the
player sees a condition that plainly is not being evaluated.

1. **`GameContext.area()` is always empty.** SkyPrism reads the sidebar island but has no
   reader for Hypixel's fine graph area (the TAB list). Every area-gated source is therefore
   permanently shut: `GHOST_MIST` (`The Mist`), `ENDER_DRAGON` and `ENDSTONE_PROTECTOR` where
   gated on `Dragon's Nest`, `CARNIVAL_FRUIT_DIGGING` (`Carnival`). Fix: one argument in
   `LootMachine.buildContext` once a TAB area reader exists.
2. **There is no SkyBlock-season token.** `SourceGate.season(...)` is a pass-through, so
   seasonal sources — Hoppity, the Great Spook, the Season of Jerry, the Year of the Pig — are
   **armed year-round**. Chosen over a permanently-shut gate because a gate that can never open
   is the silent-never-fires failure. Safe in practice because their patterns are distinctive,
   but not free. The sidebar already carries the SkyBlock date.

### 6.6 The drop parser's four defects, now fixed

`core/diana/LootParser` is what feeds the reels, and it shipped with four defects that Diana
never exercised but every new source did. All four are fixed as of 2026-08-30, test-first, with
15 new cases in `LootParserTest`:

| Defect | Symptom | Fixed by |
| --- | --- | --- |
| The bracketed "went to sacks" banner captured the item name as a bare `(` | A slayer sack drop locked a reel onto `(` | The bracket is consumed as a delimiter rather than matched as a name |
| The **two-space** `VERY RARE DROP!` / `CRAZY RARE DROP!` forms did not match at all | The two tiers a slot machine exists for never landed | The separator is ` {1,2}` — one space or two, not exactly one |
| The `§r` required after the banner is absent on Garden pest drops | Pest drops were not parsed | The post-banner reset is optional |
| A trailing `§8xN` count form was lost | Counts were dropped | A trailing dark-grey count group is tried after the item name |

**One deliberate tightening came with them, and it is worth knowing about.** Making the
post-banner `§r` optional would otherwise have let a completely unformatted line — a banner a
*player* typed into chat — parse from character one, which is both a pre-existing negative test
and a way for anyone to spin anyone else's machine. So the leading formatting run went from
`(?:§.)*` to `(?:§.)+`: a drop line must now begin with at least one formatting code. Hypixel's
banners are always coloured and bolded, so no Hypixel-shaped line changed; a differential run
over 280,896 corpus lines found 4,085 affected and every one of them began with no formatting
code at all. Pinned by `playerTypedBannersStillYieldNothing`.

**Known remaining limit, documented rather than fixed:** an item name Hypixel painted dark grey
(`§8`) *and* split across a component boundary would have its own `§8x16` swallowed as a name
continuation, because the item group's same-colour continuation runs possessively before the
count group is tried. No SkyBlock rarity colour is dark grey, so this shape should not exist.

**Duplication: resolved 2026-08-30.** `core/loot/events/RareDropBanner` independently
implemented the same four fixes for the 64-source bus, and two further files carried the
vocabulary again. All three copies are gone; see §3.10 for what each one did differently and
what replaced it.

### 6.7 Four more shapes the unified parser fixed

Found by running the two former parsers side by side over one line set, so every row below is
observed output rather than inspection.

| Shape | What both parsers did | Now |
| --- | --- | --- |
| Crop Fever, `RARE DROP! You dropped 48x Enchanted Melon Slice!` | item name = the whole English sentence, on a reel and in the stats file | `Enchanted Melon Slice` × 48 (§3.5) |
| `UNCOMMON DROP!` | missed entirely, while `BannerLines` armed a detector on it | decomposed, and flagged **not** rare |
| An aqua (DIVINE-rarity) item | swallowed its own `(+168% Magic Find)` into the item name | `(?!\(\+)` on the continuation (§3.3) |
| A bracketed line whose bracket pattern fails | item name = `(` | refused outright; the catch-all still spins the machine (§3.10) |

Two more, verified by measurement rather than by a captured line, are fixed by the same pass: a
name carrying a second formatting code (`§r§6§lDivan's Alloy`) and one carrying an obfuscated
run (`§9§kXXX§r§9Undiscovered`) were both silent misses, because the colour group admitted
exactly one code. It now admits a colour plus any style codes stacked on it.

**And the feature this was all in service of:** Magic Find is now carried on `LootDrop` from the
parser all the way to the jackpot reveal. See §3.6 for the five suffix forms and §3.9 for what
the HUD draws.

---

## 7. Cross-source collisions — one line, one owner

With 51 detectors listening, the new failure mode is not a detector that stops working but one
that starts working on somebody else's line. The bus is **first-match-wins in registration
order**, so registration order is load-bearing and a collision is decided by it.

`CrossSourceCollisionTest` sweeps every detector's captured samples past every other detector
that could be listening *in the same context* (gates applied — without them the sweep reports
21 overlaps, almost all between sources that can never be armed at once, which buries the real
ones). Result: **12 reachable overlaps, 7 of which are the design working** — a specific source
claiming its line ahead of the universal catch-all.

**The three that are real, and accepted:**

`TrapperDetector` is armed by nothing but the island and then claims *any* rare-drop banner on
The Farming Islands, because its `ON_RARE_BANNER` policy can only ever be satisfied by a
banner. It is registered ahead of `PRIMAL_FEAR`, `REINDRAKE` and `MOB_RARE_DROP`, so on that
island it takes their lines. The cost is a caption, not a lost or doubled roll — the bus stops
at the first match. Narrowing it needs the hunt-assignment line, which carries no marker this
source declares and so never reaches the detector.

**The one that would have been a Diana regression, and how it is prevented.** Diana burrows
spawn on the Hub *and on The Farming Islands*. A Minos Inquisitor's drop reaches chat as a bare
`§6§lRARE DROP! §r§5Minos Relic` — textually indistinguishable from any other rare drop there.
`BannerLines` guards the burrow *treasure* sentence (`You dug out`), so a dug payout is safe; it
cannot guard the creature drop. The separation therefore does not come from the text at all, it
comes from `LootMachine.admit`, which refuses every bus event while a Diana roll is on screen.
`DianaOutranksTheBusMcTest` pins that branch.

**Two sources are claimed by two detector implementations each**, resolved in
`LootMachine.PREFERRED_ON_COLLISION` (`LootEventBus.register` throws on a duplicate, which would
take the whole feature down at startup): `MOB_RARE_DROP` goes to the events version, `PET_DROP`
to the combat version. The two losing implementations are unreachable dead code;
`/skyprism sources` reports the contested constants so somebody deletes them.

**Global registration order that must not change:** the universal `GenericRareDropDetector`
must be registered **last across the whole bus**. Registering it early makes it swallow every
specific source in the mod, and that failure is silent.

---

## 8. Scoreboard and TAB strings

These are read by `mc/diana/HypixelContext.java` and drive the `DianaGate`. They are not
chat, but they fail the same way and are debugged with the same reflexes.

| What | Value | Where |
| --- | --- | --- |
| Hypixel domains | `hypixel.net`, `hypixel.io` | `HypixelContext.java:78` |
| SkyBlock sidebar title | contains `SKYBLOCK` **or** `SKIBLOCK`, upper-cased, by containment | `HypixelContext.java:289` |
| Area line marker | `⏣` (U+23E3 BENZENE RING WITH CIRCLE) | `HypixelContext.java:81` |
| Deep Caverns marker | `ф` (U+0444 CYRILLIC SMALL LETTER EF) | `HypixelContext.java:82` |
| Mayor row | `(?i)\bmayor\b[\s:]*diana\b` | `HypixelContext.java:99` |
| Mayor header row | `(?i)^(?:mayor\|election)\s*:?$` | `HypixelContext.java:110` |
| Mayor value row | `(?i)^(?:mayor\s+)?diana\b.*` | `HypixelContext.java:118` |

Notes that matter:

- **`SKIBLOCK` is not a typo.** It is Hypixel's April Fools re-skin of the sidebar title, and
  it is a real live variant.
- **Sidebar text is not in the score entry name.** Hypixel puts almost nothing there; the
  visible line is the team prefix concatenated with the team suffix. The recipe is objective
  → entries → each entry's team (`lineTextOf`).
- **The domain check is suffix-anchored with a dot.** A bare `endsWith("hypixel.net")` would
  also accept `nothypixel.net`, which is exactly the look-alike a chat-reading mod should not
  be pointed at. `-Dskyprism.forceHypixel=true|false` overrides it for a dev client.
- **"Mayor" and "Diana" must be adjacent.** Mere ordering accepts a combined office row such
  as `Mayor Foraging Fortune | Minister Diana`, and — because the loop walks all eighty real
  player rows — a player who calls themselves `MayorDiana`. Both would arm the whole feature
  under a mayor who is not Diana, and `LootParser` is deliberately not Diana-specific, so any
  rare drop within a stale spawn's five-minute lifetime would then be recorded as Diana loot.
- **A minister is not a mayor.** `Minister Diana` is explicitly rejected: a minister's perks
  do not include the Mythological Ritual, so rolling the machine on a burrow that cannot
  exist would be a bug the player could not explain. That is why `DIANA_VALUE` is anchored at
  the start.
- **The header/value split is guessed defensively.** Hypixel routinely splits a TAB widget
  into a header row and an indented value row, so `Mayor:` on one row and `Diana` on the next
  is handled as well as both on one. Requiring both words on one row was a bet on a layout
  Hypixel has changed several times.
- **There is a built-in alarm for this one.** After three consecutive mayor polls inside
  SkyBlock that found nothing mayor-shaped anywhere in the TAB list, `noteMayorRowMiss` logs
  one WARN naming the problem. Finding *any* mayor row — even one naming somebody else —
  clears the count. That log line is the fastest evidence that Hypixel changed the election
  widget.

---

## 9. The marker list — a contract, not an optimisation

`mc/chat/DianaLineFilter.java:65`

```java
public static final List<String> MARKERS =
        List.of("dug out", "DROP!", "burrow chain", "has spawned near");
```

Every system message Hypixel sends is tested against these four `String.contains` calls
before anything is reconstructed or any regex is run. A line that matches none of them never
reaches the core.

| Marker | Covers |
| --- | --- |
| `dug out` | `SPAWN`, `TREASURE_DUG`, the ordinary half of `BURROW_DUG`, and both treasure shapes in `LootParser` |
| `DROP!` | every banner in `BANNER_DROP` — RARE, VERY RARE, CRAZY RARE, INSANE, PET. The exclamation mark is part of the marker, which keeps it off a player typing "rare drop" in all-chat |
| `burrow chain` | the chain-finished half of `BURROW_DUG`, the one Diana line that never says "dug out" |
| `has spawned near` | `INQUISITOR_SHARE` |

**Case matters and is deliberate.** Matching case-insensitively would drag in ordinary
player chatter for no gain, since Hypixel's own casing is fixed.

**Adding a pattern to `DianaPatterns` or `LootParser` without a marker here silently deletes
the feature** — no exception, no log line, nothing. That is why `DianaMarkerContractMcTest`
enumerates every pattern `DianaPatterns` exposes *by reflection*, feeds a known matching line
for each through this exact method, and fails if any is rejected. It also tests the raw
section-coded form, which is why every marker must be plain, uninterrupted text: a marker
with a colour code spliced through the middle would be found in neither form.

There is exactly one copy of this list. `ChatRouter.mightMatterToDiana` and
`DianaController.looksRelevant` both delegate to it. They did not always: `DianaController`
once carried its own copy that was missing `"burrow chain"`, which was masked only because
`ChatRouter` had the full list and fed the controller anyway.

### 9.1 The SkyBlock-wide filter is derived, not hardcoded

The 51 bus detectors do **not** share the four markers above. A hardcoded keyword list is the
obvious implementation and the wrong one: it drifts the moment somebody adds a detector and
forgets the list, and the symptom is a feature that works in the unit test and silently never
fires in game. So `LootEventBus`'s filter is *derived from the registered detectors
themselves* — it is the union of what they declared, recomputed only when the context changes,
and it cannot contain anything they did not ask for or omit anything they did.

The contract each detector signs is the same one as above, stated as an invariant:
**every string `onChat` can match must contain at least one `chatMarkers()` entry verbatim.**
`LootEventBusPreFilterTest` takes every registered detector's `triggerSamples()` — real
captured lines — feeds them through the real bus, and fails if the filter swallowed one.

### 9.2 One markerless detector must not switch the filter off for everybody

A detector may return no markers, which opts it out of filtering entirely: it is offered every
line. That is always correct and never fast, and it is the right default for the safe
direction.

The bus originally treated "some open detector declared no markers" as "nobody can be
filtered". **That turned out to cost more than the rest of the feature.** Exactly one shipped
detector is markerless — `SeaCreatureDetector`, whose ninety announcements genuinely share no
literal — and its gate is open on every island, because fishing is possible everywhere. So in
live play the filter was bypassed across the whole of SkyBlock, and every line of guild chat
ran the regexes of all twenty-odd open detectors.

Measured on The Farming Islands, 2,000,000 lines of ordinary chat:

| | ns per line |
| --- | --- |
| Filter bypassed (before) | 2,566 – 3,331 |
| Filter active (after) | 703 |
| Pre-filter alone, for reference | 184 |
| Nothing armed, e.g. a lobby | 8 |

The fix is to stop making it an all-or-nothing decision: a detector that declared markers has
promised every line it can match contains one of them, so on a line the filter rejected there
is provably nothing for it to do; a markerless detector made no such promise and is offered the
line anyway. One boolean per open detector, registration order preserved exactly.

**If you add a markerless detector, know that you are putting its full cost on every line of
chat in the game.** `LootEventBus.unmarkedDetectorCount()` is the number to watch.

---

## 10. If Hypixel changes something

Find the symptom. Go to the file and line. The three questions worth asking in every case
are: has the wording changed, has the *colour code sequence* changed, or has a component
boundary appeared somewhere new?

| What the user reports | Most likely cause | Look here |
| --- | --- | --- |
| "Level numbers aren't coloured anywhere any more" | Tag format changed, or the feature is scoped off. Run bare `/skyprism` first — `levels.onlyOnSkyBlock` is on by default, so this also looks exactly like the sidebar title check failing. | `core/level/LevelTagLocator.java:92`, then `mc/diana/HypixelContext.java:289` |
| "Levels colour in chat but not in TAB" (or vice versa) | One surface's circuit breaker tripped, or one toggle is off | logs for `SkyPrism Surfaces` at ERROR (`mc/surfaces/LevelSurfaces.java`, `FAILURE_BUDGET = 8`); config `levels.applyToChat` / `applyToTabList` / `applyToNameTags` |
| "Something that isn't a level got coloured" | A boundary or content rule needs tightening. **Do not** widen anything here to fix a missing match. | `core/level/LevelTagLocator.java:92` |
| "High-level players aren't coloured" | The level passed the configured ceiling | `SkyPrismConfig.LevelSettings.maxLevel`, `core/config/SkyPrismConfig.java:369`; the ceiling constant is `LevelTagLocator.STANDARD_MAX` |
| "The emblem got recoloured" | Something added emblem matching that must not exist | `core/level/LevelTagLocator.java:92` and `mc/text/ComponentRewriter.java` — the fix is to remove it, then check `RecolourProbe.java:222` still passes |
| "The machine never spins at all" | The gate is shut. Bare `/skyprism` prints `DianaGate.describe()`, which names the failing condition rather than just saying "closed". | `core/diana/DianaGate.java`, `describe()` |
| "…and status says the mayor isn't Diana, but she is" | The TAB election widget changed | `mc/diana/HypixelContext.java:99`, `:110`, `:118`. Look for the WARN from `noteMayorRowMiss` in the log first — it distinguishes "not Diana" from "cannot read the row" |
| "…and status says not in SkyBlock, but I am" | Sidebar title changed | `mc/diana/HypixelContext.java:289` |
| "…and status says the area isn't whitelisted" | Area glyph or area line changed, or the whitelist is wrong | `mc/diana/HypixelContext.java:81–82` (`areaIn`); config `diana.allowedAreas` — empty means *any* area |
| "It spins for the wrong creature / never for the right one" | Creature name changed, or the spawn line's shape changed | `core/diana/DianaPatterns.java:67`, then `core/diana/MythologicalCreature.java:20` |
| "It spins, but every reel says No Drop" | Spawn parsing works, drop parsing does not | `core/diana/LootParser.java:71` / `:87` / `:133` |
| "A reel shows a chopped-off item name" | An item name is being split across component nodes and the backreference continuation is not matching — most often because the colour code changed mid-name | `core/diana/LootParser.java:71` and `:133`, the `\k<color>` groups |
| "A reel shows something that reads like a sentence" | The treasure guard was bypassed | `core/diana/LootParser.java:182` (`isTreasureDig`) and the `(?!You dug out)` lookahead at `:133` |
| "A new drop type never appears on the reels" | Hypixel added a banner | `core/diana/LootParser.java:133`, the `banner` alternation — and add a marker at `mc/chat/DianaLineFilter.java:65` if it does not contain `DROP!` |
| "A drop line vanished from chat and never appeared on a reel" | Suppression outran the reels | `mc/diana/DianaController.java`, `onDrops` — suppression is capped at `diana.reelCount`; turning off `diana.suppressDropChatLines` is the immediate workaround |
| "Someone in party chat made my HUD spin" | An anchored match was relaxed to `find()` | `core/diana/DianaPatterns.java` — all three helpers must use `matches()` |
| "The Inquisitor waypoint broadcast is ignored" | Expected with defaults. `diana.onlyMyBurrows` is on. | config `diana.onlyMyBurrows`; the pattern is at `core/diana/DianaPatterns.java:108` |
| "Chat is laggy when a lot is happening" | A pattern picked up catastrophic backtracking | `core/diana/LootParser.java:133` — the item quantifier must stay possessive (`++` / `*+`) |
| Nothing Diana-related fires, and none of the above | A new pattern was added without a marker | `mc/chat/DianaLineFilter.java:65`; run `gradlew :26.2:mcTest` — `DianaMarkerContractMcTest` is the test that catches this |

### The five-minute repair loop

1. Capture the real lines into a text file, one per line, under the game directory, and run
   `/skyprism replay <file>`. That pushes each line through the mod's actual chat handling
   (`mc/command/ChatPipeline.java` → `ChatRouter.replay`) at one line per tick, so you are
   testing the shipped path and not a fixture. `/skyprism replay stop` cancels a run in
   progress; a file longer than 2,000 lines is truncated.
2. Edit the one pattern named above.
3. `bash <scratchpad>/corecheck.sh com.skyprism.core.diana` — a few seconds, no game.
4. If you touched `DianaPatterns` or `LootParser`, add the line to
   `src/test/java/com/skyprism/core/diana/` as a fixture, and check whether
   `DianaLineFilter.MARKERS` still covers it.
5. `gradlew mcTestAllVersions` before calling it done.

---

## 11. Where these came from, and how to re-verify

The source code records the following provenance. **I have not independently re-checked any
of it against the live server or against the upstream repositories** — treat the dates below
as claims made by the code's authors at the time, not as verification performed for this
document.

| Group | Recorded source | Recorded as re-read |
| --- | --- | --- |
| `SPAWN`, `BURROW_DUG`, `TREASURE_DUG`, `INQUISITOR_SHARE` | SkyHanni `beta`, `src/main/java/at/hannibal2/skyhanni/features/event/diana/GriffinBurrowHelper.kt` (repo-pattern keys for the burrow dig, the generic mythological spawn, the treasure dig, and the rare-mob waypoint share) | 2026-08-28 |
| `TREASURE_ITEM`, `TREASURE_COINS`, `BANNER_DROP` | SkyHanni `beta` (`DianaProfitTracker.kt`, `RareDropMessages.kt`) and Skytils `1.x` (`features/impl/trackers/impl/MythologicalTracker.kt`) | 2026-08-28 |
| Creature display names, rare flags, colour codes, aliases | SkyHanni's community-maintained constants, `constants/events/Diana.json` | — |
| Mob and drop facts, the ritual itself | `https://hypixelskyblock.minecraft.wiki/w/Mythological_Ritual` | 2026-08-28 |
| Level tag format, emblems, sidebar and TAB layout | No single citation in the source; these are observed behaviour | — |

**The official `wiki.hypixel.net` shut down in July 2026 and must not be used as a
re-verification source.** `DianaPatterns`' class javadoc records this explicitly.

### The rule for re-verification

**Re-copy from SkyHanni rather than guessing.** These expressions are maintained against the
live server by a very large user base. An "obvious" tidy-up here is far more likely to be a
regression than a fix — every non-obvious construct on this page (`{1,2}` on the lookbehind,
`\d[\d,]*` rather than `[\d,]+`, the possessive quantifiers, the article on both sides of
the colour run, the `\k<color>` continuations) is a defect that was found and fixed once
already.

### What has *not* been verified against a live server

Every automated fixture in this repository is synthetic. The unit tests build the lines
themselves; `RecolourProbe` states in its own output that there is "no server, no Hypixel"
and pushes a hand-built `Component` tree through the real entry points; the self-test
screenshots are taken from a dev client with no world. That is enough to prove the pipeline
does what the patterns say, and it is **not** evidence that the patterns still match what
Hypixel currently sends. Only a capture from a live session is.

**Since the SkyBlock-wide rework this caveat carries far more weight than it used to.** The
Diana patterns in §§1–5 were at least built by watching a live server and fixing what broke.
The 51 detectors in §6 were built entirely by reading two other mods' source. Nothing in §6
has ever matched a line that Hypixel actually sent to this project. The honest summary:

| | Verified how |
| --- | --- |
| §§1–5, Diana | Observed on the live server, then tested |
| §6, everything else | Copied from SkyHanni / Skyblocker beside their own captured samples — **never observed here** |
| The ~20 items in §6.3 | Not even that: inferred, widened, or reasoned about by a human |
| The six GUI screen titles | Transcribed from SkyHanni / Skyblocker; the feed that carries them is new and **the titles themselves have never been read off Hypixel** |
| The item-art table | Read off the SkyBlock wiki on 2026-08-29/30; no live drop has been matched against a row |
| §3.6, the Magic Find suffix | All five forms copied from SkyHanni / Skyblocker regression corpora — **never observed here**. See below |

The list in §6.3 is where to look first when a new source does not fire.

#### Magic Find, added 2026-08-30

The parser reads five suffix forms and the jackpot reveal draws the result (§3.6, §3.9). None
of the five has been read off a live server by this project. What each one rests on:

| Form | Rests on | Confidence |
| --- | --- | --- |
| Split runs, `%` present and absent | SkyHanni `RareDropMessages.kt`, the same line pinned twice, once per form; Skyblocker writes `%?` independently | high — two mods, agreeing |
| Flat single run, legacy ✯ | This project's own `LootSourceRegistry` samples | high, but the glyph is the *old* one |
| Bracketed/sack, unstyled tail | SkyHanni `ChatFilter.kt`, ~25 hardcoded lines | high |
| **No icon, trailing `!`** | SkyHanni `DungeonChatFilter.kt:136` **only**, and SkyHanni's own regex there is malformed (`\(+` escapes the paren then quantifies it) | medium — but see below; a Catacombs run is the *wrong* place to confirm it |
| Absent entirely | Three independent confirmations, including this project's own pre-existing test | high |

Because the file it is named after is a *dungeon* filter, this variant was previously written up as a
dungeon-specific shape and flagged as the highest-value thing to confirm in a Catacombs run. That was
backwards. Magic Find does not affect Catacombs chest loot at all, so a dungeon chest reward is the
one place the suffix has least reason to be printed. Confirm it where Magic Find is actually in force
— a slayer boss is the cheapest and most frequent — and see the "Where Magic Find actually applies"
note above before treating a missing figure on a dungeon roll as a defect.

None of this needs a code change: the pattern anchors on the literal words "Magic Find", so it reads
this variant whether or not the icon and the exclamation mark are present.

The icon is deliberately not in the pattern at all, so a sixth glyph would not break it. What
*would* break it is Hypixel rewording the stat: the pattern requires the literal words "Magic
Find", which is the only durable anchor and therefore also the single point of failure. That
trade is argued in `LootParser.MAGIC_FIND`'s own javadoc.

Two look-alikes sit in exactly the same position and must never be read as Magic Find — pet
luck's gold `(+1300☘)` and the Garden's yellow `(+134)` farming fortune. Both are pinned as
negative cases in `BannerCorpusTest`, and both survived this change.

#### The screen-title feed, added 2026-08-30

Six sources fire from a GUI opening rather than a chat line — Croesus, the two reward chests,
the Experimentation Table, the Witches Stew and Ubik's Split or Steal. `LootEventBus.onScreenTitle`
and the detectors that implement it have existed all along, but until now **nothing ever called
them**, so those six only ever fired from their chat halves and `CROESUS_CHEST` never armed at
all. `ClientPacketListenerOpenScreenMixin` now feeds every server-opened container's title into
`mc/loot/ScreenTitleFeed`.

What that does and does not prove:

- **Proven.** The injector lands. It is declared `require = 1`, so a miss fails the client at
  load, and the client boots and completes its self test on 26.2. The target bytecode was
  checked with `javap` against both shipped jars and is identical instruction for instruction,
  so no Stonecutter conditional is involved. `TAIL`, not `HEAD`, because vanilla's
  `ensureRunningOnSameThread` makes the method body run twice and `HEAD` would double-feed.
- **Proven headlessly.** `ScreenTitleFeedMcTest` drives packet → strip → correct detector →
  admit, feeding each detector the exact string it says it wants.
- **Not proven.** That any of those strings is what Hypixel actually sends. The fixtures come
  from SkyHanni and Skyblocker, not from a capture by this project. A wrong title fails
  silently — the source simply stays as quiet as it is today.
- **Cheapest live check.** One session: open Croesus, a reward chest, the Experimentation Table
  and Ubik's game, and log `ScreenTitleFeed`'s stripped title.

Two further caveats on the feed itself. `YEAR_OF_THE_WITCH_STEW` ships `RollPolicy.NEVER`, and
unarmed sources get no detector registered, so **five, not six**, of those sources are newly
reachable on default settings. And `EXPERIMENTS_REWARDS` gains a caption, not a trigger: its
`onScreenTitle` deliberately returns empty and only remembers which minigame the table is on,
so its events still come from chat — what changed is that the caption now names the game
instead of the bare tier.

`ScreenTitleFeed` reaches the machine's bus through one reflective field read, because
`LootMachine` keeps its bus private and has no public screen-title route, and a privately built
detector set cannot substitute (`CroesusChestDetector` arms from a title and claims from chat,
so a copy would arm an object the machine never reads). **The intended fix** is to give
`LootMachine` a `public Optional<LootEvent> onScreenTitle(String, long)` mirroring `onChat` and
call `ScreenTitleFeed.bind(...)` where the machine is wired; `bind()` is checked before the
reflection, so nothing else changes. Until then the field name is pinned by a test, so a rename
fails a build rather than silently killing six sources in game.

---

## 12. Where the drop tables come from, added 2026-08-31

Three things in this repository are lists of *SkyBlock item names*, and all three were
originally assembled by a human reading wiki pages:

| What | File | What a wrong name does |
| --- | --- | --- |
| The per-source jackpot lists | `core/loot/LootSourceRegistry.java` | that entry can never be matched, so the source never celebrates |
| The item-art table | `assets/skyprism/drop_symbols.json` | that row is dead weight; the real drop falls to the chest fallback |
| The shared filler names | `mc/hud/FillerStrip.GENERIC` | invented loot scrolls past on every reel |

A reel strip *is* its source's jackpot list plus `GENERIC`, so a wrong jackpot name is not a
quiet data defect — it is visible, spinning, in front of the player. That is exactly how this
was reported: "the wrong drop tables are still being used."

### 12.1 The failure mode, named

**A fake item name fails exactly like a detector that never fires.** The gate opens, the line
arrives, the parser reads it, the name is compared against a jackpot list holding a spelling
Hypixel has never sent — no match, no flourish, no log line, nothing on screen. It is
indistinguishable from standing somewhere the source cannot happen, which is the same silent
failure §6 warns about for the patterns.

So: **when a "source X never spins" report comes in, check the item names before touching the
regex.** §6.3 is where to look first for a bad pattern; this section is where to look first for
a bad name. The two are not distinguishable from outside the code, and the names have
historically been the more wrong of the two.

### 12.2 The canonical name source: the NotEnoughUpdates repo

`NotEnoughUpdates-REPO` is the community's maintained SkyBlock item database — 8,755 item
files, each carrying `internalname`, `displayname` (with `§` colour codes), `lore`, `itemid`
and `nbttag`. It is what NEU, SkyHanni and most of the ecosystem resolve item names against,
and it is the closest thing to a ground truth that exists off Hypixel's own servers.

- **Upstream:** `https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO`, branch `master`,
  directory `items/*.json`. Anyone can clone it; it is JSON and nothing else.
- **On this machine, already on disk:**
  `C:/Users/evanP/AppData/Roaming/ModrinthApp/profiles/SkyBlock Enhanced/config/notenoughupdates/repo`
  — NEU keeps it current as part of the live game install. **Read only.** Never write into a
  profile under `AppData/Roaming`.
- **The useful sibling directory** is `constants/`: `bestiary.json` (island → mob roster),
  `museum.json`, `attribute_shards.json`, `garden.json`, `essencecosts.json`.

Two limits found the hard way, so nobody re-discovers them:

- **`constants/bestiary.json` carries no drop tables.** It is `{island: {name, icon, mobs}}`
  and stops there. `repo/mobs/*.json` is skin and render data, also no drops. NEU answers
  *"is this a real item, and what is it called exactly"*. It does not answer *"what drops
  it"*.
- **NEU's `lore` often does answer "what drops it"**, and it is the cheapest signal available:
  rune lore reads `Obtained rarely from slaying the Tarantula Broodfather` verbatim, and
  `Requires Wolf Slayer 7` is what proved Hunter Ring and Hunter Talisman sit on the wrong
  source. Grep the lore before reaching for the network.

### 12.3 The content-area signal: Hypixel's own server resource pack

Hypixel ships a server resource pack (`pack_format` 84, namespace `hypixel_skyblock`) with
1,092 item-model definitions and 1,369 textures, and **its model paths are semantic** — they
encode which content area an item belongs to:

```
assets/hypixel_skyblock/items/item/community_center/mayor/diana/daedalus_blade
assets/hypixel_skyblock/items/item/island_relevant/mining_3/goblins/eggs/red_goblin_egg
assets/hypixel_skyblock/items/item/slayer/blaze/pets_related/subzero_inverter
```

That is a genuine second, independent answer to the one question the drop tables kept getting
wrong: *which source does this item belong to.* Hypixel's own art directory says there are
four goblin eggs and they are red, blue, green and yellow, which is how "Golden Goblin Egg"
and "Diamond Goblin Egg" were caught.

- **How to get it:** join Hypixel with server resource packs enabled. Minecraft caches the zip
  at `<instance>/downloads/<server-uuid>/<sha1>` with no file extension — on this machine, the
  newest file under
  `AppData/Roaming/ModrinthApp/profiles/SkyBlock Enhanced/downloads/242681e6-5e10-3cc8-8a64-451bdbf97e0b/`.
  It is an ordinary zip; unzip it somewhere outside the profile and read
  `assets/hypixel_skyblock/items/`.
- **Its limit is coverage, not accuracy.** The pack only ships art Hypixel has re-textured,
  329 of the 1,092 paths sit in `uncategorized`, and only 52 of the mod's 207 jackpot names
  matched a path at all. **It can confirm a placement or refute one. It cannot enumerate a
  drop table.** In particular, `community_center/mayor/diana/` holds only the six spade and
  blade models, so the pack cannot police Diana's table.

### 12.4 Why not the Fandom wiki

`hypixel-skyblock.fandom.com` is the obvious source and **it does not work**:

- `curl` with a browser user-agent → **HTTP 403**.
- through a fetch proxy → **HTTP 402**.

Fandom blocks automated access outright. Do not spend an hour re-confirming that.

**`https://hypixelskyblock.minecraft.wiki` is not blocked, and it carries per-mob and
per-chest drop tables with exact chances.** The catch is that it 403s `curl` and
`Invoke-WebRequest` as well — it is reachable only through the agent `WebFetch` tool, which
renders the page. 24 pages were pulled that way in one session with no failures. Paired with
NEU lore, that is enough to rebuild any single table.

The official `wiki.hypixel.net` shut down in July 2026 and must not be used (§11).

### 12.5 How to re-verify one source's table

1. **Name the source.** Find its entry in `LootSourceRegistry` and read its jackpot list.
2. **Pull the drop table.** `WebFetch` the mob, chest or mechanic page on
   `hypixelskyblock.minecraft.wiki`. Budget one page per source, plus one per sub-boss for the
   grouped sources — `CRIMSON_MINIBOSS` needs Ashfang, Mage Outlaw, Bladesoul and Barbarian
   Duke X.
3. **Resolve every name against NEU.** Normalise the way the mod does (`TextClean.clean`, then
   lower-case) and look the result up in the `displayname` index built from `items/*.json`. A
   name resolving to nothing is fake. A name resolving to a *mob*, a *pet* or a *currency* is
   not automatically fine — that is how `Lord Jawbus`, `Slug` and `El Dorado` got in.
4. **Cross-check placement against the pack** wherever a path exists. A pack path under a
   different content area than the source claims is a real conflict and needs a wiki ruling.
   `Warty` is the open example: the pack files it under `island_relevant/foraging_2`, while
   NEU's lore points at Nether Wart farming.
5. **Give every new name a `drop_symbols.json` row**, keeping the per-source uniqueness rule in
   §5.1: two drops from one event must not land on the same sprite.
6. **Run the checks.** `DropSymbolsMcTest` for sprite collisions, plus the item-name check
   below.

### 12.6 The build-time name check

The lesson of this bug is that a fake item name must not survive a green build. The check walks
every name the mod ships — the `drop_symbols.json` keys, every jackpot list in
`LootSourceRegistry`, `FillerStrip.GENERIC` — and **fails the build on a name that is not a
real SkyBlock item**, matched against a snapshot of NEU display names checked into this
repository so the test needs neither the network nor a game install.

Two things to know about it:

- **It is a spelling check, not a placement check.** It proves the item exists. It cannot prove
  the item drops from that source — `Hunter Ring` is a perfectly real item that sat on the
  wrong table for months. Placement is still steps 2 and 4 above, done by hand.
- **The snapshot goes stale.** Regenerate it from a current NEU checkout when Hypixel adds
  items. A name that vanishes upstream is not automatically a defect: `Bag of Cash` is a real
  item, spelled right, that Hypixel removed from every drop table in May 2021 and that can
  therefore never roll.

If that check is not in your tree, it has not landed yet, and everything in this section is
being enforced by hand.

### 12.7 Sources that are still unverified

Written down so the honest state lives on the page rather than in a chat log. The 2026-08-31
audit resolved 373 of 476 name spellings to a real NEU item and left the rest below.

**Empty jackpot list — these scroll only `GENERIC`, so they are honest rather than wrong**
(20 sources): `SLAYER_MINIBOSS`, `DUNGEON_RUN_COMPLETE`, `ENDSTONE_PROTECTOR`, `VANQUISHER`,
`BROODMOTHER`, `HEADLESS_HORSEMAN`, `RIFT_BOSS`, `COMBAT_SHARD`, `FOSSIL_EXCAVATION`,
`EXPERIMENTS_REWARDS`, `SPOOKY_CHEST`, `FISHING_SEA_CREATURE`, `FISHING_TROPHY_FISH`,
`GARDEN_CROP_FEVER`, `MINING_PRISTINE_GEMSTONE`, `MINING_COMPACT`, `YEAR_OF_THE_WITCH_STEW`,
`RIFT_UBIK_SPLIT_OR_STEAL`, `RIFT_MOTES_ORB`, `RIFT_VERMIN_VACUUM`. `SPOOKY_CHEST` is the
cheapest to fill — Ectoplasm plus the Trick or Treat Chest table — and `ENDSTONE_PROTECTOR`,
`VANQUISHER`, `BROODMOTHER`, `HEADLESS_HORSEMAN` and `FOSSIL_EXCAVATION` each have a wiki page
nobody has pulled yet.

**Genuinely unknown. Do not guess a second time:**

| Source | Why it is unknown |
| --- | --- |
| `CROESUS_CHEST` | The Croesus page does not enumerate contents and every Catacombs floor has its own table, so a correct list needs F1–F7 and M1–M7 data that neither NEU nor the pack carries. Its current list is two *chest tiers* — the container, not the loot. |
| `DRACONIC_SACRIFICE` | The mechanic itself could not be established. Its two entries duplicate `ENDER_DRAGON`'s. |
| `MOB_RARE_DROP` | "Rare Mob Drop" is a category, not a mob, so there is no table to verify against. All five entries are borrowed from named sources; it probably should not carry a jackpot list at all. |
| `LOOT_CHEST` | Undefined scope. Its list is the first five entries of `POWDER_CHEST` copied verbatim, so two sources scroll identical reels. It needs a definition before it can be given a table. |
| `POWDER_CHEST` | Believed correct — all 18 entries resolve and NEU lore backs each one — but the Crystal Hollows Treasure Chest wiki page 404s, so no drop table confirmed it. |
| `WINTER_GIFT` | Only the Snow Suit pieces are confirmed. The authoritative per-gift-tier list was not found. |
| `CARNIVAL_FRUIT_DIGGING` | The wiki confirms eight fruits — Dragonfruit, Mango, Coconut, Apple, Cherry, Pomegranate, Durian, Watermelon — and **none of the eight exists in NEU or in the pack**, so no sprite can be resolved from either local source. |
| One-entry lists | `GLACITE_MINESHAFT_PORTAL`, `SUSPICIOUS_SCRAP`, `YEAR_OF_THE_PIG_ORB`, `GARDEN_VERY_RARE_CROP` — each single entry is real, but a one-entry list cannot be checked for completeness. |

**Two intentional non-items, recorded so a later reader does not "fix" them into items.**
`GLACITE_MINESHAFT_PORTAL` pays `Glacite Mineshaft`, a location, because the payout genuinely
is *you found a mineshaft*. And `Coins` is a legitimate Diana jackpot outcome because Hypixel
prints `You dug out 2,500 coins` verbatim.

**Diana is clean.** All 18 `DIANA_MYTHOLOGICAL` jackpot names were checked against the
Mythological Ritual table and are correct in substance. The one open question there is
rendering, not naming: `Chimera` is an *enchantment*, so the drop Hypixel prints is
`Enchanted Book (Chimera I)` and the sprite belongs on a glinted book. Diana's 260 core tests
must keep passing unmodified.

**A caveat covering every table on this page.** Several fishing tables pulled during the audit
clearly post-date the mod's data — Sea Lumies, Mangcore, Bobbin' Scriptures, Emperor's Skull
and Thunder Fragment are all absent from the mod. A table verified once is verified as of that
date and no later.
