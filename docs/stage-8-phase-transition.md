# 阶段 8：通用 PhaseTransition Framework

阶段 8 已完成通用、持久化、可恢复的阶段迁移框架，并用它闭环第一阶段到第二阶段。阶段 9 的法术系统只能消费这里提交后的 `PHASE_TWO`，不得绕过迁移状态机。

## 开工前阶段 0～7 核对

- `./gradlew build --stacktrace` 成功，阶段 0 静态 Gate、Java 21、依赖锁和制品构建正常。
- 既有 27 项 GameTest 全部纳入本次回归，没有删除或放宽原测试。
- Encounter、Arena/参与者、网络 revision、V3 SavedData、战斗预算、Split、Escrow 和 Pending Return 的权威入口仍保持不变。
- 审计发现的阶段 8 缺口是：原迁移只有 claim/commit；carrier 死亡会直接退回 ACTIVE；没有旧阶段任务代际；P1 同 tick 全灭尚未自动执行 P1→P2。

## 已实现范围

- `phaseGeneration` 是持久化的阶段任务代际。`PhaseTaskToken` 捕获 encounter、phase 与 generation；begin 或 rollback 都推进 generation，排队的 P1 工作随后无法提交。
- `PhaseTransitionRecord` 除唯一 transition ID 与当前 carrier 外，持久化稳定的 `recoveryBossId`、恢复维度和恢复位置。
- 普通 begin 会从合法存活 Boss 原子 claim 唯一 carrier，并在 durable barrier 前取消 PREPARED Split、关闭 P1 注册窗口、推进任务代际。
- P1 同 tick 全灭使用 `beginPhaseTransitionWithRecoveryCarrier`：先把预分配 UUID 以 `MISSING_PENDING` 写入 Registry 并 durable，再生成实体，避免“先生成、后记账”。
- carrier 在 commit 前死亡或被移除时不再产生新 transition；Controller 保留原 transition ID，并把同一记录切换到预分配 recovery carrier。
- `PhaseTransitionRuntimeService` 只在 P1 所有 Amon 都真实死亡后开始迁移，从最终死亡记录选择稳定恢复位置，并生成全新、满血的唯一 P2 carrier。
- transition claim 后会立即丢弃所有非 carrier 的旧 P1 实体（包括仍在死亡动画中的尸体），避免同 tick 全灭时出现多个“假死”阿蒙；P2 始终只有唯一可见 carrier。
- recovery carrier 创建后恢复满血、重建共享偷窃属性，再由同一 transition ID 提交 P2。未加载的普通 carrier 不会被误判死亡或复制生成。
- commit 和显式 safe rollback 都经过 durability barrier。rollback 是恢复策略 API，不由普通 death/remove 回调擅自触发。
- P1→P2 与 P2→P3 commit 都会触发服务端广播的三秒粒子/声音仪式，不发送聊天框提示；动画以提交后的阶段开始 tick 为准。
- `/mysterious debug phase <id>` 现在显示 generation、carrier、recovery carrier 与恢复位置。

## 存档升级

当前根版本为 `schemaVersion=4 / dataVersion=4`。

V3→V4 显式加入：

- Encounter 的 `phaseGeneration`；
- 活跃迁移的 `recoveryBossId`；
- recovery dimension 与 position。

旧 V3 活跃迁移的 recovery UUID 由 transition ID 确定性派生，位置从旧 carrier 的 BossRecord 迁移。未来版本仍失败关闭到 safe mode。

## 关键定位路径

| 问题 | 首查位置 |
| --- | --- |
| P1 全部死亡但不进 P2 | `PhaseTransitionRuntimeService.tick/startRecoveryTransition`，再查 Registry 的 `isAlive` |
| 分裂任务在迁移后仍执行 | `PhaseTaskToken`、`phaseGeneration`、`cancelOldPhaseWork` |
| carrier 死亡后迁移丢失 | `recoverTransitionIfCarrierTerminated` 与 active transition 的 recovery 字段 |
| 同 tick 全灭生成重复 Boss | `beginPhaseTransitionWithRecoveryCarrier`、recovery UUID、durability barrier |
| 重启后卡在 TRANSITION | `/mysterious debug phase`，核对 carrier lifecycle、recovery dimension 与区块是否加载 |
| 旧存档加载失败 | `EncounterMigrationRegistry.migrateSchemaV3ToV4` 与 safe-mode 日志 |

## 自动验证

`StageEightGameTests` 新增 5 项：

1. begin 使旧阶段任务 token 失效；
2. 全灭 recovery carrier 在生成实体前原子入账；
3. carrier death/remove 保留同一 transition 与固定 recovery identity；
4. recovery 元数据和任务代际可完整 NBT 往返；
5. safe rollback 幂等且恢复 ACTIVE。

2026-09-23 验收结果：

```text
./gradlew build --stacktrace                 BUILD SUCCESSFUL
./gradlew runGameTestServer --stacktrace     All 32 required tests passed
```

阶段 9 开工前必须继续保持 32 项回归全部通过，并在新增 Spell Framework 后同步提高测试数量与维护导航。
