# 阶段 11：P3 Transformation Timeline

## 已实现

- P2→P3 commit 时原子创建 `PhaseThreeState`，以 commit tick 作为唯一时间原点。
- 800 tick 仅用于安排第二形态转换；P3 从开始时即可正常受到玩家、生物和环境伤害，没有开场无敌窗口。
- P2→P3 提交时在承接 Amon 位置广播一次 Dragon Breath、Reverse Portal、Soul Fire、End Rod 与声音组合提示。P3 不创建玩家结界，不会强制传送或移动玩家；场地边界与硬回场逻辑已删除。
- 第 740 tick 发送一次 `PHASE_THREE_TRANSFORM` WarningEvent，提供默认 60 tick 预警。
- `warningSent` 与 `timelineCompleted` 持久化，重启不会重复预警，也不会跳过未执行事件。
- 时间线、预警 tick、完成位与后续阶段 12 状态统一保存于 `advanced.phaseThree`。

## 边界

阶段 11 只负责转换时间线与功能性预警。40 秒后的玩家生命调整、飞行、弹射物免疫和第二形态切换属于阶段 14，不在本阶段提前执行。转换前阿蒙可以正常受伤和死亡；死亡后以全新实体复活并继续原时间线，必须完成第二形态后的死亡才结算胜利。

若重启后时间线异常，先用 `/mysterious debug phase <id>` 核对 P3 commit tick，再检查存档 `advanced.phaseThree` 的三个绝对 tick 与完成位。存档键 `invulnerableUntilTick` 因兼容旧存档而保留，但运行时只把它解释为 `transformAt`，不再提供无敌。
