# Latticium 架构讨论草案 001

状态：讨论稿；没有实现代码。2026-09-29。发行形态和自动化方案续见 [讨论草案 002](002-packaging-and-automation.md)；类型化 DSL 和 profile 的最新版定义见 [讨论草案 003](003-typed-dsl.md)，以 003 为准。

## 目标与边界

目标是独立的 Minecraft 世界施工引擎：查询哪些坐标需要处理，确定每个坐标的目标，再通过真实玩家交互使世界趋近目标。填充、替换、清除和按蓝图施工共用同一条流水线。Litematica 和 WorldEdit 只作为可选的目标或选区来源；核心运行不依赖二者。本稿讨论的是选区、目标与施工能力的替代，不预设第一版复制 Litematica 的全部渲染和编辑 UI。

参考对话中较新的 DSL 决定优先于较早的提议：`!`、`&`、`|`，`let`，无递归 `def`，最外层 `query/order by/limit`，无函数值，无通用脚本执行。多版本组织参考 [xaero-world-map-bridge](https://github.com/billstark001/xaero-world-map-bridge)；当前 [JohnMuyuan/litematica-printer](https://github.com/JohnMuyuan/litematica-printer) 的 README 仍要求客户端安装 Litematica，其选区功能也依赖 Litematica。

## 核心模型

```text
JobSpec = Universe × CandidateQuery × TargetProvider × ConstructionPolicy

有限 Universe + 当前世界/目标数据
    → section scanner 执行 CandidateQuery
    → 目标与当前状态的结构化差异
    → 状态转换 planner 与依赖调度
    → 动作计划
    → 版本化 executor
    → 服务端世界观察、确认、必要时重新规划
```

- `Universe` 是外部给出的有限、可枚举工作区；DSL 中的 `!` 不会造成无限扫描。
- `CandidateQuery` 是坐标集合表达式，不规定最终要放什么。
- `TargetProvider(pos)` 给出 `ExactBlockState`、`Clear` 或 `DontCare`。目标缺失、区块未加载等读取失败还需要独立的 `Unknown`，不可当作 `DontCare` 或 false。
- `ConstructionPolicy` 决定能否破坏方块、是否只填空气、物品和交互限制、确认与重试预算等。规则包不能自行提升这些权限。
- 替换操作的源谓词可能在施工后不再成立。任务必须把已领取的候选和期望结果保留到确认结束，不能因动态查询结果变化而悄悄丢失工作项。

## 一种 DSL，两类用途

纯 Java `dsl-core` 负责词法、语法、带源位置的 AST、静态类型、`let`/`def` 展开与限制、序列化和诊断。这里没有 Minecraft 类、注册表和世界对象。`#namespace:tag[state=value]`、`$#namespace:biome_tag` 等只被解析成符号；目标版本的 adapter 再核验 ID、tag、属性和值。

表达式的结果始终是坐标谓词/集合：`!`、`&`、`|`；`x/y/z=<整数范围>`；block/tag 谓词；biome/tag 谓词；受信任宿主注册的有界、纯函数，例如 `state`、`adjacent`、`offset`。名字建议 `[A-Za-z_][A-Za-z0-9_]*`；属性 value 按解析上下文读取，允许 Vanilla 风格的未引号值。`#` 只表示 tag，`-` 不作为集合运算符。`def` 是不可递归、不可传递函数值的编译期 AST 抽象；注册原语仅由宿主或已安装的 mod 提供，不由查询文本定义 Java 逻辑。

为了让同一 DSL 服务方块状态规则，建议加入**显式数据视图**，而非第二套条件语言：

```text
let wrong_delay =
    has_target()
    & current(minecraft:repeater)
    & target(minecraft:repeater)
    & changed(delay);

query wrong_delay
order by y asc, z asc, x asc
limit 512;
```

`current(E)` 与 `target(E)` 在相同坐标、不同状态视图评估 E；裸方块谓词在交互查询中可默认 `current`，规则文件应要求显式写出视图。`changed(property)`、`same(property)` 要求双方都有该属性，否则返回 false/诊断，不做字符串猜测。`has_target()` 排除 `DontCare`。这些函数仍返回集合，不开放任意标量表达式。版本一可只实现规则真正用到的少数函数。

规则包是**结构化的转换声明**，其 `when`、`verify`、`requires` 字段引用同一个 DSL 表达式 AST；动作与效果是有限枚举的类型化字段，例如 `interact`、`place`、`break`、`use_item`，没有 `while`、Java 表达式或任意回调。这样查询语言依旧是只读的，规则包也不能成为通用脚本。规则 schema 与 DSL AST 可分别版本化。

`query` 是集合到有序结果流的终结操作。排序不进入 `SetExpr`；`limit` 没有排序时建议必须明确写 `limit any N`。全局任意排序不保证流式；坐标顺序可按遍历顺序生成，`order by ... limit K` 可用 top-K，距离顺序可利用 section 距离下界。不能把“lazy”宣传成所有查询都可提前停止。

一个细节需要固定：Universe 限制**输出坐标**，`offset/adjacent` 为判断边界位置可在有限半径内读取 Universe 外的邻居。根表达式的补集仍只输出 Universe 内的坐标；区块未加载或数据不可得时产生 `Unknown`，`!Unknown` 仍为 `Unknown`，不会误选中它。注册原语须声明读取半径、纯度、成本与所需数据类型，scanner 才能申请 halo、做短路与预算控制。

## 离线规则编译器与 planner

建议单独的 `planning-core` 只依赖 `dsl-core` 和中立数据模型：资源 ID、属性映射、坐标、目标、抽象世界快照、背包/能力、动作原语。它能在普通 JVM 上完成语法和类型检查、规则冲突与循环检查、有限状态图搜索，以及用假世界/录制快照验证计划。

编译分两阶段：

1. **离线编译**：规则结构、表达式、引用、递归、半径和资源预算合法；产生包含符号 ID 的可移植 IR。
2. **版本绑定**：Minecraft adapter 根据该版本和已加载 mod 的 registry/tag/property schema 检查符号、加载覆盖规则，并提供真实世界与放置预测能力。缺失的 mod ID 可按规则包的依赖声明停用该规则，不能静默解释为任意方块。

状态对比保留每个属性的差异。`placement`、`interaction`、`world-derived`、`transient`、`resource-dependent` 可作规则元数据，而不是在 core 中给属性硬编码永久类别：同名属性在不同方块或 mod 中可能有不同机制。planner 搜索可达的状态转换并计入破坏、物品、交互次数和风险成本。对于雪层 7→3，允许破坏时可找到“破坏→重新放置→累加”；禁止破坏时应返回有理由的 `NoPlan`。`Skip` 不等于“目标已完成”。

一般方块的放置状态由对应 Minecraft 版本的 `PlacementOracle` 枚举合法点击面、命中点、朝向、潜行等，再调用游戏或模组自己的放置逻辑预测。离线 planner 通过同一接口使用测试 oracle；因此**离线可运行不等于脱离 Minecraft 也能完整预测所有模组放置行为**。不可预测或 GUI/自定义包等操作可由少量 native SPI 处理，规则包默认只能调用有限动作原语。

规则声明每个动作的前置条件、预期影响范围、可观察后置条件和预算。随机或服务器相关效果只声明可能的推进方向；executor 每次动作后观察权威世界、确认或重新规划。多方块结构、支撑、流体和红石的邻居更新需要跨坐标依赖调度；单格状态图本身不足以保证整片区域的完成。

规则包可由本项目、数据包或其他 mod 提供，以资源 ID、tag、属性签名和目标版本范围匹配；不按 Java 方块类名猜测行为。加载时记录规则来源，检测相互冲突的效果与重复 ID；明确覆盖或禁用规则，不能依赖一个隐蔽的“第一个匹配者获胜”顺序。规则和原语都带版本/能力要求，缺失能力时给出可解释的 `Unsupported`。

## Minecraft scanner/executor 与多版本构建

参考桥接项目的 `root source + shared-mc-<release> + fabric-common/neoforge-common + 每版本每 loader 产物` 模式，但为本项目增设真正独立的 `dsl-core` 和 `planning-core`。目标版本矩阵应显式列出 Minecraft、Java、loader、映射和规则包兼容范围；每个二进制不兼容的 release line 单独出 JAR。纯 JVM core 使用目标矩阵中最低可用 Java 语言级别。不要从参考项目直接推断本项目必须支持其全部八个产物。

```text
dsl-core                  无 Minecraft
planning-core             无 Minecraft；抽象 world / placement oracle
rules-vanilla             声明式规则数据与版本适用范围
versions/shared-mc-X      X 版 registry、BlockState、placement、world snapshot
versions/fabric-X         启动、事件、交互、UI
versions/neoforge-X       启动、事件、交互、UI
integrations/*            可选 Litematica、WorldEdit 等目标来源
```

scanner 只遍历有限 Universe 的 section，使用 4096-bit bitmap 做集合代数；优先坐标过滤、palette 排除与短路。仅读取已加载 chunk，在主线程取安全快照；可把纯 bitmap 计算移到工作线程。section 版本戳、邻域 halo 与 dirty queue 用于失效和重新扫描。执行器在主线程做 reach、物品、点击/挖掘、旋转和交互；发动作不等于成功，必须按世界状态确认。本项目设计时不考虑服务端伴生模组。

兼容性验证分三层：纯 JVM DSL/规则测试；各目标版本的编译和启动；在真实客户端/服务端上的交互与重试测试。与参考桥接项目类似，声明“保证支持”的版本要经过对应二进制和运行验证。

## 建议的第一条可验证纵切面

先定义 `Universe`、目标三态、DSL 状态视图与 `Unknown` 语义，再选**一个** Minecraft/Fabric 版本走通：有限盒子内的 `fill / clear / replace`，普通放置、破坏、服务器确认，并用雪层和中继器验证状态转换图。接着加入邻居依赖及第二个 Minecraft 版本，最后再扩到第二 loader 和 Litematica/WorldEdit bridge。这个顺序用于验证抽象边界，不是预先承诺完整 Litematica 功能覆盖。

已确定的首批目标为 Minecraft 26.2、26.3 的 Fabric 与 NeoForge，共四种发行目标；仅客户端。目标来源先做内建常量和选区，并集成已安装的 Litematica 或其 NeoForge 移植；`.litematic` 文件导入由原模组负责。
