package com.mysterious.phase;

import com.mysterious.encounter.EncounterPhase;

import java.util.Objects;
import java.util.UUID;

/** Capability captured by phase-owned work and invalidated when a transition begins. */
public record PhaseTaskToken(UUID encounterId, EncounterPhase phase, long generation) {
    public PhaseTaskToken {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(phase, "phase");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }
}
