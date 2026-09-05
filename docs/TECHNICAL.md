# SkyZH — Technical Documentation

> **This is developer-facing technical documentation.** It describes how the translation
> engine works internally — render surfaces, mixins, the corpus format, and pixel
> measurement for wrapping and centring. If you are a player looking for what the mod does
> and how to install it, read the [main README](../README.md) instead.

[中文技术文档](TECHNICAL_zh-CN.md) · [English](TECHNICAL.md)

A Chinese translation layer for Hypixel SkyBlock. Minecraft 26.1.x and 26.2 / Fabric, client only —
one jar per Minecraft, from one source tree. See [Building](#building).

SkyBlock has no official Chinese, so Chinese-speaking players either work through the English or
watch friends bounce off a screenshot they can't read. This mod replaces the text on screen so that
someone who has never played is willing to keep reading, and a new player doesn't have to learn
vocabulary before learning the game.

## It does not break other mods

Translation happens **at the moment text is drawn**, and nothing else changes:

| What another mod reads | State |
|---|---|
| An `ItemStack`'s name and lore components | Original English |
| Chat history `allMessages`, clipboard copies | Original English |
| `Screen#getTitle()` | Original English |
| `BossEvent#getName()` | Original English |
| The HUD's `overlayMessageString` / `title` / `subtitle` | Original English |
| `Objective#getDisplayName()` and the `Scoreboard`'s entries | Original English |

Every mod that parses chat does so in the packet handler or through Fabric's chat events, both of
which run *before* this mod's hooks, so SkyHanni and SkyBlocker keep seeing exactly what Hypixel
sent. That is the design goal, not a side effect: a translation mod that broke SkyHanni would be a
worse deal for most SkyBlock players than reading English.

Every hook is `require = 0`. If a Minecraft update moves a method, or another mod claims the same
instruction first, the result is "this surface stops being translated", not "the modpack won't boot".
The startup log prints how many records loaded per surface, which is how you tell "not translated
yet" from "not working".

One optional path does read packets, and only when a switch that is off by default is turned on: the
capture described [below](#runtime-capture-which-is-off-by-default), which writes text the corpus
cannot answer for to a file. It changes no packet, no game state and no pixel. It does send exactly
one thing, and only with that switch on: a subscription to Hypixel's own location event, described
[below](#the-one-thing-that-is-sent).

## The three known problems, and how each is handled

**Centring drifts.** Hypixel centres text by padding it with spaces, counted in English pixels, so
Chinese leaves a gap down the right. The padding is discarded and recomputed. For container titles,
the boss bar, the action bar and the title/subtitle the mod picks the draw coordinate itself, so this
is exact to the pixel. Chat banners can only be padded with spaces (4px each, so within ~2px) —
which is also the accuracy Hypixel's own centring trick achieves in English.

**Ragged lore.** Lines stay left-aligned exactly as in English; that is a deliberate choice, not an
omission. The real problem is elsewhere: a sentence Hypixel broke across two lore lines is stored as
one record, the trailing line is removed at render time, and the reunited Chinese is re-broken by
measured pixel width (CJK breaks almost anywhere, but never leaving closing punctuation stranded at
the start of a line). If the first line is still untranslated the second is left alone — half Chinese
followed by half English is the worst possible outcome.

**Colours drift or vanish.** A SkyBlock line often changes colour several times, at boundaries that
have nothing to do with sentence structure. The corpus splits such lines into `segments`, each with
its own translation, and the renderer **reads each segment's colour off the live text** rather than
trusting the colour codes recorded in the data — a record is a snapshot and goes stale on a Hypixel
update, while the line being drawn right now never does. Placeholder captures (player names, item
names, numbers) are copied across with their own colours untouched. When a record turns out to be
multi-coloured in game but flat in the data, the log names it so a `segments` array can be added, and
rendering continues in the first colour meanwhile.

### The action bar is a row of widgets, not a sentence

The line across the middle of the screen is SkyBlock's own HUD: health, the place, mana, defence, and
whatever experience notice has just pushed its way in, laid side by side with five spaces between
them —

```
2,610/2,235❤     ⏣ The Lift     469/469✎ 400⸕     104/104♨
```

Looked up whole it matches nothing, because every number in it changes as the player walks: two
frames apart it is two different "sentences". So it is split on the wide gaps (three spaces or more)
and each widget is looked up on its own, with the gaps put back exactly as they arrived — that is
SkyBlock's own layout, measured in its own spaces, and the Chinese widgets sit where the English ones
did. The whole line is tried **first**, so a record that spells one out still wins, and a single
action-bar message the server centred is unaffected.

The capture uses the same split. Before it, one session produced eight hundred records that differed
only in numbers; after it, a dozen short widgets, half of which are a number and an icon with no word
in them at all and are not captured.

### The sidebar, and its shimmer

The sidebar is the one surface in the game that genuinely animates: Hypixel re-sends the objective's
display name every tick, the plain text always `SKYBLOCK` and only the per-character colours moving —
a highlight travelling across the letters. Two things keep it:

- **Rendered lines are never cached.** Only *which record matches a piece of text* is cached, and
  that text is what stays the same tick to tick, so the cache is right and the colouring is redone
  every frame.
- **Colour is spread per character.** Eight letters become four characters; taking one colour for all
  of them would turn a travelling highlight into the whole word blinking. Each character takes its
  colour from the letters it stands in for, preferring one that differs from the word's prevailing
  colour — so a highlight one letter wide is never lost in the gap, it just rests two frames per
  position.

The hooks are also on the *source* of the text rather than the draw call, because
`displayScoreboardSidebar` measures the title and every row to decide the panel's width, its left
edge and where the centred title starts — and does so after reading them. Translating at the source
means those measurements are of the Chinese.

## Settings

Mod Menu is a soft dependency. Without it, edit `config/skyzh.json`, which documents itself.

| Option | Default | Meaning |
|---|---|---|
| `enabled` | on | Master switch |
| `translateSkyBlockName` | on | Render "SkyBlock" as 空岛生存. Compounds use the short form and get their spacing fixed: `你的 SkyBlock 等级` → `你的空岛等级`; standalone occurrences keep the full name. See below for where the substitution is allowed to happen |
| `showOriginal` | on | Keep the English beside the Chinese, as 收藏品（Collections）. Applies to container titles, item names, and to names that appear inside chat NPC lines or item lore (象牙化石（Tusk Fossil）). Hold **X** to show every surface in English temporarily — capture is not turned off |
| `captureUntranslated` | **off** | A switch for whoever is filling the corpus in. It writes files to your disk; leave it off to play. See below |

### Where "SkyBlock" is swapped, and where it is not

The word turns up inside sentences nobody has translated, which makes it the one place this mod can
produce the thing it exists to prevent: one Chinese word stranded in an English line. So on a line
the corpus does **not** cover, the swap only happens when "SkyBlock" is the only English word there.

| Line | Result |
|---|---|
| `SKYBLOCK` (the sidebar title) | 空岛生存 |
| `SkyBlock Level 42` | unchanged — `Level` is still English |
| `You unlocked SkyBlock XP!` | unchanged |
| `SKYBLOCK CO-OP` | unchanged — see `TODO.md` |

On a line the corpus **does** cover, the swap runs over the finished translation, so a record whose
Chinese keeps the word — `"SkyBlock 菜单"` — comes out as 空岛菜单 with the switch on and
`SkyBlock 菜单` with it off. That is the intended way to make a particular line translate sooner:
write the record, keep the word in it, and the option keeps working.

### The icons, which are drawn from a font

SkyBlock used to write its stat icons as ordinary characters — `❤ Health`, `☘ Mining Fortune`, `⸕`
for Amber — and now sends private-use codepoints instead, rendered from a font in the server's
resource pack. The corpus is collected from sources that disagree about which: the wiki records the
old symbols, NEU-REPO carries whatever Hypixel sent the day it was scraped.

A record spelled one way never matches a line spelled the other, and nothing about that is visible —
no warning, no malformed data, just a sentence that stays English. Both sides are therefore folded
onto one spelling before anything is compared, and the icon is put back **as the server sent it**
when the Chinese is drawn: the icon on screen is still SkyBlock's, only the words changed.

### The markers hidden inside words

A scoreboard's entries have to be distinct strings, so Hypixel makes them distinct by hiding a `§`
and a letter inside the text — `Dwarven M§qines`, `W§qind Compass` — at a different place on each
row. `q` names no colour, and that is the point: the game's renderer drops `§` and whatever follows
it whether or not the pair means anything, so the marker is there in the data and invisible on
screen.

Minecraft's own `ChatFormatting.stripFormatting` does **not** do the same — it removes the codes with
names and leaves the rest — and a mod that reads text with it is reading two characters no player can
see. That single difference is worth spelling out because of how much it broke: sidebar rows never
matched a record and stayed English, and every captured line was filed under a place called
`Dwarven M§qines`. Everything in this mod that flattens text now goes through one reading,
`StyledText.plainOf`, which is the renderer's.

## The corpus

Translations live in `original_text/`, split by gameplay category and by the surface they are drawn
on; the format is documented in `original_text/README.md`. The mod reads those files directly — they
are shipped verbatim, with no intermediate build format — so a wrong line on screen leads straight
back to one file and one `id`. An empty `zh` means "not translated yet" and leaves the line in
English; it never blanks it.

After editing the corpus, check it without launching the game:

```bash
./gradlew checkTranslations
```

This runs the real matching engine over the real files and verifies colour reconstruction,
placeholder handling, indentation, surface isolation and more. The only thing it cannot cover is
anything needing a `Font` — wrapping and centring measure pixels, and those are checked in game.

The other half of the question — not "is this record right" but "what did a real session show that
the corpus has never heard of" — is answered by pointing the same engine at a client log:

```bash
./gradlew auditLog                        # everything under logs/
./gradlew auditLog -Plog=logs/latest.log  # one file
```

Minecraft writes every chat message it receives to `logs/`, so this reads a file that already
exists. It prints the lines no record answered for, most-frequent first.

## Runtime capture, which is off by default

A log only holds chat. **What the items inside a menu an NPC opens are called, and what their lore
says**, is in no log, is only partly on the wiki's `/UI` subpages, and is not in NEU-REPO at all —
that text exists nowhere except on the screen of somebody playing. `captureUntranslated` exists for
that gap: switched on, server text the corpus cannot answer for is written to `skyzh-capture/` in the
game directory. **It changes nothing that is drawn.** It reads and never writes.

### Why other mods' text cannot get in this time

An earlier attempt at this feature was abandoned over exactly this, and could not be salvaged: once
text has been drawn it is just text, and a line SkyHanni rendered is indistinguishable from a line
Hypixel sent. **The mistake was where it hooked, not how it filtered.**

Every capture point is now a *packet handler*, or a read of state only a packet can write:

| What | Where | Why no mod's text can arrive |
|---|---|---|
| Chat (system and NPC) | `ClientPacketListener#handleSystemChat` HEAD | Earlier than Fabric's chat events and than SkyHanni or SkyBlocker. A mod's own message goes in through `ChatComponent#addMessage` and **never passes this method** |
| Container title | `handleOpenScreen` HEAD | The `title` field of the server's packet |
| Item name and lore | `handleContainerContent` / `handleContainerSetSlot` HEAD | Reads the stack's `CUSTOM_NAME` and `LORE` components, **not** `getTooltipLines()` — that list is where every mod adds its own lines |
| Sidebar | The `Scoreboard` object, every 10 ticks | The same server state vanilla renders from. A useful side effect: **it keeps working when SkyHanni's Custom Scoreboard has replaced the sidebar's rendering entirely** |
| Tab list | `handleTabListCustomisation` for header and footer, player entries every 10 ticks | As above |
| Boss bar | `BossHealthOverlay#update` TAIL | That map is written by the packet handler and by nothing else |
| Action bar, title, subtitle | `setActionBarText` / `setTitleText` / `setSubtitleText` HEAD | As above |

There is deliberately **no filter by name**. `[Bazaar]` and `[Sacks]` are Hypixel's own prefixes, and
a blocklist of mod-shaped tags would throw away real SkyBlock text to catch something that cannot
arrive anyway.

Two more guards: the server address has to match `captureServer` (`hypixel.net` by default, empty to
skip the check), and the sidebar's title has to say SKYBLOCK. Neither satisfied, nothing is kept.

### The one thing that is sent

With capture **on**, and only then, and only if the [`hypixel-mod-api`][modapi] mod is installed,
SkyZH subscribes to Hypixel's own location event — one register packet, sent by that mod's plumbing,
naming the event it wants. In return Hypixel says which island the player is on the moment they
arrive, which is the answer to "which gameplay folder does this text belong in" straight from the
server instead of read off a sidebar drawn for a human. The library is compiled against and never
bundled; without that mod the integration does not load and the other two readings carry on.

With capture **off** — every ordinary player — nothing is subscribed and nothing is sent. That half
of the mod remains what it says on the tin: a client running SkyZH is, as far as Hypixel and as far
as every other mod is concerned, a client running in English.

[modapi]: https://github.com/HypixelDev/ModAPI

### What comes out

Three piles, each laid out the way `original_text/` is — gameplay, then surface, then name:

```
skyzh-capture/
├── untranslated/          no record answered for these
│   ├── Mining/NPC_Message/Fragilis.json
│   ├── Mining/GUI_Item/Commissions.json        ← every slot of that menu, names and lore
│   └── _Unknown_Gameplay/ScoreBoard/Sidebar.json
├── mixed/                 a record answered and the line still came out half English
│   └── Mining/ChatMessage/Server_Messages.json
└── colour/                a record answered, every word is Chinese, the colours are flattened
    └── Mining/ScoreBoard/Sidebar.json
```

The third pile is the one that is hard to find any other way. A record that recorded a line as one
flat run, when the server actually changes colour partway through it, draws the whole translation in
whichever colour came first — a white `(+` beside a gold `5`, a white snowflake beside a blue number.
The startup log can say *that* a record does this and can never say *where* the colour changed; the
capture file can, and writes out the `segments` array split at the server's own boundaries, ready to
paste into the record it names.

Only what is *drawn* counts as a colour here. Hypixel splits a line on a tooltip as readily as on a
colour — the sacks message sends `" items"` and the `"."` after it as two runs of the same yellow,
the first carrying an "Added items:" hover — and judging that by whole-`Style` equality filed a
record whose `segments` were already correct into this pile, advising a split at a boundary with no
colour on either side of it. So the comparison is over colour, font and the five format flags.

The gameplay comes from where the player was standing, through `assets/skyzh/capture/areas.json`; an
area nobody has classified lands in `_Unknown_Gameplay`, which is fixed by adding a line to that file
rather than by changing code.

Where the player is standing is read three ways, in order: the sidebar's `⏣` row, then Hypixel's Mod
API if that mod is present, then the tab list's `Area:` row. The order is not arbitrary — the sidebar
names the corner of the island rather than the island, so it is the most specific answer, and it is
also assembled out of a team's prefix and suffix and so the last part of a warp to settle, which is
exactly when the other two are already answering. A line captured
in the seconds before either answers is **held**, not filed: it waits up to eight seconds for the
sidebar and is then written under the area that arrived, which is the area it came from. Only a
sidebar that never answers at all puts anything in `_Unknown_Gameplay` — and when that happens the
log gets the sidebar and the tab list printed out verbatim, invisible characters spelled as
`\uXXXX`, because a folder of unknowns cannot otherwise be told apart from a table missing a line.

Each record is in the corpus's own format — colour codes kept, `segments` split at the real colour
boundaries, `zh` left empty. Promoting one is deleting its `_capture` block, checking the
placeholders and writing the Chinese.

### It says so in chat

Part of the same switch, with no separate option. Captures are grouped **by the file they are written
to** and announced one second after the last one arrives:

```
[SkyZH] 采集 43 条 · Mining / GUI_Item / Commissions  在 Dwarven Mines
        未翻译 41、中英混杂 2  [打开未翻译]  [打开中英混杂]
```

The place at the end is where the sidebar said the player was, and it appears only when that is not
the same as the gameplay folder. `Hub_General` is a folder, not a place — the Hub goes there and so
does a private island and a museum — so a message naming only the folder reads as "you did this in
the Hub" wherever it actually happened.

Grouped, because a SkyBlock menu arrives as one packet holding fifty-four items with a dozen lore
lines each: a message per line would put forty of them on screen at once and push everything else out
of a chat box that shows ten. Chat text arrives a line at a time anyway, so grouping costs it
nothing — a batch of one goes quiet immediately. The file name is clickable and opens the JSON in
whatever the system uses for it; the full path is in the tooltip. A capture that never stops is
announced anyway after five seconds rather than accumulating forever.

The line is added as a *client* system message rather than dressed up as one from the server. It
never passes a packet handler, so the capture cannot see its own announcements — but Minecraft does
write it to `logs/latest.log`, so `auditLog` skips anything opening with `[SkyZH]` rather than
reporting this mod's own output back as untranslated SkyBlock text.

### The judgements it makes for you

**Lines that differ only in a number or a name are merged into one template.** This was the second
reason the last attempt was dropped — a capture cannot tell a fixed word from a value — and the
answer is that one sighting cannot but several can. A position is only made a placeholder once it has
been **seen to hold two different values**, every value ever seen is written out under
`_capture.observed` so the decision can be checked, and a position seen holding one value is left
alone. The cut may not fall inside a number (`x23` and `x24` give `x%s`, never `x2%s`) or inside a
word (`Force` and `Axe` are not cut down to `Forc` and `Ax`), and a merge is refused outright when it
would leave more placeholders than words — that is a grammar, not a sentence.

**A line already written down under another menu is not written down again.** Every SkyBlock menu
carries the same back button and the same boilerplate, every page of a guide repeats the same
paragraph, and **the last thirty-six slots of every container packet are the player's own backpack**,
re-sent each time a menu opens. Filed per menu, 5,208 of one session's 9,758 records were copies of a
record in the folder next door. The backpack now goes to `_Inventory`, a line seen in a second menu
keeps the record it already has, and the other menus are listed under `_capture.also_seen` — which is
exactly the signal that the line belongs in `_shared/`. Deduplication is per gameplay: filing a
Foraging line into a Mining folder is contamination nobody would spot afterwards.

**Other people's names stay out.** Hypixel draws a guild icon between the level and the rank tag
(`⸕`, `ᛝ` — some of them are letters), and hangs AFK and party symbols off the end of a name. Those
symbols used to defeat the "this row is just a player" test, and one session wrote three strangers'
sentences and fifty strangers' usernames into the output.

**Text the server already sent in Chinese is skipped.** Hypixel translates some of its own messages;
those are not this mod's to collect.

**A record that nearly matched is named.** A line goes untranslated, the corpus *does* contain that
sentence, and the two differ only in a character nobody can see — a private-use icon, a non-breaking
space. That is the most expensive class of bug in this project, because the screen, the log and the
data file all look right. Such a line now gets a `near_miss` block naming the record and the file it
is in, with both sides printed with their invisible characters spelled as `\uXXXX`.

### Checking it

```bash
./gradlew checkCapture
```

Runs the whole capture path over text a real session produced: colour reconstruction, template
merging, speaker detection, which pile a line lands in, and the directory it is written to.

### Reading the capture back

Once records have been written for what a session captured, the question becomes "did that work",
and the capture files answer it — they still hold every line verbatim, colour codes and icon glyphs
included:

```bash
./gradlew replayCapture                    # everything under logs/skyzh-capture
./gradlew replayCapture -Pcapture=<dir>    # one gameplay, one surface, one file
```

Each line is printed with what it now draws as. This catches what `checkTranslations` structurally
cannot: that check knows only what the corpus says about itself, so a record whose segments are in
the wrong order, or whose hand-written space lands between two Chinese characters, or whose value
nobody added to `_shared/Terms.json`, passes it and still looks wrong on screen. Here it is one line
of output.

## Building

```bash
./gradlew dist     # both jars into build/dist, after running every check on both
./gradlew build    # the same jars, into each target's own build/libs
```

Requires JDK 25. Minecraft 26.x ships unobfuscated, so there is no mapping layer, mods are ordinary
dependencies, and Loom has no `remapJar` step — `jar` produces the installable file.

### Two Minecraft versions, one source tree

| | jar | Minecraft it declares | Mod Menu compiled against |
|---|---|---|---|
| `fabric-26.1/` | `SkyBlockZH-<version>-Beta-26.1.jar` | `>=26.1 <26.2` | 18.0.0 |
| `fabric-26.2/` | `SkyBlockZH-<version>-Beta-26.2.jar` | `>=26.2 <26.3` | 20.0.1 |

Both targets compile `src/main/` — the engine, the corpus loader, the capture, nine of the twelve
mixins — and add one small tree of their own, `src/mc26_1/` or `src/mc26_2/`. Everything about a
target other than its version numbers lives in `gradle/target.gradle`, so the two cannot drift in how
they package the corpus or which Java release they compile for.

Only four things differ between the two Minecrafts, and each one is a file in the version tree:

| What moved | 26.1.x | 26.2 |
|---|---|---|
| The class that draws the HUD | `Gui` | `Hud`, split out of `Gui` |
| The player entity type constant | `EntityType.PLAYER` | `EntityTypes.PLAYER` |
| `SubmitNodeCollector#submitNameTag` | takes a trailing `double` | does not |
| Who owns the chat box and the screen stack | `Gui#getChat`, `Minecraft#setScreen` | `Gui.hud#getChat`, `Gui#setScreen` |

The last row is `platform/ClientGui`, one copy per target with the same signature. The first three are
`mixin/HudMixin`, `mixin/HudScoreboardMixin` and `mixin/EntityRendererMixin` — the annotations only;
what they do when they fire is shared code in `hook/`, written once.

One jar covers all of 26.1, 26.1.1 and 26.1.2: every class this mod touches is byte-identical across
the three. Both ranges are closed at the top on purpose. Installing a jar on the Minecraft it was not
built for would not crash — it would leave the HUD, the sidebar and every hologram silently English,
because every injector here is `require = 0` — so the range is what stops Fabric from loading it at
all.

### Checking that the injectors still apply

```bash
./gradlew checkMixinTargets     # part of `check`, run for both targets
```

This is the check `require = 0` makes necessary. It reads the annotations back out of the *compiled*
mixins — the real `@Mixin` value, the real `method` list, the real `At.target` — and resolves every one
of them against the Minecraft actually on that target's compile classpath, including `@Shadow` members,
which are not `require = 0` and crash at apply time rather than failing quietly. Compiling proves the
mod's source agrees with itself and proves nothing at all about whether the injection points still
exist. See `gradle/check_mixin_targets.py`.

To check the shipped classes against a Minecraft the build is not currently pointed at — which is how
`>=26.1 <26.2` was established rather than assumed:

```bash
./gradlew :fabric-26.1:build -Pmc26_1_version=26.1.2   # compile the 26.1 tree against a later patch
```

## Author

BingKKni (小布丁)
