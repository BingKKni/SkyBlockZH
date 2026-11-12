# SkyZH — Technical Documentation

> **This is developer-facing technical documentation.** It describes how the translation
> engine works internally — render surfaces, mixins, the corpus format, and pixel
> measurement for wrapping and centring. If you are a player looking for what the mod does
> and how to install it, read the [main README](../README.md) instead.

[中文技术文档](TECHNICAL_zh-CN.md) · [English](TECHNICAL.md)

A Chinese translation layer for Hypixel SkyBlock. Minecraft 26.1.x, 26.2 and 26.3 / Fabric, client only —
one jar per Minecraft, from one source tree. See [Building](#building).

SkyBlock has no official Chinese, so Chinese-speaking players either work through the English or
watch friends bounce off a screenshot they can't read. This mod replaces the text on screen so that
someone who has never played is willing to keep reading, and a new player doesn't have to learn
vocabulary before learning the game.

## Compatibility at the default priority

The default `displayPriority` preserves the behavior below. The opt-in `high` and `force` modes
prioritize translations; both bypass some utility-mod display changes while still preserving server data.

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

Runtime translation entries check `HypixelServer.canTranslate()` before translated caches, term-table
fallbacks, wrapping or centring. Original-mode tooltips still enforce the server/master boundary and
check item-name coverage solely for the restore-translation hint, without translating or reflowing lore. The address is deliberately not checked: accelerators commonly expose
an IP or local relay instead of `hypixel.net`. Two independent pieces of live server state are required
instead — Hypixel identifying itself on this exact connection (its official inbound `hypixel:hello`
payload, or the standard `minecraft:brand` payload naming `Hypixel BungeeCord`, the same signal
SkyBlocker checks), and the SkyBlock sidebar objective — matched by its internal name `SBScoreboard`,
the identifier SkyHanni keys on, rather than by the display title, which carries a travelling
highlight, an icon and `CO-OP`/`GUEST` suffixes that any restyle can change. The first excludes unrelated and SkyBlock-style
servers; the second excludes other games on Hypixel. Both payloads are read off the packet's own
`handle`, not off the listener: Fabric API cancels the listener method for every payload type some mod
has registered, and `hypixel-mod-api` registers the hello, so a listener hook never sees it on a client
running SkyHanni or SkyBlocker. Other servers, singleplayer and the main menu keep vanilla text and
coordinates. Entering/leaving SkyBlock also rebuilds wrapped chat to remove stale translations, and
changing the network connection clears both pieces of evidence before any new text can pass. The pure
corpus engine and `locate` stay server-independent for offline checks and capture classification.

Every hook uses `require = 0`, so a missing injection point can leave that surface untranslated
without failing the minimum-injection check. The startup log prints how many records loaded per surface.

Hooks that wrap calls use MixinExtras `@WrapOperation`, not `@Redirect`. Redirects compete for exclusive
ownership of an instruction; losing that conflict can make a required hook fail at startup.
`@WrapOperation` supports chaining with other hooks, including redirects. `checkMixinTargets` checks
the configured mixins for `@Redirect`.

Original-key hints observe incoming server chat without modifying or sending packets; rebuilding chat
history cannot emit another hint. The separate, default-off [capture](#runtime-capture-which-is-off-by-default)
path writes uncovered text to files without changing packets, game state or pixels. Only capture may send
a subscription to Hypixel's own location event, described [below](#the-one-thing-that-is-sent).

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

- **Display results are cached by complete text and live styles.** Colour, font, interaction styles
  and line boundaries must all agree. A colour-only update produces fresh output, preserving the
  shimmer. Caches are bounded and invalidated by configuration/resource changes.
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
| `enabled` | on | Master switch. Turning it off also prevents update checks |
| `updateCheck` | on | Automatically check and download Mod and translation updates in SkyBlock. Translations apply immediately; Mod installation waits for exit. `/skyzh updatecheck on/off` controls automatic updates; no argument checks Mod then translations, while `mod` / `translate` checks only that target. Explicit checks bypass this setting and cooldowns, but still require the master switch and SkyBlock. All aliases share one busy guard |
| `updateChannel` | `release` | Which Mod builds updates look for: formal Releases, or the per-commit `beta` builds from Actions. The settings page merges it with `updateCheck` into "Off / Release / Beta" |
| `updateInstall` | `auto` | After a newer Mod is found: `auto` downloads and installs on exit; `manual` downloads and posts `[点击安装]`; `notify` only posts `[点击下载并安装]`. Both repeat on every check (about hourly), replacing the download when a newer build appears. Translations are unaffected |
| `translateSkyBlockName` | on | Render "SkyBlock" as 空岛生存. Compounds use the short form and get their spacing fixed: `你的 SkyBlock 等级` → `你的空岛等级`; standalone occurrences keep the full name. See below for where the substitution is allowed to happen |
| `translateItemNames` | on | Keep physical item names and explicitly marked references in English when off; surrounding text stays translated. The choice refreshes existing chat and tooltips without changing source data or capture coverage |
| `translateMobNames` | on | Keep mob names in English when off: health-bar names, `mob_name` placeholders, `raw`/`category_name` values and tab rows found in the `mob_name` terms, and fixed references marked with `mob_refs`. Placeholders that borrow a mob name for a pet, rabbit or shard say `"mob": false`; `not_creatures` in `Terms.json` lists words that mean a class, faction or pet in other placeholders |
| `legacyFont` | on | "Text spacing": layer the bundled 1.12.2 Unicode glyphs and widths (`skyzh_legacy_font/`, equivalent to a top resource pack) over the client resources, so commas and full stops keep their 1.12.2 widths. Off uses vanilla full-width glyphs. Changing it reloads resources when the settings page closes |
| `originalTips` | on | "Translation hints": append a current-key hint to translated real-item tooltips in GUIs. Incoming server chat mentioning eligible translated items may show a dismissible hint, at least five minutes apart. `ItemNames` uses the bundled offline NEU catalog, excluding menus, states and unknown names. `/skyzh switch tip on/off` (also `/skyblockzh`) controls hints, not the original-text toggle. The old `showOriginal` field is ignored and no longer saved; missing `originalTips` defaults to on. Normal rendering no longer appends bracketed originals |
| `helpImproveTranslation` | on | Reserved state retained for old config compatibility; no current screen control or reporting feature |
| `captureUntranslated` | **off** | A switch for whoever is filling the corpus in. It writes files to your disk; leave it off to play. See below |
| `captureNotifications` | on | Report newly captured untranslated text, colour errors and mixed-language text in chat. Turning reports off does not stop file writes |
| `autoClearCapture` | **off** | Clear the previous captures once per client launch, whether or not capture is enabled or a server is joined. Reconnecting never clears files |

The settings page uses MoulConfig (also used by SkyHanni and Skyblocker's default settings): a "SkyZH <version> Beta" title bar, categories on the left (General, Translation,
Advanced), and a search field at the top right that filters by option name and description and hides empty categories. Each
option is a card with a toggle or dropdown on the left and its description on the right. Chinese descriptions reuse `TextLayout` wrapping without shrinking the font; "Update installation" appears only while update checks are
on. Display priority and capture are collapsible blocks in Advanced. With the master switch off, every other option is greyed out.
Old configs missing these keys use the defaults above. The mod does not need Fabric API: `ResourceManagerMixin` adds the mod's own
`assets/` above vanilla on each client resource reload, and the font pack on top when `legacyFont` is on. On a fresh installation, a welcome chat message is sent once after the client has confirmed entry to Hypixel. Its internal `welcomeShown` state can be reset to `false` to send it again on the next entry. `/skyzh` (also `/skyblockzh`) and Mod Menu share the settings page, with the welcome text and GitHub link at the top of General. Startup clearing
runs once in Fabric's client initializer, before the main menu, through the exact `TextCapture.clear()`
path used by `/skyzh clear`: in-memory state, queued work and JSON files in the six capture buckets
are cleared; unrelated files remain. Failures are logged without preventing startup.

The original-text toggle is a vanilla `KeyMapping`, appended idempotently by `OptionsMixin` before
`Options.load` reads `options.txt`. Vanilla owns persistence, conflicts, reset and NONE. The default X
switches to originals on one press and restores translation on the next; the old `HoldOriginal` class
and key ID remain for saved-binding compatibility. Keyboard/scancode events ignore repeats/releases;
mouse bindings use physical rising edges. Text entry, key rebinding and an unfocused window reject new
presses without clearing the selected mode. Toggle before opening chat to read old messages. World changes, temporary scoreboard gaps and leaving
SkyBlock preserve the selected mode; only disabling the mod or unbinding clears this session-only state, without saving or changing capture.
Accepted user toggles play `ui.button.click` in the BLOCKS category (volume parameter 100, pitch 2) and
announce the new display state; automatic resets are silent. Eligible item tooltips show the currently
bound key and offer originals or translations according to the selected mode, retaining the hint colours.

Before drawing, chat observes the effective translation policy, including the master switch. Only
wrapped display lines are rebuilt; `allMessages` stays original and the scroll offset is retained within
bounds. `ChatDisplayCache` caches translated components by message, font, width, corpus and name settings.
Default chat still calls the splitter chain on each layout pass so custom per-line renderers are registered;
the independent High view continues caching wrapped lines. Better Hypixel Chat receives a scoped visual
translation of the current message while filters, search, history and line parents keep the original.
Grey duplicate counters from Compacting, Enhanced Chat and Stfu are preserved separately only when the
entire body matches its packet snapshot. Snapshots live beside vanilla messages; no third-party class is injected.
Toggling no longer discards translated tooltips or re-translates unchanged chat.
Vanilla rescaling, history clearing and component mutations invalidate cached layouts. A cold language
view or messages received during bypass still need work; this is not a measured low-end FPS guarantee.

```bash
./gradlew checkClientSettings  # all targets: config migration, vanilla mappings, server boundary
```

In-game checks still needed: rebind/unbind and verify persistence after a restart; toggle twice while
hovering item lore, and open chat after toggling to inspect history; check the left/right settings layout; restart to the main menu to verify startup
clearing, then disconnect/rejoin within that launch to verify the files remain.

### Display priority

Settings are grouped under General and Developer headings. Display priority cycles through Default,
High and Force without a restart. The adjacent item-name button controls physical item names and
explicit references across surfaces, refreshing existing chat and tooltips when changed.

- `default`: use display-only translation and detected chat adapters, without optional sidebar detection,
  late source matching or independent display views.
- `high`: inherit the former Force text/font protection, original item-name/lore reconstruction and
  server chat display view. Read-only public-state probes for CustomScoreboard, SkyHanni and NoammAddons
  yield to an enabled custom sidebar (also when its API is incompatible), including temporary hiding.
  Only complete known server rows are translated, retaining custom colours, icons, padding and interactions.
  Bounded sidebar sources survive another 20 ticks for cached displays. Without a custom panel, draw the
  server sidebar in the final stratum.
- `force`: include High's protection and take over the sidebar inside `GameRenderer.extract`.
  Vanilla `GuiRenderState.findAppropriateNode` checks up to 64 caller frames for the three verified sidebar
  renderer owners, rejecting their text, backgrounds, images and items before drawing the translated
  server sidebar. It does not mask a screen rectangle or block other HUDs from those mods. Vanilla tooltip
  scheduling and widget click hooks also reject operations from the replaced panel. Draw-stack scanning
  runs only when read-only probes report an enabled panel or an unknown API state; absent or disabled
  panels skip it. Filtering unwinds on normal completion or exceptions; returning to High
  restores the custom panel. Third-party settings, data and classes are never modified.

High/Force tooltip reconstruction may hide extra mod text or tooltip images. Provenance remains restricted
to server packets and HUD state, with a conservative prefix filter before complete source matching.
Replacement backgrounds follow vanilla text-background opacity. Unknown renderers, deferred work outside
verified caller chains and direct GPU rendering are not guaranteed to be intercepted.

While enabled on an identified Hypixel connection, a display history retains at most 100 messages and 128 Ki characters.
Server system messages are snapshotted at packet dispatch; client notices and signed player messages are
also retained without translation. High/Force temporarily borrow this view only during vanilla chat drawing,
click hit testing and scrolling, restoring the original display queue and scroll state even on exceptions.
It never writes `allMessages` or replays chat events. Upstream translations, counters and asynchronous
line replacement cannot change this source; filtered server messages may reappear. Vanilla wrapping,
hover/click actions and fading remain in use, with a separate scroll anchor. The original-text key changes
only the view's language. The history lives as long as vanilla's own: a world change keeps it through
vanilla's `storeState` / `restoreState`, while disconnecting, disabling the mod and clearing chat clear it;
font reloads invalidate layouts only.

All modes respect the server boundary, master switch and original-text key. `PriorityText` source records
are bounded and cleared on world/server changes, disabling the mod, changing priority or toggling custom
sidebar ownership in High. Default collects no enhanced sources. The independent
chat history continues bounded collection in Default so switching to High/Force retains recent messages.
Original-text mode retains
sources, so restoring translations does not require another server packet. Configuration changes clear
translated-output protection records. Force is not a global Fabric rendering lock: completely replaced
chat renderers, removal before packet dispatch and later layers can still interfere. Rate-limited policy
transition logs report identity/sidebar/loading/master/original gates without chat or player data.
`checkDisplayPriority` checks provenance, colour, Unicode, chat snapshots, scroll anchors, bounds and
isolation; final visuals with other mods still require in-game checks.

### Where "SkyBlock" is swapped, and where it is not

The word turns up inside sentences nobody has translated, which makes it the one place this mod can
produce the thing it exists to prevent: one Chinese word stranded in an English line. So on a line
the corpus does **not** cover, the swap only happens when "SkyBlock" is the only English word there.

| Line | Result |
|---|---|
| `SKYBLOCK` (the sidebar title) | 空岛生存 |
| `SkyBlock Level 42` | unchanged — `Level` is still English |
| `You unlocked SkyBlock XP!` | unchanged |
| `SKYBLOCK CO-OP` | unchanged — `CO-OP` is another English word |

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
on; the format is documented in `original_text/README.md`. Packaging strips translator-only metadata
and preserves runtime fields — so a wrong line on screen leads straight
back to one file and one `id`. An empty `zh` means "not translated yet" and leaves the line in
English; it never blanks it.

Dynamic templates use mandatory literal substrings to select candidates, then retain the complete
regex/capture validation and stable specificity ordering. Equivalent definitions share compiled
structures while each occurrence keeps its own id and file. Candidates are never restricted by island.
`StyledText` stores complete style runs with immutable range views. A lore plan reuses flattened lines
and per-start searches while preserving longest matches, blank boundaries and the 12-line/2048-character limits.
Literal name tags, sidebar rows, tab rows and tooltips, which vanilla rebuilds every frame, are matched
against their recorded node-by-node text, styles and child counts without flattening again; any in-place
change misses and falls back to the full key. The initial corpus loads on a background thread, and the SkyBlock
gate stays shut until it lands. In-game reloads keep the previous valid corpus and the gate active.

### Complete bundles and live reload

`original_text/_data/` owns `item-names.json`, `areas.json` and `preserved-text.json` alongside the translation records.
These item identities, area categories and preserved-name/profile references share the jar's minifier and the bundle exporter.
Both sources use `TranslationLoader.compile`; auxiliary tables belong to the index generation. Each queued capture stores its admission
generation, which background processing pins until completion. Minecraft still owns the Mod's standard language resources.

Run `python tools/export_translations.py` (JDK 25 and this project's Gradle environment required) to create and check
`build/translations/translations.zip`. `--output <file>` copies it only after validation succeeds. The equivalent Gradle tasks are
`packTranslations checkTranslationPack`. The ZIP contains `manifest.json` and `original_text/` with unchanged relative paths.
Place it, without extracting, in the active Minecraft instance's `skyzh-translations/` directory. The Mod creates that directory
under `FabricLoader.getGameDir()`, which may differ from the launcher's directory. Startup and `/skyzh reload` or `/skyblockzh reload`
read the fixed `translations.zip` file, then close it so it can be replaced.

The manual `skyzh-translations/translations.zip` always has priority. Otherwise, a usable automatic `skyzh-updates/translations.zip`
with a matching API takes priority only when its timestamp is newer than the built-in snapshot and its content hash differs.
An absent or invalid source, including compilation failure, falls back to another usable source; if all fail, the current index remains.
After built-in data compiles successfully, superseded automatic ZIPs are deleted; deletion failures are logged without affecting selection.
If built-in loading fails, a usable automatic bundle is retained and loaded.
Each bundle replaces the complete corpus without per-line fallback. Removing both external ZIPs restores built-in data on reload.
Only one reload runs at a time; publication occurs on the client thread, refreshing caches and capture deduplication without clearing chat or original mode.

`gradle.properties` supplies the single fixed `translation_api`, exported to both the Mod's resources and the bundle manifest.
The API must exactly match the engine's declaration; neither lower nor higher values are accepted. Mod versions do not determine
bundle compatibility. Bump the API when new corpus requirements or engine behavior break compatibility, not for wording-only edits
or compatible optimizations. `format_version` independently describes the ZIP/manifest structure and remains 1.
API 4 removes the required Mod version range fields; API 3 bundles must be re-exported for the new engine.
`revision` is HEAD, `source_dirty` covers corpus/build-rule/version-property changes, and `build_at_utc8` is the generation time with an
explicit `+08:00` offset. These provenance fields do not determine compatibility. `content_sha256` hashes sorted relative paths as
UTF-8 path, NUL, exact JSON bytes, NUL, excluding the manifest itself.

`checkTranslationPack` covers exported content, auxiliary tables, compatibility boundaries, failure fallback, cache refresh and concurrent
snapshot isolation on every target. Open menus, chat scrolling, original mode and the three display priorities still need in-game verification.

### GitHub automatic updates

Every push starts `.github/workflows/translations.yml`; main publishes when the inputs differ from the last successfully published source.
First publication and manual dispatch force packaging. `tools/publish_updates.py` reuses the exporter and Gradle checks, with DEFLATE level 9.
`--prepare` stages `build/pages` with `skyzh-updates/<ZIP SHA-256>/translations.zip` and a convenience latest-download path.
Pages deploys `build/pages` to `https://skyblock.xbdqaq.com/`, including `skyzh-updates/update.json` for translations/betas
and `release.json` for formal builds. Clients check and download only through that domain. Each deployment retains two previous translation
archives and the previous beta/formal JAR sets; legacy Pages descriptors are migrated. `--publish` verifies current downloads before recording
repository state, unless packaging inputs changed during deployment. The repository manifest keeps GitHub URLs for older clients using
immutable `legacy-updates` prerelease attachments; the site's manifest keeps domain URLs. `pages_state` preserves deployment retention data.
Compatibility attachments never replace old links or the latest formal Release. Configure the Pages custom domain and `contents: write`, `pages: write`, `id-token: write`.

To publish a formal build, run `./gradlew dist`, attach all three formal JARs to a Release matching the Mod version, then publish it.
Keep GitHub download links available so older clients can upgrade automatically.
Release `published`, `edited` and `released` events dispatch a main-branch deployment, mirroring the latest formal Release after checking ID,
version, channel, size and SHA-256. Failed runs can be retried by manual dispatch. Pushes generate translations and beta builds, and also mirror changed formal Release attachments.

Background updates become eligible only after verified SkyBlock entry and completion of initial corpus loading, even if that load failed.
Automatic checks, explicit commands and retry buttons share one worker and busy guard. Combined checks run Mod first, then translations;
per-target cooldowns remain independent, and a Mod-check failure does not prevent the translation check.
The custom domain directly supplies `update.json`: `update_format: 1`, bundle manifest fields, download URL, ZIP size and SHA-256.
Exact API equality, a newer timestamp and a different content hash are required. The baseline uses the same timestamp/hash rule as loading:
only an automatic bundle newer than the built-in snapshot becomes the baseline, even if a stale ZIP could not be deleted.
Otherwise use `skyzh-builtin-translations.json`, generated by the same metadata task as the exported ZIP. Manual bundles retain loading priority
and do not participate in update comparisons.

Beta channel: when packaging inputs (including `src/` and each `fabric-26.x/`) change, the same workflow builds all three targets
with `-Pskyzh.channel=beta`, versioned `<version>+mc26.x.beta.<commit count>`, stages them under `skyzh-updates/mod/<SHA-256>/` and lists
version, size and digest per target in `mod_beta.assets` of `update.json`; the previous beta set is kept for one more deployment.
Every jar's `skyzh-build.properties` records its channel and commit count. Beta builds compare by commit count within one base version;
switching back to the release channel lets a release of the same version replace a beta build.

Downloads use bounded files and Range resumption keyed by fixed URL, size and hash. Ignored ranges restart the download; mismatched ranges are rejected.
Connections and responses time out; streaming downloads allow slow progress but close after 60 seconds without data.
Archive integrity, matching inner metadata and full corpus compilation must pass before installing the automatic ZIP under the existing reload lock.
Failures retain the installed file and running index. An active manual bundle remains selected after an automatic update.

`config/skyzh-updates.json` persists separate outcomes and deadlines. Success, no update and failures normally wait one hour;
API incompatibility waits seven days only for translations. Due checks run again while in SkyBlock or on the next eligible launch, without retry limits.
Local Mod/API changes invalidate the relevant old cooldown. Directory initialization failures also persist their cooldown in the config directory.
Chat uses short explanations; detailed errors stay in logs. Retry buttons bypass deadlines while reusing the same workers, validation and busy guards.
Initialization retries prepare directories again. An incompatible-translation Mod-download button requires both a newer Mod and newer translation data;
missing Minecraft assets are recorded silently. The manual-pack button irreversibly deletes only `skyzh-translations/translations.zip` under the existing reload lock,
then loads the automatic/built-in fallback. Local random tokens use a bounded registry and hidden commands intercepted for all aliases;
only bracketed actions carry click/hover styles. Accepted retries consume their token, later failures issue a new button, and busy/rejected requests retain it;
an uninstall token is revoked after successful removal/loading, while failures remain retryable.

Mod downloads select the correct Minecraft asset from the site's `release.json`, require the verified SHA-256 and validate Mod ID, version and Minecraft/Loader/Java dependencies.
The embedded JDK-only `skyzh-updater.jar` runs independently, acknowledges readiness and waits for the exact game process to exit.
It verifies the downloaded JAR again, backs up the current installed JAR and replaces the original filename, preventing duplicate mods.
Manual replacements count as manual updates: old installation records do not block later updates, and a JAR replaced while waiting
may also be overwritten after backing up the actual file. Downloaded JAR checksum validation remains required.
Pending state and failures remain in `skyzh-updates/mod-install.properties` and `installer.log`.
Development, nested and installations outside the instance's `mods/` are not automatically replaced. Launchers that kill the full process tree or immediately
reopen the old JAR can require another retry. `checkUpdates` covers loopback transfers and real child-JVM installation;
`checkUpdatePublisher` covers publication order without GitHub writes. Full client/launcher behavior still needs in-game verification.

`./gradlew checkPerformance` compares indexed matching with the previous exhaustive scan and checks
style equality, cache collisions, shape hits and capture eviction recovery. `./gradlew benchmarkPerformance` reports
offline CPU/allocation costs, not client FPS. Java checks, platform fixtures, JSON samples and Python
checks live under `tests/` and are excluded from distributed jars.

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
| World name tags | `handleSetEntityData` TAIL plus a 10-tick recheck of packet-observed entities | Bounded entity ids are tied to their `ClientLevel` and cleared on world changes. Reads visible names on invisible armour stands and custom names on non-player living entities, without scanning client-created Mod entities. NPC names, pet names and unknown dynamic health bars remain capture evidence; only actually translated mob names count as covered |
| Sidebar | The `Scoreboard` object, every 10 ticks | The same server state vanilla renders from. A useful side effect: **it keeps working when SkyHanni's Custom Scoreboard has replaced the sidebar's rendering entirely** |
| Tab list | `handleTabListCustomisation` for header and footer, player entries every 10 ticks | As above |
| Boss bar | `BossHealthOverlay#update` TAIL | That map is written by the packet handler and by nothing else |
| Action bar, title, subtitle | `setActionBarText` / `setTitleText` / `setSubtitleText` HEAD | As above |

Books and signs use independent `Surface.BOOK` / `Surface.SIGN` corpora in `Book/` / `Sign/`.
`BookViewScreen.visitText` translates a display copy before vanilla wrapping, so rendering, hover and
click hit-testing share one layout. Policy and corpus changes invalidate the page cache. Editors and
item components stay untouched. Whole pages/sides win before explicit-line fallback; blank rows remain.
Book padding is recomputed against the 114px page only when source geometry supports centering; short
indents remain. `AbstractSignRenderer.submitSignText` prepares translated four-row copies and retains
vanilla per-row centering, filtering, dye and glow for both sides and both sign types. It never writes
translated content into `SignText` or its original render cache; malformed row counts fall back to the
original. Book clicks inherit live actions/styles; text hovers use BOOK records only. `checkDocuments`
replays frozen observations and checks interactions, colours, layout and surface isolation.

Highest-priority packet `handle(ClientGamePacketListener)`
HEAD hooks freeze raw book components from container, inventory and cursor-item packets. All pages are
captured together, including pages never opened; no editor or later client inventory text is read.
Up to 64 immutable book candidates and 1 Mi page characters can wait in memory before server verification
or while capture is off. Once the SkyBlock and capture gates open, they enter the existing asynchronous
queue. Candidates clear on connection/world changes and capture clearing; evicted books require another
server packet. This also captures server books the player has not opened.

Sign text comes directly from initial chunk and block-entity update NBT, using version-specific
`platform/ServerSignData` adapters for normal and hanging signs. Signs require capture and server gates
to be open when the packet arrives. Each side keeps its four ordered rows, blank lines, original spaces,
colours, dye and glow state, together with dimension and position. No world/rendered sign text is read.
Output uses `Book/Books.json` and `Sign/Signs.json`; `_capture.book_contexts` and `sign_contexts` retain
up to three concrete contexts per record, with `server_contexts_limited` marking overflow. RGB and other
styles that cannot be represented by legacy colour codes use `style_runs`. Capture diagnoses coverage,
colour, mixed text and values on BOOK/SIGN rather than MISC; undecided symbols remain capturable.
`checkCapture` covers source isolation,
complete later pages, sign sides/layout, candidate gates, lifecycle clearing and memory bounds.

There is deliberately **no filter by name**. `[Bazaar]` and `[Sacks]` are Hypixel's own prefixes, and
a blocklist of mod-shaped tags would throw away real SkyBlock text to catch something that cannot
arrive anyway.

Two more guards: this exact live connection must have identified itself as Hypixel (the official
`hypixel:hello`, or a `minecraft:brand` naming Hypixel), and the sidebar objective has to be named
`SBScoreboard`. Both are required; the configured address never participates. Every capture entry checks the current
connection rather than trusting the previous tick after a server switch. Disconnect or leaving
SkyBlock cleanup runs even if capture was switched off mid-session; it never deletes files.

### The one thing sent to Hypixel

With capture **on**, and only then, and only if the [`hypixel-mod-api`][modapi] mod is installed,
SkyZH subscribes to Hypixel's own location event — one register packet, sent by that mod's plumbing,
naming the event it wants. In return Hypixel says which island the player is on the moment they
arrive, which is the answer to "which gameplay folder does this text belong in" straight from the
server instead of read off a sidebar drawn for a human. The library is compiled against and never
bundled; without that mod the integration does not load and the other two readings carry on.

With capture **off** — every ordinary player — nothing is subscribed and no packet is sent to Hypixel.
This is independent of `updateCheck`: with both switches enabled, verified SkyBlock entry permits checks and downloads through the update domain.
No player/game data is uploaded and no update traffic is sent to Hypixel. The site's `release.json` identifies formal Mod releases, ignoring `+mc26.x` metadata;
translation pushes deploy files and manifests to the same site.

[modapi]: https://github.com/HypixelDev/ModAPI

### What comes out

Six buckets, each laid out the way `original_text/` is — gameplay, then surface, then name:

```
skyzh-capture/
├── untranslated/          no record answered for these
│   ├── Mining/NPC_Message/Fragilis.json
│   ├── Mining/GUI_Item/Commissions.json        ← every slot of that menu, names and lore
│   ├── Mining/Hologram/Holograms.json
│   └── _Unknown_Gameplay/ScoreBoard/Sidebar.json
├── mixed/                 a record answered and the line still came out half English
│   └── Mining/ChatMessage/Server_Messages.json
├── colour/                a record answered, every word is Chinese, the colours are flattened
│   └── Mining/ScoreBoard/Sidebar.json
├── layout/                conflicting chat alignment, offsets or overflow
├── incomplete/            suspected English continuation after translated lore
└── value/                 lost numbers or missing/unbound placeholder output
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

The gameplay comes from where the player was standing, through `original_text/_data/areas.json`; an
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

Controlled by the capture-notifications sub-option, on by default. Turning it off drops pending
reports without stopping file writes; turning it back on does not replay reports from the silent period.
Captures are grouped **by the file they are written to** and announced one second after the last one arrives:

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

### Alignment, continuation and value diagnostics

`ChatLayout` shares the same styled font measurements between rendering and server-packet capture.
It compares the source padding and text width against Hypixel's default 320px canvas and the current
chat width. Short list indents stay on the left; wider padding needs evidence of a center or right
edge. Shared or undeclared layouts are corrected when that anchor is known; explicit center/left/right
policies take precedence and conflicting geometry is reported as a suspicion. Speaker prefixes are
measured with the line, without retaining their old internal padding. Policy, pixel widths and
before/after text are saved in `layout`. Ambiguous layouts retain their current policy. This does
not inspect screenshots or infer the intended layout of another mod's custom interface.

`LoreTranslation` supplies one ordered plan to both tooltips and packet capture. Container handlers
freeze item names, ordered lore, menu, slot and area on the client thread; complete matching and
diagnostics run on the existing capture worker, avoiding synchronous analysis of a full Bazaar page.
The worker retains counts, context and clear generations without reading a later ItemStack.
`incomplete` keeps source and output lines for suspected English tails without deleting them. Only readable characters
are judged (`§k` decoration is ignored): a tail opening on a lowercase word is a continuation; one
opening on a capital is reported only when the head reads as prose (lowercase words, no colon, no
closing punctuation) and the tail does not open with a list mark — the English after a heading such
as `Ability: X` or `Topaz Crystal Hunter` is an untranslated body, not a torn sentence. `value`
counts placeholder values only: ordinal, duration and multiplier conversions are applied, then every
digit group must appear in the Chinese as often as it arrived. Numbers written into the record's own
English (`12:00 am-11:59 pm`, `5x`) and values the term table translates whole (`2X POWDER` →
双倍粉末) are the translator's decisions, not losses, and `5m` is no longer read as five million.
`layout_overflow` is reported only when every source line fitted the box and the translation does
not; English Hypixel already sends wider than the box wraps in vanilla either way. Unbound translated
arguments fail `checkTranslations`.

All six buckets use the existing capture switch, notifications, asynchronous writer, area buffering,
deduplication and clear lifecycle. Capture close/disconnect requests drain and persist on the worker;
only JVM shutdown waits, with one 30-second deadline covering admission and completion. Timeout never
falls back to synchronous disk writes. The two alias indexes are capped at 16,384 entries each;
repeats retain original evidence so an evicted alias can be classified and recovered.
`_capture.diagnostic.original_lines`, `rendered_lines` and `records`
keep a concrete observation; combining unrelated `observed` values is not a valid reconstruction.
`./gradlew checkDiagnostics` checks the screenshot cases, 73 independent colour samples, deliberately
broken records, persistence and clearing on all targets. Deterministic font metrics test the pixel
arithmetic; resource-pack rendering and mod combinations still need an in-game check.

### Known names above entities

Capture reads packet-observed name tags within 32 blocks, retaining at most 12 neighbouring rows per observation. These are spatial neighbours, not confirmed paragraphs. Each record retains at most three position/area scenes; late metadata can enrich an existing scene. Observation signatures include visible styles so colour-only changes are diagnosed again. Exact `translate: false` decisions and NPC names still inform rendering, but no longer suppress capture. Museum labels borrow only catalog-confirmed ITEM names; auction ranks, reforges and stars keep their live styles.

Green NPC role labels may use explicit hologram records; colour alone is not a translation policy.
For non-player living entities and invisible armour stands, a separate bounded health-bar path (skipped entirely when mob names are off) replaces
only whole names present in the `mob_name` term table. Level, glyphs, health and live styles remain
unchanged; proper names, players and dropped items are not translated by a generic name lookup.
Untranslated dynamic health bars are captured and their changing values use the existing template
merger. All render bypasses and server gates apply.

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
Records in `incomplete/` and `value/` carry the ordered lines of one observation, so they are
replayed the way the tooltip was — the same plan and the same checks — and printed as cleared or
still reported, with what the lines draw as now. `layout/` needs the client font and is not replayed.

## Whole lore sentences from NEU

`GUI_Lore/` is its own render surface, `Surface.LORE`, consulted only for the lines after a tooltip's
item name. `LoreMatcher` matches a complete authored sentence across the server's wrap boundaries —
at most 12 lines or 2048 characters, never across a blank line — and it only ever uses this surface's
own templates; it does not extend an old per-line ITEM record into the lines below it. A whole-sentence
hit wins; a miss consumes nothing and the line falls through to the per-line path. Values, icons and
colours still come off the live components through `TranslationEntry`, and `TextLayout` re-wraps the
Chinese by pixel width. The item-name path never asks this surface, so a skill title that happens to
share an item's name does not rename the item; with translation off or original-text mode selected,
the translated tooltip cache is not read.

`TextCapture.item` uses the same matcher: a sentence the corpus covers is checked for mixed language and
flattened colours under `GUI_Lore`, and lines it does not cover are still captured one by one. Nothing
here touches the `ItemStack`, no online translation is introduced, and nothing downloads NEU at build
or run time.

- `checkLore` runs on all targets: the recorded multi-line samples, the same sentences re-wrapped at
  other points, hand-written expectations, the line and length limits, item names left alone, and the
  capture diagnostics.
- `auditNeuLore -PneuItems=<items directory>` is a development tool only and writes
  `build/neu-lore-audit.json`. It reports per-line matches and whole-item matches separately; a stat
  line matching is not an item being done. Pet placeholders are sampled at 10, which is not a test of
  every level.
- `samples` and the source-item lists are for translators and tests; the minifier does not ship them.

## Building

```bash
./gradlew dist     # all three jars into build/dist, after running every check on every target
./gradlew build    # the same jars, into each target's own build/libs
```

Requires JDK 25. Minecraft 26.x ships unobfuscated, so there is no mapping layer, mods are ordinary
dependencies, and Loom has no `remapJar` step. `shadowJar` produces the installable file: it bundles and relocates each target's MoulConfig 4.8.0, merges its access widener and service declarations, and nests Kotlin's standard library. Players need no extra dependency mod. `jar` only produces an unrelocated development artifact under `build/devlibs/`, not a distributable.

### Three Minecraft versions, one source tree

| | jar | Minecraft it declares | Mod Menu compiled against |
|---|---|---|---|
| `fabric-26.1/` | `SkyBlockZH-<version>-Beta-Fabric-26.1.jar` | `>=26.1 <26.2` | 18.0.0 |
| `fabric-26.2/` | `SkyBlockZH-<version>-Beta-Fabric-26.2.jar` | `>=26.2 <26.3` | 20.0.1 |
| `fabric-26.3/` | `SkyBlockZH-<version>-Beta-Fabric-26.3.jar` | `>=26.3 <26.4` | 21.0.0 |

Every target compiles `src/main/` and selects platform sources through `versionSourceDirs`.
26.1 uses `src/mc26_1/`; 26.2 and 26.3 share the GUI and Mixin code in `src/mc26_2/`.
26.1/26.2 read mouse state through `src/glfw/`, while 26.3 uses the SDL adapter in `src/mc26_3/`.
`ClientInput` isolates native input; the toggle policy stays shared. SDL uses one-based mouse button
IDs and a bit mask, and physical keyboard scancodes; version-specific harnesses check these differences.
`gradle/target.gradle` defines packaging and verification for every target. Corpus tools such as
`probe` and `auditLog` are registered only on the newest target.

The three GUI differences are:

| What moved | 26.1.x | 26.2 / 26.3 |
|---|---|---|
| The class that draws the HUD | `Gui` | `Hud`, split out of `Gui` |
| `SubmitNodeCollector#submitNameTag` | takes a trailing `double` | does not |
| Who owns the chat box and the screen stack | `Gui#getChat`, `Minecraft#setScreen` | `Gui.hud#getChat`, `Gui#setScreen` |

The last row is `platform/ClientGui`, one copy per target with the same signature. The first two are
`mixin/HudMixin`, `mixin/HudScoreboardMixin` and `mixin/EntityRendererMixin` — the annotations only;
what they do when they fire is shared code in `hook/`, written once.

One jar covers all of 26.1, 26.1.1 and 26.1.2: every class this mod touches is byte-identical across
the three. All ranges are closed at the top so Fabric only loads each jar on its supported Minecraft
versions. `require = 0` allows missing injection points; it does not establish cross-version compatibility.

### Checking that the injectors still apply

```bash
./gradlew checkMixinTargets     # part of `check`, run for every target
```

This is the check `require = 0` makes necessary. It reads the annotations back out of the *compiled*
mixins — the real `@Mixin` value, the real `method` list, the real `At.target` — and resolves every one
of them against the Minecraft actually on that target's compile classpath, including `@Shadow` members,
which are not `require = 0` and crash at apply time rather than failing quietly. Compiling proves the
mod's source agrees with itself and proves nothing at all about whether the injection points still
exist. See `tests/check_mixin_targets.py`.

To check the shipped classes against a Minecraft the build is not currently pointed at — which is how
`>=26.1 <26.2` was established rather than assumed:

```bash
./gradlew :fabric-26.1:build -Pmc26_1_version=26.1.2   # compile the 26.1 tree against a later patch
```

## Author

BingKKni (小布丁)
