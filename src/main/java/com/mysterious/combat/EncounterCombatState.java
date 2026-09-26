package com.mysterious.combat;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persisted shared threat table and target selection state. */
public record EncounterCombatState(Map<UUID, ThreatEntry> threats, Optional<UUID> currentTargetId,
                                   long lastThreatDecayTick) {
    public EncounterCombatState {
        threats = Map.copyOf(Objects.requireNonNull(threats, "threats"));
        currentTargetId = Objects.requireNonNull(currentTargetId, "currentTargetId");
        if (lastThreatDecayTick < 0) {
            throw new IllegalArgumentException("lastThreatDecayTick must be non-negative");
        }
    }

    public static EncounterCombatState initial(long tick) {
        return new EncounterCombatState(Map.of(), Optional.empty(), tick);
    }
}
