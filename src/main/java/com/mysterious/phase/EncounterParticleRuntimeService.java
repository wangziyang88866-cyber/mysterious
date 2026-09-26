package com.mysterious.phase;

import com.mysterious.encounter.EncounterBoundEntity;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.entity.AmonEntity;
import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.entity.WormOfTimeEntity;
import com.mysterious.mysterious;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Stage 16 layered visual language built exclusively from vanilla particle types. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class EncounterParticleRuntimeService {
    private EncounterParticleRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) return;
        long tick = server.overworld().getGameTime();
        for (EncounterSnapshot snapshot : EncounterManager.controller(server).snapshots().values()) {
            if (snapshot.lifecycle().isTerminal()) continue;
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof EncounterBoundEntity bound)
                            || bound.mysterious$getEncounterId().filter(snapshot.encounterId()::equals).isEmpty()) {
                        continue;
                    }
                    if (entity instanceof AmonEntity amon) renderAmon(level, amon, snapshot, tick);
                    else if (entity instanceof WormOfTimeEntity worm) renderWorm(level, worm, tick);
                    else if (entity instanceof PhantomClockEntity clock) renderClock(level, clock, tick);
                }
            }
        }
    }

    public static void teleportBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y + 1.0D, position.z,
                72, 0.9D, 1.2D, 0.9D, 0.18D);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, position.x, position.y + 0.8D, position.z,
                28, 0.65D, 0.9D, 0.65D, 0.045D);
        level.sendParticles(ParticleTypes.END_ROD, position.x, position.y + 1.0D, position.z,
                20, 0.5D, 0.8D, 0.5D, 0.035D);
        ring(level, ParticleTypes.PORTAL, position.add(0.0D, 0.2D, 0.0D), 2.5D, 20, 0.02D);
    }

    /** One-shot impact that starts the longer P1 -> P2 transition animation. */
    public static void phaseTwoEntryBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.WITCH, position.x, position.y + 1.0D, position.z,
                100, 1.8D, 1.5D, 1.8D, 0.1D);
        level.sendParticles(ParticleTypes.ENCHANT, position.x, position.y + 1.0D, position.z,
                72, 1.4D, 1.2D, 1.4D, 0.16D);
        ring(level, ParticleTypes.ELECTRIC_SPARK, position.add(0.0D, 0.35D, 0.0D), 3.5D, 42, 0.06D);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.END_PORTAL_SPAWN,
                SoundSource.HOSTILE, 1.15F, 1.2F);
    }

    /** One-shot, server-broadcast cue for the P2 -> P3 commit and transformation timeline start. */
    public static void phaseThreeEntryBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.DRAGON_BREATH, position.x, position.y + 1.0D, position.z,
                120, 2.2D, 1.7D, 2.2D, 0.12D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y + 1.0D, position.z,
                96, 1.6D, 1.4D, 1.6D, 0.2D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y + 1.0D, position.z,
                48, 1.3D, 1.2D, 1.3D, 0.16D);
        ring(level, ParticleTypes.SOUL_FIRE_FLAME, position.add(0.0D, 0.35D, 0.0D), 3.0D, 36, 0.05D);
        ring(level, ParticleTypes.END_ROD, position.add(0.0D, 1.15D, 0.0D), 4.5D, 48, 0.08D);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 1.8F, 0.65F);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.END_PORTAL_SPAWN,
                SoundSource.HOSTILE, 1.25F, 0.85F);
    }

    public static void clockExpiryBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.SONIC_BOOM, position.x, position.y + 0.55D, position.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y + 0.55D, position.z,
                40, 1.1D, 0.8D, 1.1D, 0.18D);
        ring(level, ParticleTypes.ENCHANT, position.add(0.0D, 0.55D, 0.0D), 3.0D, 36, 0.12D);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.HOSTILE, 0.9F, 1.35F);
    }

    /** Target-side feedback for a clock hit, implemented only with vanilla effects. */
    public static void clockTargetBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(ParticleTypes.SONIC_BOOM, position.x, position.y + 1.0D, position.z,
                1, 0.0D, 0.0D, 0.0D, 0.0D);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, position.x, position.y + 1.0D, position.z,
                28, 0.65D, 0.9D, 0.65D, 0.14D);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, position.x, position.y + 0.9D, position.z,
                20, 0.55D, 0.8D, 0.55D, 0.09D);
        level.playSound(null, position.x, position.y, position.z, SoundEvents.RESPAWN_ANCHOR_DEPLETE,
                SoundSource.HOSTILE, 0.8F, 0.65F);
    }

    private static void renderAmon(ServerLevel level, AmonEntity amon, EncounterSnapshot snapshot, long tick) {
        renderPhaseTransitionCue(level, amon, snapshot, tick);
        if (tick % 3L == 0L) {
            double phase = tick * 0.16D;
            for (int layer = 0; layer < 4; layer++) {
                double angle = phase + layer * Math.PI / 2.0D;
                double radius = amon.mysterious$isSecondForm() ? 1.35D : 0.8D;
                point(level, layer % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.SOUL_FIRE_FLAME,
                        amon.getX() + Math.cos(angle) * radius,
                        amon.getY() + 0.35D + layer * 0.48D,
                        amon.getZ() + Math.sin(angle) * radius);
            }
            level.sendParticles(ParticleTypes.WITCH, amon.getX(), amon.getY() + 1.25D, amon.getZ(),
                    4, 0.55D, 0.75D, 0.55D, 0.015D);
        }
        if (amon.mysterious$isSecondForm() && tick % 2L == 0L) {
            Vec3 velocity = amon.getDeltaMovement();
            Vec3 trail = amon.position().subtract(velocity.normalize().scale(1.4D));
            level.sendParticles(ParticleTypes.END_ROD, trail.x, trail.y + 0.7D, trail.z,
                    3, 0.3D, 0.3D, 0.3D, 0.015D);
            level.sendParticles(ParticleTypes.ENCHANT, amon.getX(), amon.getY() + 1.0D, amon.getZ(),
                    5, 1.4D, 0.7D, 1.4D, 0.1D);
        }
        if (snapshot.lifecycle() == EncounterLifecycle.CROSS_DIMENSION_CHASE && tick % 4L == 0L) {
            ring(level, ParticleTypes.PORTAL, amon.position().add(0.0D, 0.25D, 0.0D), 2.0D, 14, 0.08D);
            ring(level, ParticleTypes.REVERSE_PORTAL, amon.position().add(0.0D, 1.6D, 0.0D),
                    1.25D, 10, 0.12D);
        }
        snapshot.advanced().phaseThree().ifPresent(phase -> {
            if (tick >= phase.warningAtTick() && tick < phase.transformationAtTick() && tick % 5L == 0L) {
                ring(level, ParticleTypes.ELECTRIC_SPARK, amon.position().add(0.0D, 0.1D, 0.0D),
                        2.5D + (tick % 20L) * 0.08D, 20, 0.04D);
            }
        });
    }

    /**
     * Three-second, restart-safe phase cue. It is derived from the authoritative phase start tick,
     * so it needs no client state and cannot silently disappear after a reconnect.
     */
    private static void renderPhaseTransitionCue(ServerLevel level, AmonEntity amon,
                                                  EncounterSnapshot snapshot, long tick) {
        if (snapshot.phase() == EncounterPhase.PHASE_ONE || snapshot.phaseGeneration() == 0L) return;
        long age = tick - snapshot.timers().phaseStartedAtTick();
        if (age < 0L || age >= 60L || age % 2L != 0L) return;

        Vec3 center = amon.position().add(0.0D, 0.25D, 0.0D);
        boolean phaseThree = snapshot.phase() == EncounterPhase.PHASE_THREE;
        ParticleOptions primary = phaseThree ? ParticleTypes.DRAGON_BREATH : ParticleTypes.WITCH;
        ParticleOptions secondary = phaseThree ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.ENCHANT;

        if (age < 20L) {
            // A contracting double helix makes the change readable before the impact.
            double radius = 4.5D - age * 0.16D;
            double angle = age * 0.42D;
            for (int arm = 0; arm < 4; arm++) {
                double armAngle = angle + arm * Math.PI / 2.0D;
                point(level, arm % 2 == 0 ? primary : secondary,
                        center.x + Math.cos(armAngle) * radius,
                        center.y + 0.5D + age * 0.08D,
                        center.z + Math.sin(armAngle) * radius);
            }
            ring(level, secondary, center, radius, 28, 0.045D);
            return;
        }

        if (age < 40L) {
            // The middle beat forms a tall pillar that remains obvious behind other combat particles.
            for (int height = 0; height <= 8; height++) {
                level.sendParticles(height % 2 == 0 ? ParticleTypes.ELECTRIC_SPARK : secondary,
                        center.x, center.y + height * 0.65D, center.z,
                        3, 0.2D, 0.12D, 0.2D, 0.035D);
            }
            ring(level, primary, center.add(0.0D, 2.6D, 0.0D), 2.0D, 36, 0.06D);
            if (age == 20L) {
                level.playSound(null, center.x, center.y, center.z, SoundEvents.WARDEN_SONIC_BOOM,
                        SoundSource.HOSTILE, 1.4F, phaseThree ? 0.55F : 0.9F);
            }
            return;
        }

        // Finish with two rapidly expanding shockwave rings instead of a chat notification.
        double radius = 2.0D + (age - 40L) * 0.3D;
        ring(level, ParticleTypes.ELECTRIC_SPARK, center, radius, 48, 0.09D);
        ring(level, primary, center.add(0.0D, 1.4D, 0.0D), radius * 0.75D, 40, 0.08D);
        if (age == 40L) {
            level.sendParticles(ParticleTypes.SONIC_BOOM, center.x, center.y + 1.0D, center.z,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void renderWorm(ServerLevel level, WormOfTimeEntity worm, long tick) {
        if (tick % 4L != 0L) return;
        Vec3 back = worm.position().subtract(worm.getLookAngle().scale(0.65D));
        level.sendParticles(ParticleTypes.SQUID_INK, back.x, back.y + 0.35D, back.z,
                2, 0.18D, 0.18D, 0.18D, 0.01D);
        level.sendParticles(ParticleTypes.PORTAL, worm.getX(), worm.getY() + 0.45D, worm.getZ(),
                3, 0.35D, 0.25D, 0.35D, 0.04D);
    }

    private static void renderClock(ServerLevel level, PhantomClockEntity clock, long tick) {
        if (tick % 4L != 0L) return;
        ring(level, ParticleTypes.ENCHANT, clock.position().add(0.0D, 0.55D, 0.0D), 1.15D, 8, 0.03D);
        level.sendParticles(ParticleTypes.END_ROD, clock.getX(), clock.getY() + 0.55D, clock.getZ(),
                3, 0.32D, 0.32D, 0.32D, 0.015D);
        if (clock.mysterious$expiresAtTick() - tick <= 60L) {
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, clock.getX(), clock.getY() + 0.55D, clock.getZ(),
                    5, 0.5D, 0.5D, 0.5D, 0.08D);
        }
    }

    private static void ring(ServerLevel level, ParticleOptions type, Vec3 center,
                             double radius, int points, double speed) {
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0D * index / points;
            double x = center.x + Math.cos(angle) * radius;
            double z = center.z + Math.sin(angle) * radius;
            level.sendParticles(type, x, center.y, z, 1,
                    Math.cos(angle) * 0.04D, 0.015D, Math.sin(angle) * 0.04D, speed);
        }
    }

    private static void point(ServerLevel level, ParticleOptions type, double x, double y, double z) {
        level.sendParticles(type, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }
}
