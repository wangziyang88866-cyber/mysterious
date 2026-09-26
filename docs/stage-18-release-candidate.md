# 阶段 18：数值冻结与 Release Candidate

## RC 基线

当前冻结标识为 `RC1-2026-09-24`，存档 schema/data 为 `7 / 7`，网络协议仍为 `1`。冻结默认值集中在 `ReleaseCandidateBalance`，其修改必须同步本文件、阶段测试和实战记录。

| 参数 | RC1 默认值 |
| --- | --- |
| 跨维度等待 / 冷却 | 40 / 200 tick |
| 跨维度出现半径 / 搜索 | 6～10 格 / 16 次 |
| 第二形态飞行 | 0.55 格/tick，无加速度、不可穿墙 |
| 时钟到期命中 | 每座独立命中一个战斗目标，不受距离限制 |
| 临时实体 / 法术实体 | 128 / 64 |
| Clock / Worm | 16 / 8 |

运行时场地边界、玩家回传和 Boss leash 已移除；旧的 Arena 配置仅为已有存档结构兼容而保留。Grace、弃战、受击传送、偷窃、Worm 攻击等运维参数继续由 SERVER config 管理。

## 发布检查

- `verifyStageZero`：Java 21、精确依赖版本和禁止动态依赖。
- `runGameTestServer`：55 个阶段 0～18 GameTest 全通过。
- `build`：编译、资源处理、可复现 jar、静态 Gate 全通过。
- 管理员定位入口：`/mysterious debug encounters|phase|bosses|players|transactions|escrow|pendingreturns|spells|entities|network|recovery`。
- Dedicated Server 不直接链接 Minecraft client 类；全部战斗表现只使用原版粒子、声音与 GeckoLib 动画。

## 已知边界

当前 RC 完成实施规划阶段 0～18 的 Encounter/Boss 战斗主链。源堡维度的世界生成与区域分配、进入法术、FTB Teams 实际所有权解析、奖励/战利品属于独立产品扩展，不在本轮 Boss 阶段 15～18 的交付范围；Curios 仍是只读兼容边界。它们接入时不得绕过现有 Encounter 所有权、事务、预算与 Cleanup。
