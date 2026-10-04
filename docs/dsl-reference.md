# DSL and profile reference

This is the reference for the implemented language and schema-1 profiles. The [design discussion](../discussion/003-typed-dsl.md) also describes proposed capabilities, which may not be available in the game. The DSL is read-only: it selects members of typed sets and cannot send actions. JSON profiles specify when and where a job runs, what it tries to make, and its action policy.

## Where each form works

| Input | Accepted content | Current game support |
|---|---|---|
| JSON `scope`, `select.where`, `activation.where`, `target.items`, `target.states`, `target.using` | One expression, without `;` | Yes, through a profile |
| `/latticium query <expression>` | One position expression, without `;` | Yes; bounded preview over the saved `build` selection |
| `.latticium` document | Declarations, typed functions, `query`/`count`/`exists` statements ending in `;` | Offline parser/compiler and query runner; no in-game document loader |
| Declaration-only module | Declarations and functions, with dependencies supplied by a resolver | Offline `ProfileReader` resolver overload; no game module resolver yet |
| Declarative rule JSON | `when`, `requires`, and `verify` fields each contain one `PosSet` expression | Offline `RuleBook` oracle; not loaded by the current game client |

See [Using Latticium](getting-started.md) for commands, [Bridge development](bridge-development.md) for external targets, and [validation](26.2-26.3-validation.md) for the current in-game test status.

## Lexical rules

Whitespace is ignored. `//` starts a line comment in DSL source; JSON does not allow comments. Names use ASCII letters, digits, and underscores, starting with a letter or underscore. Names are case-sensitive. Built-in and primitive names are reserved for declarations. A resource ID is `namespace:path`; literal members may omit `minecraft:`. Bare position atoms and `dimension(...)` require an explicit namespace. A namespace uses lowercase ASCII letters, digits, `_`, `.`, and `-`; a path may also contain `/`.

Double-quoted DSL strings support only `\\` and `\"` escapes. A JSON expression string has a separate JSON escaping layer: `"scope": "selection(\"build\")"` becomes the DSL expression `selection("build")`. Property values can be unquoted when they contain only ASCII letters, digits, `_`, `.`, `+`, or `-`; otherwise quote them. All integer literals fit Java's signed 32-bit range. A range is `N`, `N..M`, `..M`, or `N..`; both given endpoints are inclusive. The bare `..` is invalid.

The parser limits source length, token count, string length, and nesting depth. The binder also limits function expansion, total nodes, and spatial read radius. Errors report UTF-16 offsets in DSL source; profile errors include JSON pointers.

## Expressions and types

`!P`, `P & Q`, and `P | Q` are complement, intersection, and union. Precedence is `!`, then `&`, then `|`; parentheses override it. Both sides of `&` or `|` must have the same set type. There is no implicit block-to-item, item-to-state, or block-to-position conversion. Registry-set complement uses the bound registry domain; `PosSet` negation is evaluated at each queried position and needs finite bounds only when enumerated. An unknown world fact remains unknown under `!`.

| Type | Literal | Members |
|---|---|---|
| `BlockSet` | `b{minecraft:stone, #minecraft:logs}` | Block IDs |
| `StateSet` | `s{minecraft:oak_stairs[facing=north]}` | Legal complete block states matching any listed partial properties |
| `ItemSet` | `i{minecraft:stone}` | Item IDs, not item stacks or counts |
| `BiomeSet` | `m{minecraft:plains}` | Biome IDs |
| `FluidSet` | `f{minecraft:water}` | Fluid IDs |
| `PosSet` | No position literal | Predicates over block coordinates in a dimension |

Inside a typed literal, an omitted namespace means `minecraft`. Prefix `#` names a tag. A bare `{...}` is accepted only where the binder already knows the required type. A state literal with partial properties denotes every legal state that has those values; it does not supply default values for omitted properties. A live registry validates ID and tag existence; symbolic offline binding checks syntax and types without asserting that a game ID exists.

Position ranges use `x=1..10`, `y=64`, `z=..100`, or `x=-10..`. Bounds are inclusive. An open range is a predicate, not an enumerable job scope on its own.

The position shorthands `minecraft:stone`, `#minecraft:logs[axis=y]`, `$minecraft:plains`, and `$#minecraft:is_nether` mean current state or biome membership. A bare block atom must have a namespace. `state(open=false)` checks the current state's property at a position; `property(open=false)` creates a `StateSet`. `x=...`, `y=...`, and `z=...` apply to global block coordinates, not offsets from the player. `A & !B` is set difference. There are no arithmetic operators, assignments, loops, or commands inside expressions.

Registry literals can be empty, for example `i{}`. Members of a state literal may specify only some properties: `s{minecraft:oak_stairs[facing=north]}` includes every legal state with that facing. A tag member expands in its literal's registry. An invalid ID, tag, or property is a binding error in a live registry; a symbolic offline compiler checks syntax and types but cannot establish that a game ID exists.

## Built-in functions

| Function | Result | Meaning |
|---|---|---|
| `all()`, `none()` | `PosSet` | Constant true or false |
| `current(BlockSet or StateSet)` | `PosSet` | Current world block or state belongs to the set |
| `target(BlockSet or StateSet)` | `PosSet` | Exact external target belongs to the set |
| `biome(BiomeSet)`, `fluid(FluidSet)` | `PosSet` | Current biome or fluid belongs to the set |
| `box(x0,y0,z0,x1,y1,z1)` | `PosSet` | Inclusive box, in either corner order |
| `sphere(player,r)`, `sphere(point(x,y,z),r)` | `PosSet` | Integer-radius sphere around a block position |
| `selection("name")` | `PosSet` | Membership in a saved or bridge-provided selection |
| `dimension(namespace:path)` | `PosSet` | Position is in that dimension |
| `offset(dx,dy,dz,P)` | `PosSet` | Evaluate `P` at the offset position |
| `adjacent(P)` | `PosSet` | Any of the six face neighbors matches `P` |
| `light(range)`, `solid()`, `surface()` | `PosSet` | Current light level, full collision shape, or a non-air block touching air |
| `state(property=value)` | `PosSet` | Current state has that property value |
| `property(property=value)`, `property_range(property,range)` | `StateSet` | State property equality or integer range |
| `states_of(BlockSet)`, `blocks_of(StateSet)` | `StateSet`, `BlockSet` | Explicit registry-set conversion |
| `inventory(ItemSet)` | `ItemSet` | Restrict item types to those currently present in inventory |
| `has_target()`, `matches_target()` | `PosSet` | An actionable target exists, or current state satisfies it |
| `changed(property)`, `same(property)` | `PosSet` | Compare a property present in both current and exact target states |
| `compare(property,lt/le/gt/ge)` | `PosSet` | Integer property comparison from current to target |

`target`, `has_target`, `matches_target`, `changed`, `same`, and `compare` require a phase with target facts; an item-targeted profile cannot use them in `select.where` before selecting its material. `sphere`, `offset`, and `adjacent` must fit the host's read-radius budget, currently 16 blocks in the game adapter. Missing chunks, player data, target data, or neighbor facts can yield Unknown. For three-valued logic, `false & unknown` is false and `true | unknown` is true.

### Function details and examples

`box` sorts each pair of corners and includes both endpoints. `sphere(player, 8)` uses the current player block position; `sphere(point(0,64,0), 8)` uses a fixed center. `point` is only valid as the first argument of `sphere`, not as a general value. A fixed-center sphere's dimension comes from the position being evaluated. `selection("build")` uses a named host selection. `dimension(minecraft:overworld)` tests the position's dimension. `offset(0,-1,0,P)` evaluates `P` below the candidate; `adjacent(P)` checks the six face neighbors, not diagonals.

`current(b{minecraft:stone})` compares the current block ID; `current(s{minecraft:stone})` compares the full current state against a state set. `target(...)` examines an exact target state and is false for Clear or DontCare. `has_target()` is true for Exact or Clear, false for DontCare, and Unknown when the source cannot answer. `matches_target()` checks an exact state or an air state for Clear. `changed(facing)` and `same(facing)` compare a property on current and exact target states. `compare(layers,lt)` means the **current** integer property is less than the target property; `le`, `gt`, and `ge` are also accepted. If either state lacks the property, the comparison is false. `property_range(layers,1..4)` works on integer-valued state properties.

`inventory(i{minecraft:stone})` yields the intersection with item **types** present in the captured inventory, not counts or item components. `light(8..15)` tests the captured light level; `solid()` tests the captured full collision shape; `surface()` tests a non-air block with at least one air face neighbor. `biome(m{...})` and `fluid(f{...})` inspect captured world facts. `states_of(b{...})` and `blocks_of(s{...})` are explicit set conversions; there is no implicit item-to-block conversion.

### Unknown facts

Position predicates use `True`, `False`, and `Unknown`. A missing chunk, target, selection, player position, inventory, light value, or neighbor fact can produce Unknown. `!Unknown` stays Unknown. `False & Unknown` is False, while `True | Unknown` is True. An unknown selection is not treated as an empty selection, and an unknown target is not treated as air. Query counts and previews report unknowns separately; jobs retry deferred sections and positions rather than treating them as matches.

## Profile expressions and document syntax

A profile field such as `scope`, `select.where`, `target.items`, or `activation.where` contains **one expression without a semicolon**. The in-game `/latticium query` command also takes one expression. A job scope must contain a finite enumerable source such as `box`, `sphere`, or `selection`; adding an unbounded predicate with `&` can filter that source. The game adapter refuses scopes that include another dimension and caps the number of sections per job.

The offline `Parser.document` entry point additionally accepts declarations, nonrecursive typed `def` functions, and read-only terminals. Every document statement ends with a semicolon:

```text
stone: BlockSet = b{minecraft:stone};
def near_stone(area: PosSet): PosSet = area & adjacent(current(stone));
query near_stone(box(0,60,0,10,80,10)) order by y asc, z asc, x asc limit 20;
count current(stone) & box(0,60,0,10,80,10);
```

`query` may order positions by `x`, `y`, `z`, or `distance2(player)` and registry members by `id`. A limited query needs an `order by` clause or `limit any N`. `count` and `exists` have no order or limit. These terminals run only through the offline document/query APIs; the in-game `/query` command currently reports a bounded position-scan preview, not a result list. The profile `use` import field is supported by the offline `ProfileReader` resolver overload, but the current game client does not wire a module resolver, so in-game profiles cannot use imports yet.

Declarations use `name: Type = expression;` or `name := expression;`; the latter infers a type only when the expression supplies enough context. The six type names are `PosSet`, `BlockSet`, `StateSet`, `ItemSet`, `BiomeSet`, and `FluidSet`. A function uses `def name(parameter: Type, count: Int): ResultType = expression;`. `Int` parameters substitute integer expressions, and set parameters require their declared type. Functions are expanded at compile time; recursive calls are rejected. Modules contain only declarations and functions, not terminals. A document has no import syntax: the caller supplies module dependencies to the resolver.

`query E;` produces matches in encounter order unless ordered. `order by y desc, x asc` combines keys in sequence, with encounter order breaking ties. `limit 20` requires ordering; `limit any 20` explicitly accepts the first 20 matches. `count E;` counts known matches, and `exists E;` is True if any match is known, Unknown if there are only unknown candidates, or False otherwise. Registry-set queries enumerate the bound registry; position queries require an explicit finite list of bounds supplied to the runner. The query cell budget counts visits even where input bounds overlap. Ordering by `distance2(player)` requires a player in the same dimension.

## JSON profile schema 1

This complete manual job fills air in the saved `build` selection with a hotbar stone item:

```json
{
  "schema": 1,
  "id": "user:stone_fill",
  "scope": "selection(\"build\")",
  "select": {"where": "current(s{minecraft:air})", "choose": "nearest"},
  "target": {"items": "i{minecraft:stone}"},
  "policy": {
    "break": "deny",
    "max_actions_per_tick": 1,
    "max_actions_per_activation": 256
  }
}
```

| Field | Required | Accepted value and effect |
|---|---|---|
| `schema` | Yes | Integer `1` |
| `id` | Yes | Profile resource ID; a short ID uses the `minecraft` namespace |
| `use` | No | Unique array of module resource IDs; offline resolver only in the current implementation |
| `activation` | No | Automatic trigger object; absent means manual start |
| `scope` | Yes | `PosSet` expression containing a finite enumerable `box`, `sphere`, or `selection` source |
| `select` | Yes | Object with required `where` (`PosSet`) and optional `choose` |
| `target` | Yes | Exactly one of `items`, `clear`, or `source` forms below |
| `policy` | No | Break permission and action budgets |

An intersection has finite scope if at least one side is finite; a union needs both sides finite. For example, `box(0,60,0,5,70,5) & !current(s{minecraft:stone})` is finite, while `!current(s{minecraft:stone})` alone is not. The game refuses a scope containing another dimension and caps sections per job. `select.where` filters cells inside the scope; it does not itself define the finite enumeration. The optional `select.choose` values are `nearest` (default), `farthest`, `y_asc`, `y_desc`, and `scan`. The first two use squared distance from the player, then Y/Z/X as a tie breaker; `scan` keeps discovery order.

### Target forms

```json
{"target": {"items": "i{minecraft:stone, minecraft:dirt}",
            "states": "s{minecraft:stone, minecraft:dirt}",
            "choose": {"prefer": ["minecraft:stone", "minecraft:dirt"]}}}
```

`items` is an `ItemSet` expression. Optional `states` is a `StateSet` restriction on acceptable placed results. Without it, any state verified by the placement oracle for an allowed item can become the frozen target. `choose` may be omitted, be `"most_available"`, or contain a nonempty unique `prefer` array of item resource IDs; preferred items must belong to `items` when that can be determined without world facts. This chooses a material for each candidate, not a global bill of materials. Items need to be available in the hotbar for the current game adapter to place them.

```json
{"target": {"clear": true}}
```

`clear` requests air. It requires break permission where a block must be removed. A clear target does not imply that unknown or unloaded blocks are air.

```json
{"target": {"source": "latticium:active_blueprint",
            "using": "i{minecraft:stone}", "include_air": false}}
```

`source` names a registered provider resource ID. Optional `using` is an `ItemSet` restriction for placement. Optional `include_air` defaults to false: air in a blueprint is DontCare unless explicitly enabled as a Clear target. The matching optional bridge must be installed and have an active placement. Source targets can be Exact, Clear, DontCare, or Unknown; a missing provider or unresolved overlap is never guessed into an air target. [Bridge development](bridge-development.md) describes the provider contract.

### Activation and policy

```json
{
  "activation": {
    "where": "sphere(player, 8) & biome(m{minecraft:plains})",
    "mode": "enter",
    "initial": "ignore"
  },
  "policy": {
    "break": "selected",
    "max_actions_per_tick": 1,
    "max_actions_per_activation": 256
  }
}
```

`activation.where` is a `PosSet` expression sampled at the player position. `mode` is required and is `enter` or `while`. `enter` starts on a known outside-to-inside edge; `initial` may be `ignore` (default) or `fire_if_inside` to permit a first known inside sample. `while` starts while inside and stops after a known outside sample. Unknown samples defer without creating an edge. Loading a profile does not enable it; `/latticium enable <id>` does.

`policy.break` is `deny` (default) or `selected`. `selected` allows normal breaking only at selected positions, subject to planning and live gateway checks. `max_actions_per_tick` defaults to 1 and `max_actions_per_activation` to 256; both must be positive integers. The activation budget counts submitted actions, including actions awaiting server confirmation. Once exhausted, that activation stops. A replacement may need a break and a separate placement, so give it enough budget. Each submitted action is rechecked against the current world, inventory, target, reach, and policy before the client sends an ordinary player interaction.

The reader rejects unknown fields, duplicate JSON keys, trailing root values, invalid enum choices, wrong types, and duplicate imports or preferences. Errors identify the JSON pointer. Profile expression strings are compiled in their own phase: `activation.where` and `scope` cannot read target facts, and item-targeted `select.where` cannot read the target before a material is chosen.

The enum-backed `mode`, `select.choose`, and `policy.break` values are read without case sensitivity. The separate `activation.initial` and item `target.choose` strings must use their documented lowercase spelling. All profile IDs, module IDs, provider IDs, and preferred item IDs are parsed as resource IDs; short JSON IDs default to `minecraft`.

## Declarative rule guards (offline)

`RuleBook` accepts schema-1 JSON with a `rules` array. Each rule requires a unique resource `id`, exact `before` and `after` states, and an `action` (`place`, `use_item`, `interact`, or `break`). A state object has a `block` resource ID and optional string-valued `properties`. Short IDs in these JSON fields use the `minecraft` namespace. `before` and `after` must differ. Optional `when` and `requires` are `PosSet` expressions evaluated against the prior state; optional `verify` is evaluated against the predicted state. All three may inspect target facts. Optional `cost` contains nonnegative `materials` and `risk`; each rule costs one action. If a registry is supplied, both exact states must be legal in it. Rule proposals still require planner policy checks and live action confirmation.

```json
{
  "schema": 1,
  "rules": [{
    "id": "latticium:clear_stone",
    "before": {"block": "minecraft:stone"},
    "after": {"block": "minecraft:air"},
    "action": "break",
    "when": "current(b{minecraft:stone})",
    "verify": "current(b{minecraft:air}) & matches_target()"
  }]
}
```

This is an offline rule example, not a file format currently loaded by the game commands. The native placement oracle handles the current game's ordinary interactions.

## Practical examples

**Find exposed stone in a finite box:**

```text
box(0,60,0,10,80,10) & current(b{minecraft:stone}) & surface()
```

**Select empty cells inside a saved selection:**

```text
selection("build") & current(s{minecraft:air})
```

**Select blueprint cells whose exact target differs from the world:**

```text
has_target() & !matches_target()
```

The last expression belongs in `select.where` of a source-target profile. The profile's `scope` still needs finite bounds, such as `selection("active_blueprint")`. For an item-target profile, use world predicates such as `current(s{minecraft:air})` instead because the target does not exist at selection time.

**Read-only offline document:**

```text
stone: BlockSet = b{minecraft:stone};
def near_stone(area: PosSet): PosSet = area & adjacent(current(stone));
query near_stone(box(0,60,0,10,80,10)) order by y asc, z asc, x asc limit 20;
count current(stone) & box(0,60,0,10,80,10);
exists current(stone) & box(0,60,0,10,80,10);
```

The in-game `/latticium query` accepts only an expression and returns a bounded preview, not the terminal's match list.
