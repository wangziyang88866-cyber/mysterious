# 阶段 5～7：基础战斗、P1 分裂与物品托管

本页记录阶段 5、6、7 的首轮完整实现。所有结算仍以服务端 `EncounterController` 为唯一写入口；客户端不提交伤害、偷窃或事务状态。

## 阶段 5：基础战斗内核

- Amon 基础最大生命按 V4 修正为 200，近战伤害 10。实体类型不再标记火焰免疫，绑定与未绑定 Amon 都接受原版玩家、生物和环境伤害。
- `DamageDeliveryType` 与保守分类器统一定义 `MELEE / PROJECTILE / BEAM / AOE / DOT / DIRECT_MAGIC / ENVIRONMENT`，供后续法术和 P3 免疫直接复用。
- 所有来源在护甲、效果等减伤结算后，单次有效伤害上限为 10 点，P1/P2/P3 统一生效。Amon 不再使用保留 1 HP、取消死亡或来源拒绝的阶段保护。
- `AttackEventIdentity` 以 Encounter、攻击者、目标、直接实体和服务器 tick 生成稳定事件 ID；受击传送和偷窃共用该身份。
- Encounter 持久化共享仇恨表、最近有效参与 tick 和当前目标。伤害按 1:1 增加仇恨，被 Amon 命中增加 2，最近玩家每秒增加 1；10 秒无有效参与后每秒衰减 5%，新目标达到当前目标 120% 才切换。
- P2/P3 在 8 秒无收/发伤害后每秒恢复 5 点生命。普通受击传送上限为 10%，成功后至少 40 tick 冷却，任意两次常规传送至少间隔 80 tick；主动贴近的无有效命中窗口为 12 秒。场地边界、Boss 回拉和玩家回传均已删除；主动贴近会落在目标玩家精确坐标，传送后 20 tick 禁止近战和偷窃。
- Amon 使用 10 tick 近战间隔、4.5 格判定距离。Encounter 选中的玩家仇恨目标拥有最高优先级；没有这类玩家时，原版 `HurtByTargetGoal` 与低优先级 `NearestAttackableTargetGoal<LivingEntity>` 可反击或锁定其他生物，但排除玩家、其他 Encounter 实体和队友。

## 阶段 6：P1 与 SplitTransaction

- `PhaseOneRuntimeService.spawnInitialWave` 是 Encounter 启动端的显式 5 体生成入口；P1 注册表硬限制最多 10 个存活 Amon。
- Controller 在存活 Boss 中确定唯一、无额外战斗特权的扫描执行者；每 200 tick 扫描 16 格内村民、卫道士、唤魔者和女巫。
- 分裂先建立包含源 UUID、预分配结果 UUID、维度和位置的 `PREPARED` 计划并经过 durable barrier，之后才杀死源实体和生成 Amon。
- 重启恢复对照预分配结果 UUID 与 BossRegistry，只生成缺失结果；全部结果注册后才写入 `COMMITTED` 并永久设置 `splitConsumed=true`。
- P1 开始阶段迁移时，仍为 `PREPARED` 的分裂会明确转为 `CANCELLED`；迁移期间禁止注册新的 P1 Boss。

## 阶段 7：Theft / Escrow

- P1/P2/P3 中 Amon 的服务端有效近战命中都可触发偷窃，默认/最低概率为 60%；Join Grace、传送后锁定、20 tick 玩家偷窃保护和攻击事件去重均在服务端检查。
- 候选仅为防具和主背包第 9～35 槽；快捷栏、饰品、空物品和命中实时防偷附魔列表的物品不会被选择。防具/背包默认各 50%，一侧为空时自动回退另一侧。
- 每次严格移除 count=1。`EscrowRecord` 保存稳定 `stolenItemId`、完整单件 NBT、无身份标记的原物品指纹、原槽位、`beforeCount / expectedAfterCount` 和攻击事件 ID。
- 流程为 `PREPARED + durable barrier → 重验槽位 → shrink(1) → COMMITTED + durable barrier`。玩家登录会按指纹和数量把中断记录恢复为 `CANCELLED / COMMITTED / CONFLICT`，不会直接生成物品。
- 返还拥有稳定 `returnTransactionId`，采用 `COMMITTED → RETURNING → RETURNED`。优先原空槽，再进入背包，最后掉落；背包及已加载掉落实体都会按物品实例 ID 去重。
- Encounter 结束且玩家离线时，记录先幂等转入 `GlobalPendingReturnStore` 并 durable 落盘；玩家下次登录后从独立账本返还，不依赖已结束 Encounter。
- 被偷防具的安全属性白名单为 Armor、Toughness、Knockback Resistance、Max Health、Attack Damage、Movement Speed。转换保留原 Modifier Operation，使用确定且唯一的 Boss Modifier ID，并按 V4 上限裁剪；重建前先清除本模组旧 Modifier，最大生命变化保持当前生命百分比。

## 存档、迁移与诊断

- 本阶段当时将 Encounter 存档升级为 V3；阶段 8 当前版本为 `schemaVersion=4 / dataVersion=4`。V2→V3 仍负责补入 combat、phaseOne、escrow 和根级 pendingReturns，V3→V4 由阶段 8 文档说明。
- `/mysterious debug transactions`、`escrow`、`pendingreturns` 现在报告真实事务；`spells` 明确保留到阶段 9。

## 自动验证

`StageFiveSevenGameTests` 覆盖 120% 目标切换、Split PREPARED/COMMITTED、Escrow 证据持久化和全局待返还独立持久化。

```bash
./gradlew build --stacktrace
./gradlew runGameTestServer --stacktrace
```

当前结果：构建成功，`All 27 required tests passed`。
