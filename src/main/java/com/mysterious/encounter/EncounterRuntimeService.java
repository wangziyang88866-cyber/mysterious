package com.mysterious.encounter;

import com.mojang.logging.LogUtils;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.network.NetworkSyncService;
import com.mysterious.phase.SealManager;
import com.mysterious.registry.ModEffects;
import com.mysterious.theft.StolenAttributeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Server-authoritative participation and boss lifecycle runtime. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class EncounterRuntimeService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private EncounterRuntimeService() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) {
            return;
        }
        tick(server, server.overworld().getGameTime());
    }

    static void tick(MinecraftServer server, long tick) {
        EncounterController controller = EncounterManager.controller(server);
        Map<UUID, EncounterSnapshot> snapshots = controller.snapshots();

        for (EncounterSnapshot snapshot : snapshots.values()) {
            if (snapshot.lifecycle().isTerminal()) {
                continue;
            }
            for (PlayerEncounterState state : snapshot.players().values()) {
                updateKnownPlayer(controller, snapshot, state, server.getPlayerList().getPlayer(state.playerId()), tick);
            }
        }

        snapshots = controller.snapshots();
        for (EncounterSnapshot snapshot : snapshots.values()) {
            if (snapshot.lifecycle().isTerminal()) {
                continue;
            }
            updateAbandonment(controller, snapshot, server, tick);
            EncounterSnapshot current = controller.requireEncounter(snapshot.encounterId());
            if (!current.lifecycle().isTerminal()) {
                refreshBossObservations(controller, current, server, tick);
            }
        }
    }

    private static void updateKnownPlayer(EncounterController controller, EncounterSnapshot snapshot,
                                          PlayerEncounterState state, ServerPlayer player, long tick) {
        if (player == null) {
            controller.markPlayerLeft(snapshot.encounterId(), state.playerId(), tick);
            return;
        }
        if (state.participation() == PlayerParticipationState.LEFT) {
            controller.joinPlayer(snapshot.encounterId(), state.playerId(), tick);
        } else {
            controller.markPlayerActive(snapshot.encounterId(), state.playerId());
        }
    }

    private static void updateAbandonment(EncounterController controller, EncounterSnapshot snapshot,
                                          MinecraftServer server, long tick) {
        long activePlayers = snapshot.players().values().stream()
                .filter(state -> state.participation() != PlayerParticipationState.LEFT)
                .count();
        if (activePlayers > 0) {
            if (snapshot.lifecycle() == EncounterLifecycle.ABANDONED_PENDING) {
                controller.resumeActive(snapshot.encounterId());
            }
            return;
        }
        if (snapshot.lifecycle() == EncounterLifecycle.ACTIVE) {
            controller.markAbandonedPending(snapshot.encounterId(), tick);
            return;
        }
        if (snapshot.lifecycle() != EncounterLifecycle.ABANDONED_PENDING) {
            return;
        }
        long abandonedSince = snapshot.timers().abandonedSinceTick().orElseThrow();
        long elapsed = Math.max(0L, tick - abandonedSince);
        if (elapsed >= snapshot.center().arena().abandonedTimeoutTicks()) {
            try {
                controller.finalizeEncounter(snapshot.encounterId(), EndReason.ABANDONED, tick);
            } catch (EncounterFinalizationException exception) {
                LOGGER.error("[recovery] abandoned cleanup failed encounterId={}", snapshot.encounterId(), exception);
            }
            return;
        }
        if (snapshot.phase() == EncounterPhase.PHASE_ONE
                && elapsed >= snapshot.center().arena().phaseOneRecoveryDelayTicks()
                && tick % 20L == 0L) {
            float fraction = (float) snapshot.center().arena().phaseOneRecoveryFractionPerSecond();
            snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                    .map(record -> findEntity(server, record.entityId()).orElse(null))
                    .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                    .forEach(amon -> amon.heal(amon.getMaxHealth() * fraction));
        }
    }

    private static void refreshBossObservations(EncounterController controller, EncounterSnapshot snapshot,
                                                MinecraftServer server, long tick) {
        for (BossRecord record : snapshot.bosses().values()) {
            if (!record.isAlive()) {
                continue;
            }
            Entity entity = findEntity(server, record.entityId()).orElse(null);
            if (!(entity instanceof AmonEntity amon)) {
                if (record.lifecycle() == BossLifecycleState.LOADED && tick % 20L == 0L) {
                    controller.markBossMissingPending(snapshot.encounterId(), record.entityId(), tick);
                }
                continue;
            }
            if (tick % 20L == 0L && !amon.isRemoved()) {
                controller.markBossLoaded(snapshot.encounterId(), record.entityId(),
                        amon.level().dimension(), amon.blockPosition(), tick);
            }
        }
    }

    public static boolean mayAcquireTarget(AmonEntity amon, ServerPlayer player) {
        if (amon.level().isClientSide || amon.mysterious$getEncounterId().isEmpty()) {
            return false;
        }
        EncounterSnapshot snapshot = EncounterManager.controller(player.getServer())
                .findEncounter(amon.mysterious$getEncounterId().orElseThrow()).orElse(null);
        if (snapshot == null) {
            return false;
        }
        PlayerEncounterState state = snapshot.players().get(player.getUUID());
        if (snapshot.lifecycle() == EncounterLifecycle.CROSS_DIMENSION_CHASE) {
            return state != null && state.participation() != PlayerParticipationState.LEFT
                    && snapshot.advanced().crossDimensionChase()
                    .filter(chase -> chase.bossInTargetDimension()
                            && chase.targetPlayerId().equals(player.getUUID())
                            && chase.targetDimension().equals(player.serverLevel().dimension()))
                    .isPresent()
                    && amon.level() == player.level() && player.isAlive()
                    && !player.isCreative() && !player.isSpectator()
                    && player.serverLevel().getGameTime() >= state.joinGraceUntilTick();
        }
        if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE) return false;
        boolean eligible = state != null && state.participation() != PlayerParticipationState.LEFT
                && amon.level() == player.level()
                && player.isAlive() && !player.isCreative() && !player.isSpectator()
                && player.serverLevel().getGameTime() >= state.joinGraceUntilTick()
                && snapshot.combat().currentTargetId().filter(player.getUUID()::equals).isPresent();
        // The encounter's selected threat player has priority. If there is no such player, Amon's
        // lower-priority vanilla goals remain free to retaliate against or acquire another living entity.
        return eligible;
    }

    @SubscribeEvent
    public static void onPlayerDamagesBoss(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || !(event.getEntity() instanceof AmonEntity amon)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || amon.mysterious$getEncounterId().isEmpty()) {
            return;
        }
        UUID encounterId = amon.mysterious$getEncounterId().orElseThrow();
        EncounterController controller = EncounterManager.controller(player.getServer());
        controller.findEncounter(encounterId).map(snapshot -> snapshot.players().get(player.getUUID()))
                .filter(state -> state != null && state.participation() != PlayerParticipationState.LEFT)
                .ifPresent(state -> controller.endJoinGrace(
                        encounterId, player.getUUID(), player.serverLevel().getGameTime()));
    }

    @SubscribeEvent
    public static void onBossDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof AmonEntity amon && amon.level() instanceof ServerLevel level) {
            UUID encounterId = amon.mysterious$getEncounterId().orElse(null);
            EncounterController controller = EncounterManager.controller(level.getServer());
            EncounterSnapshot snapshot = encounterId == null ? null : controller.findEncounter(encounterId).orElse(null);
            if (snapshot != null && !isCurrentLivingBoss(snapshot, amon.getUUID())) {
                // Death animations and modded AOE callbacks can outlive a phase commit. Only the currently
                // registered living incarnation is allowed to advance encounter state.
                return;
            }
            updateBossLifecycle(amon, level.getServer(), true, level.getGameTime());
        }
    }

    /** Rejects stale death callbacks from an earlier phase without canceling a real death. */
    public static boolean isCurrentLivingBoss(EncounterSnapshot snapshot, UUID bossId) {
        if (snapshot == null || snapshot.lifecycle().isTerminal()) return false;
        BossRecord record = snapshot.bosses().get(bossId);
        return record != null && record.isAlive() && record.phase() == snapshot.phase();
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof AmonEntity amon && event.getLevel() instanceof ServerLevel level) {
            UUID encounterId = amon.mysterious$getEncounterId().orElse(null);
            if (encounterId == null) {
                if (!event.loadedFromDisk()) {
                    ServerPlayer owner = level.getPlayers(player -> player.isAlive() && !player.isSpectator()).stream()
                            .min(Comparator.comparingDouble(player -> player.distanceToSqr(amon))).orElse(null);
                    if (owner != null && owner.distanceToSqr(amon) <= 64.0D * 64.0D) {
                        try {
                            EncounterStartService.startWithSummonedAmon(owner, amon);
                            LOGGER.info("[encounter] converted summoned Amon={} into P1 owner={}",
                                    amon.getUUID(), owner.getUUID());
                        } catch (IllegalStateException | IllegalArgumentException exception) {
                            LOGGER.warn("[encounter] left summoned Amon={} unbound: {}", amon.getUUID(),
                                    exception.getMessage());
                        }
                    }
                }
                return;
            }
            EncounterController controller = EncounterManager.controller(level.getServer());
            controller.findEncounter(encounterId).filter(snapshot -> !snapshot.lifecycle().isTerminal())
                    .filter(snapshot -> snapshot.bosses().containsKey(amon.getUUID()))
                    .ifPresent(snapshot -> {
                        controller.markBossLoaded(encounterId, amon.getUUID(),
                                level.dimension(), amon.blockPosition(), level.getGameTime());
                        StolenAttributeManager.rebuild(encounterId, level.getServer());
                    });
        }
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AmonEntity amon && event.getLevel() instanceof ServerLevel level) {
            boolean destroyed = amon.getRemovalReason() != null && amon.getRemovalReason().shouldDestroy();
            if (destroyed) {
                updateBossLifecycle(amon, level.getServer(), true, level.getGameTime());
            } else {
                updateBossLifecycle(amon, level.getServer(), false, level.getGameTime());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            boolean hasLiveParasite = EncounterManager.controller(player.getServer()).snapshots().values().stream()
                    .filter(snapshot -> !snapshot.lifecycle().isTerminal())
                    .flatMap(snapshot -> snapshot.advanced().phaseThree().stream())
                    .map(state -> state.parasites().get(player.getUUID()))
                    .anyMatch(state -> state != null && state.remainingTicks() > 0);
            if (!hasLiveParasite) player.removeEffect(ModEffects.PARASITE);
            SealManager.clearOrphaned(player);
            NetworkSyncService.resyncPlayer(player.getServer(), player,
                    EncounterManager.controller(player.getServer()).snapshots());
        }
    }

    private static void updateBossLifecycle(AmonEntity amon, MinecraftServer server, boolean dead, long tick) {
        UUID encounterId = amon.mysterious$getEncounterId().orElse(null);
        if (encounterId == null) {
            return;
        }
        EncounterController controller = EncounterManager.controller(server);
        controller.findEncounter(encounterId).filter(snapshot -> !snapshot.lifecycle().isTerminal())
                .filter(snapshot -> snapshot.bosses().containsKey(amon.getUUID()))
                .ifPresent(snapshot -> {
                    if (dead) {
                        BossRecord previous = snapshot.bosses().get(amon.getUUID());
                        if (previous == null || !previous.isAlive()) {
                            return;
                        }
                        controller.markBossDead(encounterId, amon.getUUID(), tick);
                        EncounterSnapshot current = controller.requireEncounter(encounterId);
                        if (current.phase() == EncounterPhase.PHASE_THREE
                                && current.advanced().phaseThree()
                                .map(state -> state.secondFormActive()).orElse(false)
                                && current.bosses().values().stream().noneMatch(BossRecord::isAlive)) {
                            try {
                                controller.finalizeEncounter(encounterId, EndReason.VICTORY, tick);
                            } catch (EncounterFinalizationException exception) {
                                LOGGER.error("[recovery] victory cleanup failed encounterId={}",
                                        encounterId, exception);
                            }
                        }
                    } else {
                        controller.markBossUnloaded(encounterId, amon.getUUID(), tick);
                    }
                });
    }

    private static Optional<Entity> findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

}
