# 阶段 2：Config、ArenaBounds 与参与者运行时

> 历史实现说明：运行时场地边界、自动加入/离场、玩家回传、Boss 软/硬 leash 和 `ArenaBounds` 已在后续产品规则中删除。下述结构只说明旧存档字段来源；`ArenaConfigSnapshot`/Center 数据暂留用于兼容已有 V7 存档，不再限制战斗空间。

阶段 2 已把 V4 的场地规则接入服务端权威运行时。所有会改变本场几何或计时解释的配置在 Encounter 创建时冻结为 `ArenaConfigSnapshot`；配置重载不会改写进行中的战斗。

## 配置与几何

- NeoForge SERVER 配置提供加入/保持/硬退出半径、Boss 软/硬回场、垂直范围、Join Grace、离场迟滞、Abandoned、P1 离场恢复和有限安全位置搜索次数。
- `ArenaConfigSnapshot` 校验 `join < retention < hardExit`、`bossSoft < bossHard < playerHardExit`、非负计时和恢复比例。跨字段配置无效时记录错误并回退到整套安全默认值。
- `EncounterCenter` 固定维度、中心坐标、持久化安全锚点与配置快照。
- `ArenaBounds` 是唯一圆柱几何实现；先验维度，再分别比较 XZ 水平距离和 Y 垂直距离，并避免极端整数坐标溢出。
- 紧急回场最多尝试快照指定次数，在中心 24～48 格候选环内寻找无碰撞位置；全部失败后使用持久化安全锚点，不会无限搜索。

## 参与者与弃战

- 玩家进入 64 格加入区后加入；已加入玩家在 80 格保持区外最多保留 10 秒，超过 96 格或换维度立即离开；重新加入必须再次进入 64 格区域。
- 首次加入和重新加入均获得 3 秒 Join Grace；玩家对绑定本场的 Amon 造成有效伤害后提前结束。
- 参与人数变为 0 时进入 `ABANDONED_PENDING`。玩家在 60 秒内重新加入会恢复 `ACTIVE`，不重置阶段、Boss Registry 或生命状态。
- P1 连续弃战 20 秒后，每秒为仍加载的存活 Amon 恢复 2% 最大生命；60 秒到期统一走幂等 `Finalize/Cleanup`，不发胜利奖励。

## Boss leash 与恢复

- 超过 72 格软边界或弃战期间，Amon 清除外向目标并向安全锚点返回。
- 超过 88 格、垂直越界或位于错误维度时，在恢复普通 AI 前执行有限安全位置搜索和紧急传送。
- 实体加入、卸载和死亡事件更新同一个 `BossRegistry`；卸载不会被当作死亡，找不到已加载实体先进入 `MISSING_PENDING`。
- Amon 只会选择本场仍有参与资格、同维度且 Join Grace 已结束的服务端玩家。

## 自动验证

`StageTwoGameTests` 覆盖配置关系校验、参与者迟滞/Join Grace/重新加入，以及 Abandoned 恢复不重置战斗状态。场地极值、持久化和迁移测试由阶段 3/4 套件继续覆盖。
