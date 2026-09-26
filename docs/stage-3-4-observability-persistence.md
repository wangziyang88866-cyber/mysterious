# 阶段 3/4：网络、可观测性与基础持久化

本页记录已完成的阶段 3/4。实现保存已经稳定的 Encounter 普通状态，不提前定义阶段 5 的事务账本、Escrow、PendingReturns 或阶段 6 之后的技能内部数据。

## 阶段 3 已实现

- 协议版本为 `1`。S2C 类型包括完整 `EncounterSnapshot`、基于 `baseRevision` 的 `EncounterDelta`、带 `eventId` 的 Phase/Warning/Visual 事件。
- C2S 只有 `EncounterIntentPayload`；当前唯一意图是请求快照。包内不接受客户端坐标、阶段、命中结果、Boss 状态或物品数据。
- 服务端按当前玩家 UUID、权威 owner/参与者状态重验请求，并以每玩家最近 request ID 窗口拒绝重放。FTB Teams 成员校验在适配器完成前默认拒绝，不猜测权限。
- 客户端投影只接受不倒退的快照和以当前 revision 为 base 的增量；发现缺口会发出一次完整重同步请求。一次性事件按 event ID 去重。
- `/mysterious debug ...` 提供 encounters、encounter、players、bosses、phase、arena、entities、network、recovery 查询。transactions、escrow/pendingReturns、spells 在对应阶段前明确报告未初始化。
- 高风险写入口记录 encounter ID、transition/finalization ID、阶段、tick、revision 与结果；恢复和拒绝请求使用独立日志标签。
- Controller 每次权威变更自动向当前参与者发布完整快照或连续增量；登录会完整重同步，离场与终态会发送清场事件并删除客户端缓存。阶段 claim/commit 使用可重复推导的 event ID。

HUD 等后续表现消费者可直接消费这里已经具备重同步与清场语义的载荷。

## 阶段 4 已实现

- `EncounterSavedData` 保存在主世界 DataStorage，文件键为 `mysterious_encounters`；每个服务端只恢复一个 `EncounterController`。
- 阶段 4 当时的根数据为 V2；阶段 5～7 推进到 V3，阶段 8 当前为 `schemaVersion=4`、`dataVersion=4`。V1→V2 补入场地恢复字段，V2→V3 补入战斗/分裂/Escrow/PendingReturn，V3→V4 补入阶段代际和 recovery carrier；未知未来版本仍失败关闭。
- 保存 Encounter ID、owner、Center/ArenaSnapshot、安全锚点、created tick、revision、lifecycle、phase、BossRegistry、PlayerState、基础 timers、transition、termination 与 Cleanup 状态。
- 迁移 claim 和 Finalize 外部 Cleanup 前执行 durability barrier：等待 NeoForge 异步 IO worker 完成原子写盘，再读取磁盘 NBT 与权威内存快照逐项比对；失败会阻止破坏性步骤继续。若重启读到 `IN_PROGRESS` Cleanup，会保留同一 `finalizationId`，转为 `ENDED_ERROR_RECOVERY + PENDING`，等待同一逻辑操作重试。
- 解码、版本或不变量失败会进入管理员可见的 safe mode：原 NBT 被完整保留，Controller 禁止写入，防止坏档被空状态覆盖。
- 停服时捕获 Controller 最新快照并要求 DataStorage 保存。普通变更通过 SavedData dirty 标记进入原版保存周期。
- 恢复先解码中心/Arena 与 Registry，再由服务器 tick 在恢复攻击 AI 前重绑实体并执行越界安全回场；终态 Cleanup 会清除客户端状态并移除本场绑定 Boss。

## 阶段 2 接口

历史阶段 3/4 曾让 `EncounterCenter`、`ArenaConfigSnapshot` 与 `ArenaBounds` 驱动边界；当前产品规则已删除 `ArenaBounds`、玩家回传与 Boss leash。Center/配置字段只为 V7 存档兼容与生成原点保留。

## 自动验证

`StageThreeFourGameTests` 覆盖：

1. 单一、维度感知且无整数溢出的 Arena 几何；
2. Center/Arena、Registry、Player、Timer 与生命周期的完整 NBT 往返；
3. Cleanup durability barrier 后模拟中断，恢复为相同 finalization ID 的可重试错误态；
4. 未知 schema 进入不覆盖原数据的 safe mode；
5. 网络快照编解码、revision 缺口、重放与 stale delta；
6. 阶段 commit 不能早于 claim。
7. V1 数据通过显式迁移获得 V2 的安全锚点和恢复配置。

验收命令：

```bash
./gradlew clean build --stacktrace
./gradlew runGameTestServer --stacktrace
```

本阶段完成时阶段 0～7 套件为 27 项；阶段 8 当前完整基线已提高到 32 项。故障注入用例会有预期的 ERROR/recovery 堆栈，但最终必须显示全部 required tests passed。
