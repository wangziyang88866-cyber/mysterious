# 阶段 15：跨维度追击与 Missing Recovery

## 已实现流程

- 仅 P3 第二形态参与：目标换维度后先保留参与资格，不再把新维度坐标与原 `EncounterCenter` 比较。
- 原维度仍有合法参与者时由既有仇恨系统重选目标；只有“原维度无人且远端只剩一个合法目标”才创建追击候选。
- 候选等待 40 tick。期间原场玩家返回、目标变化或 200 tick 共享冷却尚未结束都会取消候选。
- 落点在目标 6～10 格内最多检查 16 次，验证世界边界和完整碰撞箱。失败只重启有界等待，不生成替代 Boss。
- 成功后进入 `CROSS_DIMENSION_CHASE`，原中心/维度保持不变，时钟、虫和普通法术因非 `ACTIVE` 自动暂停；传送后沿用 Amon 的 20 tick 攻击锁。
- 目标失效、离线或原场重新出现合法参与者（含 chase 期间新进入 64 格的玩家）时，原实体返回 `safeAnchor`/安全场内点。返回失败进入 `returnPending`，停止目标、导航和速度，并在后续 tick 幂等重试。
- 追击目标与 Boss 在同一维度时仍是合法战斗双方；传送后 20 tick 攻击锁结束即可近战，其他玩家不能借追击态越权伤害 Boss。

## 持久化与恢复

`AdvancedEncounterState.crossDimensionChase` 保存目标 UUID、目标维度、请求/执行 tick、是否已经迁移和返回重试位；共享冷却仍由 `EncounterTimerState` 保存。存档升级为 schema/data V7，V6 缺少该字段时迁移为空闲态。

`CrossDimensionRuntimeService` 每秒跨全部服务端维度按 Registry UUID 对账 `MISSING_PENDING`。找到同一实体只执行 `markBossLoaded`，找不到则继续等待，绝不把未加载等同死亡，也绝不生成第二个 Amon。

## 定位顺序

1. `/mysterious debug phase <encounterId>`：检查 chase 六字段和 cooldown。
2. `/mysterious debug bosses <encounterId>`：确认原 UUID、最后维度与生命周期。
3. `/mysterious debug recovery`：查看 `missingPending` 与 safe mode。
4. 无法追击先查唯一远端目标、40 tick 等待、200 tick 冷却和 16 次碰撞落点；无法返回先查原维度是否加载。

`StageFifteenSixteenGameTests` 覆盖 chase/cooldown 重启恢复、V6→V7 迁移以及所有冻结边界值。
