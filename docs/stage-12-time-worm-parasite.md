# 阶段 12：TimeWorm 与 Parasite

## 时之虫

- P3 开始 20 tick 后生成第一只，之后每 20 tick（1 秒）尝试生成一只，共 8 只；达到活跃上限时本轮拒绝，不积压请求。旧存档中过远的下一次调度会自动收紧到新周期。
- 时之虫同时计入每场 128 个临时实体总预算；法术预留与已加载 Encounter 临时实体合并核算。预算拒绝也会推进到下一周期，不会在空位出现时补发旧请求。
- 属性为 10 HP、3 攻击，火焰与闪电免疫。
- 实体持久化 Encounter UUID 和最近成功攻击 tick；有效攻击间隔默认 40 tick，由服务端 `combat.wormAttackIntervalTicks` 配置。
- 时之虫不再维护一套只认玩家的独立仇恨：生成时及每个 P3 tick 都镜像阿蒙当前可攻击的 `LivingEntity` 目标，因此阿蒙转而仇恨普通生物或由其他模组分配目标时，全部时之虫会同步追击。Encounter Cleanup 会扫描并清除所有绑定本场的临时实体。

## 寄生

- 时之虫或 P3 Amon 成功近战命中玩家时增加 100 tick，而不是刷新为 100 tick；非玩家目标正常承受近战伤害，但不会写入玩家专属的寄生/必死账本。
- 寄生写入前会再次验证 Encounter 仍为 ACTIVE、目标仍属于本场且尚未 LEFT，实体目标选择不能替代结算入口校验。
- `ParasiteState` 分别保存 remainingTicks、连续 infectionTicks 与 lastProcessedTick。
- 寄生存在期间每 20 tick 造成 1 点挤压伤害；只有效果真实存在时才累计 infectionTime。
- `mysterious:parasite` 是可被牛奶和兼容净化正常移除的有害效果。服务端观察到效果完全消失后删除状态并将 infectionTime 归零；仅持续时间减少不会清零。
- 状态随当前 V7 Encounter 存档恢复；离线期间不会偷偷推进计时。
- 600 tick 阈值已经成为持久、可查询状态；阶段 13 的 `BossExecutionManager` 将消费该阈值并处理复活/免死去重。

## 验证与定位

GameTest 覆盖 +5 秒叠加语义、时之虫 Encounter NBT、非玩家目标同步和高级状态完整往返。生成问题查 `PhaseThreeRuntimeService.spawnWorm`/`retargetWorms`；重复快速攻击查实体 `MysteriousLastAttackTick`；净化不清零查玩家是否仍有 `mysterious:parasite`。

## 2026-09-24 阶段 9～12 审计

- `build` 与原有 40 项 GameTest 基线均通过。
- 修复时之虫只检查 8 只活跃上限、未进入全局临时实体预算的问题。
- 修复生成被拒后 `nextWormSpawnTick` 不推进而可能形成隐式积压的问题。
- 修复寄生结算仅信任攻击实体绑定、没有在写状态前重验参与资格的问题。
- 修复 40 tick 攻击间隔写死、未满足“服务端可配置”的问题；同时把 Amon 寄生入口限制为共享分类中的 `MELEE`，法术伤害不会误叠寄生。
- 阶段 9 的 cast lock/cooldown/预算/NBT、阶段 10 的 P2→P3 单 carrier、阶段 11 的转换预警绝对 tick 未发现破坏性缺陷；后续阶段 13/14 回归继续覆盖这些边界。
