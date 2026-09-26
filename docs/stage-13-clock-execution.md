# 阶段 13：Clock 与 Boss Execution

## 已实现

- `ClockRuntimeService` 在 P3 开始 20 tick 后尝试生成第一座时钟，之后每 40 tick（2 秒）尝试生成；每次最多搜索 12 个候选点，失败或预算拒绝会消费本周期，不积压补发。旧存档中过远的下一次调度会自动收紧到新周期。
- 时钟绑定 Encounter UUID，保存绝对到期 tick，默认存活 600 tick。候选点以当前 Amon 为原点，在水平 24、垂直 ±8 范围内搜索，并验证世界边界与实体碰撞。
- 同 tick 到期的时钟先按 UUID 排序统一收集并播放 Sonic Boom/Electric Spark 消失反馈。每座时钟都独立伤害阿蒙当前可攻击的 `LivingEntity` 目标；没有实体目标时才退回 Encounter 玩家目标/最近合法参与者。每座时钟独立清除受击无敌帧、造成 1 点挤压伤害并在目标位置播放原版粒子/声音，因此同 tick 多座时钟也不会被无敌帧吞伤害。目标为玩家时才增加个人必死计数；普通生物只承受伤害。
- 时钟和时之虫共同进入 128 个临时实体预算；时钟实体实现 `EncounterBoundEntity`，因此统一 Cleanup 会删除。
- `BossExecutionManager` 是寄生 600 tick、时钟 20 次的唯一结算入口。同玩家同 tick 的提交 tick 持久化去重；Creative、Spectator、已死亡玩家不执行。
- Execution 使用服务端魔法伤害进入原版/NeoForge 正常死亡链，不屏蔽图腾和可取消死亡事件。成功提交处决后按 reason 消费寄生 infectionTime 或时钟计数，无论玩家死亡还是被复活能力救下都不会在下一 tick 用同一阈值重复处决。
- Encounter 存档升级为 schema/data V6；V5→V6 显式补入时钟调度、个人计数、Execution 去重与第二形态位。

## 定位顺序

时钟不生成先查 `nextClockSpawnTick`、临时实体预算和同维度参与者，再查 12 次位置搜索；到期不结算查实体的 `MysteriousExpiresAtTick` 与 Encounter UUID；重复处决查 `lastExecutionTicks`，复活后立刻再次触发则查对应 reason 的计数是否归零。

## 验证

`StageThirteenGameTests` 覆盖时钟 NBT、非玩家实际目标伤害、计数/去重/寄生状态完整往返、关键阈值，以及原版不死图腾实际救援、对应累计重置和同 tick 二次提交拒绝。阶段 0～12 的回归套件必须继续全部通过。
