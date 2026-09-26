package com.mysterious.phase;

import com.mysterious.config.ReleaseCandidateBalance;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.entity.AmonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

/** Fixed-speed, collision-respecting second-form movement. */
public final class FlightController {
    public static final double SPEED_PER_TICK = ReleaseCandidateBalance.FLIGHT_SPEED_PER_TICK;

    private FlightController() {
    }

    public static void activate(EncounterSnapshot snapshot, MinecraftServer server) {
        for (var participant : snapshot.players().values()) {
            if (participant.participation() == PlayerParticipationState.LEFT) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player != null && player.isAlive() && !player.isCreative() && !player.isSpectator()) {
                player.setHealth(adjustedPlayerHealth(player.getHealth()));
            }
        }
        for (BossRecord record : snapshot.bosses().values()) {
            Entity entity = findEntity(server, record.entityId());
            if (record.isAlive() && entity instanceof AmonEntity amon) {
                amon.setHealth(amon.getMaxHealth());
                amon.mysterious$setSecondForm(true);
                if (amon.level() instanceof ServerLevel level) {
                    EncounterParticleRuntimeService.teleportBurst(level, amon.position());
                    level.sendParticles(ParticleTypes.DRAGON_BREATH,
                            amon.getX(), amon.getY() + 1.0D, amon.getZ(),
                            64, 1.6D, 1.2D, 1.6D, 0.08D);
                    level.playSound(null, amon.blockPosition(), SoundEvents.WITHER_SPAWN,
                            SoundSource.HOSTILE, 1.6F, 0.72F);
                }
            }
        }
    }

    public static float adjustedPlayerHealth(float currentHealth) {
        if (!Float.isFinite(currentHealth) || currentHealth < 0.0F) {
            throw new IllegalArgumentException("currentHealth must be finite and non-negative");
        }
        return Math.max(1.0F, currentHealth * 0.5F);
    }

    public static void tick(EncounterSnapshot snapshot, MinecraftServer server) {
        for (BossRecord record : snapshot.bosses().values()) {
            Entity entity = findEntity(server, record.entityId());
            if (!record.isAlive() || !(entity instanceof AmonEntity amon)
                    || !(amon.level() instanceof ServerLevel level)) continue;
            amon.mysterious$setSecondForm(true);
            amon.getNavigation().stop();
            amon.setDeltaMovement(Vec3.ZERO);
            LivingEntity target = CombatRuntimeService.resolveCombatTarget(snapshot, amon, server).orElse(null);
            if (target == null || target.level() != level) continue;
            Vec3 desired = target.getEyePosition().subtract(amon.position());
            if (desired.lengthSqr() <= 9.0D) continue;
            Vec3 movement = desired.normalize().scale(SPEED_PER_TICK);
            faceMovement(amon, movement);
            Vec3 destination = amon.position().add(movement);
            AABB moved = amon.getBoundingBox().move(movement);
            if (level.getWorldBorder().isWithinBounds(BlockPos.containing(destination))
                    && level.noCollision(amon, moved)) {
                amon.move(MoverType.SELF, movement);
            }
        }
    }

    public static void faceMovement(AmonEntity amon, Vec3 movement) {
        if (movement.lengthSqr() < 1.0E-8D) return;
        float targetYaw = yawFor(movement);
        float targetPitch = pitchFor(movement);
        float yaw = approachRotation(amon.getYRot(), targetYaw, 24.0F);
        float pitch = approachRotation(amon.getXRot(), targetPitch, 18.0F);
        amon.setYRot(yaw);
        amon.setXRot(pitch);
        amon.setYHeadRot(yaw);
        amon.yBodyRot = yaw;
    }

    public static float yawFor(Vec3 movement) {
        return (float) (Mth.atan2(movement.z, movement.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    public static float pitchFor(Vec3 movement) {
        double horizontal = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        return (float) -(Mth.atan2(movement.y, horizontal) * Mth.RAD_TO_DEG);
    }

    public static float approachRotation(float current, float target, float maximumStep) {
        return current + Mth.clamp(Mth.wrapDegrees(target - current), -maximumStep, maximumStep);
    }

    private static Entity findEntity(MinecraftServer server, java.util.UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }
}
