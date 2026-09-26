# 阶段 19：显式开战与统一停止入口

## 目标

将 P1 的五体生成接入一个可操作、可诊断且不会与普通实体生成混淆的战斗入口；人工停止必须复用既有 Finalize/Cleanup/Escrow 路径。

## 实现

- OP 以玩家身份执行 `/mysterious start` 时，`EncounterStartService` 在执行者当前维度与方块坐标创建 Encounter，并冻结当时的 Arena 配置。
- 创建后先把执行者加入玩家表，再调用 `PhaseOneRuntimeService.spawnInitialWave`；因此初始 5 个 Amon 都有 Encounter UUID、Boss Registry 记录和 P1 阶段归属。
- 入口拒绝 safe mode、同一玩家已有未终结 Encounter、或执行者处于其他活跃 Encounter 的 hard-exit 范围。普通 `/summon mysterious:amon` 不会创建或接管 Encounter。
- 初始生成中若发生异常，服务会以 `ERROR_RECOVERY` 终结刚创建的 Encounter，避免保留半场状态或遗漏已生成的绑定实体。
- OP 可用 `/mysterious stop <encounter_id>` 人工中止。它以 `ABANDONED` 调用 Controller Finalize，因此绑定实体清理、客户端清场、Escrow 返还和离线待返还账本均与原有弃战路径一致。
- OP 可用 `/mysterious clear <encounter_id>` 在完整 Cleanup 后删除指定持久化记录，或用 `/mysterious clear` 清除全部 Encounter；完成后可立即重新 `/mysterious start`。
- 正常结局没有改动：P1/P2/P3 继续依赖既有阶段运行时，P3 最终死亡仍以 `VICTORY` 终结；玩家全部离开仍按既有弃战计时结束。

## 人工测试

1. 使用拥有 OP 权限的生存玩家启动 `./gradlew runClient`，进入空旷区域。
2. 执行 `/mysterious start`。聊天栏应显示 Encounter ID 和 `bosses=5`，附近出现 5 个绑定的 P1 Amon。
3. 执行 `/mysterious debug encounters`，再用输出 ID 执行 `/mysterious debug phase <id>`、`/mysterious debug bosses <id>`；应看到 `ACTIVE`、`PHASE_ONE`、一名玩家和五个 Boss。
4. 正常战斗可沿已有 P1→P2→P3 链推进。战斗中行走动画只有在移动时播放；技能视觉使用已有原版多层粒子与客户端效果。
5. 若需立即验证结束清理，执行 `/mysterious stop <id>`，然后再次执行 `/mysterious debug encounter <id>`；应为终态，`cleanup=COMMITTED`，周围绑定 Boss 被移除。重复 stop 不会重复返还或重复清理。

## 回归

`StageNineteenGameTests.explicitStartCreatesBoundWaveAndStopFinalizes` 覆盖开战时的 P1、玩家加入、五个 Boss 登记，以及 stop 后的终态 committed Cleanup。
