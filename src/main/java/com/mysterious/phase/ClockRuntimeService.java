package com.mysterious.phase;

import com.mysterious.config.ReleaseCandidateBalance;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.encounter.EncounterBoundEntity;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.entity.AmonEntity;
import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.registry.ModEntities;
import com.mysterious.spell.EncounterEntityBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stage 13 clock scheduling, bounded placement, batch expiry and per-player counters. */
public final class ClockRuntimeService {
    public static final int MAX_ACTIVE_CLOCKS = ReleaseCandidateBalance.MAX_ACTIVE_CLOCKS;
    public static final int CLOCK_LIFETIME_TICKS = 30 * 20;
    public static final int CLOCK_SEARCH_ATTEMPTS = 12;
    public static final int CLOCK_INITIAL_DELAY_TICKS = 20;
    public static final int CLOCK_SPAWN_INTERVAL_TICKS = 40;

    private ClockRuntimeService() {
    }

    public static PhaseThreeState tick(EncounterSnapshot snapshot, PhaseThreeState state,
                                       MinecraftServer server, long tick) {
        long nextClock = Math.min(state.nextClockSpawnTick(), safeAdd(tick, CLOCK_SPAWN_INTERVAL_TICKS));
        if (tick >= nextClock) {
            int temporary = countTemporary(snapshot.encounterId(), server)
                    + snapshot.advanced().spells().reservedTemporaryEntities();
            if (countClocks(snapshot.encounterId(), server) < MAX_ACTIVE_CLOCKS
                    && EncounterEntityBudget.canReserveTemporary(temporary, 1)) spawnClock(snapshot, server, tick);
            // Failed placement or budget rejection consumes this period and never queues a catch-up spawn.
            nextClock = safeAdd(tick, CLOCK_SPAWN_INTERVAL_TICKS);
        }

        Map<UUID, Integer> hits = new LinkedHashMap<>(state.clockHits());
        List<PhantomClockEntity> expired = expiredClocks(snapshot.encounterId(), server, tick);
        for (PhantomClockEntity clock : expired) {
            if (clock.level() instanceof ServerLevel level) {
                EncounterParticleRuntimeService.clockExpiryBurst(level, clock.position());
            }
            for (LivingEntity target : clockTargets(snapshot, clock, server)) {
                // Every clock in the expiry batch is an independent hit. Vanilla hurt cooldowns
                // must not silently consume its damage or lethal accumulator.
                target.invulnerableTime = 0;
                target.hurt(target.damageSources().cramming(), 1.0F);
                if (target.level() instanceof ServerLevel targetLevel) {
                    EncounterParticleRuntimeService.clockTargetBurst(targetLevel, target.position());
                }
                if (target instanceof ServerPlayer player) {
                    hits.compute(player.getUUID(),
                            (ignored, current) -> incrementClockHit(current == null ? 0 : current));
                }
            }
            clock.discard();
        }

        PhaseThreeState updated = copy(state, nextClock, hits, state.lastExecutionTicks(), state.parasites());
        for (var participant : snapshot.players().values()) {
            if (participant.participation() == PlayerParticipationState.LEFT) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) continue;
            EnumSet<BossExecutionReason> reasons = EnumSet.noneOf(BossExecutionReason.class);
            ParasiteState parasite = updated.parasites().get(player.getUUID());
            if (parasite != null && parasite.infectionTicks() >= BossExecutionManager.PARASITE_THRESHOLD) {
                reasons.add(BossExecutionReason.PARASITE);
            }
            if (updated.clockHits().getOrDefault(player.getUUID(), 0) >= BossExecutionManager.CLOCK_THRESHOLD) {
                reasons.add(BossExecutionReason.CLOCK);
            }
            updated = BossExecutionManager.trigger(updated, player, reasons, tick);
        }
        return updated;
    }

    private static boolean spawnClock(EncounterSnapshot snapshot, MinecraftServer server, long tick) {
        AmonEntity boss = snapshot.bosses().values().stream().filter(record -> record.isAlive())
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                .findFirst().orElse(null);
        ServerLevel level = boss != null && boss.level() instanceof ServerLevel current ? current : null;
        LivingEntity combatTarget = boss == null ? null
                : CombatRuntimeService.resolveCombatTarget(snapshot, boss, server).orElse(null);
        boolean hasParticipantInLevel = level != null && snapshot.players().values().stream().anyMatch(participant -> {
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            return participant.participation() != PlayerParticipationState.LEFT && player != null
                    && player.serverLevel() == level;
        });
        if (level == null || !hasParticipantInLevel
                && (combatTarget == null || combatTarget.level() != level)) return false;
        PhantomClockEntity clock = ModEntities.PHANTOM_CLOCK.get().create(level);
        if (clock == null) return false;
        int horizontal = 24;
        int vertical = 8;
        Vec3 origin = boss.position();
        for (int attempt = 0; attempt < CLOCK_SEARCH_ATTEMPTS; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0D;
            double radius = Math.sqrt(level.random.nextDouble()) * horizontal;
            double y = origin.y + level.random.nextInt(vertical * 2 + 1) - vertical;
            Vec3 candidate = new Vec3(origin.x + Math.cos(angle) * radius,
                    y, origin.z + Math.sin(angle) * radius);
            AABB moved = clock.getBoundingBox().move(candidate.subtract(clock.position()));
            if (level.getWorldBorder().isWithinBounds(BlockPos.containing(candidate))
                    && level.noCollision(clock, moved)) {
                clock.mysterious$initialize(snapshot.encounterId(), safeAdd(tick, CLOCK_LIFETIME_TICKS));
                clock.moveTo(candidate.x, candidate.y, candidate.z, level.random.nextFloat() * 360.0F, 0.0F);
                return level.addFreshEntity(clock);
            }
        }
        return false;
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static List<PhantomClockEntity> expiredClocks(UUID encounterId, MinecraftServer server, long tick) {
        List<PhantomClockEntity> result = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PhantomClockEntity clock
                        && clock.mysterious$getEncounterId().filter(encounterId::equals).isPresent()
                        && clock.mysterious$expiresAtTick() <= tick && !clock.isRemoved()) result.add(clock);
            }
        }
        result.sort(Comparator.comparing(Entity::getUUID));
        return result;
    }

    private static int countTemporary(UUID encounterId, MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EncounterBoundEntity bound && !(entity instanceof AmonEntity)
                        && bound.mysterious$getEncounterId().filter(encounterId::equals).isPresent()
                        && !entity.isRemoved() && count < Integer.MAX_VALUE) count++;
            }
        }
        return count;
    }

    private static int countClocks(UUID encounterId, MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PhantomClockEntity clock
                        && clock.mysterious$getEncounterId().filter(encounterId::equals).isPresent()
                        && !clock.isRemoved() && count < Integer.MAX_VALUE) count++;
            }
        }
        return count;
    }

    /** Every expired clock resolves exactly one live combat target, independent of spawn distance. */
    static List<LivingEntity> clockTargets(EncounterSnapshot snapshot, PhantomClockEntity clock,
                                           MinecraftServer server) {
        AmonEntity boss = snapshot.bosses().values().stream().filter(record -> record.isAlive())
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                .findFirst().orElse(null);
        if (boss != null) {
            LivingEntity direct = CombatRuntimeService.resolveCombatTarget(snapshot, boss, server).orElse(null);
            if (direct != null) return List.of(direct);
        }
        Map<UUID, ServerPlayer> eligible = new LinkedHashMap<>();
        snapshot.players().values().stream()
                .filter(participant -> participant.participation() != PlayerParticipationState.LEFT)
                .map(participant -> server.getPlayerList().getPlayer(participant.playerId()))
                .filter(player -> isEligibleTarget(snapshot, player))
                .forEach(player -> eligible.put(player.getUUID(), player));
        Map<UUID, Double> distances = new LinkedHashMap<>();
        eligible.forEach((id, player) -> distances.put(id, player.serverLevel() == clock.level()
                ? player.distanceToSqr(clock) : Double.POSITIVE_INFINITY));
        return selectClockTargetId(snapshot.combat().currentTargetId(), distances)
                .map(eligible::get).<List<LivingEntity>>map(List::of).orElseGet(List::of);
    }

    public static java.util.Optional<UUID> selectClockTargetId(java.util.Optional<UUID> currentTarget,
                                                                Map<UUID, Double> eligibleDistances) {
        if (currentTarget.isPresent() && eligibleDistances.containsKey(currentTarget.orElseThrow())) {
            return currentTarget;
        }
        return eligibleDistances.entrySet().stream()
                .min(Map.Entry.<UUID, Double>comparingByValue()
                        .thenComparing(entry -> entry.getKey().toString()))
                .map(Map.Entry::getKey);
    }

    private static boolean isEligibleTarget(EncounterSnapshot snapshot, ServerPlayer player) {
        var participant = snapshot.players().get(player.getUUID());
        return participant != null && participant.participation() != PlayerParticipationState.LEFT
                && player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    private static PhaseThreeState copy(PhaseThreeState state, long nextClock,
                                        Map<UUID, Integer> hits, Map<UUID, Long> executions,
                                        Map<UUID, ParasiteState> parasites) {
        return new PhaseThreeState(state.startedAtTick(), state.invulnerableUntilTick(), state.warningAtTick(),
                state.warningSent(), state.timelineCompleted(), state.wormsSpawned(), state.nextWormSpawnTick(),
                state.secondFormActive(), nextClock, hits, executions, parasites);
    }

    public static int incrementClockHit(int current) {
        if (current < 0) throw new IllegalArgumentException("Clock hit count cannot be negative");
        return current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;
    }

    private static long safeAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
