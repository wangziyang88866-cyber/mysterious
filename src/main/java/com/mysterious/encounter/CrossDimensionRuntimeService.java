package com.mysterious.encounter;

import com.mojang.logging.LogUtils;
import com.mysterious.config.ReleaseCandidateBalance;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.phase.EncounterParticleRuntimeService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Stage 15 cross-dimension wait/chase/return transaction and missing-entity reconciliation. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class CrossDimensionRuntimeService {
    public static final int WAIT_TICKS = ReleaseCandidateBalance.CROSS_DIMENSION_WAIT_TICKS;
    public static final int COOLDOWN_TICKS = ReleaseCandidateBalance.CROSS_DIMENSION_COOLDOWN_TICKS;
    public static final int SAFE_SEARCH_ATTEMPTS = ReleaseCandidateBalance.CROSS_DIMENSION_SEARCH_ATTEMPTS;
    public static final double APPEAR_MIN_RADIUS = ReleaseCandidateBalance.CROSS_DIMENSION_MIN_RADIUS;
    public static final double APPEAR_MAX_RADIUS = ReleaseCandidateBalance.CROSS_DIMENSION_MAX_RADIUS;
    private static final double CHASE_SPEED = 0.55D;
    private static final Logger LOGGER = LogUtils.getLogger();

    private CrossDimensionRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) return;
        long tick = server.overworld().getGameTime();
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.lifecycle().isTerminal()) continue;
            reconcileMissingBosses(controller, snapshot, server, tick);
            if (!isEligiblePhase(snapshot)) continue;
            tickEncounter(controller, controller.requireEncounter(snapshot.encounterId()), server, tick);
        }
    }

    static boolean isEligiblePhase(EncounterSnapshot snapshot) {
        return snapshot.phase() == EncounterPhase.PHASE_THREE
                && snapshot.advanced().phaseThree().map(state -> state.secondFormActive()).orElse(false);
    }

    private static void tickEncounter(EncounterController controller, EncounterSnapshot snapshot,
                                      MinecraftServer server, long tick) {
        CrossDimensionChaseState chase = snapshot.advanced().crossDimensionChase().orElse(null);
        if (snapshot.lifecycle() == EncounterLifecycle.CROSS_DIMENSION_CHASE) {
            if (chase == null) return;
            tickActiveChase(controller, snapshot, chase, server, tick);
            return;
        }
        if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE) return;

        List<ServerPlayer> original = eligiblePlayers(snapshot, server).stream()
                .filter(player -> player.serverLevel().dimension().equals(snapshot.center().dimension()))
                .toList();
        if (!original.isEmpty()) {
            if (chase != null) clearPending(controller, snapshot);
            return;
        }

        List<ServerPlayer> remote = eligiblePlayers(snapshot, server).stream()
                .filter(player -> !player.serverLevel().dimension().equals(snapshot.center().dimension()))
                .toList();
        if (remote.size() != 1 || snapshot.timers().crossDimensionCooldownUntilTick().orElse(0L) > tick) {
            if (chase != null) clearPending(controller, snapshot);
            return;
        }
        ServerPlayer target = remote.getFirst();
        if (chase == null || !chase.targetPlayerId().equals(target.getUUID())
                || !chase.targetDimension().equals(target.serverLevel().dimension())) {
            CrossDimensionChaseState pending = new CrossDimensionChaseState(target.getUUID(),
                    target.serverLevel().dimension(), tick, safeAdd(tick, WAIT_TICKS), false, false);
            controller.updateAdvancedState(snapshot.encounterId(), withChase(snapshot, Optional.of(pending)));
            return;
        }
        if (tick < chase.executeAfterTick()) return;

        AmonEntity boss = findLivingBoss(snapshot, server);
        if (boss == null) return;
        // Product rule: dimension changes also land on the player's exact server position.
        Vec3 position = target.position();
        if (boss.level() instanceof ServerLevel sourceLevel) {
            EncounterParticleRuntimeService.teleportBurst(sourceLevel, boss.position());
        }
        boolean moved = boss.teleportTo(target.serverLevel(), position.x, position.y, position.z,
                Set.<RelativeMovement>of(), boss.getYRot(), boss.getXRot());
        if (!moved) return;
        AmonEntity teleported = boss;
        teleported.getNavigation().stop();
        teleported.setTarget(target);
        teleported.mysterious$markTeleported(tick);
        EncounterParticleRuntimeService.teleportBurst(target.serverLevel(), teleported.position());
        target.serverLevel().playSound(null, teleported.blockPosition(), SoundEvents.PORTAL_TRAVEL,
                SoundSource.HOSTILE, 1.3F, 0.78F);
        CrossDimensionChaseState active = chase.teleported();
        controller.updateAdvancedState(snapshot.encounterId(), withChase(snapshot, Optional.of(active)));
        controller.beginCrossDimensionChase(snapshot.encounterId());
        EncounterSnapshot current = controller.requireEncounter(snapshot.encounterId());
        controller.updateTimers(snapshot.encounterId(), new EncounterTimerState(
                current.timers().phaseStartedAtTick(), OptionalLong.empty(),
                OptionalLong.of(safeAdd(tick, COOLDOWN_TICKS))));
        controller.updateThreatSelection(snapshot.encounterId(), current.combat().threats(),
                Optional.of(target.getUUID()), tick);
        LOGGER.info("[cross-dimension] chase-start encounterId={} boss={} player={} dimension={} tick={}",
                snapshot.encounterId(), teleported.getUUID(), target.getUUID(),
                target.serverLevel().dimension().location(), tick);
    }

    private static void tickActiveChase(EncounterController controller, EncounterSnapshot snapshot,
                                        CrossDimensionChaseState chase, MinecraftServer server, long tick) {
        ServerPlayer target = server.getPlayerList().getPlayer(chase.targetPlayerId());
        boolean originalParticipantReturned = eligiblePlayers(snapshot, server).stream().anyMatch(player ->
                player.serverLevel().dimension().equals(snapshot.center().dimension()));
        boolean invalidTarget = target == null || !target.isAlive() || target.isCreative() || target.isSpectator()
                || !target.serverLevel().dimension().equals(chase.targetDimension())
                || snapshot.players().get(chase.targetPlayerId()) == null
                || snapshot.players().get(chase.targetPlayerId()).participation() == PlayerParticipationState.LEFT;
        if (originalParticipantReturned || invalidTarget || chase.returnPending()) {
            returnToOrigin(controller, snapshot, chase, server, tick);
            return;
        }
        AmonEntity boss = findLivingBoss(snapshot, server);
        if (boss == null || boss.level() != target.serverLevel()) {
            returnToOrigin(controller, snapshot, chase.awaitingReturn(), server, tick);
            return;
        }
        boss.setTarget(target);
        boss.getNavigation().stop();
        boss.setDeltaMovement(Vec3.ZERO);
        moveToward(boss, target);
    }

    private static void returnToOrigin(EncounterController controller, EncounterSnapshot snapshot,
                                       CrossDimensionChaseState chase, MinecraftServer server, long tick) {
        AmonEntity boss = findLivingBoss(snapshot, server);
        ServerLevel targetLevel = server.getLevel(snapshot.center().dimension());
        if (boss == null || targetLevel == null) {
            if (!chase.returnPending()) {
                controller.updateAdvancedState(snapshot.encounterId(), withChase(snapshot,
                        Optional.of(chase.awaitingReturn())));
            }
            if (boss != null) {
                boss.setTarget(null);
                boss.getNavigation().stop();
                boss.setDeltaMovement(Vec3.ZERO);
            }
            return;
        }
        BlockPos origin = snapshot.center().position();
        Vec3 destination = new Vec3(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        if (boss.level() instanceof ServerLevel sourceLevel) {
            EncounterParticleRuntimeService.teleportBurst(sourceLevel, boss.position());
        }
        boolean moved = boss.teleportTo(targetLevel, destination.x, destination.y, destination.z,
                Set.<RelativeMovement>of(), boss.getYRot(), boss.getXRot());
        if (!moved) {
            controller.updateAdvancedState(snapshot.encounterId(), withChase(snapshot,
                    Optional.of(chase.awaitingReturn())));
            return;
        }
        AmonEntity returned = boss;
        returned.setTarget(null);
        returned.getNavigation().stop();
        returned.setDeltaMovement(Vec3.ZERO);
        returned.mysterious$markTeleported(tick);
        EncounterParticleRuntimeService.teleportBurst(targetLevel, returned.position());
        targetLevel.playSound(null, returned.blockPosition(), SoundEvents.PORTAL_TRAVEL,
                SoundSource.HOSTILE, 1.1F, 1.18F);
        controller.resumeActive(snapshot.encounterId());
        EncounterSnapshot active = controller.requireEncounter(snapshot.encounterId());
        controller.updateAdvancedState(snapshot.encounterId(), withChase(active, Optional.empty()));
        LOGGER.info("[cross-dimension] chase-return encounterId={} boss={} tick={}",
                snapshot.encounterId(), returned.getUUID(), tick);
    }

    private static void clearPending(EncounterController controller, EncounterSnapshot snapshot) {
        controller.updateAdvancedState(snapshot.encounterId(), withChase(snapshot, Optional.empty()));
    }

    private static AdvancedEncounterState withChase(EncounterSnapshot snapshot,
                                                     Optional<CrossDimensionChaseState> chase) {
        return new AdvancedEncounterState(snapshot.advanced().spells(), snapshot.advanced().phaseThree(), chase);
    }

    private static List<ServerPlayer> eligiblePlayers(EncounterSnapshot snapshot, MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        snapshot.players().values().stream()
                .filter(state -> state.participation() != PlayerParticipationState.LEFT)
                .sorted(Comparator.comparing(state -> state.playerId().toString()))
                .forEach(state -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(state.playerId());
                    if (player != null && player.isAlive() && !player.isCreative() && !player.isSpectator()) {
                        players.add(player);
                    }
                });
        return players;
    }

    private static AmonEntity findLivingBoss(EncounterSnapshot snapshot, MinecraftServer server) {
        return snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .sorted(Comparator.comparing(record -> record.entityId().toString()))
                .map(record -> findEntity(server, record.entityId()))
                .filter(AmonEntity.class::isInstance).map(AmonEntity.class::cast).findFirst().orElse(null);
    }

    private static Entity findEntity(MinecraftServer server, java.util.UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }

    private static void reconcileMissingBosses(EncounterController controller, EncounterSnapshot snapshot,
                                                MinecraftServer server, long tick) {
        if (tick % 20L != 0L) return;
        snapshot.bosses().values().stream()
                .filter(record -> record.lifecycle() == BossLifecycleState.MISSING_PENDING)
                .forEach(record -> {
                    Entity entity = findEntity(server, record.entityId());
                    if (entity instanceof AmonEntity amon && !amon.isRemoved()) {
                        controller.markBossLoaded(snapshot.encounterId(), record.entityId(),
                                amon.level().dimension(), amon.blockPosition(), tick);
                        LOGGER.info("[recovery] reconciled missing boss encounterId={} boss={} dimension={}",
                                snapshot.encounterId(), record.entityId(), amon.level().dimension().location());
                    }
                });
    }

    static Optional<Vec3> findSafePositionNear(ServerLevel level, Entity entity, Vec3 center) {
        for (int attempt = 0; attempt < SAFE_SEARCH_ATTEMPTS; attempt++) {
            double angle = Math.PI * 2.0D * attempt / SAFE_SEARCH_ATTEMPTS;
            double progress = (double) attempt / (SAFE_SEARCH_ATTEMPTS - 1);
            double radius = APPEAR_MIN_RADIUS + (APPEAR_MAX_RADIUS - APPEAR_MIN_RADIUS) * progress;
            int x = (int) Math.floor(center.x + Math.cos(angle) * radius);
            int z = (int) Math.floor(center.z + Math.sin(angle) * radius);
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int y = Math.max(level.getMinBuildHeight() + 1,
                    Math.min(level.getMaxBuildHeight() - 2,
                            Math.abs(surface - center.y) <= 12.0D ? surface : (int) Math.floor(center.y)));
            Vec3 candidate = new Vec3(x + 0.5D, y, z + 0.5D);
            BlockPos block = BlockPos.containing(candidate);
            AABB moved = entity.getBoundingBox().move(candidate.subtract(entity.position()));
            if (level.getWorldBorder().isWithinBounds(block) && level.noCollision(entity, moved)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static void moveToward(AmonEntity boss, ServerPlayer target) {
        Vec3 direction = target.getEyePosition().subtract(boss.position());
        if (direction.lengthSqr() <= 9.0D) return;
        Vec3 movement = direction.normalize().scale(CHASE_SPEED);
        AABB moved = boss.getBoundingBox().move(movement);
        BlockPos destination = BlockPos.containing(boss.position().add(movement));
        if (boss.level().getWorldBorder().isWithinBounds(destination)
                && boss.level().noCollision(boss, moved)) {
            boss.move(MoverType.SELF, movement);
        }
    }

    private static long safeAdd(long tick, int amount) {
        return tick > Long.MAX_VALUE - amount ? Long.MAX_VALUE : tick + amount;
    }
}
