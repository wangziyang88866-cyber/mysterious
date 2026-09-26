# 阶段 14：第二形态、Flight、弹射物免疫与 Seal

## 第二形态提交

- `PhaseThreeState.secondFormActive` 是持久化单次提交位。P3 绝对时间线到 800 tick 后，只执行一次正式转换。
- 转换顺序为：合法参与玩家执行 `max(1, currentHealth × 0.5)` 的直接生命设置；存活 Amon 恢复满血并写入实体第二形态 NBT；随后进入飞行。该生命调整不创建 DamageSource，不经过护甲、抗性或受击无敌帧，也不会致死。
- 740 tick 的功能性预警沿用阶段 11 的一次性 WarningEvent；第二形态提交位与预警/完成位一起重启恢复。
- 第二形态最后一个 Registry 存活 Amon 正常死亡后统一 `Finalize(VICTORY)`；Cleanup 同时删除时钟/虫、移除寄生效果并解除本场封印。

## Flight

- `FlightController` 每 tick 使用固定 0.55 格/tick 位移，不累计加速度；无目标或距离足够近时悬停。
- 每次移动前验证世界边界和完整碰撞箱，使用正常 `MoverType.SELF`，不会关闭碰撞穿墙；不再存在场地 leash。
- 受阻时本 tick 不移动；飞行追踪使用阿蒙当前可攻击的任意生物目标。普通贴近传送仍只用于合法 Encounter 玩家，并传送到该玩家精确坐标。
- Amon 的 `secondForm` 使用同步实体数据同步到客户端，并与无重力状态一同写入实体 NBT；GeckoLib 使用现有 fly 循环动画覆盖移动与悬停。服务端每 tick 根据飞行向量更新 yaw/pitch，并在原版 AI 后再次提交朝向，防止身体方向被旧的 LookControl 覆盖。

## 弹射物免疫

- `CombatRuntimeService` 在 `LivingIncomingDamageEvent` 最高优先级通过共享 `DamageDeliveryClassifier` 判断。
- 仅第二形态默认取消 `PROJECTILE`，并直接取消 incoming event，因此伤害以及依赖该命中的击退、状态和 on-hit 链不会继续。BEAM/AOE/DOT/DIRECT_MAGIC 不因远程来源被误判。

## Seal

- 每次第二形态 Amon 的有效命中只有在共享伤害分类明确为 `MELEE`，并重新验证 Encounter、阶段与参与资格后，才尝试封印一个合法快捷栏、主背包、offhand 或 Curios 物品；法术/AOE 不会误触发。
- 每名玩家最多 3 个活跃封印，默认 160 tick；达到上限时本次失败且不刷新旧封印。
- 封印保存 `item_instance_id`、`seal_id`、`seal_encounter_id` 和绝对到期 tick，移动槽位后仍跟随物品。封印时同步写入相同剩余时长的原版物品冷却，登录或物品重建后会自动恢复；服务端右键、攻击、挖掘和持续使用拦截仍作为不可绕过的最终约束。原版冷却按物品类型显示，因此同种物品会共用冷却转圈，但封印归属仍精确到单个 ItemStack。
- 快捷栏、主背包、offhand 与 Curios 中的单件或堆叠物品都可被封印；封印数据组件跟随 ItemStack，拆分出的子栈继承同一封印并在同一绝对 tick 到期，带封印组件的栈不能与未封印栈合并。服务端会拦截右键物品/方块/实体、持续使用、持封印主手物品攻击实体与挖掘方块，并向玩家显示封印提示。
- `mysterious:seal_blacklist` 是任务/系统物品的 datapack 扩展点。到期扫描自动清除全部封印组件，Encounter Cleanup 按 `seal_encounter_id` 提前解除。

## 验证与定位

`StageFourteenGameTests` 覆盖特殊生命下限、固定飞行速度、Amon 第二形态 NBT、物品身份/封印序列化和绝对到期语义。飞行卡住先查世界边界与碰撞；投射物仍触发效果查分类结果；移动物品后可使用则查四个数据组件是否被外部模组重建时丢弃。
