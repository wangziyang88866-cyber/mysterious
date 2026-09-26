# 阶段 10：P2/P3 法术池与 P2→P3

## 已实现

- P2 以 45～75 tick 间隔施法；P3 加速为 20～40 tick（1～2 秒）并把 ISS 计算后的有效吟唱时间缩短至 55%。缩放发生在 `getEffectiveCastTime` 之后，因此扩展法术的原始施法时间和 ISS `cast_time_reduction` 属性仍然生效。
- P2 和 P3 从当前 ISS 注册表（包括其他扩展模组注册的法术）的全部启用法术中等概率选取，不再受命名空间、固有池、玩家复制池或旧安全池限制。
- 全部法术以 `LEGENDARY` 类别和各法术当前配置的最大等级执行。阿蒙实现 ISS `IMagicEntity`，统一通过 `CastSource.MOB` 的准备、逐 tick、提交与取消生命周期释放真实法术；玩家专属法术若拒绝 MOB 施法，只跳过本次并解除施法状态，不会拖垮 Encounter。
- P2/P3 每 25～50 tick 在阿蒙当前可攻击的 `LivingEntity` 目标附近生成一次服务端真实闪电，法术也读取同一个实体目标；每 3 tick 在参与玩家周围 8～28 格的环境空间随机生成 Electric Spark、Reverse Portal 与 Witch 粒子，这些粒子不绑定在阿蒙身上，P3 开始后不会中断。
- P2 可以正常死亡；死亡记录提交后，阶段 8 的通用迁移框架使用固定 recovery carrier 生成全新、满血的 P3 Amon，不取消死亡也不保留 1 HP。
- P3 从满血开始，普通伤害立即有效。第二形态前如果死亡，会按最后记录位置生成全新实体继续时间线；第二形态死亡才结算胜利。
- P2/P3 脱战 8 秒后每秒恢复 5 HP 的阶段 5 行为保持不变；玩家死亡不提供额外回血。

## 维护边界

ISS 或扩展模组新增并启用的法术会自动进入 P2/P3 全量池。单个扩展法术若注册异常或拒绝 MOB 施法，只安全跳过本次并释放施法状态，不能破坏其他法术或 Encounter。

P2→P3 问题依次检查 `EncounterRuntimeService.onBossDeath`、active transition、`PhaseTransitionRuntimeService.reconcileCarrier` 和 Boss Registry。
