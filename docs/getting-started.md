# Using Latticium

Latticium is a client mod. Commands act through the player's normal reach, inventory, placement, and breaking interactions. Work only starts after a command or an explicitly enabled profile activation. Test a small selection first: the 26.2/26.3 in-world acceptance matrix is still in progress.

## A finite selection and an immediate job

Stand at one corner and run `/latticium pos1`, then stand at the opposite corner and run `/latticium pos2`. Save the inclusive box with `/latticium selection save build`. The selection is stored for the current server and dimension. A profile that names an unavailable or empty selection fails to start with an explicit error.

The commands `/latticium fill minecraft:stone`, `/latticium replace minecraft:dirt minecraft:stone`, and `/latticium clear` use `build`. Fill selects air, replace selects the named source block, and clear selects the whole box. Use `/latticium status`, `pause`, `resume`, and `cancel` to inspect and control the job. Pausing prevents the next submission; an action already sent to the server still has to settle. An action budget limits submissions in one activation, and reaching it ends that activation even if candidates remain.

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
    "max_actions_per_tick": 1,
    "max_actions_per_activation": 256
  }
}
```

Run `/latticium profile load stone-fill.latticium.json`, then `/latticium preview user:stone_fill` and `/latticium start user:stone_fill`. Preview reads at most four sections and reports how many sections remain; it is an estimate, not a whole-selection count. The separate `/latticium query <expression>` command likewise checks at most four sections of `build`; target-view predicates in this standalone query report Unknown because no target source is supplied. Profile expression strings contain one expression with no trailing semicolon. JSON string escaping still applies.

`scope` must have enumerable finite bounds, such as `box(x0,y0,z0,x1,y1,z1)`, `sphere(player,r)`, or `selection("build")`. `select.where` filters positions inside that scope. `target` has exactly one main form: `items` (an item set), `clear: true`, or `source` (a registered target provider). Item targets may also specify a `states` state set and a `choose` preference. Source targets may specify `using` to restrict items and `include_air` to turn blueprint air into a clear target. The default break policy is `deny`; `selected` permits breaking selected positions. Missing fields and unknown JSON keys produce profile errors.

For a blueprint, install the matching optional bridge and select an active placement in the source mod. A profile can use `"scope": "selection(\"active_blueprint\")"` and `"target": {"source": "latticium:active_blueprint", "include_air": false}`. The bridge reads the loaded in-memory placement; Latticium does not open `.litematic` files. Blueprint air is ignored unless `include_air` is true. Missing placements, unavailable chunks, and overlapping subregions remain unresolved rather than becoming air.

## Expression essentials

The DSL is a typed, read-only set language. `!`, `&`, and `|` mean complement, intersection, and union; use parentheses to group. `b{...}`, `s{...}`, `i{...}`, `m{...}`, and `f{...}` make block, state, item, biome, and fluid sets. Examples:

```text
current(s{minecraft:oak_stairs[facing=north]}) & y=60..80
selection("build") & !current(s{minecraft:air})
biome(m{minecraft:plains}) & light(8..15)
```

`current(...)` and `target(...)` apply a block or state set at a position. Other built-ins include `box`, `sphere`, `selection`, `dimension`, `offset`, `adjacent`, `fluid`, `biome`, `inventory`, `has_target`, `matches_target`, `light`, `solid`, and `surface`. A missing world fact evaluates to Unknown; negation does not turn Unknown into a match. Resource IDs are checked against the live game's registries when a profile is bound. The [implemented DSL reference](dsl-reference.md) lists syntax and built-ins. The [typed DSL design](../discussion/003-typed-dsl.md) also discusses proposed capabilities beyond the current in-game commands.

## Automatic activation

An optional `activation` object supplies a position expression in `where`, `mode` (`enter` or `while`), and `initial` (`ignore` or `fire_if_inside`). Loading a profile does not enable it. `/latticium enable <id>` permits its activation and `/latticium disable <id>` removes that permission. `enter` starts on a known outside-to-inside edge; `fire_if_inside` also permits the first known inside sample. `while` starts while inside and stops when a known outside sample arrives. Unknown samples do not create an edge.

Use `/latticium status` to inspect blocked counts, one example blocked reason, and automatic activation errors. Unsupported placement behavior, out-of-reach positions, unavailable data, and failed server confirmation can leave work blocked or deferred. The current client supports ordinary hotbar block placement, snow layers, repeater delay interaction, and ordinary breaking; it does not predict arbitrary modded block behavior or multi-block effects.
