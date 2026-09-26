# 阶段 9：Spell Framework

阶段 9 已建立不依赖玩家施法对象的 Boss 法术框架。核心入口位于 `com.mysterious.spell`。

## 已实现

- `BossSpellDefinition` 固定 spell ID、类别、目标类型、距离、准备/持续/冷却、cast group、全局锁、并发上限、中断策略、伤害分类、实体预算、恢复与清理策略。
- `BossSpellRegistry` 注册 `TEST_PROJECTILE / TEST_AOE / TEST_SUMMON`，并为阶段 10 注册三个固有法术与安全传说法术。
- `SpellCastManager` 提供选择、PREPARE、持续 tick、结束释放预算、阶段中断和重启恢复所需的纯状态转换。
- `EncounterEntityBudget` 强制每场 128 临时实体、64 法术实体上限；超额立即拒绝，不排队补生成。
- `SpellRuntimeState` 持久化 cooldown、instance ID、owner boss、持续时间、全局锁、下一次施法/环境事件和预算占用。
- 阶段迁移 begin 会清空旧阶段 spell instance 与全局锁，防止排队施法跨阶段提交。

## 定位

`/mysterious debug spells` 显示每场 nextCast、nextEnvironment、实例数与预算。卡锁先查 `activeCastInstanceId` 是否仍存在于 instances；预算不释放查 `SpellCastManager.tick`；重启重复实例查当前 V7 `advanced.spells`。

## 验证

`StageNineTwelveGameTests` 覆盖三类测试法术、预算边界、全局锁/独立冷却、实例到期清理和 NBT 恢复。
