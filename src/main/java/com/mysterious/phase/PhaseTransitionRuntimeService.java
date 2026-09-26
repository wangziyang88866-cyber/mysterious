package com.mysterious.phase;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.BossLifecycleState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PhaseTransitionRecord;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.registry.ModEntities;
import com.mysterious.theft.StolenAttributeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** Stage 8 generic transition driver and P1-to-P2 policy. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class PhaseTransitionRuntimeService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private PhaseTransitionRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) {
            return;
        }
        long tick = server.overworld().getGameTime();
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.lifecycle() == EncounterLifecycle.ACTIVE
                    && snapshot.phase() == EncounterPhase.PHASE_ONE
                    && shouldStartPhaseTwoAfterDeaths(snapshot)) {
                startRecoveryTransition(controller, snapshot, server, tick, EncounterPhase.PHASE_TWO);
            } else if (shouldStartPhaseThreeAfterDeath(snapshot)) {
                startRecoveryTransition(controller, snapshot, server, tick, EncounterPhase.PHASE_THREE);
            }
            EncounterSnapshot current = controller.requireEncounter(snapshot.encounterId());
            if (current.lifecycle() == EncounterLifecycle.PHASE_TRANSITION) {
                reconcileCarrier(controller, current, server, tick);
            }
        }
    }

    /** P1 ends only after every incarnation has really died; no survivor is made invulnerable. */
    public static boolean shouldStartPhaseTwoAfterDeaths(EncounterSnapshot snapshot) {
        return snapshot.lifecycle() == EncounterLifecycle.ACTIVE
                && snapshot.phase() == EncounterPhase.PHASE_ONE
                && !snapshot.bosses().isEmpty()
                && snapshot.livingBossCount() == 0;
    }

    /** Also recovers a save made after the P2 death commit but before the P3 carrier was created. */
    public static boolean shouldStartPhaseThreeAfterDeath(EncounterSnapshot snapshot) {
        return snapshot.lifecycle() == EncounterLifecycle.ACTIVE
                && snapshot.phase() == EncounterPhase.PHASE_TWO
                && !snapshot.bosses().isEmpty()
                && snapshot.livingBossCount() == 0;
    }

    private static void startRecoveryTransition(EncounterController controller,
                                                EncounterSnapshot snapshot, MinecraftServer server, long tick,
                                                EncounterPhase targetPhase) {
        UUID transitionId = UUID.randomUUID();
        BossRecord fallback = snapshot.bosses().values().stream()
                .max(Comparator.comparingLong(BossRecord::lastConfirmedTick)
                        .thenComparing(record -> record.entityId().toString()))
                .orElseThrow();
        UUID recoveryId = UUID.randomUUID();
        controller.beginPhaseTransitionWithRecoveryCarrier(snapshot.encounterId(), transitionId,
                recoveryId, fallback.lastKnownDimension(), fallback.lastKnownPosition(),
                targetPhase, tick);
        discardNonCarrierBodies(snapshot, server, recoveryId);
    }

    private static void reconcileCarrier(EncounterController controller, EncounterSnapshot snapshot,
                                         MinecraftServer server, long tick) {
        PhaseTransitionRecord transition = snapshot.activeTransition().orElseThrow();
        BossRecord carrier = snapshot.bosses().get(transition.carrierBossId());
        if (carrier == null || !carrier.isAlive()) {
            return;
        }
        Entity entity = findEntity(server, carrier.entityId());
        if (entity == null && carrier.entityId().equals(transition.recoveryBossId())
                && carrier.lifecycle() == BossLifecycleState.MISSING_PENDING) {
            ServerLevel level = server.getLevel(transition.recoveryDimension());
            if (level == null) {
                return;
            }
            AmonEntity created = Optional.ofNullable(ModEntities.AMON.get().create(level))
                    .orElseThrow(() -> new IllegalStateException("Unable to create phase recovery Amon"));
            created.setUUID(carrier.entityId());
            created.mysterious$bindEncounter(snapshot.encounterId());
            created.moveTo(transition.recoveryPosition(), 0.0F, 0.0F);
            if (!level.addFreshEntity(created)) {
                throw new IllegalStateException("Unable to add phase recovery Amon " + carrier.entityId());
            }
            controller.markBossLoaded(snapshot.encounterId(), carrier.entityId(), level.dimension(),
                    created.blockPosition(), tick);
            entity = created;
            LOGGER.info("[phase] recovery-carrier-spawned encounterId={} transitionId={} carrier={} tick={}",
                    snapshot.encounterId(), transition.transitionId(), carrier.entityId(), tick);
        }
        if (!(entity instanceof AmonEntity amon)) {
            return;
        }
        prepareCarrierForPhase(amon, transition.to());
        amon.mysterious$playSummonAnimation();
        StolenAttributeManager.rebuild(snapshot.encounterId(), server);
        controller.commitPhaseTransition(snapshot.encounterId(), transition.transitionId(), tick);
        if (amon.level() instanceof ServerLevel level) {
            if (transition.to() == EncounterPhase.PHASE_TWO) {
                EncounterParticleRuntimeService.phaseTwoEntryBurst(level, amon.position());
            } else if (transition.to() == EncounterPhase.PHASE_THREE) {
                EncounterParticleRuntimeService.phaseThreeEntryBurst(level, amon.position());
            }
        }
    }

    /** Restores the transition carrier before committing its next phase. */
    public static void prepareCarrierForPhase(AmonEntity amon, EncounterPhase targetPhase) {
        if (targetPhase != EncounterPhase.PHASE_TWO && targetPhase != EncounterPhase.PHASE_THREE) {
            throw new IllegalArgumentException("Unsupported carrier target phase: " + targetPhase);
        }
        // Recovery transitions create a fresh incarnation; every new phase starts at full health.
        amon.setHealth(amon.getMaxHealth());
        // P3 opens as a damageable mobile ground fighter; flight begins only with second form.
        amon.mysterious$setSecondForm(false);
    }

    private static void discardNonCarrierBodies(EncounterSnapshot snapshot, MinecraftServer server,
                                                UUID carrierId) {
        snapshot.bosses().keySet().stream().filter(id -> !id.equals(carrierId)).forEach(id -> {
            Entity entity = findEntity(server, id);
            if (entity instanceof AmonEntity amon) {
                amon.discard();
            }
        });
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null && !entity.isRemoved()) {
                return entity;
            }
        }
        return null;
    }
}
