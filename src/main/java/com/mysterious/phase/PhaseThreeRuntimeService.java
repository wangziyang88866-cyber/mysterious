package com.mysterious.phase;

import com.mysterious.config.ReleaseCandidateBalance;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.combat.DamageDeliveryClassifier;
import com.mysterious.combat.DamageDeliveryType;
import com.mysterious.entity.WormOfTimeEntity;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.network.NetworkSyncService;
import com.mysterious.network.WarningCode;
import com.mysterious.network.WarningEventPayload;
import com.mysterious.registry.ModEffects;
import com.mysterious.registry.ModEntities;
import com.mysterious.spell.EncounterEntityBudget;
import com.mysterious.theft.StolenAttributeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stage 11 timeline and Stage 12 worm/parasite runtime. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class PhaseThreeRuntimeService {
    public static final int MAX_WORMS = ReleaseCandidateBalance.MAX_ACTIVE_WORMS;
    public static final int PARASITE_ADD_TICKS = 100;
    public static final int WORM_SPAWN_INTERVAL_TICKS = 20;

    private PhaseThreeRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) return;
        long tick = server.overworld().getGameTime();
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE || snapshot.phase() != EncounterPhase.PHASE_THREE
                    || snapshot.advanced().phaseThree().isEmpty()) continue;
            snapshot = reviveOpeningFormIfNeeded(controller, snapshot, server, tick);
            retargetWorms(snapshot, server);
            PhaseThreeState state = snapshot.advanced().phaseThree().orElseThrow();
            boolean warning = state.warningSent();
            boolean completed = state.timelineCompleted();
            int worms = state.wormsSpawned();
            long nextWorm = Math.min(state.nextWormSpawnTick(), safeAdd(tick, WORM_SPAWN_INTERVAL_TICKS));
            if (!warning && tick >= state.warningAtTick()) {
                sendWarning(snapshot, server, state);
                warning = true;
            }
            if (!completed && tick >= state.transformationAtTick()) completed = true;
            if (worms < MAX_WORMS && tick >= nextWorm) {
                long activeWormCount = activeWorms(snapshot.encounterId(), server);
                long temporaryCount = activeTemporaryEntities(snapshot.encounterId(), server)
                        + snapshot.advanced().spells().reservedTemporaryEntities();
                if (activeWormCount < MAX_WORMS
                        && EncounterEntityBudget.canReserveTemporary(
                        (int) Math.min(Integer.MAX_VALUE, temporaryCount), 1)
                        && spawnWorm(snapshot, server, tick)) {
                    worms++;
                }
                // A refused spawn consumes this opportunity instead of becoming queued backlog.
                nextWorm = safeAdd(tick, WORM_SPAWN_INTERVAL_TICKS);
            }
            Map<UUID, ParasiteState> parasites = tickParasites(snapshot, state.parasites(), server, tick);
            PhaseThreeState updated = new PhaseThreeState(state.startedAtTick(), state.invulnerableUntilTick(),
                    state.warningAtTick(), warning, completed, worms, nextWorm, state.secondFormActive(),
                    state.nextClockSpawnTick(), state.clockHits(), state.lastExecutionTicks(), parasites);
            if (completed && !updated.secondFormActive()) {
                FlightController.activate(snapshot, server);
                updated = new PhaseThreeState(updated.startedAtTick(), updated.invulnerableUntilTick(),
                        updated.warningAtTick(), updated.warningSent(), updated.timelineCompleted(),
                        updated.wormsSpawned(), updated.nextWormSpawnTick(), true, updated.nextClockSpawnTick(),
                        updated.clockHits(), updated.lastExecutionTicks(), updated.parasites());
            }
            if (updated.secondFormActive()) FlightController.tick(snapshot, server);
            updated = ClockRuntimeService.tick(snapshot, updated, server, tick);
            EncounterSnapshot current = controller.requireEncounter(snapshot.encounterId());
            controller.updateAdvancedState(snapshot.encounterId(),
                    new AdvancedEncounterState(current.advanced().spells(), Optional.of(updated),
                            current.advanced().crossDimensionChase()));
        }
    }

    @SubscribeEvent
    public static void onBossHitPlayer(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0 || !(event.getEntity() instanceof ServerPlayer player)) return;
        UUID encounterId = null;
        boolean shouldSeal = false;
        if (event.getSource().getEntity() instanceof WormOfTimeEntity worm) {
            encounterId = worm.mysterious$getEncounterId().orElse(null);
        } else if (event.getSource().getEntity() instanceof com.mysterious.entity.AmonEntity amon) {
            if (DamageDeliveryClassifier.classify(event.getSource()) != DamageDeliveryType.MELEE) return;
            encounterId = amon.mysterious$getEncounterId().orElse(null);
            shouldSeal = amon.mysterious$isSecondForm();
        }
        if (encounterId != null) {
            long tick = player.serverLevel().getGameTime();
            addParasite(player.getServer(), encounterId, player.getUUID(), tick);
            EncounterSnapshot snapshot = EncounterManager.controller(player.getServer()).findEncounter(encounterId)
                    .orElse(null);
            var participant = snapshot == null ? null : snapshot.players().get(player.getUUID());
            if (shouldSeal && snapshot != null && snapshot.lifecycle() == EncounterLifecycle.ACTIVE
                    && snapshot.phase() == EncounterPhase.PHASE_THREE
                    && snapshot.advanced().phaseThree().map(PhaseThreeState::secondFormActive).orElse(false)
                    && participant != null && participant.participation() != PlayerParticipationState.LEFT) {
                SealManager.trySeal(player, encounterId, tick);
            }
        }
    }

    public static void addParasite(MinecraftServer server, UUID encounterId, UUID playerId, long tick) {
        EncounterController controller = EncounterManager.controller(server);
        EncounterSnapshot snapshot = controller.requireEncounter(encounterId);
        if (snapshot.phase() != EncounterPhase.PHASE_THREE || snapshot.advanced().phaseThree().isEmpty()) return;
        var participant = snapshot.players().get(playerId);
        if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE || participant == null
                || participant.participation() == PlayerParticipationState.LEFT) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) return;
        PhaseThreeState phase = snapshot.advanced().phaseThree().orElseThrow();
        Map<UUID, ParasiteState> parasites = new LinkedHashMap<>(phase.parasites());
        ParasiteState current = parasites.getOrDefault(playerId, new ParasiteState(0, 0, tick));
        ParasiteState next = current.addDuration(PARASITE_ADD_TICKS, tick);
        parasites.put(playerId, next);
        player.removeEffect(ModEffects.PARASITE);
        player.addEffect(new MobEffectInstance(ModEffects.PARASITE, next.remainingTicks(), 0, false, true));
        updatePhase(controller, snapshot, phase, parasites);
    }

    private static Map<UUID, ParasiteState> tickParasites(EncounterSnapshot snapshot,
                                                          Map<UUID, ParasiteState> source,
                                                          MinecraftServer server, long tick) {
        Map<UUID, ParasiteState> result = new LinkedHashMap<>();
        source.forEach((playerId, state) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            long elapsedLong = Math.max(0L, tick - state.lastProcessedTick());
            int elapsed = (int) Math.min(Integer.MAX_VALUE, elapsedLong);
            if (player == null) {
                result.put(playerId, new ParasiteState(state.remainingTicks(), state.infectionTicks(), tick));
            } else if (!player.hasEffect(ModEffects.PARASITE)) {
                // A full legal cleanse is authoritative and resets the continuous infection clock.
            } else {
                int remaining = Math.max(0, state.remainingTicks() - elapsed);
                if (remaining > 0) {
                    int infection = state.infectionTicks() > Integer.MAX_VALUE - elapsed
                            ? Integer.MAX_VALUE : state.infectionTicks() + elapsed;
                    result.put(playerId, new ParasiteState(remaining, infection, tick));
                    if (tick % 20L == 0L) player.hurt(player.damageSources().cramming(), 1.0F);
                }
            }
        });
        return result;
    }

    private static void updatePhase(EncounterController controller, EncounterSnapshot snapshot,
                                    PhaseThreeState phase, Map<UUID, ParasiteState> parasites) {
        PhaseThreeState updated = new PhaseThreeState(phase.startedAtTick(), phase.invulnerableUntilTick(),
                phase.warningAtTick(), phase.warningSent(), phase.timelineCompleted(), phase.wormsSpawned(),
                phase.nextWormSpawnTick(), phase.secondFormActive(), phase.nextClockSpawnTick(),
                phase.clockHits(), phase.lastExecutionTicks(), parasites);
        controller.updateAdvancedState(snapshot.encounterId(),
                new AdvancedEncounterState(snapshot.advanced().spells(), Optional.of(updated),
                        snapshot.advanced().crossDimensionChase()));
    }

    /** The timed opening cannot be skipped, but it is enforced by a real death and a new incarnation. */
    public static boolean shouldReviveOpeningBoss(EncounterSnapshot snapshot) {
        return snapshot.lifecycle() == EncounterLifecycle.ACTIVE
                && snapshot.phase() == EncounterPhase.PHASE_THREE
                && snapshot.advanced().phaseThree().isPresent()
                && !snapshot.advanced().phaseThree().orElseThrow().secondFormActive()
                && !snapshot.bosses().isEmpty()
                && snapshot.livingBossCount() == 0;
    }

    private static EncounterSnapshot reviveOpeningFormIfNeeded(EncounterController controller,
                                                                EncounterSnapshot snapshot,
                                                                MinecraftServer server, long tick) {
        if (!shouldReviveOpeningBoss(snapshot)) return snapshot;
        BossRecord origin = snapshot.bosses().values().stream()
                .max(Comparator.comparingLong(BossRecord::lastConfirmedTick)
                        .thenComparing(record -> record.entityId().toString()))
                .orElseThrow();
        ServerLevel level = server.getLevel(origin.lastKnownDimension());
        if (level == null) return snapshot;
        AmonEntity revived = ModEntities.AMON.get().create(level);
        if (revived == null) return snapshot;
        revived.mysterious$bindEncounter(snapshot.encounterId());
        revived.mysterious$setSecondForm(false);
        revived.moveTo(origin.lastKnownPosition(), 0.0F, 0.0F);
        revived.setHealth(revived.getMaxHealth());
        if (!level.addFreshEntity(revived)) return snapshot;
        try {
            controller.registerBoss(snapshot.encounterId(), BossRecord.loaded(revived.getUUID(),
                    EncounterPhase.PHASE_THREE, level.dimension(), revived.blockPosition(), tick));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            revived.discard();
            return controller.findEncounter(snapshot.encounterId()).orElse(snapshot);
        }
        revived.mysterious$playSummonAnimation();
        StolenAttributeManager.rebuild(snapshot.encounterId(), server);
        EncounterParticleRuntimeService.phaseThreeEntryBurst(level, revived.position());
        return controller.requireEncounter(snapshot.encounterId());
    }

    private static boolean spawnWorm(EncounterSnapshot snapshot, MinecraftServer server, long tick) {
        AmonEntity boss = snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                .findFirst().orElse(null);
        ServerLevel level = boss != null && boss.level() instanceof ServerLevel current ? current : null;
        if (level == null) return false;
        WormOfTimeEntity worm = ModEntities.WORM_OF_TIME.get().create(level);
        if (worm == null) return false;
        worm.mysterious$bindEncounter(snapshot.encounterId());
        worm.moveTo(boss.position().add(0.0D, 1.0D, 0.0D));
        boolean added = level.addFreshEntity(worm);
        if (added) worm.mysterious$assignTarget(
                CombatRuntimeService.resolveCombatTarget(snapshot, boss, server).orElse(null));
        return added;
    }

    private static void retargetWorms(EncounterSnapshot snapshot, MinecraftServer server) {
        AmonEntity boss = snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                .findFirst().orElse(null);
        LivingEntity target = boss == null ? null
                : CombatRuntimeService.resolveCombatTarget(snapshot, boss, server).orElse(null);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof WormOfTimeEntity worm
                        && worm.mysterious$getEncounterId().filter(snapshot.encounterId()::equals).isPresent()
                        && !worm.isRemoved()) {
                    worm.mysterious$assignTarget(target != null && target.level() == worm.level() ? target : null);
                }
            }
        }
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static long activeWorms(UUID encounterId, MinecraftServer server) {
        long count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof WormOfTimeEntity worm
                        && worm.mysterious$getEncounterId().filter(encounterId::equals).isPresent()
                        && !worm.isRemoved()) count++;
            }
        }
        return count;
    }

    private static long activeTemporaryEntities(UUID encounterId, MinecraftServer server) {
        long count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof com.mysterious.encounter.EncounterBoundEntity bound
                        && !(entity instanceof com.mysterious.entity.AmonEntity)
                        && bound.mysterious$getEncounterId().filter(encounterId::equals).isPresent()
                        && !entity.isRemoved()) count++;
            }
        }
        return count;
    }

    private static void sendWarning(EncounterSnapshot snapshot, MinecraftServer server, PhaseThreeState state) {
        UUID eventId = UUID.nameUUIDFromBytes((snapshot.encounterId() + ":p3-warning")
                .getBytes(StandardCharsets.UTF_8));
        for (var participant : snapshot.players().values()) {
            if (participant.participation() == PlayerParticipationState.LEFT) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null) {
                NetworkSyncService.send(player, new WarningEventPayload(snapshot.encounterId(),
                        eventId, snapshot.revision(), WarningCode.PHASE_THREE_TRANSFORM,
                        (int) Math.max(0L, state.transformationAtTick() - state.warningAtTick())));
                player.playNotifySound(net.minecraft.sounds.SoundEvents.WARDEN_HEARTBEAT,
                        net.minecraft.sounds.SoundSource.HOSTILE, 1.4F, 0.72F);
            }
        }
    }

    private static long safeAdd(long tick, int amount) {
        return tick > Long.MAX_VALUE - amount ? Long.MAX_VALUE : tick + amount;
    }
}
