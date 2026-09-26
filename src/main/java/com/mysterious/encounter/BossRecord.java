package com.mysterious.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete registry record; entity lookup success is never used as a death signal. */
public record BossRecord(
        UUID entityId,
        EncounterPhase phase,
        ResourceKey<Level> lastKnownDimension,
        BlockPos lastKnownPosition,
        long lastConfirmedTick,
        BossLifecycleState lifecycle,
        Optional<UUID> carrierTransitionId
) {
    public BossRecord {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(lastKnownDimension, "lastKnownDimension");
        lastKnownPosition = Objects.requireNonNull(lastKnownPosition, "lastKnownPosition").immutable();
        Objects.requireNonNull(lifecycle, "lifecycle");
        carrierTransitionId = Objects.requireNonNull(carrierTransitionId, "carrierTransitionId");
        if (lastConfirmedTick < 0) {
            throw new IllegalArgumentException("lastConfirmedTick must be non-negative");
        }
        if (lifecycle.isTerminal() && carrierTransitionId.isPresent()) {
            throw new IllegalArgumentException("A terminal boss cannot be a transition carrier");
        }
    }

    public static BossRecord loaded(
            UUID entityId,
            EncounterPhase phase,
            ResourceKey<Level> dimension,
            BlockPos position,
            long confirmedTick
    ) {
        return new BossRecord(entityId, phase, dimension, position, confirmedTick,
                BossLifecycleState.LOADED, Optional.empty());
    }

    public boolean isAlive() {
        return !lifecycle.isTerminal();
    }

    public boolean isTransitionCarrier() {
        return carrierTransitionId.isPresent();
    }

    BossRecord observe(
            EncounterPhase newPhase,
            ResourceKey<Level> dimension,
            BlockPos position,
            long confirmedTick,
            BossLifecycleState newLifecycle
    ) {
        requireNonDecreasingTick(confirmedTick);
        return new BossRecord(entityId, newPhase, dimension, position, confirmedTick,
                newLifecycle, carrierTransitionId);
    }

    BossRecord changeLifecycle(BossLifecycleState newLifecycle, long confirmedTick) {
        requireNonDecreasingTick(confirmedTick);
        Optional<UUID> carrier = newLifecycle.isTerminal() ? Optional.empty() : carrierTransitionId;
        return new BossRecord(entityId, phase, lastKnownDimension, lastKnownPosition,
                confirmedTick, newLifecycle, carrier);
    }

    BossRecord assignCarrier(UUID transitionId) {
        if (lifecycle.isTerminal()) {
            throw new IllegalStateException("A terminal boss cannot carry a phase transition");
        }
        return new BossRecord(entityId, phase, lastKnownDimension, lastKnownPosition,
                lastConfirmedTick, lifecycle, Optional.of(transitionId));
    }

    BossRecord clearCarrier(UUID transitionId) {
        if (carrierTransitionId.isEmpty()) {
            return this;
        }
        if (!carrierTransitionId.get().equals(transitionId)) {
            throw new IllegalStateException("Boss belongs to a different phase transition");
        }
        return new BossRecord(entityId, phase, lastKnownDimension, lastKnownPosition,
                lastConfirmedTick, lifecycle, Optional.empty());
    }

    BossRecord commitPhase(EncounterPhase committedPhase) {
        return new BossRecord(entityId, committedPhase, lastKnownDimension, lastKnownPosition,
                lastConfirmedTick, lifecycle, Optional.empty());
    }

    private void requireNonDecreasingTick(long tick) {
        if (tick < lastConfirmedTick) {
            throw new IllegalArgumentException("Boss observations cannot move backwards in server time");
        }
    }
}
