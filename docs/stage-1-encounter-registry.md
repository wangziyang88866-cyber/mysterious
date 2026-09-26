# 阶段 1 Encounter 与 Registry 核心

阶段 1 的目标是先固定战斗状态的唯一所有者和生命周期语义，再允许后续模块加入场地、网络、持久化和战斗规则。阶段 3/4 开工后，现有状态已接入基础 durable SavedData；阶段 1 的所有写入语义仍只由 Controller 负责。

## 已实现范围

- `EncounterManager` 为每个运行中的 `MinecraftServer` 提供唯一 `EncounterController`，停服时移除运行时引用。
- `EncounterController` 是所有 Encounter、BossRegistry、阶段迁移和结束状态的唯一写入口；实体不能自行提交阶段或清理。
- Encounter 生命周期明确区分 `ACTIVE`、`ABANDONED_PENDING`、`PHASE_TRANSITION`、`CROSS_DIMENSION_CHASE`、`ENDED_VICTORY`、`ENDED_ABANDONED`、`ENDED_ERROR_RECOVERY`。
- `RealmOwner` 固定 Encounter 所有权；空 UUID 或空所有权类型会被拒绝。
- `BossRecord` 保存实体 UUID、阶段、最后维度、最后坐标、最后确认 tick、生命周期和可选的迁移承接 ID。
- Boss 生命周期明确区分 `LOADED`、`UNLOADED`、`MISSING_PENDING`、`DEAD`、`REMOVED`。找不到已注册实体本身不会提交死亡。
- 阶段迁移使用唯一 `transitionId`、显式 `PHASE_TRANSITION`、唯一 carrier 和单次 commit；重复 claim/commit 是幂等操作。
- 阶段迁移只允许 P1→P2、P2→P3；提交时全部 Registry 记录与权威阶段一起前进，避免多 Boss 快照出现阶段分裂。
- victory、abandoned、error recovery 都通过 `finalizeEncounter`。每次逻辑结束拥有稳定 `finalizationId`；成功后不重复执行 Cleanup，失败后进入 `ENDED_ERROR_RECOVERY` 并以相同 ID 重试。
- `AmonEntity` 只持久化 Encounter UUID 绑定，不持有全局战斗状态，也不能据此自行结束 Encounter。

## 核心不变量

1. 只有 `EncounterController` 可以修改 Encounter 和 BossRegistry。
2. 重复注册完全相同的 BossRecord 是 no-op；相同 UUID 携带不同状态会被拒绝，防止静默覆盖。
3. `UNLOADED` 与 `MISSING_PENDING` 均计入存活 Boss；只有显式 `DEAD` 或 `REMOVED` 是终态。
4. Boss 观察 tick 不允许倒退，避免迟到回调覆盖更新状态。
5. 一个 `transitionId` 只能有一个 carrier，也只能提交一次。
6. Finalize 的外部清理不在 Controller 锁内执行；并发重复调用只能看到 `IN_PROGRESS`，不会重复启动清理。
7. Cleanup 失败保留原 `finalizationId`，把原因归一为 `ERROR_RECOVERY`，状态回到可重试的 `PENDING`。
8. revision、时间与注册表键必须单调且与快照一致；阶段提交不能早于 claim，终态必须同时具有 termination 与非 `NOT_REQUESTED` Cleanup 状态。
9. Finalize 若发生在迁移中，会先释放 carrier/transition 元数据，再进入统一终态，避免留下无法恢复的半迁移状态。

## 自动化覆盖

`StageOneGameTests` 当前覆盖九项：

- 重复 Boss 注册不产生重复记录。
- unload → reload 保留同一 Boss 身份并刷新位置。
- missing pending 不误判死亡且仍计入存活数。
- 重复 Finalize 只提交一次 Cleanup。
- Cleanup 故障后以同一 finalizationId 重试。
- 阶段迁移只 claim 一个 carrier 且只 commit 一次。
- 非法跨阶段迁移会被拒绝，多 Boss 提交后 Registry 阶段保持一致。
- 阶段 1 原始测试验证未提交 carrier 不会留下不可序列化状态；阶段 8 已将策略升级为保留同一 transition ID 并切换到预分配 recovery carrier，而不是自动退出迁移。
- Amon 的 Encounter UUID 绑定经过实体 NBT 往返后保持不变。

连同阶段 0 的三个、阶段 2 的三个和阶段 3/4 的七个测试，`runGameTestServer` 当前共执行二十二个必需测试。

## 后续阶段边界

- 阶段 2 已接入完整参与资格、离场迟滞、Join Grace、Abandoned 和 Boss leash。
- 阶段 3 已接入 `EncounterSnapshot` 网络视图、revision、事件 ID、意图重验、重同步、参与者广播、客户端清场和管理员查询。
- 阶段 4 建立了 V2、显式迁移链、SavedData、安全模式和 Cleanup 中断恢复；阶段 5～7 已沿此边界升级到 V3 并接入 Split、Escrow 与 PendingReturns。
- 阶段 8 会在现有 transition claim/commit 原语上增加完整迁移任务、故障注入和恢复步骤。
- Cleanup 的具体任务停止、实体删除、临时状态、Escrow、共享属性与最终落盘由对应后续阶段逐项接入同一 `EncounterCleanup` 边界。

## 验收命令

```bash
./gradlew clean build --stacktrace
./gradlew runGameTestServer --stacktrace
```

阶段 1 回归 Gate 要求构建成功、二十二个必需 GameTest 全部通过，并且专服不加载客户端类。故障注入测试会按预期写出带堆栈的 recovery 日志，但测试必须恢复并通过。
