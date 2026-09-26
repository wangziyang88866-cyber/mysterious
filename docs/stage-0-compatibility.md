# 阶段 0 技术冻结与兼容性矩阵

本文记录阿蒙 Boss 工程阶段 0 的固定技术基线、兼容性 Spike、降级策略与验收命令。修改任何固定版本、映射或依赖范围后，必须重新执行本页全部验收，并更新矩阵。

## 冻结基线

| 项目 | 固定值 | 约束位置 |
| --- | --- | --- |
| Minecraft | 1.21.1 | `gradle.properties`、`neoforge.mods.toml` |
| NeoForge | 21.1.251 | `gradle.properties`、`neoforge.mods.toml` |
| FML Loader | 4.0.44 | `gradle.properties` |
| Java | 21 | Gradle toolchain、`options.release`、CI |
| Gradle Wrapper | 9.2.1 bin | `gradle-wrapper.properties` |
| ModDevGradle | 2.0.147 | `build.gradle` |
| Parchment | 1.21.1 2024.11.17 | `gradle.properties` |
| 文件编码 | UTF-8 | `build.gradle` |

构建同时启用依赖锁、可复现 JAR 顺序与时间戳归一化。`verifyStageZero` 会拒绝 Java 版本错误、关键版本漂移或动态依赖。

## Compatibility Matrix

| 依赖或接口 | 必须 | 最低～最高已验证版本 | 兼容结论 | 不可用时策略 |
| --- | --- | --- | --- | --- |
| NeoForge | 是 | 21.1.251 ～ 21.1.251 | 实体注册、数据组件、GameTest、服务端事件可用 | 阻止加载，不允许降级 |
| GeckoLib | 是 | 4.9.3 ～ 4.9.3 | 三种实体模型、动画和渲染器已链接；客户端实机可进入世界 | 阻止加载，不以静态模型替代 |
| Curios | 是 | 9.5.1+1.21.1 ～ 9.5.1+1.21.1 | `CuriosApi.getCuriosInventory` 可读取玩家槽位；由 `CuriosAccess` 隔离 | 阻止加载；不允许静默跳过饰品状态 |
| Iron's Spells 'n Spellbooks | 是 | 1.21.1-3.16.3 ～ 1.21.1-3.16.3 | 可读取启用法术及施法类型、等级、冷却；Amon 实现 `IMagicEntity` 并使用官方 MOB 施法生命周期；由 `IronsSpellAccess` 隔离物品容器读取 | 阻止加载；BossSpellAdapter 不猜测未知 API |
| Iron's Lib | 间接必须 | 1.21.1-2.1.0 ～ 1.21.1-2.1.0 | ISS 运行时依赖已固定 | 由 ISS 依赖检查阻止加载 |
| Player Animator | 间接必须 | 2.0.4+1.21.1 ～ 2.0.4+1.21.1 | ISS 运行时依赖已固定 | 由 ISS 依赖检查阻止加载 |
| FTB Teams | 否 | 2101.1.11 ～ 2101.1.11 | 编译边界已固定，尚未实现队伍解析器 | 使用个人 UUID 所有权 |
| FTB Library | FTB 间接依赖 | 2101.1.30 ～ 2101.1.30 | 与 FTB Teams 编译版本配套 | FTB Teams 不启用 |
| Architectury | FTB 间接依赖 | 13.0.8 ～ 13.0.8 | 与 FTB Teams 编译版本配套 | FTB Teams 不启用 |
| 原版不死图腾 | 是 | MC 1.21.1 ～ 1.21.1 | `LivingUseTotemEvent` 只观察、不取消；Boss Execution 后续必须尊重该事件 | 不得绕过原版免死 |
| 模组死亡拦截 | 是 | NeoForge 21.1.251 ～ 21.1.251 | `LivingDeathEvent` 以最低优先级观察已取消事件，不改变结果 | 无通用事件的模组进入专用兼容层 |
| ItemStack 唯一标记 | 是 | MC 1.21.1 ～ 1.21.1 Data Component | `mysterious:item_instance_id` 持久化并网络同步，GameTest 验证序列化 | 标记失败时不得启动封印事务 |

## Spike 产物

- `ModDataComponents` 与 `ItemStackIdentity`：验证单件物品可获得稳定 UUID；多件堆叠必须先拆分，避免无意标记整个堆叠。
- `CuriosAccess`：只暴露玩家饰品处理器和槽位数量，不让后续业务散落直接 API 调用。
- `IronsSpellAccess`：只读法术元数据，为后续 BossSpellAdapter 提供稳定入口，不直接把玩家法术实例交给 Boss。
- `DeathProtectionObserver`：观察图腾和被其他模组取消的死亡事件，不取消、补发或重复提交死亡。
- `StageZeroCompatibilityProbe`：专服启动后把 Java、依赖版本和可读 ISS 法术数量写入 `latest.log`；玩家登录时以 DEBUG 记录 Curios 槽位。
- `StageZeroGameTests`：覆盖实体生成与 NBT 往返、ItemStack 身份序列化、ISS 读取和 Curios 玩家能力。

## 运行目录与日志

| 任务 | 游戏目录 | 目的 |
| --- | --- | --- |
| `runClient` | `run/` | 客户端手工验证，可放客户端辅助模组 |
| `runServer` | `run-server/` | 纯专服验证，不扫描 `run/mods` |
| `runGameTestServer` | `run-gametest/` | 自动 GameTest |
| `runData` | `run-data/` | 数据生成 |

各目录的日志由 Minecraft 写入自身 `logs/`。不得再次让客户端和专服共用游戏目录；阶段 0 已确认 `run/mods` 中的 Sodium 会让专服因缺少 LWJGL 客户端类而提前退出。

## 验收命令

```bash
./gradlew clean build
./gradlew runGameTestServer
./gradlew runServer
./gradlew runClient
```

验收标准：

1. `build` 包含 `verifyStageZero` 且成功，生成可复现 JAR。
2. GameTestServer 中三个 `StageZeroGameTests` 全部通过并正常退出。
3. 专服完成启动，日志包含 `[stage0] dedicated-server compatibility ready`，且不出现客户端类加载异常。
4. 客户端完成启动，三种实体渲染、动画与原版粒子/声音正常。
5. 依赖变更后执行 `./gradlew dependencies --write-locks` 并审查 `gradle.lockfile`，禁止无意升级。

## 本次验收记录

2026-09-23 已在 macOS arm64、Eclipse Adoptium Java 21.0.12.1 上重新完成阶段 0 Gate；阶段 0～4 由完整回归套件持续覆盖：

- `./gradlew clean build --stacktrace` 成功，`verifyStageZero` 与制品构建全部通过。
- `./gradlew runGameTestServer --stacktrace` 成功，三个阶段 0、九个阶段 1、三个阶段 2 与七个阶段 3/4 测试全部通过并正常退出。
- `./gradlew runServer` 在隔离的 `run-server/` 中启动到 `Done`；日志包含阶段 0 专服探针，未加载 `run/mods` 中的客户端辅助模组。
- `./gradlew runClient` 进入已有测试世界，三实体渲染注册正常加载。此次命令行验收在完成检查后由开发者终止进程，因此 Gradle 进程退出码为 130，不代表游戏启动失败。

## 已知边界

阶段 0 只冻结技术入口，不实现 Encounter、事务持久化、正式 BossSpellAdapter、FTB Teams 归属或 Boss Execution。复活兼容当前覆盖原版图腾和 NeoForge 可取消死亡事件；具体第三方复活模组需在阶段 13 建立实测矩阵。
