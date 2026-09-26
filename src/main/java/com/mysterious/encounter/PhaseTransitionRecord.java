package com.mysterious.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/** One claimed phase transition. Its ID is the idempotency key. */
public record PhaseTransitionRecord(
        UUID transitionId,
        EncounterPhase from,
        EncounterPhase to,
        UUID carrierBossId,
        UUID recoveryBossId,
        ResourceKey<Level> recoveryDimension,
        BlockPos recoveryPosition,
        long startedAtTick
) {
    public PhaseTransitionRecord {
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(carrierBossId, "carrierBossId");
        Objects.requireNonNull(recoveryBossId, "recoveryBossId");
        Objects.requireNonNull(recoveryDimension, "recoveryDimension");
        recoveryPosition = Objects.requireNonNull(recoveryPosition, "recoveryPosition").immutable();
        if (!from.canAdvanceTo(to)) {
            throw new IllegalArgumentException("Phase transitions must advance exactly one phase");
        }
        if (startedAtTick < 0) {
            throw new IllegalArgumentException("startedAtTick must be non-negative");
        }
    }

    public PhaseTransitionRecord withCarrier(UUID carrierId) {
        return new PhaseTransitionRecord(transitionId, from, to, carrierId, recoveryBossId,
                recoveryDimension, recoveryPosition, startedAtTick);
    }
}
