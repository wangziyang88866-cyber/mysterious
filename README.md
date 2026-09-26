# Mysterious — 源堡与 Amon 基础工程

Minecraft 1.21.1 / NeoForge 21.1 / Java 21。当前已完成服务端权威的完整 Amon 战斗主链：P1 单次分裂与偷窃托管、可恢复阶段迁移、P2/P3 全注册表法术，以及 P3 时间线、Worm/Parasite、Clock/Execution、第二形态、跨维度追击和原版客户端表现。

阶段 0～19 已完成。阶段 15 增加跨维度与 Missing Recovery，阶段 16 增加原版粒子/声音表现，阶段 17～18 固定组合回归、资源上限和 RC1 数值，阶段 19 补齐显式开战和统一停止入口。日常定位请先看 [`docs/maintenance-guide.md`](docs/maintenance-guide.md)，逐阶段说明位于 `docs/stage-0-*` 至 `docs/stage-19-*`。

## 结构

```text
src/main/java/com/mysterious/
├── mysterious.java                 # 公共模组入口
├── registry/ModEntities.java       # 三实体注册、属性
├── encounter/                      # EncounterController、BossRegistry、生命周期与 Finalize
├── arena/                          # 旧存档兼容用 Center/配置数据（运行时边界已移除）
├── combat/                         # 伤害预算、攻击身份、仇恨和目标选择
├── phase/                          # P1 scanner、任务代际与通用阶段迁移运行时
├── transaction/                    # Split 事务状态和替换计划
├── theft/                          # 偷窃、Escrow、返还、离线账本和属性投影
├── config/                         # NeoForge 服务端配置与安全默认回退
├── network/                        # S2C 快照/增量/事件、C2S 意图和 revision 投影
├── persistence/                    # V7 schema/dataVersion、迁移、SavedData 与恢复
├── debug/                          # 只读管理员诊断命令
├── dimension/                      # 预留：源堡区域分配与持久化边界
├── ownership/                      # 个人归属已实现；FTB Teams 解析预留
├── battle/                         # 预留：战斗 session 与奖励聚合
├── inventory/                      # 单件 ItemStack 稳定身份
├── integration/                    # Curios/ISS 探针；FTB 正式适配预留
├── spell/                          # Boss 法术定义、生命周期、实体预算与 P2/P3 调度
├── entity/                         # Amon、Phantom Clock、Worm of Time
└── client/
    ├── ModEntityRenderers.java     # 仅客户端注册渲染器
    ├── model/                      # 各实体的 GeckoLib 资源路径
    └── renderer/                   # 各实体渲染器
src/main/resources/assets/mysterious/
├── animations/                    # GeckoLib 动画
├── geo/                           # GeckoLib 几何模型（必须为 geo）
├── textures/entity/                # 实体贴图
└── lang/                          # 中文/英文名称
```

完整架构、多人所有权、可选 FTB Teams 兼容和实现顺序见 [`docs/architecture.md`](docs/architecture.md)。当前已建立个人/队伍归属的代码边界；实际 FTB Teams 适配器会在实现区域分配时接入。未安装 FTB Teams 时将始终使用个人归属。

## 开发与验证

`./gradlew build` 编译并执行阶段 0 静态 Gate；该 Gate 要求 Gradle 本身运行在 Java 21。`./gradlew runGameTestServer` 执行兼容性、状态机、网络、战斗、事务恢复、跨维度、视觉清理、开战/停止与压力基线测试；开发客户端使用 `./gradlew runClient`，纯专服使用 `./gradlew runServer`。管理员以玩家身份执行 `/mysterious start` 开始当前坐标的一场 P1 战斗，执行 `/mysterious stop <id>` 统一结束并清理；`/mysterious debug encounters`、`encounter <id>`、`players <id>`、`bosses <id>`、`phase <id>`、`transactions`、`escrow`、`pendingreturns`、`entities <id>`、`network`、`recovery`、`spells` 查询真实状态。客户端与专服目录已经隔离，禁止把 Sodium 等客户端模组复制到 `run-server/mods`。

构建将从官方 Maven 获取 GeckoLib、Curios、Iron's Spells 'n Spellbooks、Iron's Lib 和 ISS 要求的 Player Animator。FTB Teams、FTB Library 与 Architectury API 是可选集成：仅在希望使用队伍归属时安装。正式安装时所有已启用的前置都需随整合包单独安装，不会被本模组 jar 内嵌。版本固定在 `gradle.properties`，直接依赖要求在 `neoforge.mods.toml`。
