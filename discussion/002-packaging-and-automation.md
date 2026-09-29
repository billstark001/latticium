# Latticium 发行形态与自动化讨论草案 002

状态：讨论稿；没有实现代码。2026-09-29。承接 [草案 001](001-architecture.md)。DSL/profile 的正式拟定语法见 [草案 003](003-typed-dsl.md)，以 003 为准。

## 已确定的产品约束

首批发行目标：Minecraft 26.2/26.3 × Fabric/NeoForge，共四种客户端 JAR。Latticium 不安装服务端组件，也不设计服务端伴生协议；所有动作经正常客户端玩家交互发出，以客户端看到的世界变化作为确认依据。目标先来自内建常量、内建选区或已安装的 Litematica 系模组；Latticium 不读入 `.litematic` 文件。

截至本稿日期，Modrinth 上 [Litematica](https://modrinth.com/mod/litematica) 提供 26.2、26.3 Fabric 版本；NeoForge 使用第三方 [Forgematica](https://modrinth.com/mod/forgematica)，其公开兼容版本目前到 26.2。故四目标都要有完整的内建功能；桥接在 26.2 Fabric、26.3 Fabric、26.2 NeoForge 验证。26.3 NeoForge 桥接预留接口，在对应目标模组可用后再宣布兼容，不能以不可测试的猜测作支持承诺。

## “无状态库”与游戏内状态的实际边界

`dsl-core` 和 `planning-core` 不持有全局客户端状态；它们对给定 AST、规则、快照和能力输入返回查询计划或动作计划。缓存是一次查询/规划的局部状态。游戏内施工本身必须有 `JobController`：它记录选区、进度、待确认动作、重试、暂停、库存变化和当前世界身份。这层与 UI 分开，但不可能被设计成完全无状态。

```text
纯 JVM：DSL + planner + 规则编译器
            ↓
Minecraft 版本适配：registry/world/placement/executor
            ↓
客户端 JobController：任务生命周期与持久配置
            ├── 命令入口
            ├── 可选轻量状态 UI
            ├── 用户自动化 profile
            └── 可选 Litematica/Forgematica provider
```

## 三种用户发行形态

| 方案 | 优点 | 代价 | 第一版建议 |
|---|---|---|---|
| 底层 mod JAR + 独立 UI/状态 JAR | 下游可只安装底层；组件边界可见 | 一次安装需要配对两个 JAR；实际施工的状态层仍是必需；版本组合变多 | 暂不采用 |
| 单一完整 JAR，同时开放 API | 一次安装；四目标矩阵清楚；桥接与 UI 可按需启用 | 只想调用 API 的用户也下载 UI 代码 | **采用** |
| 同时发布 core-only 与 full 两种游戏 JAR | 下游可选最小运行体积 | 八个发行件；嵌入/依赖关系和重复 mod ID 易混淆；测试矩阵扩大 | 有实际下游需求后再考虑 |

因此建议**每个 Minecraft/loader 目标只发行一个 Latticium 客户端 JAR**。它提供稳定的 `submit/query/preview/status` API；内部模块仍严格分层。另把纯 JVM `dsl-core`、`planning-core` 作为普通库 artifact 提供给开发者或离线工具，它们不要求玩家另装 JAR。若其他 mod 在游戏里调用 API，依赖相应的完整 Latticium mod JAR 即可。

“同时开放 API 和用户功能”不等于开机自动工作：没有启用任务时只注册入口和可选 provider，不扫描世界、不产生 HUD、不改变 Litematica 快捷键或菜单。默认快捷键可不绑定；需要施工的用户可用命令、API 或之后的可选 UI 开始任务。Litematica 本身的查看和编辑流程不因装了 Latticium 而改变。

Litematica/Forgematica 以 `compileOnly`/可选运行依赖对待；启动时先确认对应 mod 与版本，再加载桥接类，缺失时不访问它的类或 Mixin 目标。Fabric 与 NeoForge 各有独立桥接实现，共享中立的 `TargetProvider` 与 `UniverseProvider` 协议。首版先通过命令引用当前已启用 placement/selection，不侵入 Litematica UI。蓝图中的 air 默认解释为 `DontCare`；只有用户选择“精确匹配/清空额外方块”时才变成 `Clear`。多个 placement 重叠且目标冲突时明确报冲突或由用户选择优先级。

## 用户入口：命令与声明式 profile 同时存在

命令适合一次性工作：定义或引用选区、给出目标、预览、启动、暂停、继续、取消和查看阻塞原因。建议所有入口最终都生成同一 `JobSpec`，不让命令和 API 各自维护一套施工逻辑。命令前缀例如 `/latticium`；具体语法待独立设计。

自动化适合保存为**声明式 profile**。它是一个含触发条件、有限范围、查询表达式、目标策略和施工策略的文档，不是允许 `if/while/for` 和任意动作调用的脚本。查询字段使用草案 001 的只读 DSL；玩家事件和任务生命周期属于 profile schema；方块状态转换则属于独立规则包。这三个层次共享类型和编译诊断，但权能不同：

| 层次 | 能描述 | 不能描述 |
|---|---|---|
| 查询 DSL | 坐标集合、当前/目标状态判断 | 执行动作、监听事件 |
| 用户 profile | 何时启停、扫描哪里、目标和破坏策略 | 定义新交互原语、发自定义包 |
| 规则包 / native SPI | 合法状态转换与可观察后置条件 | 绕过 JobPolicy 的动作限制 |

作为讨论用的 profile 伪格式（尚非最终语法）：

```yaml
id: user:basalt_lava
activation:
  while_player_in_biome: minecraft:basalt_deltas
scope:
  kind: loaded_and_reachable
  radius: 5
select: "fluid(minecraft:lava) & $minecraft:basalt_deltas"
target:
  one_of:
    - minecraft:stone
    - minecraft:dirt
    - minecraft:netherrack
  choose: first_available
policy:
  break: deny
  max_actions_per_tick: 1
  max_actions_per_activation: 256
```

这里的“所有岩浆”应精确定义为**profile 活跃期间、玩家周围已加载且正常交互可达范围内、满足查询的岩浆**。客户端不能一次处理整个生物群系或未加载的区块。`on_enter` 表示进群系时触发一次有界快照；`while_player_in_biome` 表示在群系内随玩家移动维护有界滑动窗口，两者应作为不同生命周期模式。退出群系、换维度或离开世界时停止领取新工作；待确认动作按明确的停止策略结算。

为满足多种填充材料，`TargetProvider` 应从单一 `ExactBlockState` 扩展出 `AcceptAnyOf(states, choicePolicy)`。当前方块若已属于可接受集合则直接完成；否则由资源与可放置性选择一个具体目标，**为该坐标冻结选项直至确认或明确重选**，避免库存变化时反复改目标。Litematica 来源仍给出精确状态。`fluid(minecraft:lava)` 是宿主注册的纯查询原语，不能只靠普通 block ID 推断所有流体状态。

`policy.break` 默认 `deny`；即使规则图认为“破坏重放”成本最低，planner 也必须先按作业策略过滤动作。清除或替换任务可以显式允许在候选坐标破坏；以后可加更细的破坏过滤表达式。自动 profile 的启用状态由用户显式保存，关闭时不产生后台施工。客户端能力还需要统一预算：每 tick 动作数、活跃候选数、扫描 section 数、失败次数、库存消耗以及 reach 限制。

## 状态、配置与恢复

持久化 profile 定义、启用状态、用户选区及少量偏好；按服务器/存档和维度隔离。`JobInstance` 的待确认动作与扫描游标首先视作会话状态。断线或世界切换时终止不确定的交互，重新进入后从当前世界重新扫描，而非假设旧动作已成功。一次任务被领取的候选须保留到目标确认或明确失败，避免“替换了源方块后源查询不再命中”导致进度消失。

## 四目标的交付边界与验证

| 目标 | 内建选区、常量目标、施工 | 蓝图 provider |
|---|---|---|
| Fabric 26.2 | 必须验证 | Litematica，必须验证 |
| Fabric 26.3 | 必须验证 | Litematica，必须验证 |
| NeoForge 26.2 | 必须验证 | Forgematica，必须验证 |
| NeoForge 26.3 | 必须验证 | 预留桥接；待兼容的源模组公开并可测试 |

CI 至少分别编译四个 JAR；纯 JVM 测试验证 DSL、profile 编译和 planner；四目标做客户端启动与基本交互测试；三个已有蓝图 provider 的目标做实际选区/目标读取测试。特别验收：不装 Litematica/Forgematica 也能启动和施工；装了但未启用 Latticium 时没有扫描/动作/UI 干扰；禁止破坏的 profile 永不发挖掘动作；自动 profile 只影响其范围与预算内的坐标。
