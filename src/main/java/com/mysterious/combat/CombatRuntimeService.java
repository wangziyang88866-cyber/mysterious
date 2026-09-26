package com.mysterious.combat;

import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerEncounterState;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.entity.AmonEntity;
import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.entity.WormOfTimeEntity;
import com.mysterious.config.MysteriousServerConfig;
import com.mysterious.mysterious;
import com.mysterious.phase.EncounterParticleRuntimeService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stage 5 server-authoritative damage, threat, targeting, regeneration and teleport runtime. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class CombatRuntimeService {
    public static final float MAX_EFFECTIVE_DAMAGE_PER_HIT = 10.0F;
    private static final int OUT_OF_COMBAT_TICKS = 8 * 20;
    public static final int MIN_NORMAL_TELEPORT_INTERVAL_TICKS = 4 * 20;
    public static final double MAX_HIT_TELEPORT_CHANCE = 0.10D;
    public static final int MIN_HIT_TELEPORT_COOLDOWN_TICKS = 2 * 20;
    private CombatRuntimeService() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void validateIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof PhantomClockEntity) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof WormOfTimeEntity
                && (event.getSource().is(DamageTypeTags.IS_FIRE)
                || event.getSource().is(DamageTypeTags.IS_LIGHTNING))) {
            event.setAmount(0.0F);
        }
        // Damage eligibility is deliberately not filtered for Amon: bound and unbound instances,
        // players, mobs and environmental sources all use vanilla damage handling. Participant
        // state only controls encounter bookkeeping and player-threat selection.
        if (event.getEntity() instanceof AmonEntity amon
                && amon.level() instanceof ServerLevel level
                && event.getSource().getEntity() instanceof ServerPlayer player
                && amon.mysterious$getEncounterId().isPresent()) {
            EncounterSnapshot snapshot = EncounterManager.controller(level.getServer())
                    .findEncounter(amon.mysterious$getEncounterId().orElseThrow()).orElse(null);
            if (snapshot != null && mayRecordPlayerCombat(snapshot, amon, player)) {
                tryHitTeleport(snapshot, amon, player, event.getSource().getDirectEntity(), level.getGameTime());
                DamageDeliveryClassifier.classify(event.getSource());
            }
        }
    }

    /** Caps the final post-mitigation damage without making any phase invulnerable. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void handleEffectiveDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof AmonEntity amon)
                || !(amon.level() instanceof ServerLevel)
                || amon.mysterious$getEncounterId().isEmpty()) {
            return;
        }
        event.setNewDamage(capEffectiveDamage(event.getNewDamage()));
    }

    @SubscribeEvent
    public static void afterDamage(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) {
            return;
        }
        if (event.getEntity() instanceof AmonEntity amon
                && amon.level() instanceof ServerLevel level
                && event.getSource().getEntity() instanceof ServerPlayer player
                && amon.mysterious$getEncounterId().isPresent()) {
            UUID encounterId = amon.mysterious$getEncounterId().orElseThrow();
            EncounterController controller = EncounterManager.controller(level.getServer());
            EncounterSnapshot snapshot = controller.findEncounter(encounterId).orElse(null);
            if (snapshot == null || !mayRecordPlayerCombat(snapshot, amon, player)) {
                return;
            }
            long tick = level.getGameTime();
            controller.endJoinGrace(encounterId, player.getUUID(), tick);
            controller.recordThreat(encounterId, player.getUUID(), event.getNewDamage(), tick);
            amon.setTarget(player);
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player
                && event.getSource().getEntity() instanceof AmonEntity amon
                && amon.level() instanceof ServerLevel level
                && amon.mysterious$getEncounterId().isPresent()) {
            UUID encounterId = amon.mysterious$getEncounterId().orElseThrow();
            EncounterController controller = EncounterManager.controller(level.getServer());
            EncounterSnapshot snapshot = controller.findEncounter(encounterId).orElse(null);
            if (snapshot != null && mayRecordPlayerCombat(snapshot, amon, player)) {
                long tick = level.getGameTime();
                amon.mysterious$recordOutgoingHit(tick);
                controller.recordThreat(encounterId, player.getUUID(), 2.0D, tick);
            }
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) {
            return;
        }
        long tick = server.overworld().getGameTime();
        if (tick % 20L != 0L) {
            return;
        }
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE) {
                continue;
            }
            updateThreatAndTargets(controller, snapshot, server, tick);
            tickBossCombat(controller.requireEncounter(snapshot.encounterId()), server, tick);
        }
    }

    public static AttackEventIdentity identity(UUID encounterId, Entity attacker, Entity target,
                                               Entity directEntity, long tick) {
        UUID direct = directEntity == null ? attacker.getUUID() : directEntity.getUUID();
        return AttackEventIdentity.of(encounterId, attacker.getUUID(), target.getUUID(), direct, tick);
    }

    private static void updateThreatAndTargets(EncounterController controller, EncounterSnapshot snapshot,
                                               MinecraftServer server, long tick) {
        Map<UUID, ThreatEntry> updated = new LinkedHashMap<>();
        snapshot.combat().threats().forEach((playerId, entry) -> {
            PlayerEncounterState state = snapshot.players().get(playerId);
            if (state != null && state.participation() != PlayerParticipationState.LEFT) {
                double value = tick - entry.lastActionTick() >= 200L ? entry.value() * 0.95D : entry.value();
                updated.put(playerId, new ThreatEntry(value < 0.0001D ? 0.0D : value, entry.lastActionTick()));
            }
        });

        Optional<ServerPlayer> nearest = snapshot.players().values().stream()
                .filter(state -> state.participation() != PlayerParticipationState.LEFT)
                .map(state -> server.getPlayerList().getPlayer(state.playerId()))
                .filter(player -> player != null && isLegalParticipant(snapshot, player))
                .min(Comparator.comparingDouble(player -> nearestBossDistanceSquared(snapshot, server, player)));
        nearest.ifPresent(player -> {
            ThreatEntry entry = updated.getOrDefault(player.getUUID(), new ThreatEntry(0.0D, tick));
            updated.put(player.getUUID(), new ThreatEntry(entry.value() + 1.0D, entry.lastActionTick()));
        });

        Optional<UUID> best = updated.entrySet().stream()
                .filter(entry -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    return player != null && isLegalTarget(snapshot, player, tick);
                })
                .max(Map.Entry.comparingByValue(Comparator.comparingDouble(ThreatEntry::value)))
                .map(Map.Entry::getKey);
        Optional<UUID> current = snapshot.combat().currentTargetId().filter(id -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player != null && isLegalTarget(snapshot, player, tick);
        });
        Optional<UUID> selected = selectTarget(updated, current, best);
        controller.updateThreatSelection(snapshot.encounterId(), updated, selected, tick);
        selected.map(server.getPlayerList()::getPlayer).ifPresent(player ->
                snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                        .map(record -> findEntity(server, record.entityId()))
                        .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                        .filter(amon -> amon.level() == player.level() && amon.canAttack(player))
                        // A non-player target may come from vanilla retaliation, another mod's
                        // taming/command system, or the fallback target goal.  Player threat must
                        // not overwrite that valid explicit target every encounter tick.
                        .filter(amon -> amon.getTarget() instanceof ServerPlayer
                                || !isUsableCombatTarget(amon, amon.getTarget()))
                        .forEach(amon -> amon.setTarget(player)));
    }

    public static Optional<UUID> selectTarget(Map<UUID, ThreatEntry> threats, Optional<UUID> current,
                                              Optional<UUID> best) {
        if (current.isEmpty()) {
            return best;
        }
        if (best.isEmpty() || best.equals(current)) {
            return current;
        }
        double currentThreat = threats.getOrDefault(current.orElseThrow(), new ThreatEntry(0.0D, 0L)).value();
        double bestThreat = threats.getOrDefault(best.orElseThrow(), new ThreatEntry(0.0D, 0L)).value();
        return bestThreat >= currentThreat * 1.20D ? best : current;
    }

    public static float capEffectiveDamage(float effectiveDamage) {
        if (!Float.isFinite(effectiveDamage) || effectiveDamage <= 0.0F) {
            return 0.0F;
        }
        return Math.min(effectiveDamage, MAX_EFFECTIVE_DAMAGE_PER_HIT);
    }

    /** Shared target bridge for spells, flight and encounter summons. */
    public static Optional<LivingEntity> resolveCombatTarget(EncounterSnapshot snapshot, AmonEntity owner,
                                                              MinecraftServer server) {
        LivingEntity direct = owner.getTarget();
        if (isUsableCombatTarget(owner, direct)) return Optional.of(direct);
        return snapshot.combat().currentTargetId()
                .map(server.getPlayerList()::getPlayer)
                .filter(target -> isUsableCombatTarget(owner, target))
                .map(LivingEntity.class::cast);
    }

    private static boolean isUsableCombatTarget(AmonEntity owner, LivingEntity target) {
        return target != null && target.isAlive() && !target.isRemoved()
                && target.level() == owner.level() && owner.canAttack(target);
    }

    private static void tickBossCombat(EncounterSnapshot snapshot, MinecraftServer server, long tick) {
        for (BossRecord record : snapshot.bosses().values()) {
            Entity entity = findEntity(server, record.entityId());
            if (!(entity instanceof AmonEntity amon) || !record.isAlive()) {
                continue;
            }
            Optional<LivingEntity> combatTarget = resolveCombatTarget(snapshot, amon, server);
            ServerPlayer target = combatTarget.orElse(null) instanceof ServerPlayer player
                    && isLegalTarget(snapshot, player, tick)
                    && snapshot.combat().currentTargetId().filter(player.getUUID()::equals).isPresent()
                    ? player : null;
            if (target != null) {
                boolean tooFar = amon.distanceToSqr(target) > 24.0D * 24.0D;
                boolean noRecentHit = amon.mysterious$approachTeleportReady(tick);
                if ((tooFar || noRecentHit)
                        && amon.mysterious$normalTeleportReady(tick, MIN_NORMAL_TELEPORT_INTERVAL_TICKS)) {
                    teleportNear(snapshot, amon, target);
                }
            }
            if (snapshot.phase() != EncounterPhase.PHASE_ONE
                    && combatTarget.isEmpty()
                    && tick - amon.mysterious$lastCombatTick() >= OUT_OF_COMBAT_TICKS) {
                amon.heal(5.0F);
            }
        }
    }

    private static void teleportNear(EncounterSnapshot snapshot, AmonEntity amon, ServerPlayer target) {
        if (!(amon.level() instanceof ServerLevel level)) {
            return;
        }
        EncounterParticleRuntimeService.teleportBurst(level, amon.position());
        amon.teleportTo(target.getX(), target.getY(), target.getZ());
        amon.getNavigation().stop();
        amon.mysterious$markTeleported(level.getGameTime());
        EncounterParticleRuntimeService.teleportBurst(level, amon.position());
        level.playSound(null, amon.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.HOSTILE, 1.0F, 0.8F + amon.getRandom().nextFloat() * 0.25F);
    }

    private static void tryHitTeleport(EncounterSnapshot snapshot, AmonEntity amon, ServerPlayer player,
                                       Entity directEntity, long tick) {
        AttackEventIdentity identity = identity(snapshot.encounterId(), player, amon, directEntity, tick);
        if (amon.mysterious$normalTeleportReady(tick, MIN_NORMAL_TELEPORT_INTERVAL_TICKS)
                && amon.getRandom().nextDouble() < Math.min(MAX_HIT_TELEPORT_CHANCE,
                MysteriousServerConfig.hitTeleportChance())
                && amon.mysterious$claimHitTeleport(identity, tick,
                Math.max(MIN_HIT_TELEPORT_COOLDOWN_TICKS,
                        MysteriousServerConfig.hitTeleportCooldownTicks()))) {
            teleportNear(snapshot, amon, player);
        }
    }

    private static boolean isLegalParticipant(EncounterSnapshot snapshot, ServerPlayer player) {
        PlayerEncounterState state = snapshot.players().get(player.getUUID());
        return state != null && state.participation() != PlayerParticipationState.LEFT
                && player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    private static boolean mayRecordPlayerCombat(EncounterSnapshot snapshot, AmonEntity amon,
                                                  ServerPlayer player) {
        if (snapshot.lifecycle() == EncounterLifecycle.ACTIVE) {
            return isLegalParticipant(snapshot, player) && amon.level() == player.level();
        }
        PlayerEncounterState state = snapshot.players().get(player.getUUID());
        return snapshot.lifecycle() == EncounterLifecycle.CROSS_DIMENSION_CHASE
                && state != null && state.participation() != PlayerParticipationState.LEFT
                && player.isAlive() && !player.isCreative() && !player.isSpectator()
                && amon.level() == player.level()
                && snapshot.advanced().crossDimensionChase()
                .filter(chase -> chase.bossInTargetDimension()
                        && chase.targetPlayerId().equals(player.getUUID())
                        && chase.targetDimension().equals(player.serverLevel().dimension()))
                .isPresent();
    }

    private static boolean isLegalTarget(EncounterSnapshot snapshot, ServerPlayer player, long tick) {
        PlayerEncounterState state = snapshot.players().get(player.getUUID());
        return player.isAlive() && isLegalParticipant(snapshot, player)
                && state != null && tick >= state.joinGraceUntilTick();
    }

    private static double nearestBossDistanceSquared(EncounterSnapshot snapshot, MinecraftServer server,
                                                     ServerPlayer player) {
        return snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast)
                .mapToDouble(amon -> amon.distanceToSqr(player)).min().orElse(Double.MAX_VALUE);
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
