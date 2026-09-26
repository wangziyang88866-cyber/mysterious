package com.mysterious.encounter;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for one logical finalization, reused by cleanup retries. */
public record EncounterTermination(UUID finalizationId, EndReason reason, long requestedAtTick) {
    public EncounterTermination {
        Objects.requireNonNull(finalizationId, "finalizationId");
        Objects.requireNonNull(reason, "reason");
        if (requestedAtTick < 0) {
            throw new IllegalArgumentException("requestedAtTick must be non-negative");
        }
    }
}
