package com.mysterious.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** Pure state transitions for prepare/cast/sustain/interrupt/recover/cleanup. */
public final class SpellCastManager {
    private SpellCastManager() {
    }

    public static Optional<BossSpellDefinition> select(SpellRuntimeState state, long tick, Random random,
                                                        List<BossSpellDefinition> playerCopies) {
        Map<SpellCategory, List<BossSpellDefinition>> pools = new LinkedHashMap<>();
        pools.put(SpellCategory.INTRINSIC, available(BossSpellRegistry.byCategory(SpellCategory.INTRINSIC), state, tick));
        pools.put(SpellCategory.PLAYER_COPY, available(playerCopies, state, tick));
        pools.put(SpellCategory.LEGENDARY, available(BossSpellRegistry.byCategory(SpellCategory.LEGENDARY), state, tick));
        int intrinsic = pools.get(SpellCategory.INTRINSIC).isEmpty() ? 0 : 50;
        int copied = pools.get(SpellCategory.PLAYER_COPY).isEmpty() ? 0 : 30;
        int legendary = pools.get(SpellCategory.LEGENDARY).isEmpty() ? 0 : 20;
        int total = intrinsic + copied + legendary;
        if (total == 0) return Optional.empty();
        int roll = random.nextInt(total);
        SpellCategory category = roll < intrinsic ? SpellCategory.INTRINSIC
                : roll < intrinsic + copied ? SpellCategory.PLAYER_COPY : SpellCategory.LEGENDARY;
        List<BossSpellDefinition> pool = pools.get(category);
        return Optional.of(pool.get(random.nextInt(pool.size())));
    }

    public static Optional<BossSpellDefinition> selectLegendary(SpellRuntimeState state, long tick, Random random,
                                                                 List<BossSpellDefinition> legendaryPool) {
        List<BossSpellDefinition> available = available(legendaryPool, state, tick);
        return available.isEmpty() ? Optional.empty()
                : Optional.of(available.get(random.nextInt(available.size())));
    }

    public static SpellRuntimeState prepare(SpellRuntimeState state, BossSpellDefinition definition,
                                             UUID ownerBossId, long tick) {
        return prepare(state, definition, ownerBossId, tick, 1.0D);
    }

    public static SpellRuntimeState prepare(SpellRuntimeState state, BossSpellDefinition definition,
                                             UUID ownerBossId, long tick, double castTimeScale) {
        if (!Double.isFinite(castTimeScale) || castTimeScale <= 0.0D) {
            throw new IllegalArgumentException("castTimeScale must be finite and positive");
        }
        long activeSameSpell = state.instances().values().stream()
                .filter(instance -> instance.spellId().equals(definition.spellId())).count();
        if (tick < state.cooldownUntil().getOrDefault(definition.spellId(), 0L)
                || activeSameSpell >= definition.maxConcurrentInstances()
                || definition.globalCastLock() && state.activeCastInstanceId().isPresent()
                || !EncounterEntityBudget.canReserve(state.reservedTemporaryEntities(), definition.entityBudgetCost())) {
            return state;
        }
        UUID instanceId = UUID.randomUUID();
        int scaledCastTicks = Math.max(1, (int) Math.round(definition.castDurationTicks() * castTimeScale));
        SpellInstanceState instance = new SpellInstanceState(instanceId, definition.spellId(), ownerBossId,
                tick, saturatedAdd(tick, definition.prepareTicks() + scaledCastTicks),
                definition.entityBudgetCost());
        Map<UUID, SpellInstanceState> instances = new LinkedHashMap<>(state.instances());
        instances.put(instanceId, instance);
        Map<ResourceLocation, Long> cooldowns = new LinkedHashMap<>(state.cooldownUntil());
        cooldowns.put(definition.spellId(), saturatedAdd(tick, definition.cooldownTicks()));
        return new SpellRuntimeState(cooldowns, instances,
                definition.globalCastLock() ? Optional.of(instanceId) : state.activeCastInstanceId(),
                state.nextCastTick(), state.nextEnvironmentTick(),
                state.reservedTemporaryEntities() + definition.entityBudgetCost());
    }

    public static SpellRuntimeState tick(SpellRuntimeState state, long tick) {
        Map<UUID, SpellInstanceState> active = new LinkedHashMap<>();
        state.instances().values().stream().sorted(Comparator.comparing(value -> value.instanceId().toString()))
                .filter(value -> value.endsAtTick() > tick).forEach(value -> active.put(value.instanceId(), value));
        int reserved = active.values().stream().mapToInt(SpellInstanceState::reservedEntities).sum();
        Optional<UUID> lock = state.activeCastInstanceId().filter(active::containsKey);
        return new SpellRuntimeState(state.cooldownUntil(), active, lock, state.nextCastTick(),
                state.nextEnvironmentTick(), reserved);
    }

    public static SpellRuntimeState scheduleNext(SpellRuntimeState state, long nextTick) {
        return new SpellRuntimeState(state.cooldownUntil(), state.instances(), state.activeCastInstanceId(),
                nextTick, state.nextEnvironmentTick(), state.reservedTemporaryEntities());
    }

    public static SpellRuntimeState scheduleEnvironment(SpellRuntimeState state, long nextTick) {
        return new SpellRuntimeState(state.cooldownUntil(), state.instances(), state.activeCastInstanceId(),
                state.nextCastTick(), nextTick, state.reservedTemporaryEntities());
    }

    public static SpellRuntimeState interruptAll(SpellRuntimeState state, long nextTick) {
        return new SpellRuntimeState(state.cooldownUntil(), Map.of(), Optional.empty(), nextTick,
                state.nextEnvironmentTick(), 0);
    }

    private static List<BossSpellDefinition> available(List<BossSpellDefinition> input,
                                                        SpellRuntimeState state, long tick) {
        List<BossSpellDefinition> result = new ArrayList<>();
        for (BossSpellDefinition definition : input) {
            if (tick >= state.cooldownUntil().getOrDefault(definition.spellId(), 0L)) result.add(definition);
        }
        return result;
    }

    private static long saturatedAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
