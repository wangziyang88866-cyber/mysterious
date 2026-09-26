package com.mysterious.encounter;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

/** Persisted Stage 15 state for the wait/teleport/return cross-dimension transaction. */
public record CrossDimensionChaseState(
        UUID targetPlayerId,
        ResourceKey<Level> targetDimension,
        long requestedAtTick,
        long executeAfterTick,
        boolean bossInTargetDimension,
        boolean returnPending
) {
    public CrossDimensionChaseState {
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        Objects.requireNonNull(targetDimension, "targetDimension");
        if (requestedAtTick < 0 || executeAfterTick < requestedAtTick) {
            throw new IllegalArgumentException("Invalid cross-dimension chase timing");
        }
        if (returnPending && !bossInTargetDimension) {
            throw new IllegalArgumentException("Only a teleported boss can be return-pending");
        }
    }

    public CrossDimensionChaseState teleported() {
        return new CrossDimensionChaseState(targetPlayerId, targetDimension, requestedAtTick,
                executeAfterTick, true, false);
    }

    public CrossDimensionChaseState awaitingReturn() {
        return new CrossDimensionChaseState(targetPlayerId, targetDimension, requestedAtTick,
                executeAfterTick, true, true);
    }
}
