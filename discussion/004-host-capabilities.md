# Latticium 宿主能力接口草案 004

状态：接口设计讨论稿，2026-09-29；没有实现代码。承接 [架构草案 001](001-architecture.md)、[发行草案 002](002-packaging-and-automation.md) 和 [DSL/profile 草案 003](003-typed-dsl.md)。本文的接口名与伪签名用于确定职责和数据流，尚不是稳定的 Java ABI。

## 1. 宿主到底是什么

这里的“宿主”是每个 Minecraft 版本及 loader 的客户端适配层，加上安装在同一客户端的可信 mod 注册的扩展。它向 `dsl-core` 和 `planning-core` 提供**符号绑定、有限世界事实、目标/选区来源、放置预测及正常玩家交互**。core 只认识中立 ID、状态、坐标、快照和动作意图，不导入 `net.minecraft.*`、Fabric 或 NeoForge 类。

```text
Java 21，离线可运行：DSL AST / binder / 物理计划 / 规则编译器 / planner
             ↑                    ↓ 中立值与接口
Java 25 游戏进程：能力目录 → 版本绑定 → 主线程快照 → section 扫描
                                      → 候选规划 → 主线程动作 → 观察确认
             ↑
Fabric/NeoForge 生命周期、可选 Litematica/Forgematica 桥接、其他可信 mod 扩展
```

Java 21 下限只适用于独立 `dsl-core`/`planning-core`；26.2/26.3 的游戏进程要求 Java 25，见 [草案 003 的版本表](003-typed-dsl.md)。一个用户只安装 Litematica 时，Latticium 不参与其流程；安装 Latticium 但未启动作业时，宿主只注册入口与可选 provider，不持续扫描或执行动作。宿主始终是**客户端**，不设计服务端伴生接口。

## 2. 能力清单与职责

| 能力种类 | 中立接口建议 | 首版提供者 | 失败时的结果 |
|---|---|---|---|
| 符号/注册表 | `RegistryCatalog` | 每个版本适配器，必需 | 绑定诊断；不把未知 ID 解释为空集合 |
| 世界事实 | `SectionSnapshotSource` | 版本适配器，必需 | `Unknown` 区域、延期或明确 `Unsupported` |
| 玩家与库存 | `PlayerSnapshotSource` | 版本适配器，必需 | 作业暂停或材料暂不可用 |
| 有限选区 | `SelectionProvider` | 内建盒子/保存选区，桥接可选 | 找不到选区则 profile 绑定失败或暂停 |
| 外部精确目标 | `TargetProvider` | Litematica/Forgematica 桥接，可选 | 来源不可用，不退化为清空或任意目标 |
| 物品→终态 | `PlacementOracle` | 版本适配器及显式扩展，必需接口 | 该物品/状态报告 `Unsupported`，不猜放置结果 |
| 转换规则 | `RuleSource` | 内建声明式规则，其他 mod 可选 | 无计划并说明缺少哪种转换 |
| DSL 查询原语 | `QueryPrimitive` + 必要的 `FactSource` | 内建 `light/solid/surface` 等，扩展可选 | 缺失原语为编译错误；事实缺失为 `Unknown` |
| 正常玩家动作 | `ActionGateway` + `ObservationSource` | 每个版本/loader 适配器，必需 | `Deferred/Rejected` 或等待观察，绝不把发送视作成功 |

`target.items` 与 `target.clear` 是 core 的目标构造形式，不要求外部蓝图 provider。内建常量目标和有限选区在四个发行目标都可工作；Litematica 系桥接只负责读取已由其加载/激活的目标与选区，Latticium 不导入 `.litematic` 文件。`ActionGateway` 是 Latticium 自己的单一执行出口，扩展可以提供放置预测或少量经审查的 native 转换提议，但不能替换安全检查和策略门禁。

实现位置也应明确：注册表、BlockState、section 和原版放置预测主要跟 **26.2/26.3** 的 Minecraft API 走；客户端生命周期、命令与动作提交主要跟 **Fabric/NeoForge** 的 loader API 走；蓝图读取则是独立的**可选桥接**。四个发行 JAR 装配这三层，但只共享中立契约，不让 core 根据 loader 名称分支。

| DSL/profile 用法 | 编译后的宿主需求 |
|---|---|
| `current(s{...})`、`fluid(f{...})` | `RegistryCatalog` 绑定集合；`SectionSnapshotSource` 提供 state/fluid 列 |
| `$minecraft:basalt_deltas`、`biome(m{...})` | biome 注册表与 section biome 列 |
| `selection("build")`、`scope` | 可枚举 `SelectionSnapshot`；动态 scope 另需玩家快照 |
| `target(s{...})`、`matches_target()` | 外部 `TargetSnapshot` 或已冻结的材料目标；目标已知 mask |
| `inventory(i{...})`、`target.choose` | 库存快照、物品 ordinal 与可放置性 oracle |
| `surface()`、`adjacent(P)` | 方块事实与至少一格 halo；变更时连邻接 section 失效 |
| `select.choose: nearest` | 玩家脚下坐标、section 距离下界、候选可执行性；不要求 DSL 浮点值 |

## 3. 一个带阶段的能力目录

能力用稳定的 `namespace:path` ID 标识，例如 `latticium:current_state`、`latticium:active_blueprint`。`ownerModId` 是诊断来源，不参与用户语法。签名和运行约束在注册时固定；是否有活动世界、活动 placement 或足够库存则是**会话可用性**，不应反复删除并重新注册能力。

```java
// 伪签名；类型名只是接口草图。
record CapabilityDescriptor(
    CapabilityId id, CapabilityKind kind, int apiMajor,
    Signature signature, Phase phase, FactDeps facts,
    Locality locality, InvalidationDeps invalidation,
    CostHint cost, String ownerModId) {}

interface HostCatalog {
    void register(CapabilityDescriptor descriptor, CapabilityFactory factory);
    CatalogSnapshot freeze();
}

interface LatticiumHostExtension {
    void register(HostCatalog catalog); // loader 客户端初始化期间调用
}

interface HostSession {
    SessionId id();
    RegistryCatalog registry();
    Availability availability(CapabilityId id);
    Epochs epochs();
}
```

`Phase` 至少区分 `SYMBOLIC_COMPILE`、`REGISTRY_BIND`、`SECTION_SCAN`、`CANDIDATE_PLAN`、`GAME_THREAD_EXECUTE`。查询原语只能在只读阶段；动作不能伪装成 `PosSet → PosSet` 函数。`Locality` 表示对当前坐标外的最大读取半径，固定值或由有界整数字面量计算；无界原语不可进入自动扫描。`FactDeps` 指明需要方块状态、biome、流体、光照、邻居、目标、玩家或库存的哪些列；`InvalidationDeps` 指明哪些变化使缓存失效。`CostHint` 仅供计划优化和预算，不是安全承诺。

离线工具可只加载 `CapabilityDescriptor` 的签名目录（内建或由测试/扩展提供），不加载 `CapabilityFactory` 或任何 Minecraft 类；此时能检查表达式类型和能力要求，却不声称某世界中的 ID/tag 一定存在。缺少扩展签名时给“需要该能力”的诊断；游戏内完整目录绑定后才能消除它。

加载流程拟为：① loader 的客户端入口收集内建与已安装扩展的描述；② 冻结目录并检查 `(kind,id)`/签名/API 主版本冲突；③ 进入世界后创建 `HostSession`，加载 registry/tag 与可用 provider；④ 绑定符号 IR 和 JSON profile；⑤ 每次 tag/数据包/目标来源重载时递增对应 epoch、重新绑定受影响计划；⑥ 离开世界时关闭 session、丢弃迟到的快照与动作结果。不同种类可共享资源 ID（例如活动蓝图的选区和目标），同种类重复注册不采用“先注册者获胜”；失败要带来源诊断。`ownerModId` 应由 loader 的注册上下文给出，而非任由扩展伪造。若要覆盖原语，必须有独立的显式替换机制，首版不开放隐式覆盖。

Fabric 可用自定义客户端 entrypoint 收集 `LatticiumHostExtension`，再用事件系统驱动 tick；NeoForge 可在物理客户端初始化阶段借专用注册事件或 IMC 收集相同中立接口，具体钩子在首次游戏纵切面确定。两侧的事件名称与线程要求由各目标适配器选定，不进入中立 API。Fabric 文档允许其他 mod 定义自定义 entrypoint；NeoForge 文档说明部分生命周期事件可并行运行，需明确安排主线程工作。因此 `freeze()` 的时机和主线程调度须由 loader 层实现，而不是由 core 假设。[Fabric entrypoint 文档](https://docs.fabricmc.net/develop/loader/fabric-mod-json)、[NeoForge 事件与生命周期文档](https://docs.neoforged.net/docs/concepts/events/)

## 4. 符号绑定接口：离线与游戏内分开

`dsl-core` 解析 `s{minecraft:oak_stairs[facing=north]}` 时只保留字符串符号和源区间。离线编译可核对语法、类型、有限性、规则 schema 与能力签名；**游戏内绑定**才判断 ID/tag 是否存在、属性值是否合法，以及集合在该 registry epoch 对应哪些整数 ordinal。

```java
interface RegistryCatalog {
    RegistryEpoch epoch();
    ResolveResult resolve(RegistryKind kind, ResourceId id);
    ResolveResult resolveTag(RegistryKind kind, ResourceId tag);
    StateSchema stateSchema(ResourceId blockId);
    OrdinalSet universe(RegistryKind kind);
}

record BindResult(Optional<BoundPlan> plan, List<Diagnostic> diagnostics) {}
BindResult bind(SymbolicPlan ir, RegistryCatalog registry,
                CatalogSnapshot capabilities, ProfileContext profile);
```

`ResolveResult` 明确区分 `FOUND`、`MISSING`、`UNAVAILABLE`：缺失 ID/tag 是诊断，不与合法空 tag 混淆；尚未连接世界而不能读取动态 registry 是 `UNAVAILABLE`。ordinal 只在当前 `RegistryEpoch` 内有效，不能写入持久 profile。绑定结果可缓存，但 registry/tag 代次变更必须重新验证。已安装 mod 注册的方块可通过同一机制进入集合，core 不持有模组 Java 类名。

## 5. 世界快照与三值事实

世界事实的抓取与纯计算必须有清晰边界。版本适配器在客户端游戏线程检查区块是否已加载，按每 tick 预算捕获所需 section 和 halo，产出不可变的中立快照；扫描器可在 worker 线程对快照做 bitmap 运算。既不强制复制整个世界，也不允许 worker 直接读活 `ClientLevel`。`SectionKey` 包含 `SessionId`、维度和 section 坐标，杜绝跨世界复用。

```java
interface SectionSnapshotSource {
    CaptureResult capture(SectionKey key, FactRequest request); // game thread
}

sealed interface CaptureResult {
    record Ready(SectionFacts facts) implements CaptureResult {}
    record Unloaded() implements CaptureResult {}
    record Deferred(Reason reason) implements CaptureResult {}
    record Unsupported(FactKind fact) implements CaptureResult {}
}

interface SectionFacts {
    SectionKey key();
    Epochs epochs();
    KnownMask known(FactKind fact);
    StateColumn states();
    Optional<BiomeColumn> biomes();
    Optional<FluidColumn> fluids();
    Optional<LightColumn> light();
}
```

上面的列与 mask 是**只读视图**，真实布局可以是局部 palette、packed indices、原始整数数组或缓存 bitmap；接口不强迫为每格分配对象。快照交给 worker 后，其底层存储在显式 lease/引用计数结束前不可复用或修改，不能把活世界的可变数组直接伪装成快照。可选列为空表示该列未被请求或此快照没有可读值，二者由 `FactRequest` 与对应 known mask 区分。`Ready` 可对不同世界事实给不同 known mask：方块状态已知而光照暂不可用时，不能把光照也说成已知。`Unloaded` 与 `Deferred` 只令**依赖该世界事实**的谓词成为 `Unknown`；纯坐标范围、选区成员以及独立蓝图 provider 的目标数据可能仍已知。前者等区块加载，后者可在预算恢复后重试。`Unsupported` 是能力缺失诊断，不能被自动当作 false。`!Unknown` 仍为 `Unknown`；section 位图继续按 003 中的真值/已知双 mask 计算。

抓取时要用 `FactRequest` 的读取半径扩展 halo。`offset` 在选区边界外仍可读有限邻居，但候选输出仍裁剪到有限 `scope`。快照抓取和 `TargetProvider`/`SelectionProvider` 的读取必须遵守相同 session 与 epoch 规则。没有可靠失效事件的扩展事实，只允许短寿命或每次重新采样，不进入持久 section 缓存。

## 6. 有限选区、目标与库存

选区接口应先给出**有限可枚举界**，再按 section 交集提供成员 mask；不能只提供 `contains(pos)` 而让宿主猜应扫描多大的世界。动态 `sphere(player,r)` 在一次扫描片段中固定玩家锚点，下一次移动时更新覆盖 section。外部选区可跨多个盒子或维度，但每个活动维度的界必须有限。

```java
interface SelectionProvider {
    List<SelectionInfo> list(HostSession session);
    OpenResult<SelectionSnapshot> open(SelectionId id, HostSession session);
}
interface SelectionSnapshot {
    Revision revision();
    List<FiniteBounds> bounds();
    SectionMask membership(SectionKey section);
}

interface TargetProvider {
    List<TargetInfo> list(HostSession session);
    OpenResult<TargetSnapshot> open(TargetId id, HostSession session);
}
interface TargetSnapshot {
    Revision revision();
    TargetCell targetAt(DimPos pos); // 正确性基线；活 provider 默认 game thread
    Optional<TargetSlice> captureSection(SectionKey key); // 可选批量快照
}
sealed interface TargetCell {
    record Exact(StateValue state) implements TargetCell {}
    record Clear() implements TargetCell {}
    record DontCare() implements TargetCell {}
    record Unknown(Reason reason) implements TargetCell {}
}
// OpenResult<T> = Ready(T) | Missing | Unavailable(reason) | Conflict(reason)
```

`list` 只为命令补全/UI 提供当前可见的 ID 和描述，不等于已打开或已获世界数据。`OpenResult` 把不存在、当前不可用与配置冲突区分开；它们都不是空选区或 `DontCare`。短名 `selection("build")` 可按用户选区命名空间解析；草案 003 使用的 `selection("active_blueprint")` 拟保留为活动蓝图别名。若命名冲突，诊断要求使用完整资源 ID，不能依靠 provider 顺序。`DontCare` 是**明确没有施工要求**；`Unknown` 是尚不能知道，不能因 `!matches_target()` 被选成清空任务。Litematica 活动蓝图的 air 默认映射 `DontCare`，profile 的 `include_air: true` 才把对应位置映射 `Clear`。外部 provider 只读取原模组已加载的数据；同一位置多个 placement 冲突时可在 `open` 阶段拒绝整个快照，或要求显式优先级，不由注册顺序决定。Litematica/Forgematica 缺失时桥接类不加载，内建选区与常量目标仍可运行。

活 mod 的 target/selection 对象默认只在游戏线程读取。`TargetSnapshot.targetAt` 适于少量候选；扫描 `target(...)` 或 `matches_target()` 时，优先在游戏线程按 section 捕获不可变 `TargetSlice` 供 worker 使用。`captureSection` 的空 Optional 只表示 provider 没有批量实现，**不表示该 section 为 `DontCare`**；此时可在预算内逐点构造 slice，但不能让 worker 直接调用第三方的活 provider。`SelectionSnapshot.membership` 也须返回稳定的 mask 或在主线程复制；修订号变化后重新打开。`PlayerSnapshotSource` 只在有活动玩家的 session 中返回 `PlayerFacts`，否则报告暂不可用并暂停相关作业。

`target.items` 是另一条目标路线：`ItemSet` 来自 registry，`PlayerSnapshotSource` 提供一次主线程抓取的库存快照（物品类型、数量、可用槽位与必要的 stack 属性）。planner 只在候选坐标上用 `PlacementOracle` 找可验证的终态；选定后将具体目标冻结在 JobInstance 中，直到确认或显式重选。库存变化使材料选择与可执行性失效，不应冲掉无关的世界掩码。`ItemSet` 本身不悄悄变成 `ItemStack` 谓词。

```java
interface PlayerSnapshotSource {
    PlayerCapture capture(SessionId session, PlayerFactRequest request); // game thread
}
// PlayerCapture = Ready(PlayerFacts) | NoPlayer | Deferred(reason)
interface PlayerFacts {
    DimPos feetBlock();
    Vec3 exactPosition();
    InventorySnapshot inventory();
    InteractionContext interaction(); // reach、朝向、手、游戏模式等
    Epochs epochs();
}
```

`activation.where` 判断 `feetBlock()`；`select.choose: nearest` 使用 003 指定的脚下坐标与平局规则。内部精确玩家位置可供合法交互预测和 reach 检查，不意味着 DSL 支持浮点字面量。库存包含槽位和必要组件以选择实际可用的 stack，但 `ItemSet` 的集合元素仍只有物品类型。

## 7. DSL 查询原语 SPI

`current(...)`、`target(...)`、`biome(...)`、`fluid(...)` 和集合运算是编译器内建语义，不能被第三方同名覆盖。扩展原语只解决无法由现有表达式描述的**只读事实**，例如其他 mod 自己维护的方块属性。它们注册时提供完整签名与事实依赖；文档作者只能按名字调用，不能在 DSL 中定义 Java 回调。

```java
interface QueryPrimitiveFactory {
    BoundPrimitive bind(ConstArguments args, RegistryCatalog registry);
}
interface BoundPrimitive {
    Truth test(FactWindow facts, DimPos pos);       // 必需的正确性基线
    Optional<SectionKernel> sectionKernel();       // 可选批量优化
}
interface SectionKernel {
    void evaluate(SectionWindow facts, MaskSink truth, MaskSink known);
}
interface FactSource {
    FactCapture capture(SectionKey key, FactRequest request); // game thread
}
interface SectionWindow {
    SectionFacts world();
    Optional<TargetSlice> target(); // 独立目标来源及其 known mask
    SectionMask scope();
    PlayerFacts player();
}
```

`ConstArguments` 是编译时已验证的参数：类型、ID、整数范围和读取半径都已固定。`FactWindow`/`SectionWindow` 是只读快照及必要 halo；`test` 不接收活世界对象。`Truth` 为 `TRUE/FALSE/UNKNOWN`，`sectionKernel` 必须与逐点 `test` 在所有 known/unknown 情形等价。扫描器可用内建 bitmap kernel，第三方未提供 kernel 时按已筛候选逐点调用，并对其设更紧预算。若原语需要自定义事实，须另注册 `FactSource` 在主线程采集、版本化与失效；不能在 worker 中从 `ClientLevel` 偷读。

能力描述还要声明**读取局部性**和**变化依赖**。例如 `adjacent(P)` 的半径为 `P` 的半径加一；`light(range)` 依赖光照更新；`inventory(I)` 依赖库存代次；`sphere(player,r)` 依赖玩家锚点。编译器据此申请 halo、确定缓存键并拒绝超半径自动 profile。返回 `UNKNOWN` 不能作为 `FALSE` 取补。已安装 mod 的 Java 扩展是可信进程内代码，描述符并不能把它沙箱化；运行时只能监测耗时、关闭出错能力、记录来源。用户提供的 DSL/JSON/规则文件永远不能注册此 SPI。

## 8. 规则、放置预测与动作网关

`planning-core` 对给定事实和策略搜索状态转换，**预测**与**提交真实交互**是两种不同的能力。放置预测首先做只读候选枚举；没有可验证结果时返回 `Unsupported`，不能因物品 ID 看似对应方块就假设会放出某状态。规则包的 `when/requires/verify` 复用 DSL 条件；其动作只能是有限的意图种类。需要模组特有交互预测时，可信扩展可注册 native oracle，但仍只能向统一网关提出动作意图。

```java
interface RuleSource {
    RuleBundle load(RuleLoadContext context); // 声明式规则与来源/版本范围
}
interface PlacementOracle {
    Prediction predict(PlaceQuestion question, CandidateFacts facts);
}
sealed interface Prediction {
    record Proposals(List<TransitionProposal> steps) implements Prediction {}
    record NoLegalPlacement(Reason reason) implements Prediction {}
    record Unsupported(Reason reason) implements Prediction {}
    record Unknown(Reason reason) implements Prediction {}
}
record TransitionProposal(ActionIntent intent, Preconditions before,
                          ExpectedEffect after, AffectedArea effects,
                          CostVector cost) {}

interface ActionGateway {
    Submission submit(ActionIntent intent, Preconditions before,
                      JobPolicy policy, SessionId session); // game thread
}
interface ObservationSource {
    Observation observe(Receipt receipt, ExpectedEffect expected);
}
```

`NoLegalPlacement` 表示机制已知、但此位置/库存/朝向下没有合法放置；`Unsupported` 表示 oracle 不会预测这种物品或状态；`Unknown` 表示事实不足，三者对应不同的重试与诊断。`ActionIntent` 可表达 `PLACE/USE_ITEM/INTERACT/BREAK` 等由宿主支持的正常玩家操作，携带手、面、命中点、物品槽位等执行细节；内部命中点可用浮点坐标，不要求 DSL 添加浮点字面量。`Preconditions` 至少包含世界/session 身份、目标位置与相关邻居的状态摘要、库存槽位与数量、目标修订、reach/交互上下文和规则要求。`ExpectedEffect` 表示要在权威世界观察到的结果，而非“客户端方法返回成功”。`AffectedArea` 让多方块和邻居更新进入依赖调度。

有两道策略门：planner 先按 JobPolicy 删除不允许的边；`ActionGateway` 在游戏线程重新检查同一 JobPolicy 与实时前置条件。`break: deny` 必须保证没有 `BREAK` 意图发出；`break: selected` 只允许已领取坐标及规则明确声明的附属影响区。`Submission` 的 `Accepted` 仅表示交互已送出；JobController 持有 receipt 等待观察，结果为 `Confirmed`、`StillPending`、`Contradicted` 或 `TimedOut`。若世界/库存/目标已改变，网关返回 `Deferred/Stale/Rejected`，scanner 与 planner 再依据新快照处理。客户端不需要、也不假设存在 Latticium 服务端协议。

对 `target.items` 的典型路径是：扫描 `select.where` → 从可执行候选按 `select.choose` 选坐标 → 读取目标和库存 → oracle 提议可达最终状态 → 按 `target.choose` 冻结一个具体目标 → planner 搜索步骤 → 策略门禁 → 网关提交一个步骤 → 观察世界 → 完成或重规划。对蓝图目标则直接使用 `TargetSnapshot.Exact/Clear`，仍走同一后半段。选区之外的状态变化只能是规则声明并经 policy 允许的有限附属效果。

## 9. 会话、线程和失效协议

| 时机 | 宿主动作 | core/作业反应 |
|---|---|---|
| 客户端初始化 | 收集描述、检查重复和 API 主版本、冻结目录 | 只有符号签名可用；没有世界事实 |
| 进入世界/切换维度 | 建 `SessionId`，抓 registry/tag/player/provider 代次 | 绑定 profile 和计划；丢弃旧 session 游标 |
| 每 tick | 主线程按预算抓脏 section/halo 与玩家库存，提交前重查 | worker 只计算不可变快照；JobController 分批规划/执行 |
| 方块/光照/库存/蓝图/选区变化 | 根据 `InvalidationDeps` 递增局部或全局代次 | 只失效相关 mask、材料选择或目标，不全局清空 |
| tag/资源重载 | 重建符号绑定及规则来源代次 | 受影响计划重新绑定；不合法作业暂停并诊断 |
| 离开世界/断线 | 关闭 session，停止新动作，结算或撤销待确认工作项 | 迟到的快照、oracle 提议与 receipt 因 session 不匹配被丢弃 |

`Epochs` 不应只用一个全局数：建议至少拆为 registry/tag、世界 section、目标 provider、选区、库存、玩家锚点、规则包。每个计划节点声明其依赖集合；`light` 若无法收到可靠更新，就标记为 volatile 并限制缓存寿命。纯扫描工作可以并行，但提交队列必须串行受预算约束。一次请求或结果都携带 `SessionId`，执行前仍要实时重查，不能仅凭旧 epoch 的“相同数字”推断安全。

性能上，单格 `TargetSnapshot.targetAt` 是**正确性基线**；蓝图密集读取可选批量 `captureSection(SectionKey)`。`SelectionSnapshot.membership` 已是 section 批量接口。`FactSource` 和 `PlacementOracle` 亦可提供批量或缓存特化，但其逐点/单候选语义先固定。这样宿主可以逐步优化 section palette 和 bitmap 路径，而不把纯 core 绑在某版本的 Minecraft 内存布局上。

## 10. 面向命令、UI 与其他 mod 的服务 API

入口层不直接接触 `ActionGateway`；命令、轻量 UI 和其他 mod 统一调用一个客户端 `LatticiumService`。建议公开：`capabilities()`、`compileProfile(jsonOrSpec)`、`query(expr, optionalScope)`、`preview(JobSpec)`、`submit(JobSpec)`、`pause/resume/cancel(jobId)`、`status(jobId)` 和只读状态订阅。方法返回结构化诊断、缺失能力与阻塞原因，而不是只返回布尔值。`preview` 在明确扫描预算内给候选数、未知/阻塞数、材料与可能破坏范围的**估计或下界**，标注未扫描部分；它不提交动作。

`submit` 进入唯一 JobController，由它落实显式启用的自动 profile、用户全局暂停、scope/预算和 policy。其他 mod 不能通过公共服务 API 直接发送 `BREAK` 或自定义包。命令与 API 只负责生成同一种 `JobSpec`，不各自维护施工状态机。启动、暂停、取消和世界离开后，对已发送但尚未确认的动作应有一致的观察/结算语义。

公共 Java 21 API 只暴露中立值和能力描述；具体 Fabric/NeoForge 注册桥接可以有薄适配器，避免下游依赖四套不同的 core 包。`apiMajor` 改变时需要显式适配，不承诺对任意版本/loader 的二进制即插即用。当前每个 Minecraft/loader 目标仍只发行一个完整客户端 JAR，纯 core 可另作普通库使用，沿用 [草案 002 的发行决定](002-packaging-and-automation.md)。

## 11. 两种作业如何穿过接口

以 003 的玄武岩三角洲填岩浆 profile 为例：JSON reader 交给 DSL core 三个表达式；`RegistryCatalog` 绑定 biome、fluid 与物品集合；`PlayerSnapshotSource` 给脚下坐标、库存及当前交互上下文；`SectionSnapshotSource` 只抓半径内已加载 section 的 fluid 等所需事实。activation 为 `Unknown` 时不启动，扫描遇未加载 section 只延后那部分；已知候选进入 `PlacementOracle`，按可用石头/泥土/地狱岩提出合法终态，`break: deny` 滤去破坏路径。`ActionGateway` 重查后提交一次正常交互，`ObservationSource` 看到世界达到冻结目标才确认完成。

以活动蓝图施工为例，`selection("active_blueprint")` 和 `latticium:active_blueprint` 分别打开有限选区和 `TargetSnapshot`。如果未安装桥接，目录会报告能力缺失；若桥接存在但没有活动 placement，`OpenResult.Unavailable` 使这一 profile 明确阻塞。内建盒子填充 profile 不受影响。如果蓝图该格是 `DontCare`，不生成施工目标；若蓝图与世界状态不同，planner 才产生可被 policy 允许的转换步骤。蓝图在等待动作期间切换使目标 revision 改变，执行前重查会拒绝旧意图并重新规划。

## 12. 004 建议固定的边界与验证点

首版先固定**中立事实类型、三值语义、选区有限性、目标四态、双重 policy 门禁和 session/epoch 规则**。这些边界一旦模糊，后续多版本适配与缓存优化都会变得不可验证。接口名、具体 Java 泛型、异常形式、loader 事件钩子和 native oracle 的扩展点仍可在第一个纵切面中调整。

验证顺序建议为：

1. Java 21 假宿主：给可控 registry/tag、section、目标、库存和 oracle；验证缺失能力、`Unknown`、halo、重绑定与 `break: deny`。
2. 一个游戏目标：内建盒子与物品目标，贯通抓取→扫描→规划→正常交互→观察，记录每 tick 主线程耗时。
3. 扩到四个目标：同一纯 core 契约分别绑定 26.2/26.3 × Fabric/NeoForge，启动和基本施工都验证。
4. 可选蓝图桥接：仅在安装并启用对应来源 mod 时验证活动目标、选区、air 与重叠冲突；缺失桥接时内建作业继续可用。
5. 扩展 SPI：用一个测试 mod 注册只读原语和事实源，比较逐点与 section kernel 的三值结果、半径/失效声明及超预算处理。

004 的结论是将“能力”当成**带签名、阶段、数据依赖和失效协议的受控宿主服务**。DSL 用户看到稳定的集合函数和 JSON profile；版本适配器负责把它们兑现为游戏事实和真实交互；planner 始终保留离线运行与替换假宿主的能力。
