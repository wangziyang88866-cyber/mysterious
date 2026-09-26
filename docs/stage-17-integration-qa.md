# 阶段 17：集成 QA、兼容与压力基线

## 组合矩阵

本阶段把既有逐阶段测试收束为统一 Gate。自动化覆盖单人/四人状态、频繁伤害/仇恨写入、阶段迁移、实体 NBT、事务崩溃恢复、维度 chase 重启、客户端清场和预算满载重复拒绝。需要人工客户端才能观察的项目保留为发布检查项：2～4 人实际网络延迟、ISS 与扩展模组法术实战、区块卸载/上线循环和原版粒子/动画表现。

| 场景 | 自动 Gate | 人工抽查重点 |
| --- | --- | --- |
| 单人 P1→P2→P3→结束 | 各阶段状态机/GameTest | 战斗节奏、提示与清理 |
| 2～4 人 | 四玩家 1000 次高频仇恨写入 | 目标切换、个人 Clock/Execution |
| 高频攻击/模组法术 | 单次 10 点有效伤害上限、死亡复活、事件去重、Spell budget | ISS 投射物/Beam/AOE 分类 |
| unload/missing/restart | Registry、V1→V7、chase round-trip | 真实区块卸载后 UUID 重绑 |
| 维度切换 | chase/cooldown/返回状态测试 | 2 秒等待、原场优先、视觉重同步 |
| 长时间资源压力 | 1000 次满预算拒绝 | 128 临时实体、64 法术、16 Clock、8 Worm |

## 资源与泄漏判定

- 达到预算后每次请求立即失败，不进入下 tick backlog。
- Threat Map 在四名玩家高频更新后仍只有四项；序列化再读取必须完全相等。
- Client visual clear 后缓存为空；Finalize 仍由统一 Cleanup 删除 Encounter 绑定实体、效果、Seal 和属性。
- `MISSING_PENDING` 只允许重绑相同 UUID，不允许用生成实体“修复”。

## 执行

完整自动回归：`./gradlew runGameTestServer`。构建、依赖锁与兼容 Gate：`./gradlew build`。GameTestServer 本身也验证专服类加载边界；粒子、声音和动画仍需 `runClient` 人工观察。
