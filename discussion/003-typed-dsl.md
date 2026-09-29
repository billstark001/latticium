# Latticium 类型化 DSL 草案 003

状态：**拟定第一版语言与 profile 格式**，供讨论；没有解析器或游戏实现。2026-09-29。本稿扩展并在类型、目标及位置补集语义上取代 [001](001-architecture.md)、[002](002-packaging-and-automation.md) 中相应的初步提议。首批目标仍为 26.2/26.3 × Fabric/NeoForge，仅客户端。无 Minecraft 依赖的 `dsl-core` 与 `planning-core` 以 Java 21 为最低兼容目标；Minecraft 26.1 起游戏本身要求 Java 25，[26.2](https://piston-meta.mojang.com/v1/packages/33c420747ce582e48dff1d8c5d8e67e5bb6257c9/26.2.json) 与 [26.3](https://piston-meta.mojang.com/v1/packages/bc098d111a72e9f6178801544a42099bdfbb0cf2/26.3.json) 官方版本元数据也都标记 `javaVersion.majorVersion = 25`。因此四个客户端适配器必须在 Java 25 游戏进程中构建、测试和运行，mod 元数据也应按游戏运行时标注 Java 25。[Minecraft 26.1 官方发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1)

| 产物 | 编译目标 | 实际最低运行时 |
|---|---|---|
| `dsl-core`、`planning-core` | `--release 21` | Java 21 |
| 26.2/26.3 × Fabric/NeoForge 客户端适配器 | Java 25 游戏开发环境 | Java 25 |

## 1. DSL 与 profile 的边界

核心是纯、只读、强类型的**集合表达式**。`.latticium` 文档包含集合声明、`def` 和只读 `query/count/exists`；`.latticium.json` 文档保存 profile 对象。profile 的触发、范围、选点、目标、策略由 JSON schema 描述；其中的表达式字符串交给同一个 DSL core parser 和 binder。DSL 没有命令执行、赋值、循环或任意函数值。

```text
集合表达式 ───> PosSet / BlockSet / StateSet / ItemSet / BiomeSet / FluidSet
                                   │
                                   ├── query/count/exists：只读结果
                                   └── JSON profile 表达式字段：编译成 JobSpec
```

所有 Minecraft ID 在纯 JVM core 中只是符号。Minecraft 版本适配器在加载世界和注册表后绑定 ID、tag、属性和值。数据包可以提供 tag 和声明式规则；已安装的 mod 可以向宿主注册带签名的纯查询原语。用户文档不能注册 Java 回调。

## 2. 集合类型和元素

| 类型 | 元素 | 补集的域 | 典型用途 |
|---|---|---|---|
| `BlockSet` (`block`) | 注册的方块类型 ID | 该版本的方块注册表 | 标签、材料类别 |
| `StateSet` (`state`) | 完整、合法的 `(block ID, properties)` 状态 | 该版本所有合法 BlockState | 朝向、层数、含水等 |
| `ItemSet` (`item`) | 注册的物品类型 ID | 该版本的物品注册表 | 目标材料和动作物品筛选 |
| `BiomeSet` (`biome`) | 生物群系 ID | 当前注册表 | 位置谓词 |
| `FluidSet` (`fluid`) | 流体类型 ID | 当前注册表 | 岩浆、水等位置谓词 |
| `PosSet` (`pos`) | 带维度的方块坐标 | 当前世界的坐标谓词域 | activation、scope、select |

`ItemSet` 的元素是**物品类型**，不是背包槽位或带组件的 ItemStack；数量、损坏值、组件和物品可用性由库存快照与独立策略判断。若以后需要按组件精确筛选，应增加独立的 `StackPredicateSet`，不改变 `ItemSet` 的元素含义。`StateSet` 的元素是完整状态，但一个字面量可以只写部分属性，代表所有满足条件的完整状态。例如 `s{minecraft:oak_stairs[facing=north]}` 同时包含合法的不同 `half` 等状态。集合 AST 保留符号表示，不强制展开全部成员。

相同的 `minecraft:stone` 在 `b{}`、`s{}`、`i{}` 内属于不同注册表；**没有隐式 Block→Item、Item→State 或 Block→Pos 转换**。同一类型才能进行 `|`、`&`、`!`。`A & !B` 是差集。`-`、`^` 不作集合运算符。

## 3. 字面量和词法

```text
b{minecraft:stone, #minecraft:logs}
s{minecraft:oak_stairs[facing=north,half=top], #minecraft:logs[axis=y]}
i{minecraft:stone, minecraft:dirt, #example:building_materials}
m{minecraft:basalt_deltas, #minecraft:is_nether}
f{minecraft:lava}
```

字面量的规范前缀拟定为 `b/s/i/m/f`，分别构造 `BlockSet/StateSet/ItemSet/BiomeSet/FluidSet`。`m` 取自 bio**m**e；`e` 留给可能出现的 `EntitySet`，`o` 没有明确类型联想。`$id` 已是常用的 **PosSet 生物群系谓词**，因此不复用 `$ {...}` 来表示 `BiomeSet`：同一个符号在两种位置产生不同类型，会增加阅读和补全负担。`p{}` 暂时保留，不作为首版字面量；`PosSet` 的通常元素是范围、选区或世界谓词，点列表还必须明确维度与坐标语法。若后来确需有限点集，可设计显式的 `p{dimension@x,y,z,...}`，而不把 `p{}` 偷换成任意谓词包装。保留长词作为文档中的类型名称，但首版语法只认短前缀，避免两套等价写法。

取舍：全词前缀读起来直白，但反复嵌套时太长；为每种类型发明新符号会与已有的 `#` tag、`$` 位置谓词和集合运算混杂；全部省略则使 `stone` 同时可能是方块、状态和物品。短字母加局部省略把歧义留在编译期。`m` 与 `$` 分别回答“哪些生物群系”和“哪些坐标位于这些生物群系”，因此两者并存有实际类型意义。

在**已有唯一预期集合类型**的地方可省略前缀，写 `{...}`：如 JSON 字段 `"items": "{minecraft:stone}"`、DSL 声明 `rock: BlockSet = {minecraft:stone};`。预期类型来自显式声明/`def` 注解、profile 字段或无重载函数参数；`rock := {...};`、`query {...};`、`current({...})` 都没有唯一预期类型，必须写 `b/s/i/m/f`。省略前缀只是**按预期类型解释字面量**，不引入隐式跨类型转换。`PosSet` 不适用 `{...}` 省略写法。`$id` 与完整 `namespace:path` 的 PosSet 简写仍保留。

字面量成员中的 `#` 一律是**该字面量所属注册表的 tag**。成员以逗号连接，含义是并集，顺序**没有优先级**。空字面量（如 `i{}`）表示空集合。**类型化字面量内部**的资源 ID 可省略 `minecraft:`，但规范化 IR 始终保存完整 ID；文档示例尽量显式写命名空间。裸的 PosSet 方块简写要求完整 `namespace:path`，避免 `stone` 在变量名与方块 ID 之间产生歧义。

标识符：`[A-Za-z_][A-Za-z0-9_]*`。资源 ID：`[namespace:]path`，允许 Minecraft 资源名中的小写字母、数字、`_`、`.`、`-` 和 path 中的 `/`。整数可有负号。范围为 `N`、`N..M`、`..M` 或 `N..`，端点包含；`..` 本身非法。属性 value 按 `[...]` 内的上下文读取，未引号值暂限 `[0-9A-Za-z_.+-]+`；如有特殊字符使用双引号，支持 `\\`、`\"`。DSL 文档注释为 `//` 行注释；严格 JSON profile 不支持注释。词法器不能把 `y=-64..32` 贪婪读成一个 value。

方块状态字面量沿用玩家熟悉的 `id[property=value]` 外观，但 core 自己解析符号，不调用 Minecraft 类；方块实体 NBT 和物品组件谓词暂不进入这个小语言。Vanilla 的物品命令谓词已经包含组件和子谓词等另一套语法，不能把方块的 `[...]` 属性语义原样套给物品。[Minecraft 官方 1.21.11 说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11)给出了其物品谓词形式。

## 4. 集合运算、声明和类型规则

运算优先级由高到低：`!`、`&`、`|`；括号改变分组。`!` 在 registry 集合上相对当前注册表取补集；在 `PosSet` 上是位置谓词的逻辑否定。位置表达式只可在给定有限 `scope/Universe` 内**枚举**，但可在边界外的有界 halo 评估邻居谓词；所以 `offset(0,-1,0,...)` 在 scope 下边缘仍能检查其下方。未加载的世界数据是 `Unknown`，`!Unknown` 仍是 `Unknown`，不能被误选中。

```text
building: ItemSet = {minecraft:stone, minecraft:dirt};
vertical_logs: StateSet =
    states_of({#minecraft:logs}) & property(axis=y);

other_logs := b{#minecraft:logs};

def exposed(s: PosSet): PosSet = s & adjacent(!s);
```

集合声明固定以 `名字:` 开始，可写 `s: BlockSet = b{...};` 或 `s := b{...};`；`s = ...;` 非法。后一种形式从右侧推导类型，但无前缀 `{...}` 必须能从上下文得到唯一集合类型。若右侧使用下节的 PosSet 简写，也必须有预期类型。声明是不可变绑定，不是运行时赋值。`def` 可以输入/输出任一集合类型和有界整数字面量，但不可递归、不可传递函数值；有展开深度、节点数、邻居读取半径限制。注册原语在编译时由可信宿主白名单确定，带签名、读取范围和成本元数据。

类型错误示例：

```text
b{minecraft:stone} & i{minecraft:stone}  // 不同集合类型
current(i{minecraft:stone})                    // current 不接受物品集合
target(i{minecraft:stone})                     // target 读取世界目标状态，不读材料清单
```

## 5. 固定的跨类型函数

| 函数 | 签名 | 含义 |
|---|---|---|
| `states_of(B)` | `BlockSet → StateSet` | B 内方块的全部合法状态 |
| `blocks_of(S)` | `StateSet → BlockSet` | 投影出所含方块类型 |
| `property(k=v)` | `→ StateSet` | 所有拥有该属性且值匹配的状态；无属性者不匹配 |
| `property_range(k,range)` | `→ StateSet` | 仅针对整数属性，匹配包含端点的值域 |
| `current(B or S)` | `BlockSet/StateSet → PosSet` | 当前位置的真实方块匹配 |
| `target(B or S)` | `BlockSet/StateSet → PosSet` | 已提供的目标状态匹配；仅有外部目标视图或已解析目标时可用 |
| `biome(B)` | `BiomeSet → PosSet` | 该位置的 biome 匹配 |
| `fluid(F)` | `FluidSet → PosSet` | 该位置的 fluid state 类型匹配，包括流动状态 |
| `changed(k)`、`same(k)` | `→ PosSet` | 当前/目标都有 k，值分别不同/相同 |
| `compare(k,rel)` | `→ PosSet` | 当前值相对目标值的 `lt/le/gt/ge`；只接受整数属性 |
| `has_target()`、`matches_target()` | `→ PosSet` | 目标已定义；当前状态满足目标的 goal policy |
| `inventory(I)` | `ItemSet → ItemSet` | 当前玩家库存快照中数量大于零的物品类型 |

`target(...)`、`changed(...)`、`same(...)`、`compare(...)`、`matches_target()` 在**使用物品集合现场生成目标的 profile 的 activation/select 阶段**不可用，否则会形成“先选坐标还是先选材料”的循环。它们可用于 Litematica 等已知目标的扫描，以及材料选定后的规则 guard。若目标为 `DontCare`，`has_target()` 为 false；若目标读取失败，结果为 `Unknown`。`inventory(I)` 对一次库存快照是纯函数；快照更新后相关计划失效，不得把结果永久缓存。`property_range`/`compare` 在版本绑定时验证属性确实是整数类型。

`property(axis=y)` 跨方块匹配时采用严格语义；tag 内有些方块没有 `axis` 不会令整个表达式报错，但它们不匹配。绑定阶段可以诊断“该 tag 中有成员不具备这个属性”或“没有任何成员可匹配”。

## 6. PosSet 原语和旧式查询简写

| 构造 | 签名/效果 | 说明 |
|---|---|---|
| `all()`、`none()` | `→ PosSet` | 恒真/恒假位置谓词；最终枚举仍受有限 scope 裁剪 |
| `x=range`、`y=range`、`z=range` | `→ PosSet` | 全局方块坐标，端点包含 |
| `dimension(id)` | `→ PosSet` | 匹配维度 ID |
| `box(x1,y1,z1,x2,y2,z2)` | `→ PosSet` | 两端点包含，当前维度 |
| `sphere(anchor,r)` | `→ PosSet` | `anchor` 可为 `point(x,y,z)` 或 `player`；r 有界 |
| `selection("name")` | `→ PosSet` | 已保存/桥接选区；宿主负责绑定 |
| `adjacent(P)` | `PosSet → PosSet` | 六个面邻居至少一个匹配 P |
| `offset(dx,dy,dz,P)` | `Int³ × PosSet → PosSet` | 在 p 判断 P(p + offset)；整数偏移有上限 |
| `light(range)`、`solid()`、`surface()` | `→ PosSet` | 可选的已声明 Minecraft 查询原语，读取当前世界 |

`selection()` 和 `sphere(player,r)` 可以作为有限 scope；`!current(s{minecraft:stone})` **不能独自成为自动作业的 scope**，因为无法枚举无限世界。宿主还会限制只扫描已加载 section、动作预算和交互距离。`surface()` 指世界方块接触空气；`boundary(P)` 可由 `P & adjacent(!P)` 定义，是集合边界，二者不同。

在明确期望 `PosSet` 的字段或 `query` 顶层，保留草案 001 的简写：

```text
minecraft:stone                 == current(s{minecraft:stone})
#minecraft:logs[axis=y]         == current(s{#minecraft:logs[axis=y]})
$minecraft:basalt_deltas        == biome(m{minecraft:basalt_deltas})
$#minecraft:is_nether           == biome(m{#minecraft:is_nether})
state(open=false)               == current(property(open=false))
```

`query` 对直接使用这些旧式 atom 的表达式给出 `PosSet` 预期类型；其他终结操作可按同样规则。普通无注解声明没有这个默认值：`rock := minecraft:stone;` 报缺少类型上下文，`rock: PosSet = minecraft:stone;` 合法。这样旧查询仍易写，新增类型又不使裸资源名多义。

## 7. 只读查询终结操作

`query E` 枚举任意集合类型；`count E` 和 `exists E` 可在不逐个构造位置对象的情况下工作。`PosSet` 查询由调用方提供有限 Universe；其他集合以目标版本注册表为域。`order by` 不是集合表达式的一部分，只在 query 终结阶段出现。

```text
query current(vertical_logs) & y=..63
order by y asc, z asc, x asc
limit 1000;

query i{minecraft:stone, minecraft:dirt}
order by id asc;

count current(s{minecraft:oak_stairs[facing=north]});
```

`limit N` 要求显式排序；若接受任意 N 项则写 `limit any N`。位置可按坐标或 `distance2(player)` 排序，registry 集合可按 `id` 排序；不提供任意排序函数。玩家锚点在一次 query 开始时固定。任意全局排序不保证能提前停止，`order by ... limit K` 可用 top-K；有空间下界的 `distance2(player)` 可做 section 优先遍历。

## 8. Profile 是 JSON 任务声明

拟定每个 `.latticium.json` 文件是一个 profile JSON 对象，不把 profile 块继续塞进 DSL grammar。schema 有版本号 `schema: 1` 和必需的资源 ID `id`；字段顺序无意义。表达式字段为 JSON 字符串，由 DSL parser 解析并由字段类型提供预期类型。缺失必需字段、重复键和未知键都报错。自动 profile 的 `activation.where` 和 `select.where` 都必须是 `PosSet`，`target.items` 必须是 `ItemSet`。

```json
{
  "schema": 1,
  "id": "user:basalt_lava",
  "activation": {
    "where": "$minecraft:basalt_deltas & dimension(minecraft:the_nether)",
    "mode": "while"
  },
  "scope": "sphere(player, 5)",
  "select": {
    "where": "fluid({minecraft:lava}) & $minecraft:basalt_deltas",
    "choose": "nearest"
  },
  "target": {
    "items": "{minecraft:stone, minecraft:dirt, minecraft:netherrack}",
    "choose": {"prefer": ["minecraft:stone", "minecraft:dirt", "minecraft:netherrack"]}
  },
  "policy": {
    "break": "deny",
    "max_actions_per_tick": 1,
    "max_actions_per_activation": 256
  }
}
```

`select.choose` 省略时默认为 `nearest`。JSON 的布尔值、整数和数组保留 JSON 类型，不能写成 DSL 表达式；只有 schema 标注的表达式字符串进入 DSL parser。表达式字符串使用 `expr + EOF` 入口，**不带分号**；嵌入的 `selection("...")` 等引号由 JSON 转义。若表达式引用外部集合声明或 `def`，profile 可选 `"use": ["user:common"]`，宿主按资源 ID 加载已验证的 `.latticium` 声明模块；显式依赖、禁止循环引用，同名绑定冲突报错。无 `use` 时只可引用内建原语。

这类 profile 的主体确实是对象。三条路线的建议如下：

| 格式 | 对用户与实现的影响 | 首版建议 |
|---|---|---|
| 原生 `profile { ... }` | 能与 DSL 声明放在一份文件，注释和表达式自然；但为配置结构再造一套语法，并迫使 DSL parser 管布尔值、数字和字段错误 | 从首版语法移除 |
| YAML | 缩进与注释便于手写；`#`、`!`、`&` 和 `{}` 在 YAML 中已有词法意义，DSL 表达式通常要引号或块字符串；还要明确 scalar 解析模式 | 暂不作为正式输入 |
| 严格 JSON | schema、编辑器和生成工具容易共用；表达式要加引号，内层引号需转义，也没有原生注释 | 作为 profile 交换与持久化格式 |

YAML 1.2.2 将 `#` 用于注释、`!` 用于 tag、`&` 用于 anchor，所以裸写 DSL 表达式存在实际冲突；JSON 对重复对象名的处理可能因实现而异，本项目将其定义为错误。[YAML 1.2.2 规范](https://yaml.org/spec/1.2.2/)、[RFC 8259 §4](https://www.rfc-editor.org/rfc/rfc8259#section-4) 若以后需要更友好的手写入口，可由 UI 编辑 JSON schema，或增加经过明确映射的 YAML 前端；两者都编译到同一个 ProfileSpec，不扩大 DSL 的表达式语法。

`activation.where` 的含义是**玩家脚下方块坐标是否属于该 PosSet**，在当前维度计算。`mode: while` 在属于集合期间持续维护作业；`mode: enter` 只在已知 false→true 时触发一次。初次启用时已经处于集合内默认不算 `enter`；如需立刻触发，可写 `initial: fire_if_inside`。世界数据 `Unknown` 时暂停判断，不把 Unknown 当成 false。手动 profile 可以不写 activation，由命令/API 启动。自动 profile 必须有可证明有界、可枚举的 scope。

`select.where` 产生候选坐标；`scope` 裁剪候选。`select.choose` 是**从当前可执行候选中选下一个坐标的排序策略**，不是集合运算。默认 `nearest`：按玩家脚下到方块中心的三维距离平方，再按 `(y,z,x)` 打破平局；玩家移动后重算。首版还可提供 `farthest`、`y_asc`、`y_desc`、`scan`。支撑依赖、交互可达性与禁止动作优先于选择顺序；最近但暂时不可执行的位置不会阻塞全部作业。这个字段与 `query order by` 有相似排序含义，但前者是动态任务调度策略。

`target.items` 是**可用于生成最终世界状态的材料集合**，不是已排序的回退列表。默认 `target.choose: most_available`：在库存中可用且当前可合法放置的物品里优先剩余数量最多者，平局按物品 ID。JSON 对象 `"choose": {"prefer": [...]}` 可列优先项，其余集合成员按默认策略回退；列出的物品必须属于 `target.items`，且不能重复。这一区分避免把物品集合的书写顺序误当优先级。

选择物品后，版本适配器枚举合法放置上下文，得到可达的最终状态；任务为该坐标冻结一个具体状态直到确认或显式重选。已有方块若属于目标可接受状态集合则直接完成。`target.states: StateSet` 可选，用来把可接受结果进一步限制为某种朝向等；无此字段时接受该材料经合法放置产生的状态。物品只有在版本适配器或规则包能声明其**可验证的世界终态**时，才可作为 `target.items` 的候选；没有这种映射的工具可供转换规则使用，却不能单靠 ItemSet 生成建筑目标。混合 tag 的不可用成员应报告，若没有任何可用成员则绑定失败。

`target` 另有两个互斥形式，避免把清空或蓝图硬塞进 ItemSet：

```json
{"target": {"clear": true}}
```

```json
{"target": {"source": "latticium:active_blueprint"}}
```

上面是互斥的 `target` 字段片段，分别表示清空和外部 provider 给出精确状态。蓝图形式可以附加 `using: ItemSet` 表达式字符串作为可用施工材料筛选，但它**不修改**蓝图的精确状态目标。`latticium:active_blueprint` 是中立的逻辑 provider 名，Fabric 由 Litematica、NeoForge 由可用的 Forgematica 桥接。`target(...)` 和 `matches_target()` 对该形式在 select 阶段可用。蓝图 air 默认为 `DontCare`，显式的 `"include_air": true` 才解释为清空目标。清空或重放需要 `policy.break` 允许；规则包没有权力覆盖这个约束。

`policy.break` 拟有 `deny`、`selected` 两级：`selected` 只允许破坏当前作业已领取的候选坐标及明确声明的多方块结构附属位置；不允许规则任意扩大影响区。`target.clear` 且 `break: deny` 属于可编译但无法推进的作业，预览应给出诊断。所有 profile 仍受客户端正常 reach、库存、加载区块和速率限制。

## 9. 其他完整用例

### 9.1 在集合层重用方块、状态与物品类别

```text
logs: BlockSet = {#minecraft:logs};
vertical := states_of(logs) & property(axis=y);
materials: ItemSet = {minecraft:stone, minecraft:cobblestone};
thin_snow: StateSet = s{minecraft:snow} & property_range(layers,1..3);
nether_biomes: BiomeSet = m{#minecraft:is_nether};

query current(vertical) & y=..64
order by distance2(player) asc
limit 100;

query materials order by id asc;
query current(thin_snow) order by y asc, z asc, x asc;
query biome(nether_biomes) & selection("search_area") limit any 100;
```

### 9.2 一次性选区填充，禁止破坏

```json
{
  "schema": 1,
  "id": "user:fill_selection",
  "scope": "selection(\"build\")",
  "select": {"where": "current(s{minecraft:air})"},
  "target": {"items": "{minecraft:stone}"},
  "policy": {"break": "deny"}
}
```

该 profile 没有 activation，必须手动启动；`select.choose` 未写，默认 `nearest`。已经不是空气的位置未被选中，符合只填空气的语义。`selection("build")` 是外部提供的有限 Universe。

若要用同一种楼梯物品、但只接受朝北的上半砖楼梯，可把该例的 target 改为：

```json
{
  "target": {
    "items": "{minecraft:oak_stairs}",
    "states": "{minecraft:oak_stairs[facing=north,half=top]}"
  }
}
```

这里 `items` 决定可用的物品，`states` 限定最终世界状态；放置预测负责找可产生该状态的点击上下文。

### 9.3 按 Litematica 活动蓝图施工

```json
{
  "schema": 1,
  "id": "user:print_active",
  "scope": "selection(\"active_blueprint\")",
  "select": {
    "where": "has_target() & !matches_target()",
    "choose": "nearest"
  },
  "target": {
    "source": "latticium:active_blueprint",
    "include_air": false
  },
  "policy": {"break": "deny"}
}
```

错误朝向能否通过交互修复由规则 planner 判断；若必须破坏重放而 policy 禁止，就明确报告阻塞，而不是悄悄跳过。已领取的工作项持续保留到确认结束，即使动态查询因施工而不再匹配。

### 9.4 在选区内清空，但只选择指定状态

```json
{
  "schema": 1,
  "id": "user:remove_open_trapdoors",
  "scope": "selection(\"demolition\")",
  "select": {
    "where": "current(s{#minecraft:trapdoors[open=true]})",
    "choose": "y_desc"
  },
  "target": {"clear": true},
  "policy": {"break": "selected"}
}
```

### 9.5 按位置集合启动，无需写独立事件语言

```json
{
  "schema": 1,
  "id": "user:ground_cleanup",
  "activation": {
    "where": "dimension(minecraft:overworld) & selection(\"home\") & y=60..100",
    "mode": "enter"
  },
  "scope": "selection(\"home\")",
  "select": {"where": "current(s{minecraft:air})"},
  "target": {"items": "{minecraft:dirt}"},
  "policy": {"break": "deny", "max_actions_per_activation": 64}
}
```

玩家进入家园位置集合才启动一次；`select.choose` 仍默认最近。这里的 activation 是位置成员判断，不是额外脚本回调。

## 10. 拟定语法与静态检查

```ebnf
document          = { declaration | function_declaration | terminal } ;
declaration       = ident, ":", [set_type], "=", expr, ";" ;
function_declaration = "def", ident, "(", [parameters], ")",
                       ":", set_type, "=", expr, ";" ;
parameters        = parameter, { ",", parameter } ;
parameter         = ident, ":", (set_type | "Int") ;
set_type          = "PosSet" | "BlockSet" | "StateSet" | "ItemSet"
                  | "BiomeSet" | "FluidSet" ;

expr              = union ;
union             = intersect, { "|", intersect } ;
intersect         = unary, { "&", unary } ;
unary             = "!", unary | primary ;
primary           = set_literal | pos_atom | built_in_call | derived_call
                  | ident | "(", expr, ")" ;

set_literal       = [ "b" | "s" | "i" | "m" | "f" ], "{",
                    [state_member_list], "}" ;
state_member_list = state_member, { ",", state_member } ;
tagged_id         = ["#"], resource_id ;
state_member      = tagged_id, ["[", property_pair,
                    { ",", property_pair }, "]"] ;
property_pair     = ident, "=", value ;

pos_atom          = ("x" | "y" | "z"), "=", int_range
                  | state_member                   (* 仅完整 namespace:id 简写 *)
                  | "$", tagged_id                (* biome 简写 *) ;
int_range         = integer | [integer], "..", [integer] ;

terminal          = "query", expr, ["order", "by", order_key,
                    { ",", order_key }],
                    ["limit", (positive_int | "any", positive_int)], ";"
                  | ("count" | "exists"), expr, ";" ;
order_key         = ("x" | "y" | "z" | "id" | "distance2(player)"),
                    ["asc" | "desc"] ;

ident             = letter_or_underscore, { letter_or_digit_or_underscore } ;
integer           = ["-"], digit, { digit } ;
positive_int      = digit_1_to_9, { digit } ;
resource_id       = [namespace, ":"], path ;
value             = unquoted_value | quoted_string ;
```

`built_in_call` 的具体参数形态由第 5、6 节的固定签名定义（例如 `property_pair`、整数、锚点或集合表达式）；`derived_call` 必须解析到同文档或显式引用模块中声明的 `def`。词法中的 `namespace`、`path`、`unquoted_value` 和字符串转义遵循第 3 节；`int_range` 的 `..` 两侧至少有一个整数。`set_literal` 的成员先按统一形状解析，绑定时才要求无前缀形式有唯一预期类型，并拒绝非 `StateSet` 成员上的属性列表；短前缀也只在下一 token 为 `{` 时解释为字面量。方块简写仅在预期类型为 `PosSet` 时允许无类型的 `state_member`，且需要显式 namespace；集合字面量内可省略 namespace。

`declaration` 中冒号必需：`s: BlockSet = b{...};` 与 `s := b{...};` 都合法，`s = b{...};` 非法。冒号后省略类型时由右侧推导；对一个声明文件，重名声明和非法前向/循环依赖报错。profile 不在此 EBNF 中，而由 JSON 语法加如下 schema 校验：

| 路径 | JSON 类型与约束 |
|---|---|
| `schema`, `id`, `use` | `schema` 必须是整数 `1`；`id` 必需且是资源 ID 字符串；`use` 可选，为无重复资源 ID 字符串数组 |
| `activation` | 可选对象；若存在须有 `where`（PosSet 表达式字符串）和 `mode`（`enter/while`）；`initial` 可选，为 `ignore/fire_if_inside` |
| `scope`, `select` | `scope` 必需，为有界 PosSet 表达式字符串；`select` 必需，其 `where` 是 PosSet 表达式字符串，`choose` 可选，为 `nearest/farthest/y_asc/y_desc/scan` |
| `target` | 必需对象，恰有 `items`（ItemSet 表达式字符串）、`source`（资源 ID 字符串）或 `clear: true` 一个主形式；`items` 可配 `states`（StateSet 表达式字符串）与 `choose`；`source` 可配 `using`（ItemSet 表达式字符串）与 `include_air`（布尔值） |
| `target.choose` | 省略或字符串 `most_available`；也可为仅含 `prefer` 的对象，其值是非空、无重复的物品资源 ID 字符串数组 |
| `policy` | 可选对象；`break` 为 `deny/selected`，默认 `deny`；`max_actions_per_tick` 与 `max_actions_per_activation` 为正整数 |

JSON reader 必须在建对象时拒绝重复键，而不是静默覆盖；schema 再拒绝未知键和主形式无关的键。`use` 仅加载声明和 `def`，不能借导入执行 query。profile 的表达式错误应指向 JSON 文件路径、JSON Pointer 字段及字符串内部的偏移。手动 profile 可省略 activation，自动 profile 还需可证明有界的 scope。

未知函数、错误类型、递归 `def`、无界自动 scope、超限 offset/sphere、目标来源不匹配以及不受支持的 ID/tag/property 都给出带位置的诊断。函数名虽然有统一的调用外观，但**只有内建、可信宿主注册和合法 `def` 的白名单名字**能通过编译；不存在“按名字查找任意 Java 方法”或运行时用户函数值。

离线编译可完成语法、类型、递归、scope 结构和策略检查；26.2/26.3 的实际 registry、tag、放置物品→方块状态结果由各版本 adapter 绑定。规则包继续复用 `StateSet`、`ItemSet` 和 `PosSet` 条件，但其状态转换字段受独立、有限的规则 schema 约束。

## 11. 从表达式到高速扫描

### 11.1 逻辑图与物理计划

借鉴 TensorFlow 的**图编译思想**，而不是引入 TensorFlow：文档只在加载、修改或注册表重绑定时解析。`def` 有界展开后，得到有类型的无环逻辑图；常量折叠、同构子表达式合并、三值语义下安全的交并化简、有限范围裁剪，再按照数据来源与成本生成扫描物理计划。例如 `P | !P` 在 `P=Unknown` 时仍为 `Unknown`，绝不可按二值布尔代数折成 `all()`。`b{...}` 等注册表集合首先绑定为当前 registry epoch 的整数 ID 集合或位图；tag 展开结果带版本号。物理计划的缓存键包含 DSL 文本/静态参数、目标 Minecraft 版本、registry/tag epoch、目标 provider 代次和选区代次。玩家坐标与库存快照等高频变化值是运行时**动态参数**，不因玩家移动每 tick 重编译。

逻辑图允许公共子表达式共享，但最终不应靠“每坐标遍历一次 AST”执行。按运算域降低：注册表集合先算一次；`x/y/z`、box、sphere 等先做 section 范围判定；`current(...)`、`biome(...)`、`fluid(...)` 使用版本适配器提供的 section 快照；`&/|/!` 融合为位图操作；只有剩下的少数候选进入世界交互可达性、状态转换规则和材料选择。对 `A & B` 可把便宜且选择性高的约束前推，但三值 `Unknown`、需要不同数据快照的原语以及有读取半径的原语必须保持语义；成本统计只能影响等价的执行顺序，不能改变结果。

建议的物理算子包括 `RegistryBitmap`、`SectionBounds`、`PaletteMatch`、`PositionMask`、`HaloShift`、`CandidateIterator`、`GoalDiff`、`RulePlanner` 和 `ActionRecheck`。每个宿主注册原语声明返回类型、可向量化/位图化能力、读取半径、数据依赖和成本。未知原语走受预算约束的逐点慢路径，不能任意进入扫描内核。可以在编译器中保留多种专门化计划（例如仅当前方块、当前+邻居、蓝图差异）；先用解释执行的物理算子，只有基准证实算子分派显著昂贵时才考虑字节码生成。

### 11.2 Java 中的数据布局

一个 16³ section 有 4096 个位置，一个布尔位置掩码可用 64 个 `long`，即 512 字节。已加载数据可能部分未知，因此一般用 `T`（已知为真）和 `K`（已知）两张掩码，且 `T ⊆ K`；`!P` 是 `K & ~T`，并保留同一个 `K`。交、并按照 Kleene 三值逻辑计算，不能把未知位当作 false 后再取补。全已知的常见路径只用一张掩码；空/满 section 直接跳过或接受。`adjacent/offset` 可用带相邻 section halo 的位移与位或实现，但必须正确处理 section 边界和未知位。

方块状态匹配不应对 4096 个位置反复查字符串、tag 或 Java `BlockState` 对象。对每个 section 的局部 palette，按注册表 state ID 预先判断每个 palette 项是否匹配，再扫描压缩的 palette 索引生成位置掩码；palette 全不匹配时立即跳过。具体 palette 读取与压缩格式由四个版本目标各自的 adapter 封装。生物群系、流体和目标蓝图也尽量转换为同样的局部整数列或掩码。使用原始类型数组、连续 section 数据、可重用的线程局部 scratch，避免逐位置分配 `BlockPos`、lambda、装箱集合和虚调用；只在候选实际进入 planner/执行器时构造必要的 Minecraft 对象。这是 Java 完全能实现的数据导向布局，不以 JNI、`Unsafe`、堆外内存或 GPU 为首版前提。

`select.choose: nearest` 不做每 tick 的全局排序。先用 section 包围盒到玩家的最小距离平方组织优先队列，再在 section 中按距离检查位图候选；当未访问 section 的距离及平局坐标下界不可能超越当前**可执行**候选时停止。被阻塞的候选记录原因与依赖版本，避免每 tick 反复完整规划；玩家移动、库存或世界变化时再评估。若附近全不可执行，最坏情况仍可能遍历整个有限 scope；每 tick 预算只限制工作量，不改变最终选择语义。`order by distance2(player) limit K` 也可复用空间下界；任意坐标排序则可能需要 top-K 或遍历全域。`select.choose: scan` 可选择稳定的 section/局部索引顺序，适合更低调度开销。

### 11.3 快照、失效与实际瓶颈

Minecraft 世界读取与交互入口由客户端游戏线程上的 adapter 负责；若后台线程扫描，只读取游戏线程按预算捕获的**不可变 section 快照**，不能直接跨线程读活世界。每 tick 限定快照数量、扫描 section 数、规则规划次数和动作次数；若超预算，下一 tick 继续。区块卸载成为 `Unknown`，执行前重查当前位置、目标、库存、reach 与权限策略；一次动作后的观察结果决定完成、重试或重新规划。

缓存以 section 及依赖代次为单位失效：方块改变使本 section 与涉及邻居/offset 的 halo 重算；光照与生物群系相关原语须使用相应变化信号，若拿不到可靠信号就缩短缓存寿命；tag/数据包重载、蓝图/选区更新、维度切换使相关绑定或计划失效。玩家移动只影响动态 scope 与排序，库存变化只影响材料与可执行性，不应冲掉无关的方块掩码。对外部 mod 原语也要求明确失效来源，否则仅能临时求值。

性能判断应分开测：① DSL 解析/绑定冷启动；② 每 section 快照时间与扫描吞吐；③ 每 tick 主线程 p95/p99 耗时和分配字节；④ 缓存命中率/失效范围；⑤ 候选 planner 与动作确认延迟。测试数据既要有合成的高/低选择性 palette，也要有真实世界、蓝图差异和大量 `Unknown` 区块。优先优化快照、palette 命中和缓存失效，再看位运算 SIMD。**纯 core 以 `--release 21` 编译，并在 Java 21 运行测试**；不能因开发机或游戏进程是 Java 25 而意外使用更高版本 API。四个 Minecraft 适配器按游戏要求使用 Java 25 构建和运行，但调用的 core 公共 API 保持 Java 21 可用。JDK 21 的 Vector API 本身仍属于 incubating module，不能作为 core 必需依赖；仅在可选路径和实际基准证实收益后考虑。[Oracle JDK 21 Vector API 模块说明](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.incubator.vector/module-summary.html)

图优化的边界是：世界是稀疏、可变且经常部分未知的；放置规则含邻居、库存和实际交互验证。把整片世界当连续 GPU tensor 或把 planner 对每个位置全量运行，都会把主要成本放错地方。扫描应尽可能便宜，精确规则规划只花在接近执行的候选上。

## 12. Parser 路线与语言复杂度控制

Parser 位于**加载时路径**，不进入 section 扫描热路径。性能目标仍应是线性扫描、可预测内存与快速命令反馈，但每 tick 性能主要由第 11 节的执行计划决定。DSL 的纯语法核心是声明、`def`、终结操作、`! & |` 三层优先级、资源 ID、范围和五种集合字面量；profile 的对象结构由 JSON reader 负责。真正复杂的是**绑定后的类型、registry、scope、目标阶段和规则检查**。把这些检查放在独立 binder，不通过词法器猜 Minecraft 类型。

| 路线 | 收益 | 代价与风险 |
|---|---|---|
| ANTLR 4 生成 lexer/parser | EBNF 易核对，语法演化、错误恢复及工具支持成熟；有 lexer mode 可处理属性上下文 | 生成物与运行时依赖进入离线 core；默认 token/parse tree 路径会产生对象，具体开销须量测；`{...}` 的预期类型仍需 binder 处理 |
| 自建 lexer + Pratt 表达式 parser + 递归下降文档 parser | 词法与 AST 可直接按此小语法设计，token 可保存源区间而不复制字符串；资源 ID、`-64..32`、属性值上下文容易精确控制 | 必须自己维护错误恢复、源位置、补全接口与语法测试；复杂度扩张时维护成本上升 |
| 正则切分或 parser 直接调用 Minecraft 命令解析器 | 初期代码少 | 范围、ID、属性和省略前缀上下文交织，错误位置与独立 JVM core 很难保持一致；不建议作为语言主体 |

首版倾向**自建单遍 lexer + Pratt/递归下降**，因为当前语法规模可控，且需要明确的上下文读取与低分配；这是一项待原型验证的工程选择，而非 ANTLR 必然较慢的结论。ANTLR 官方文档确认生成的解析器仍依赖其 runtime，并提供 lexer mode；若编辑器错误恢复/补全需求先于低分配目标，或语法迅速扩大，可改用 ANTLR 的独立 lexer grammar 与 parser grammar。比较时以同一语法、同一 AST 输出、同一诊断质量测冷/热解析时间、分配量和 JAR 体积，不能只比较“能成功解析”的最小例子。[ANTLR 入门](https://github.com/antlr/antlr4/blob/dev/doc/getting-started.md)、[lexer mode 说明](https://github.com/antlr/antlr4/blob/dev/doc/lexer-rules.md)

自建路线的具体约束：lexer 用 `CharSequence` 索引和起止 offset 表示 token，按需读取名字/资源路径片段、`#` tag、整数、`..`、字符串与标点；进入 `[...]` 才按属性值规则读取，不能把 `y=-64..32` 吞成属性值。parser 不回溯整个表达式：Pratt/固定优先级处理集合运算，文档入口处理声明与终结操作；短前缀仅在下一 token 为 `{` 时识别，裸 `{...}` 只生成待定类型的集合 AST。随后 binder 双向传播显式期望类型，但不为了猜类型任意枚举重载；`current({...})` 与无注解 `x := {...};` 按第 3 节报错。所有诊断保留源区间，不能为提速丢掉定位。

对于用户文档与外部规则包，先在语法层限制源大小、嵌套层数、字符串长度，再在 binder 限制 `def` 展开深度、节点数、邻居读取半径和 scope。无递归 `def`、无任意循环、无需要指数级回溯的语法。自动补全可复用 token/AST 前缀与静态签名，但不要求在每次按键后重绑定整份世界 registry。最终交给运行时的是不可变的有类型 AST/逻辑图与按版本绑定的计划，不是源文本或 parser 对象。

## 13. 词法与标点的进一步取舍

### 13.1 分号可否直接移除

可以，但需要定义另一套**语句边界规则**，不是简单从 EBNF 删掉 `";"`。例如“顶层一行一条语句、括号内换行不终止、行末运算符表示续行”能够工作，却让换行成为语法；`query` 的 `order by`/`limit` 跨行、长 `def`、错误恢复和格式化都必须遵守明确续行规则。完全按下一个 `名字:` 或 `query` 猜上一句结束，也会让漏写运算符的错误延后报在下一句。

首版暂**保留 `.latticium` 文档语句末尾的分号**，与第 10 节给出的 `declaration = ident, ":", [set_type], "=", expr, ";"` 一致；`s := b{...};` 已经足够简短。JSON profile 的表达式字符串和单次命令输入只解析 `expr + EOF`，不写分号。先不同时接受“有/无分号”两套文档写法，避免编辑器与错误诊断出现分支。若真实使用反馈表明分号阻碍手写，再用一份完整的换行/续行规范替换文档语法。

### 13.2 关键字应由谁判定

lexer 只负责稳定的词形边界：名字/资源路径片段、整数、字符串、`:`、`=`、`{}`、`[]`、`..`、`!&|` 等。`def/query/count/exists` 是**顶层语法位置**的软关键字；`order/by/limit/asc/desc` 是 query 尾部的软关键字；`b/s/i/m/f` 仅在后接 `{` 时表示字面量类型；`BlockSet` 等类型名由 parser/binder 在类型位置识别。这样 `order: BlockSet = b{minecraft:stone};` 可成为合法声明，不需要把 `order` 全局保留。顶层先看 `ident :` 判定声明，再看 `def ident (` 或终结操作，分支有限且确定。

`s:BlockSet = ...` 和 `minecraft:stone` 说明**不宜由 lexer 全局贪婪地产生 `RESOURCE_ID` token**：两者都有相邻的名字、冒号、名字。建议保留这些原子 token 及源区间，让 parser 在资源 ID 位置按相邻性合成，并在声明位置解析 `:` 与类型名。属性值同样由上下文处理；`property(open=false)` 中的 `false` 是待绑定的属性值符号，而 JSON 中 `false` 是布尔值。lexer 可以为少数结构词提供快捷 token，但那是实现细节，不能改变软关键字与合法标识符的语言规则。

### 13.3 是否加入浮点字面量

首版**不加入通用 Float/Double 字面量类型**。目前 x/y/z 坐标、范围、offset、`light`、方块整数属性和作业预算都是整数；`nearest` 的距离平方在运行时可用 `double` 计算，并不需要让 DSL 输入浮点数。`sphere(player, 5)` 定义为以玩家脚下方块坐标为锚点、半径为整数方块距离；不悄悄把 `5.5` 截断成 `5`。`property(k=1.5)` 若属性是字符串枚举仍按该属性的 value 验证，而不自动解释为数值比较。

以后若出现明确需求，例如以玩家连续位置为中心的 `sphere(player_exact, 5.5)` 或浮点阈值原语，再加独立的有限实数字面量和参数类型。建议届时只接受 `digits "." digits`（及可选前导 `-`），不接受 `.5`、`5.`、`NaN`、`Infinity`；优先识别 `..`，确保 `1..3` 始终是整数范围。绑定时拒绝溢出与非有限值，并为几何边界规定舍入/包含语义。JSON 中预算字段即使语法允许 `1.5`，schema 仍须拒绝非整数。
