# Mysterious 维护导航手册

本文是当前代码库的维护入口，以 2026-09-24 阶段 0～19 / RC1 的实现为准。它回答四个问题：系统现在完成了什么、每个软件包负责什么、状态从哪里流转、出现问题时先查哪里。

阶段设计与历史验收仍以同目录的阶段文档为准；本文只描述当前代码事实，不把预留包当成已完成功能。

当前产品规则已删除运行时 Arena：没有范围加入/退出、玩家回传、Boss 软/硬回场或结界。旧 `ArenaConfigSnapshot`/Center 字段仅为 V7 存档兼容及生成原点保留，不得重新用作战斗边界。战斗表现只使用原版粒子、声音与 GeckoLib 动画。

## 1. 当前基线与完成度

| 项目 | 当前值 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.251 |
| Java | 21 |
| Mod ID / 版本 | `mysterious` / `1.0.0` |
| 网络协议 | `1` |
| 存档 schema / data version | `7 / 7` |
| 已完成阶段 | 0～19 |
| 自动化基线 | 63 个必需 GameTest |
| 权威状态入口 | `EncounterController` |
| 存档文件键 | `mysterious_encounters` |

已完成的主干能力：

- 三种实体注册、GeckoLib 模型/动画/渲染，以及 Amon 的 Encounter UUID 实体 NBT 绑定。
- Encounter、Boss Registry、玩家参与状态、Arena 边界、弃战与 Boss 回场。
- 服务端权威快照、revision、客户端同步/清场、管理员只读诊断。
- V1→V2→V3→V4→V5→V6→V7 显式存档迁移、安全模式、durability barrier、Cleanup 中断恢复。
- 伤害合法性、全阶段单次 10 点有效伤害上限、死亡后阶段复活、共享仇恨、目标切换、受击传送与脱战恢复。
- P1 初始 5 体入口、唯一扫描者、单次分裂和可恢复 `SplitTransaction`。
- 防具/主背包物品偷窃、Escrow、返还事务、离线全局待返还账本和被偷属性投影。
- 通用阶段迁移代际、稳定 recovery carrier、全灭恢复和自动 P1→P2 提交。
- Boss 法术定义/实例/中断/冷却、全局施法锁、临时实体预算和 P2/P3 全注册表选择。
- P2 三种固有技能、ISS 安全复制池、传奇池、真实雷击，以及致命伤触发 P2→P3。
- P3 40 秒可伤害时间线、转换前死亡复活、3 秒预警、8 只时间之虫和可叠加/可清除寄生状态。
- P3 时钟有限生成/批量结算、个人命中计数、统一 Boss Execution 与复活后阈值重置。
- P3 第二形态生命调整、固定速度碰撞飞行、`PROJECTILE` 整链免疫、带原版冷却表现的物品身份封印与胜利清理。
- P3 唯一目标跨维度等待/追击/回场、共享冷却，以及 `MISSING_PENDING` 跨维度 UUID 重绑。
- 原版多层粒子/声音、动画、阶段屏幕边缘效果、聚合 Boss 血条和客户端完整清场。
- 四玩家高频状态、资源满载拒绝、V7 重启恢复与 RC1 数值冻结回归。
- OP 玩家显式 `/mysterious start` 创建并加入 P1 初始五体，`/mysterious stop <id>` 走统一弃战 Cleanup；普通实体生成不隐式开战。

独立扩展范围：源堡维度/Arena 分配、进入法术、FTB Teams 实际解析器、战利品与奖励。相关包有些仅保留 `package-info.java`，见第 5 节。

## 2. 维护时必须保持的不变量

1. `EncounterController` 是 Encounter 全局状态的唯一写入口。实体、网络处理器、客户端、AI 和 Cleanup 实现只能提交请求或观察结果。
2. 客户端只发送意图；坐标、阶段、伤害、Boss 生死、物品和事务状态均由服务端重新验证。
3. `UNLOADED` 和 `MISSING_PENDING` 不是死亡。Boss 只有显式进入 `DEAD` 或 `REMOVED` 才不再计入存活。
4. 任何会先改世界、玩家物品或实体，再写账本的流程都不合法。正确顺序是 `PREPARED → durable barrier → 外部副作用 → COMMITTED`。
5. 旧 Arena 几何字段只能用于存档兼容；运行时不得据此回传玩家、拉回 Boss 或拒绝合法参与者。
6. 阿蒙常规贴近传送使用目标玩家精确坐标；跨维度实体移动仍需验证世界边界和碰撞。
7. 新增持久字段必须同时修改快照模型、序列化、迁移、网络投影（如客户端需要）和 GameTest。
8. Finalize、Split、Escrow、Return 和阶段迁移必须使用稳定事务 ID，并允许重复调用而不重复产生副作用。
9. 未来版本数据无法解码时必须进入非破坏性 safe mode，不能以空数据覆盖原存档。
10. 物理客户端类只能存在于客户端加载边界；专服不能链接渲染器或模型类。

## 3. 总体运行关系

```text
NeoForge 事件 / 命令 / 实体回调
                 │
                 ▼
        EncounterStartService
        EncounterRuntimeService
        CrossDimensionRuntimeService  EncounterParticleRuntimeService
        EncounterParticleRuntimeService
        CombatRuntimeService          PhaseOneRuntimeService
        PhaseTransitionRuntimeService
        TheftRuntimeService           EscrowReturnService
                 │
                 ▼
          EncounterController  ← 唯一权威写入口
                 │
        ┌────────┴─────────┐
        ▼                  ▼
EncounterSavedData    NetworkSyncService
NBT + migration       Snapshot / Delta / Event
        │                  │
        ▼                  ▼
磁盘 durability       ClientEncounterStateCache
```

每个权威 Encounter 使用不可变 `EncounterSnapshot` 表示。Controller 的写操作生成新快照、递增 revision、触发 SavedData dirty 标记和网络发布。需要破坏性副作用的操作会在副作用前调用 durability barrier，确保 `PREPARED` 状态已经真正写盘。

## 4. 项目目录

| 路径 | 内容与维护说明 |
| --- | --- |
| `src/main/java/com/mysterious/` | Java 主源码；包级说明见第 5 节 |
| `src/main/resources/assets/mysterious/` | 客户端动画、模型、贴图、语言 |
| `src/main/resources/data/mysterious/` | 服务端数据包资源；偷窃与封印黑名单 tag |
| `src/main/templates/META-INF/neoforge.mods.toml` | 构建时展开变量的模组元数据模板 |
| `src/generated/resources/` | `runData` 生成资源；不要手工维护生成结果 |
| `docs/` | 架构、阶段验收和本维护手册 |
| `gradle.properties` | 所有冻结版本和模组元数据变量 |
| `gradle.lockfile` | 解析后的依赖锁；依赖变更时必须审查 |
| `build.gradle` | 构建、运行目录、依赖、元数据展开、阶段 0 Gate |
| `run/` | 开发客户端实例，可含客户端辅助模组 |
| `run-server/` | 纯专服实例，不得加载客户端模组 |
| `run-gametest/` | GameTestServer 实例 |
| `run-data/` | 数据生成实例 |

`build/`、`.gradle/`、运行目录日志和世界文件都是生成物，不是权威源码。

## 5. Java 软件包总览

状态含义：`已实现` 表示当前运行路径已使用；`部分实现` 表示有基础能力但产品功能未闭环；`预留` 表示只有包说明或空目录。

| 包 | 状态 | 作用 | 主要内容 |
| --- | --- | --- | --- |
| `com.mysterious` | 已实现 | 模组启动入口 | `mysterious` |
| `arena` | 兼容层 | 旧存档 Center 与配置字段；不参与运行时边界 | `ArenaConfigSnapshot`、`EncounterCenter` |
| `battle` | 预留 | 未来战斗 session、阶段编排、奖励聚合 | 仅 `package-info.java`；`session/`、`reward/` 尚无类 |
| `client` | 已实现 | 客户端实体渲染注册与阶段屏幕边缘效果 | `ModEntityRenderers`、`EncounterScreenOverlay` |
| `client.model` | 已实现 | GeckoLib 模型、贴图、动画资源定位 | 三个 `*Model` |
| `client.renderer` | 已实现 | 三实体 GeckoLib renderer | 三个 `*Renderer` |
| `client.hud` | 预留 | Encounter HUD 与归属反馈 | 仅包说明 |
| `combat` | 已实现 | 伤害过滤/预算、攻击身份、仇恨和战斗 tick | 7 个类/记录/枚举 |
| `config` | 已实现 | NeoForge SERVER 配置与安全默认值 | `MysteriousServerConfig` |
| `debug` | 已实现 | OP 级开战/停止与只读诊断命令 | `AmonDebugCommands`、`EncounterStartService` |
| `dimension` | 预留 | 未来源堡区域分配、返回点和 Realm 存档 | 仅包说明 |
| `encounter` | 已实现 | 生命周期、权威快照、Controller、运行时、Cleanup | 23 个类型 |
| `entity` | 已实现 | Amon、Phantom Clock、Worm；三者均持久化运行身份/阶段数据 | 三个实体类 |
| `entity.ai` | 预留 | 后续目标与移动 Goal | 仅包说明 |
| `entity.animation` | 预留 | 后续动画触发与状态适配 | 仅包说明 |
| `entity.skill` | 预留 | 后续技能选择、执行、冷却与阶段资格 | 仅包说明 |
| `gametest` | 已实现 | 阶段 0～19 自动验收 | 十二个测试类，共 56 项 |
| `integration` | 已实现 | 通用兼容性探针和死亡保护观察 | 2 个类 |
| `integration.curios` | 部分实现 | Curios 只读边界 | `CuriosAccess`；偷窃暂不含饰品 |
| `integration.iss` | 部分实现 | ISS 启用法术元数据只读边界 | `IronsSpellAccess` |
| `integration.ftb` | 预留 | 可选 FTB Teams 所有权解析 | 仅包说明 |
| `inventory` | 已实现 | 单件物品稳定 UUID，供 Escrow 与 Seal 共用 | `ItemStackIdentity` |
| `loot` | 预留 | 战利品表选择和奖励分发 | 仅包说明 |
| `network` | 已实现 | S2C 投影、C2S 意图、revision 与事件去重 | 12 个类型 |
| `ownership` | 部分实现 | Encounter 所有权抽象 | 个人解析已实现，FTB 队伍解析未实现 |
| `persistence` | 已实现 | V7 NBT、迁移、SavedData、安全模式和写盘屏障 | 4 个类 |
| `phase` | 已实现 | P1/迁移、P3、Clock/Execution、Flight/Seal、视觉和粒子 | 状态、Manager 与 RuntimeService |
| `registry` | 已实现 | 实体、状态效果、数据组件、物品 tag 注册键 | 4 个类 |
| `spell` | 已实现 | 法术定义/实例/预算、ISS 与扩展注册表适配、P2/P3 调度和效果执行 | `BossSpellRegistry`、`SpellCastManager`、`SpellRuntimeService` 等 |
| `theft` | 已实现 | 偷窃、Escrow、返还、离线账本和属性投影 | 8 个类型 |
| `transaction` | 已实现 | 通用事务状态与 P1 分裂计划 | 3 个类型 |

## 6. 包内类级索引

### 6.1 启动、注册和配置

`com.mysterious.mysterious`

- `mysterious`：`@Mod` 入口。注册 SERVER 配置、数据组件、三种实体、实体属性和网络 payload。增加新的 DeferredRegister 时从这里接入。

`com.mysterious.registry`

- `ModEntities`：注册 `amon`、`phantom_clock`、`worm_of_time`，并为三者注册属性。
- `ModDataComponents`：注册持久化且网络同步的 `mysterious:item_instance_id`。
- `ModItemTags`：声明 `mysterious:theft_blacklist`，偷窃候选过滤使用此键。

`com.mysterious.config`

- `MysteriousServerConfig`：定义 Arena 冻结配置和实时战斗配置。`snapshot()` 校验跨字段关系，失败时整套回退到安全默认值。
- 创建 Encounter 时冻结：加入/保持/硬退出半径、Boss 软/硬 leash、垂直范围、Join/Retention Grace、弃战超时、P1 弃战回血、落点尝试数。
- 实时读取：受击传送概率/冷却、偷窃概率/保护 tick、防偷附魔 ID 集合。

### 6.2 Arena 与所有权

`com.mysterious.arena`

- `ArenaConfigSnapshot`：单场不可变配置；校验 `join < retention < hardExit`、`bossSoft < bossHard < playerHardExit` 等关系。
- `EncounterCenter`：维度、中心坐标、安全锚点和配置快照；安全锚点必须在 Boss 硬边界内。
- `arena` 包：只保留旧存档需要的 Center/配置模型；`ArenaBounds` 已删除。

`com.mysterious.ownership`

- `OwnershipKind`：`PLAYER`、`FTB_TEAM`。
- `RealmOwner`：由所有者 UUID 和类型组成的稳定键。
- `RealmOwnerResolver`：所有权解析接口。
- `PersonalRealmOwnerResolver`：当前默认实现，返回玩家 UUID。`FTB_TEAM` 只是模型能力，尚无真实适配器。

### 6.3 Encounter 核心

`com.mysterious.encounter`

- `EncounterManager`：按 `MinecraftServer` 保存唯一 Controller/SavedData；停服时捕获、写盘并清理运行时引用。
- `EncounterController`：所有全局写操作，包括创建/恢复 Encounter、Boss/玩家状态、计时器、仇恨、P1 scanner、Split、Escrow、弃战、跨维度状态、阶段迁移和 Finalize。
- `EncounterSnapshot`：单场完整不可变权威状态及其不变量校验；字段详见第 7 节。
- `EncounterRuntimeService`：服务端 tick 和实体/玩家事件入口；维护参与资格、Join Grace、Abandoned、Boss leash、Registry 观察、登录同步和死亡处理。
- `BossRegistry`：由 Controller 内部使用的 Boss 注册表变换逻辑。
- `BossRecord`：Boss UUID、阶段、最后维度/位置/tick、生命周期和可选 transition carrier。
- `BossLifecycleState`：`LOADED`、`UNLOADED`、`MISSING_PENDING`、`DEAD`、`REMOVED`。
- `BossRegistrationResult`：区分首次注册和完全相同的重复注册。
- `PlayerEncounterState`：玩家 UUID、`ACTIVE/RETENTION_PENDING/LEFT`、加入 tick、Join Grace 截止和 Retention 截止。
- `PlayerParticipationState`：参与状态枚举，值为 `ACTIVE`、`RETENTION_PENDING`、`LEFT`。
- `EncounterTimerState`：阶段开始、弃战开始、跨维度追击冷却时间。
- `EncounterLifecycle`：活动、弃战待定、阶段迁移、跨维度追击和三个终态。
- `EncounterPhase`：P1/P2/P3，且只允许相邻前进。
- `PhaseTransitionRecord`：transition ID、from/to、carrier Boss、claim tick。
- `PhaseTransitionResult`：阶段 claim/commit 的幂等结果。
- `EncounterTermination`：稳定 finalization ID、结束原因和请求 tick。
- `EndReason`：`VICTORY`、`ABANDONED`、`ERROR_RECOVERY`，映射到对应终态。
- `CleanupState`：`NOT_REQUESTED → PENDING → IN_PROGRESS → COMMITTED`。
- `EncounterCleanup`：统一清理接口，必须用 finalization ID 去重。
- `ServerEncounterCleanup`：当前服务端清理实现；清属性、返还/转移 Escrow、清客户端状态并移除绑定实体。
- `EncounterFinalizationResult`：Finalize 的 committed/already/in-progress 结果。
- `EncounterFinalizationException`：外部 Cleanup 失败的包装异常。
- `EncounterBoundEntity`：实体侧只保存 Encounter UUID 的最小接口。

### 6.4 实体、客户端模型与渲染

`com.mysterious.entity`

- `AmonEntity`：200 最大生命、10 近战伤害；基础目标 Goal、Encounter 绑定、实体 NBT、每实体伤害预算、受击传送去重、传送后偷窃/近战锁和最近战斗 tick。全局状态不存于实体。
- `PhantomClockEntity`：1 生命、无移动的视觉实体，循环一次时钟动画；正式技能生命周期尚未接入。
- `WormOfTimeEntity`：10 生命、3 攻击、2 秒攻击节流、Encounter 绑定/NBT、镜像 Amon 当前任意生物目标，以及火焰/雷电免疫。

`com.mysterious.client`

- `ModEntityRenderers`：仅客户端注册三种 renderer。
- `model/AmonModel`、`PhantomClockModel`、`WormOfTimeModel`：定位 `geo`、texture 和 animation JSON。
- `renderer/AmonRenderer`、`PhantomClockRenderer`、`WormOfTimeRenderer`：GeckoLib renderer 和阴影尺寸。

### 6.5 战斗内核

`com.mysterious.combat`

- `CombatRuntimeService`：不拒绝 Amon 的任何原版伤害来源，将护甲后的单次有效伤害限制为 10 点；参与资格只用于仇恨记账、受击传送和 Encounter 机制。服务端 tick 同时更新仇恨、目标和 P2/P3 脱战回血。
- `DamageDeliveryType`：`MELEE`、`PROJECTILE`、`BEAM`、`AOE`、`DOT`、`DIRECT_MAGIC`、`ENVIRONMENT`。
- `DamageDeliveryClassifier`：从 `DamageSource` 保守分类；后续免疫和法术规则应继续复用。
- `AttackEventIdentity`：由 Encounter、攻击者、目标、直接实体、tick 和伤害类型形成稳定事件 ID，供传送/偷窃去重。
- `ThreatEntry`：某玩家的仇恨值和最近行动 tick。
- `EncounterCombatState`：共享仇恨表、当前目标和最近衰减 tick，属于持久化 Encounter 状态。

关键规则：所有原版伤害来源都能伤害 Amon，单次护甲后有效伤害最多 10 点；合法参与者伤害 1:1 加仇恨、Amon 命中加 2、附近玩家每秒加 1；玩家目标之间的切换仍要求新目标达到当前目标的 120%。Amon 已有可攻击的非玩家目标时，Encounter 玩家仇恨刷新不会覆盖它；目标失效后才回到选中玩家或原版非友方生物搜索。法术、飞行、时之虫和幻影时钟全部消费同一实际目标。10 秒无有效参与后每秒衰减 5%；P2/P3 只有在不存在任何有效战斗目标且 8 秒无收发伤害时才每秒回血 5。

### 6.6 P1 与事务

`com.mysterious.phase`

- `PhaseOneState`：持久化 `splitConsumed`、scanner Boss、下次扫描 tick 和可选 Split 事务。
- `PhaseOneRuntimeService`：`spawnInitialWave()` 创建 P1 初始 5 体；tick 选唯一 scanner，每 200 tick 搜索 16 格内合法施法者，并恢复/推进分裂事务。P1 最多 10 个存活 Amon。

`com.mysterious.transaction`

- `TransactionState`：`PREPARED`、`COMMITTED`、`RETURNING`、`RETURNED`、`CANCELLED`、`CONFLICT`。不是每种事务都会使用全部状态。
- `SplitTransaction`：事务 ID、scanner、源实体、替换计划、时间和状态。
- `SplitReplacement`：源实体 UUID、预分配 Boss UUID、维度与位置。预分配 UUID 是重启去重的关键。

Split 流程：

```text
发现合法目标
  → Controller.prepareSplit(PREPARED)
  → durability barrier
  → 杀死源 / 生成预分配 UUID 的 Amon / 注册 Boss
  → Controller.commitSplit(COMMITTED + splitConsumed)
```

若重启发生在 `PREPARED`，运行时按替换 UUID 检查世界与 Registry，只补齐缺失结果。P1 开始阶段迁移时，未提交事务会取消。

### 6.7 偷窃、Escrow 与物品身份

`com.mysterious.theft`

- `TheftRuntimeService`：Amon 有效近战后的偷窃入口、候选过滤、概率/保护/事件去重和登录中断恢复。
- `EscrowRecord`：完整单件物品、无身份标记的指纹、玩家、原槽、前后数量、attack event ID、事务状态和返还 ID。
- `SerializedItemStack`：借助 registry provider 在 `ItemStack` 与独立 `CompoundTag` 之间转换。
- `StolenSlotType`：当前仅 `ARMOR` 与 `MAIN_INVENTORY`。
- `EscrowReturnService`：在线返还或离线转账；优先原空槽，再背包，最后掉落，并按 item instance ID 去重。
- `PendingReturnRecord`：脱离已结束 Encounter 的离线返还记录。
- `GlobalPendingReturnStore`：根级持久账本，提供 transfer/begin/complete 和按玩家查询，并有自己的 durability barrier。
- `StolenAttributeManager`：把被偷防具的安全属性白名单投影到 Amon；用确定 modifier ID 去重、裁剪，并保持生命百分比。

`com.mysterious.inventory`

- `ItemStackIdentity`：读取、创建或清除 `mysterious:item_instance_id`。只允许单件 stack 获得身份，防止整个堆叠共享 UUID。

偷窃事务：

```text
选择防具或主背包 9～35 槽的一件物品
  → 写 Escrow PREPARED + durable barrier
  → 重新验证槽位指纹和数量
  → shrink(1)
  → 写 COMMITTED + durable barrier
  → Encounter 结束时 RETURNING → RETURNED
  → 玩家离线则先转入 GlobalPendingReturnStore
```

当前明确不偷：快捷栏、Curios、空物品、`theft_blacklist` tag 命中的物品、命中实时防偷附魔配置的物品。

### 6.8 法术、P2 与 P3

`com.mysterious.spell`

- `BossSpellDefinition`、`BossSpellRegistry`：统一描述类别、目标、前摇、持续、冷却、中断和实体成本；包含三种测试法术与动态传奇法术。
- `SpellCastManager`：处理加权选择、全局施法锁、冷却、实例到期、中断和 Encounter 级临时实体预算。
- `BossSpellAdapter`：把 ISS 只读元数据转为安全、与玩家实例解耦的复制法术定义，并过滤危险法术族。
- `SpellRuntimeService`：P2 每 45～75 tick、P3 每 20～40 tick 调度施法；P3 在 ISS `getEffectiveCastTime` 结果上使用 55% 吟唱时间。动态读取 ISS 及扩展模组的全部启用法术并按各自最高等级作为传奇级释放，施法和落雷均跟随 Amon 当前可攻击的任意 `LivingEntity`。单个扩展法术注册或 MOB 施法异常会被隔离，只取消本次施法。P2/P3 都保留 25～50 tick 真实雷击和分布式环境粒子。

`com.mysterious.phase`

- `PhaseThreeState`：持久化 P3 时间线、第二形态位、Worm/Clock 调度、寄生、个人命中和 Execution 去重。
- `PhaseThreeRuntimeService`：恢复并推进无开场无敌的转换时间线、转换前死亡复活、Worm/寄生，并在 40 秒提交第二形态。
- `ClockRuntimeService`：有限位置搜索、30 秒实体寿命、同 tick 到期批处理；伤害 Amon 当前任意生物目标，仅玩家命中进入个人必死计数。
- `BossExecutionManager`：合并寄生/时钟 reason，同玩家同 tick 一次结算，并在复活/免死后重置对应累计。
- `FlightController`：特殊生命减半、Amon 满血切形态和每 tick 固定速度碰撞飞行。
- `SealManager`：单件物品身份封印、同期限原版物品冷却、服务端使用拦截、到期与 Cleanup 解除，含 Curios 槽遍历。
- `ParasiteState`：保存剩余持续 tick、连续 infection tick 和最后处理 tick。
- `EncounterParticleRuntimeService`：生成阶段仪式、实体轨迹、时钟消失与目标命中的原版粒子/声音。
- `EncounterParticleRuntimeService`：只使用原版粒子实现 Amon、飞行、跨维度、Clock、Worm 和转阶段多层轨迹。

`com.mysterious.encounter`

- `BossBarRuntimeService`：为每场非终态战斗维护服务端原版 Boss 血条；P1 汇总分身生命，P2/P3 显示单体生命，并严格同步参与者可见性。

`com.mysterious.encounter` 的阶段 15 增量：

- `CrossDimensionChaseState`：持久化目标、维度、等待 tick、是否迁移与返回重试位。
- `CrossDimensionRuntimeService`：唯一目标资格、2 秒等待、10 秒冷却、6～10 格有限落点、追击/回场和 Missing UUID 对账。

`com.mysterious.persistence`

- `EncounterDataVersions`：当前版本常量，现为 schema/data `7/7`。
- `EncounterMigrationRegistry`：显式迁移链；V5 增加 advanced，V6 增加 Clock/Execution/第二形态，V7 增加跨维度事务状态。
- `EncounterSerializer`：所有 Encounter 和全局 Pending Return 的 NBT 编解码。读入时重新经过 record 不变量校验。
- `EncounterSavedData`：主世界 `DataStorage` 所有者；恢复 Controller、捕获快照、safe mode、dirty、同步写盘和落盘后复核。

遇到未知未来版本、解码错误或不变量错误时，SavedData 保留原 NBT、公开 recovery error 并禁止 Controller 修改。不要通过删除或重建 SavedData 来“修复”错误；应先保存坏档副本，再补迁移或修正数据。

### 6.9 网络

`com.mysterious.network`

- `ModNetwork`：注册协议 `3` 的全部 payload 和 handler；客户端 revision 缺口会请求完整快照；服务端在 safe mode 下拒绝请求。
- `NetworkSyncService`：按玩家/Encounter 保存最近投影，选择完整快照或连续 delta，发布阶段事件，并向离场/终态客户端发送清场。
- `EncounterSnapshotPayload`：完整客户端视图，含中心、阶段、生命周期、存活数和最多 64 个 Boss 视图。
- `EncounterDeltaPayload`：`baseRevision → revision` 的生命周期、阶段和存活数增量。
- `ClientBossView`：Boss 的只读网络视图。
- `ClientEncounterStateCache`：拒绝倒退、识别 revision 缺口、保存 Encounter 快照、按 event ID 去重和清场；客户端阶段边缘效果只读该缓存。
- `EncounterIntentPayload`：唯一 C2S 载荷；当前只有 `REQUEST_SNAPSHOT`。
- `NetworkIntentGuard`：校验 request ID 防重放、玩家身份、所有权/参与资格和 Encounter。
- `PhaseEventPayload`：阶段 claim/commit 一次性事件。
- `WarningEventPayload` / `WarningCode`：边界、阶段、结束预警的载荷定义；P3 预警同时播放服务端原版声音。
- `EncounterClearPayload`：Encounter 结束时通知客户端移除对应快照，不携带战斗结果。

任何新增 payload 都必须：限制大小、校验数值、在 `ModNetwork` 注册、定义明确方向、更新客户端 revision/事件规则，并补 codec GameTest。

### 6.10 集成边界

`com.mysterious.integration`

- `StageZeroCompatibilityProbe`：专服启动记录 Java/前置版本和可读 ISS 法术数量；登录时记录 Curios 槽数。
- `DeathProtectionObserver`：观察原版图腾和被取消的死亡事件；Boss Execution 仍走同一正常保护链。
- `curios/CuriosAccess`：唯一 Curios API 入口，提供 handler/槽位计数；Seal 通过该边界遍历饰品槽。
- `iss/IronsSpellAccess`：唯一 ISS 只读入口，返回法术 ID、施法类型、等级和冷却元数据。
- `ftb` 仍是预留包；当前客户端表现无额外视觉库依赖。

第三方 API 调用应集中在对应 `integration.*`，不要散落到 Controller、实体或技能中。

### 6.11 诊断与测试

`com.mysterious.debug.AmonDebugCommands`

所有命令要求权限等级 2；`start` 与 `stop` 会改变战斗状态，其余命令只读：

| 命令 | 用途 |
| --- | --- |
| `/mysterious start` | 以执行者所在维度/坐标冻结 Arena、加入执行者并生成/登记 5 个 P1 Amon；拒绝 safe mode、重复 owner 或重叠 Arena |
| `/mysterious stop <id>` | 以 `ABANDONED` 走同一 Finalize/Cleanup/Escrow 返还路径；仅用于人工中止 |
| `/mysterious debug encounters` | 列出所有 Encounter、阶段、revision 和存活 Boss 数 |
| `encounter <id>` | 总览、owner、生命周期、Cleanup、termination |
| `players <id>` | 玩家参与、Join Grace、Retention |
| `bosses <id>` / `entities <id>` | Boss Registry、位置、状态、transition carrier |
| `phase <id>` | 阶段、计时、transition、P3 与跨维度 chase/cooldown |
| `arena <id>` | 中心、安全锚点和冻结半径/计时 |
| `transactions` | Split 事务 |
| `escrow` | Encounter 内 Escrow 记录 |
| `pendingreturns` | 全局离线待返还账本 |
| `network` | 协议、recipient stream 和已观察 Encounter |
| `recovery` | safe mode、存档数量、Missing Pending 数量和恢复错误 |
| `spells` | 活跃法术实例、预算占用、下次施法和环境事件 tick |

`com.mysterious.gametest`

| 测试类 | 数量 | 覆盖重点 |
| --- | ---: | --- |
| `StageZeroGameTests` | 3 | 实体/NBT、物品身份、依赖 API |
| `StageOneGameTests` | 9 | Registry、迁移、Finalize、绑定持久化 |
| `StageTwoGameTests` | 3 | 配置关系、参与迟滞、弃战恢复 |
| `StageThreeFourGameTests` | 8 | Arena、NBT、迁移、安全模式、网络 revision |
| `StageFiveSevenGameTests` | 5 | 目标切换、Split、Escrow、Pending Return |
| `StageEightGameTests` | 6 | 任务代际、全灭 carrier、恢复身份、持久化、rollback |
| `StageNineTwelveGameTests` | 10 | 法术预算/生命周期、P3 时间线、寄生、迁移、Worm NBT |
| `StageThirteenGameTests` | 4 | Clock NBT、计数/去重持久化、阈值、图腾保护与累计重置 |
| `StageFourteenGameTests` | 4 | 生命调整、固定飞行、第二形态 NBT、Seal 身份/到期 |
| `StageFifteenSixteenGameTests` | 6 | chase/cooldown V7、迁移、静止动画、视觉曲线与清场 |
| `StageSeventeenEighteenGameTests` | 3 | 四玩家高频压力、资源满载拒绝、RC 数值/版本冻结 |
| `StageNineteenGameTests` | 1 | 显式开战：P1 五体绑定/登记、停止后的统一终态 Cleanup |
| 合计 | 56 | 阶段 0～19 回归 Gate |

## 7. 权威状态与存档内容

`EncounterSnapshot` 是定位状态的第一入口：

| 字段组 | 内容 | 主要维护包 |
| --- | --- | --- |
| 身份 | encounter ID、owner、创建 tick、revision | `encounter`、`ownership` |
| 场地 | dimension、center、safe anchor、冻结 Arena 配置 | `arena` |
| 生命周期 | lifecycle、initial/current phase、phase generation、termination、cleanup state | `encounter` |
| Boss | `Map<UUID, BossRecord>` | `encounter` |
| 玩家 | `Map<UUID, PlayerEncounterState>` | `encounter` |
| 计时 | 阶段开始、弃战、跨维度冷却 | `encounter` |
| 战斗 | 仇恨表、当前目标、衰减 tick | `combat` |
| P1 | scanner、扫描 tick、Split、是否已消耗 | `phase`、`transaction` |
| 偷窃 | 本场 `Map<stolenItemId, EscrowRecord>` | `theft` |
| 阶段迁移 | active transition、carrier/recovery identity 与位置、last committed transition ID | `encounter`、`phase` |
| 高级阶段 | 法术、P3、Worm/Clock/寄生，以及可选跨维度事务状态 | `spell`、`phase`、`encounter` |

`GlobalPendingReturnStore` 不属于任何单场快照，保存在 SavedData 根级。这样即使 Encounter 已结束或以后被归档，离线玩家的物品仍可返还。

## 8. 关键运行链路

### 8.1 服务端 tick

服务端 tick 由独立、幂等的订阅者共同推进；当前代码没有声明同阶段订阅者彼此之间的优先顺序：

- `EncounterRuntimeService` 更新玩家参与、弃战、Boss 观察与 leash。
- `CombatRuntimeService` 更新仇恨、目标选择与脱战恢复。
- `PhaseOneRuntimeService` 选 scanner、扫描目标、恢复或推进 Split。
- `SpellRuntimeService` 在 P2/P3 推进施法实例、全注册表调度、真实雷击和分布式环境粒子；P3 使用独立的快速施法节奏。
- `PhaseThreeRuntimeService` 推进 P3 时间线、Worm 生成和寄生状态。
- `CrossDimensionRuntimeService` 推进等待/chase/返回和 Missing 对账；chase 生命周期会暂停普通 P3 生成。
- Post 阶段的 `EncounterParticleRuntimeService` 只投影视觉，不提交战斗结果。
- 任一 Controller 变更会触发 SavedData dirty 和 `NetworkSyncService.publish()`。

### 8.2 玩家伤害 Amon

1. `LivingIncomingDamageEvent` 不对 Amon 做来源过滤；玩家参与资格只决定是否记录仇恨、偷窃和受击传送。
2. `LivingDamageEvent.Pre` 在护甲、效果等减伤之后把单次有效伤害限制为 10 点，不取消死亡事件。
3. `LivingDamageEvent.Post` 记录仇恨、结束 Join Grace，并按稳定 attack event ID 判定受击传送。

### 8.3 Amon 伤害玩家

1. Amon 近战前检查目标资格和传送后锁。
2. 有效命中更新最近战斗时间与仇恨。
3. `TheftRuntimeService` 依次校验阶段、参与、保护、概率、候选和事件去重，再启动 Escrow 事务。

### 8.4 结束 Encounter

1. `EncounterController.finalizeEncounter()` 建立稳定 termination/finalization ID 和 `PENDING`。
2. durability barrier 确认终止意图已落盘。
3. `ServerEncounterCleanup` 清被偷属性，在线返还或离线转账 Escrow，发送客户端 clear，移除绑定实体。
4. 成功写 `COMMITTED`；失败保留同一 ID，进入 `ENDED_ERROR_RECOVERY + PENDING` 供重试。

## 9. 外部软件包与依赖

所有版本来自 `gradle.properties`，解析结果来自 `gradle.lockfile`。

| 依赖 | 版本 | 类型 | 作用 |
| --- | --- | --- | --- |
| Java JDK | 21 | 必须 | 编译、Gradle 运行与服务端运行基线 |
| Minecraft | 1.21.1 | 必须 | 游戏运行时与原版 API/数据格式 |
| NeoForge | 21.1.251 | 必须 | 模组加载、注册、事件、网络、SavedData、GameTest |
| Parchment mappings | 2024.11.17 for 1.21.1 | 构建期 | 更可读的 Minecraft 参数名/Javadoc |
| GeckoLib NeoForge | 4.9.3 | 必须 | 三实体 GeoModel、动画控制器和 renderer |
| Curios | 9.5.1+1.21.1 | 必须 | 饰品槽访问；当前只读探测，后续 Escrow 适配入口 |
| Iron's Spells 'n Spellbooks | 1.21.1-3.16.3 | 必须 | 法术注册表读取；后续进入法术和 Boss 施法 |
| Iron's Lib | 1.21.1-2.1.0 | ISS 运行时 | ISS 的运行前置 |
| Player Animator | 2.0.4+1.21.1 | ISS 运行时 | ISS 的玩家动画前置 |
| FTB Teams | 2101.1.11 | 可选 compileOnly | 后续队伍所有权解析，当前无适配器 |
| FTB Library | 2101.1.30 | 可选 compileOnly | FTB Teams 配套前置 |
| Architectury | 13.0.8 | 可选 compileOnly | FTB 依赖链配套 API |
| ModDevGradle | 2.0.147 | 构建期 | NeoForge 开发、run 配置和模组构建 |
| Gradle Wrapper | 9.2.1 | 构建期 | 固定构建执行环境 |

依赖维护规则：

- 不使用动态版本；升级任一冻结版本都要同步 `stageZeroExpectedVersions`。
- 变更依赖后执行 `./gradlew dependencies --write-locks`，审查 `gradle.lockfile`，再跑完整 Gate。
- `compileOnly` 可选集成必须通过隔离边界加载；缺少可选模组时不能触发类加载错误。

## 10. 资源索引

| 资源 | 包含内容 |
| --- | --- |
| `assets/mysterious/animations/` | `amon`、`phantom_clock`、`worm_of_time` 三个动画 JSON |
| `assets/mysterious/geo/` | 三个 GeckoLib `*.geo.json` 模型 |
| `assets/mysterious/textures/entity/` | 三张实体 PNG 贴图 |
| `assets/mysterious/lang/en_us.json` | 英文实体名称 |
| `assets/mysterious/lang/zh_cn.json` | 中文实体名称 |
| `data/mysterious/tags/item/theft_blacklist.json` | 不能被偷窃的物品 tag；当前 `values` 为空，供数据包或后续规则扩展 |

模型资源路径必须是 `geo/`，不是 `geos/`。资源 ID、注册 ID、语言键和 Model 返回路径修改时要成组检查。

## 11. 问题定位速查

| 现象或需求 | 首查文件/包 | 继续检查 |
| --- | --- | --- |
| Encounter 状态不一致、revision 异常 | `EncounterController`、`EncounterSnapshot` | `EncounterSerializer`、对应 GameTest |
| Boss 卸载后被误判死亡 | `BossRecord`、`BossRegistry`、`EncounterRuntimeService` | 实体 join/leave/death 事件日志 |
| 玩家参与状态不对 | `EncounterRuntimeService` | `PlayerEncounterState`、在线/离线事件 |
| 阿蒙贴近传送落点错误 | `CombatRuntimeService.teleportNear` | 目标玩家服务端坐标、共享冷却 |
| Amon 伤害为零 | `CombatRuntimeService`、`ModEntities` | 来源是否被其他模组取消、单次 10 点上限是否在护甲后生效 |
| 仇恨目标跳动 | `CombatRuntimeService.selectTarget()` | `EncounterCombatState`、120% 门槛、衰减 tick |
| P1 不扫描或重复分裂 | `PhaseOneRuntimeService`、`PhaseOneState` | `SplitTransaction`、Registry、`transactions` 命令 |
| P2/P3 不施法或扩展法术异常 | `SpellRuntimeService`、`SpellCastManager`、`IronsSpellAccess` | `/mysterious debug spells`、实体预算、扩展法术日志、`advanced.spells` |
| P2 无法进入 P3 | `CombatRuntimeService`、`PhaseTransitionRuntimeService` | carrier、transition generation、Boss 生命值 |
| P3 转换/预警/Worm 时间不对 | `PhaseThreeRuntimeService`、`PhaseThreeState` | 当前 tick、V7 `advanced.phaseThree`；旧键 `invulnerableUntilTick` 现表示转换时刻 |
| 寄生不叠加或净化后未重置 | `PhaseThreeRuntimeService`、`ParasiteState` | 自定义效果是否仍存在、玩家是否在线 |
| 时钟不生成、不到期或重复处决 | `ClockRuntimeService`、`BossExecutionManager` | `nextClockSpawnTick`、预算、个人计数、Execution tick |
| 第二形态生命/飞行/免疫异常 | `FlightController`、`CombatRuntimeService` | secondForm 位、碰撞/hard leash、DamageDeliveryType |
| 封印移动后失效或不解除 | `SealManager`、`ModDataComponents` | item/seal/encounter ID、到期 tick、Cleanup |
| 换维度后不追击或不回场 | `CrossDimensionRuntimeService`、`CrossDimensionChaseState` | 唯一目标、等待/冷却、原场玩家、16 次碰撞落点 |
| Missing Boss 长期不恢复 | `CrossDimensionRuntimeService`、`BossRegistry` | `/mysterious debug recovery`、相同 UUID 是否已在任一维度加载 |
| 物品被删、复制或无法返还 | `TheftRuntimeService`、`EscrowReturnService` | Escrow/Pending Return 命令、ItemStack identity、durability 日志 |
| 被偷属性重复或残留 | `StolenAttributeManager` | Cleanup 是否执行、modifier ID、Amon 是否仍绑定 |
| 重启后状态丢失 | `EncounterSavedData`、`EncounterSerializer` | schema/data version、safe mode、服务器保存日志 |
| 客户端状态落后或残留 | `NetworkSyncService`、`ClientEncounterStateCache` | base revision、clear payload、参与者状态 |
| 粒子或声音缺失 | `EncounterParticleRuntimeService` | Encounter 绑定、阶段状态、时钟目标选择及服务端事件触发 |
| 静止时误播走路 | `AmonEntity.registerControllers()` | `mysterious$shouldPlayWalk`、GeckoLib movement predicate |
| 粒子不足或卡顿 | `EncounterParticleRuntimeService` | 只允许原版类型；检查 tick 间隔、实体上限和观看距离 |
| payload 解码失败 | 对应 `*Payload`、`ModNetwork` | size/value guard、协议版本、codec 测试 |
| 专服加载客户端类崩溃 | `client/**` | EventBus dist 限制和客户端类引用边界 |
| 模型紫黑/动画缺失 | `client.model`、资源目录 | ResourceLocation、JSON 动画名、语言/注册 ID |
| 第三方 API 变化 | `integration.curios` / `integration.iss` | 依赖锁、阶段 0 GameTest、兼容矩阵 |
| 未知存档进入 safe mode | `/mysterious debug recovery` | 日志错误、迁移链、原始 NBT；禁止先覆盖存档 |

## 12. 常见变更清单

### 新增 Encounter 持久字段

1. 在合适的不可变 record 中增加字段和构造不变量。
2. 更新 `EncounterSnapshot` 的所有构建/复制位置。
3. 在 `EncounterSerializer` 同时实现写和读。
4. 提升 `EncounterDataVersions`，在 `EncounterMigrationRegistry` 增加显式迁移。
5. 判断客户端是否需要该字段；需要时更新 snapshot/delta payload 与 cache。
6. 更新 debug 输出，保证线上可观察。
7. 增加旧版本迁移、NBT 往返、非法值拒绝和重启恢复 GameTest。

### 新增会改变世界的事务

1. 先定义稳定 transaction ID、完整证据和状态机。
2. 通过 Controller 写 `PREPARED`。
3. 执行 durability barrier。
4. 每次副作用前重新校验世界是否已执行，确保重启可重放。
5. 执行副作用并写 `COMMITTED`；必要时再次 durable。
6. 设计取消、冲突、Finalize 和离线恢复路径。
7. 用故障注入覆盖“写盘前、写盘后、副作用中、提交前”四类中断点。

### 新增服务端配置

1. 判断是“新 Encounter 冻结值”还是“全局实时值”。
2. 冻结值加入 `ArenaConfigSnapshot`、创建快照、序列化和迁移；实时值只由配置 getter 读取。
3. 为跨字段关系提供整体回退，不接受部分无效配置。
4. 更新 debug arena 输出和配置关系测试。

### 新增第三方集成

1. 在 `integration.<modid>` 建立窄接口，不让业务层直接依赖第三方类型。
2. 明确 required/optional 和客户端/服务端边界。
3. optional 集成必须在缺模组时完全不链接其类。
4. 在阶段 0 兼容矩阵、`gradle.properties`、依赖锁、模组元数据和 GameTest 中同步版本与降级策略。

## 13. 构建、验证与发布前检查

日常快速验证：

```bash
./gradlew build --stacktrace
./gradlew runGameTestServer --stacktrace
```

涉及依赖、客户端类或资源时再执行：

```bash
./gradlew runServer
./gradlew runClient
```

发布前至少确认：

- `build` 成功且 `verifyStageZero` 没有版本、Java 或动态依赖漂移。
- GameTest 输出 `All 63 required tests passed`；新增能力后应提高总数并同步本文。
- 专服不从 `run/mods` 加载任何客户端辅助模组。
- `recovery` 显示 `safeMode=false`，Escrow/Pending Return 没有无法解释的悬挂记录。
- 新资源的命名空间、注册 ID、语言键、模型路径和动画名一致。
- 新持久状态有显式迁移，不能只修改当前版本读写。

## 14. 相关设计文档

- `architecture.md`：源堡、多人归属和长期架构方向；其中部分内容仍是未来设计。
- `stage-0-compatibility.md`：冻结依赖、兼容矩阵、客户端/专服验收。
- `stage-1-encounter-registry.md`：Controller、Boss Registry、迁移与 Finalize 语义。
- `stage-2-config-arena.md`：配置快照、Arena、参与迟滞、弃战和 leash。
- `stage-3-4-observability-persistence.md`：网络、诊断、SavedData、迁移和 safe mode。
- `stage-5-7-combat-transactions.md`：战斗内核、P1 Split、Escrow 与返还账本。
- `stage-8-phase-transition.md`：通用阶段迁移、任务代际、recovery carrier 与 P1→P2。
- `stage-9-spell-framework.md`：法术模型、实例生命周期、中断和实体预算。
- `stage-10-phase-two.md`：P2 技能池、调度、真实雷击和 P2→P3。
- `stage-11-phase-three-timeline.md`：P3 40 秒可恢复时间线和预警。
- `stage-12-time-worm-parasite.md`：时间之虫、寄生叠加/伤害/净化和阶段 13 边界。
- `stage-13-clock-execution.md`：时钟生成/批结算、个人计数、统一 Execution 与复活兼容。
- `stage-14-second-form-flight-seal.md`：第二形态、固定飞行、弹射物免疫与物品身份封印。

维护时优先以代码、GameTest 和本手册的“当前状态”判断已实现范围；长期架构文档用于理解方向，不代表对应模块已经落地。
