# Using Latticium

Latticium is a client mod. Commands act through the player's normal reach, inventory, placement, and breaking interactions. Work only starts after a command or an explicitly enabled profile activation. Test a small selection first: the 26.2/26.3 in-world acceptance matrix is still in progress.

## Task controls, HUD, and settings

Press **F8**, or run `/latticium ui`, to open the native task controls. Select a loaded profile and press **Start** to start it or replace the current task. A pending server confirmation prevents replacement. **Pause / Resume**, **Cancel task**, and **Refresh scan** use the same controller as the commands. **F9** toggles pause/resume while playing. All shortcuts are rebindable under the Latticium category in Minecraft's Controls menu; the stop-all and HUD-toggle shortcuts are initially unbound.

**Stop task + auto** cancels the task and suspends all automatic activation until **Restore automatic activation** is pressed. This hold lasts for the client process, including world changes; it does not erase the saved enabled-profile flags. Already submitted interactions still need to settle, and stopping does not undo world changes. Manual starts remain explicit actions even while automatic activation is suspended.

The controls screen does not pause the game. The optional setting **Pause task when opening controls** pauses new submissions when the screen opens; continuing the task requires an explicit Resume. The task HUD is hidden over screens and follows Minecraft's Hide GUI option. It reports scanned sections, satisfied candidate checks, pending candidates, submitted actions, and blocked counts. Satisfied checks can include positions already matching the target or revisited positions; they are not a placed-block count. Continuous mode remains in monitoring after its first pass, and exhausting the action budget is a separate state from completing a pass.

The **Diagnostics** page groups blocked positions by the engine's reason, gives one sample coordinate and a localized suggestion, and reports deferred reads, unsupported sections, automatic activation errors, and registered blueprint providers. It caps displayed reason groups while preserving total counts. **Copy report** copies the displayed diagnostics and task summary to the clipboard. Original engine details can be shown for troubleshooting; arbitrary provider messages retain their original text.

Open settings from the controls screen or `/latticium settings`. On Fabric, installing the optional **Mod Menu** also adds a settings entry. On NeoForge, use **Mods → Latticium → Config**. Both open the same Cloth Config / AutoConfig screen and store client preferences in `config/latticium.json`. Cloth Config is bundled in the main JARs. UI labels, controls, diagnostic categories, and suggestions follow Minecraft's language setting: English, Simplified Chinese, Traditional Chinese, or Japanese. Version 0.0.2 does not add selection rendering or a map; selection and profile authoring commands remain available below.

## A finite selection and an immediate job

Stand at one corner and run `/latticium pos1`, then stand at the opposite corner and run `/latticium pos2`. Each command prints the recorded block coordinates, such as `pos1 set: (10, 64, -5)`. Save the inclusive box with `/latticium selection save build`. The selection is stored for the current server and dimension. A profile that names an unavailable or empty selection fails to start with an explicit error.

The commands `/latticium fill minecraft:stone`, `/latticium replace minecraft:dirt minecraft:stone`, and `/latticium clear` use `build`. Fill selects normal, cave and void air, replace selects the named source block, and clear selects the whole box. Replacement freezes a verifiable target before breaking. The current post-break proof supports plain property-free blocks with a reachable plain full-block support. Fluid-containing blocks, directly coupled multi-block structures, missing supports and unverified property-sensitive replacements defer before removing the source. Clear also defers fluid-containing or directly coupled multi-block structures. Use `/latticium status`, `pause`, `resume`, and `cancel` to inspect and control the job. `/latticium refresh` immediately queues a full rescan of the current job, including a job in manual refresh mode. Scanning proceeds within the normal per-tick budget; the command does not synchronously scan the whole selection. Pausing prevents the next submission; an action already sent to the server still has to settle. An action budget limits submissions in one activation, and reaching it ends that activation even if candidates remain. An explicit refresh after exhaustion starts a new action budget.

## A profile file

Save this as `config/latticium/profiles/stone-fill.latticium.json` in the game directory:

```json
{
  "schema": 1,
  "id": "user:stone_fill",
  "scope": "selection(\"build\")",
  "select": {
    "where": "current(s{minecraft:air})",
    "choose": "nearest"
  },
  "target": {
    "items": "i{minecraft:stone}"
  },
  "policy": {
    "break": "deny",
    "refresh": "continuous",
    "max_actions_per_tick": 1,
    "max_actions_per_activation": 256
  }
}
```

Run `/latticium profile load stone-fill.latticium.json`, then `/latticium preview user:stone_fill` and `/latticium start user:stone_fill`. Profile IDs with a namespace use the unquoted `namespace:path` form in `preview`, `start`, `enable`, and `disable`. Preview reads at most four sections and reports how many sections remain; it is an estimate, not a whole-selection count. The separate `/latticium query <expression>` command likewise checks at most four sections of `build`; target-view predicates in this standalone query report Unknown because no target source is supplied. Profile expression strings contain one expression with no trailing semicolon. JSON string escaping still applies.

`scope` must have enumerable finite bounds, such as `box(x0,y0,z0,x1,y1,z1)`, `sphere(player,r)`, or `selection("build")`. `select.where` filters positions inside that scope. `target` has exactly one main form: `items` (an item set), `clear: true`, or `source` (a registered target provider). Item targets may also specify a `states` state set and a `choose` preference. Source targets may specify `using` to restrict items and `include_air` to turn blueprint air into a clear target. The default break policy is `deny`; `selected` permits breaking selected positions. Missing fields and unknown JSON keys produce profile errors.

`policy.refresh` defaults to `continuous`; set it to `manual` for a one-pass job. Continuous jobs revisit positions affected by server block updates, including neighbors read through `offset`, `adjacent`, or `surface`. A player-dependent sphere is revisited only when the player's block position changes. Light and external target reads also receive a slower periodic fallback. A continuous job stays active after its initial scan until cancelled, stopped by its activation, or stopped by its action budget. `/latticium refresh` works in either mode and also updates the finite bounds of a `sphere(player, r)` scope at the player's current position.
Automatic activation is sampled when the player's block position changes or a block update touches its read radius; a 200-tick fallback covers facts that can change without those events. An unchanged activation is not captured every client tick.

For a blueprint, install the matching optional bridge and select an active placement in the source mod. A profile can use `"scope": "selection(\"active_blueprint\")"` and `"target": {"source": "latticium:active_blueprint", "include_air": false}`. The bridge reads the loaded in-memory placement; Latticium does not open `.litematic` files. Blueprint air is ignored unless `include_air` is true. Missing placements, unavailable chunks, and overlapping subregions remain unresolved rather than becoming air.

## Expression essentials

The DSL is a typed, read-only set language. `!`, `&`, and `|` mean complement, intersection, and union; use parentheses to group. `b{...}`, `s{...}`, `i{...}`, `m{...}`, and `f{...}` make block, state, item, biome, and fluid sets. Examples:

```text
current(s{minecraft:oak_stairs[facing=north]}) & y=60..80
selection("build") & !current(s{minecraft:air})
biome(m{minecraft:plains}) & light(8..15)
```

`current(...)` and `target(...)` apply a block or state set at a position. Other built-ins include `box`, `sphere`, `selection`, `dimension`, `offset`, `adjacent`, `fluid`, `biome`, `inventory`, `has_target`, `matches_target`, `light`, `solid`, and `surface`. A missing world fact evaluates to Unknown; negation does not turn Unknown into a match. Resource IDs are checked against the live game's registries when a profile is bound. The [implemented DSL reference](dsl-reference.md) lists syntax and built-ins; [architecture and host contracts](architecture.md) explains the boundary between this read-only DSL and actions.

## Automatic activation

An optional `activation` object supplies a position expression in `where`, `mode` (`enter` or `while`), and `initial` (`ignore` or `fire_if_inside`). Loading a profile does not enable it. `/latticium enable <id>` permits its activation and `/latticium disable <id>` removes that permission. `enter` starts on a known outside-to-inside edge; `fire_if_inside` also permits the first known inside sample. `while` starts while inside and stops when a known outside sample arrives. Unknown samples do not create an edge.

Use `/latticium status` to inspect blocked counts, one example blocked reason, and automatic activation errors. Unsupported placement behavior, out-of-reach positions, unavailable data, and failed server confirmation can leave work blocked or deferred. The current client supports ordinary hotbar block placement, snow layers, repeater delay interaction, and ordinary breaking; it does not predict arbitrary modded block behavior or multi-block effects.

## World preferences

Loaded profiles, enabled flags and saved selections are stored beneath `config/latticium/worlds/`, separately for each server/local save directory and dimension. Two local worlds with the same display name do not share settings. Renaming the display name keeps the save-directory identity; moving the save directory creates a new identity.

Earlier builds keyed local preferences by display name. Those files remain on disk and are not automatically applied to another save directory. Reload profile JSON files and recreate selections after upgrading from that format; enable automatic profiles explicitly in the intended world.

## Registry and datapack reloads

When the server sends updated registry tags, Latticium stops the current job before it can submit another action using old tag membership. An action already sent still settles. A localized chat message explains the stop; start a manual job again to bind the new tags. Enabled automatic profiles bind again at their next activation sample, using their configured initial/while behavior. Your profiles, selections and enabled settings are retained.
