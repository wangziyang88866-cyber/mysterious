# 阶段 16：客户端表现

## 原版粒子、声音与清场

客户端表现只使用 Minecraft 原版粒子、声音和 GeckoLib 动画，不依赖额外屏幕效果库。服务端仍然是权威来源：阶段仪式、传送、时钟消失和目标受击反馈均从 Encounter 或实体状态生成，客户端不能用表现包触发战斗结算。

每场非终态 Encounter 都提供一条服务端原版 Boss 血条：P1 汇总所有存活分身生命并使用十段刻度，P2/P3 显示单体生命；颜色依次为紫、蓝、红。只有本场在线参与者能看到，离场或结算后立即移除。P3 同时启用原版暗屏与雾化标志。

客户端还根据已经收到的权威 Encounter 快照绘制明确可见的阶段边缘色：P1 紫、P2 蓝、P3 脉冲暗红。出现和清场都按帧时间平滑淡入/淡出，阶段颜色、基础透明度和脉冲强度使用指数交叉过渡；新阶段的 1.5 秒强调采用正弦曲线淡入再淡出，左右边缘也由多带阶梯生成柔和透明度渐变。它只使用 `GuiGraphics` 色块与渐变，不携带战斗逻辑，也没有外部视觉依赖。

所有粒子均来自原版 `ParticleTypes`：Amon 使用 Portal/Soul Fire/Witch 螺旋，第二形态叠加 End Rod/Enchant 飞行尾迹，跨维度使用双层 Portal 环和爆发，Clock 使用 Enchant 环、临期 Electric Spark、到期 Sonic Boom，并在实际目标位置额外播放命中爆发，Worm 使用 Squid Ink/Portal 轨迹。P1→P2 与 P2→P3 提交后都会播放持续 3 秒的转阶段仪式；阿蒙传送、三秒预警和第二形态提交同样使用原版声音。

网络清场使用轻量 `EncounterClearPayload`，只通知客户端移除对应 Encounter 快照；登出会清空全部本地缓存。该载荷不携带视觉强度或战斗结果。

## 动画

Amon 第一形态只有 `state.isMoving()` 为真时才循环 walk；完全静止显式停止控制器。第二形态（含悬停）始终使用 fly，绝不会误播 walk；近战、施法和生成/转阶段分别触发资源内真实存在的 attack、cast、summon 动画。

## 定位顺序

粒子缺失先查实体是否绑定 Encounter UUID、Encounter 是否终态及服务端事件是否触发；时钟目标处没有反馈则查 `ClockRuntimeService` 的目标选择；静止仍走路或飞行不播则查 GeckoLib movement predicate，不要在模型层强制循环动画。
