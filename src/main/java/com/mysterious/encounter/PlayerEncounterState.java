package com.mysterious.encounter;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** Minimal participant state required by snapshots and restart recovery. */
public record PlayerEncounterState(
        UUID playerId,
        PlayerParticipationState participation,
        long joinedAtTick,
        long joinGraceUntilTick,
        OptionalLong retentionDeadlineTick
) {
    public PlayerEncounterState {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(participation, "participation");
        Objects.requireNonNull(retentionDeadlineTick, "retentionDeadlineTick");
        if (joinedAtTick < 0 || joinGraceUntilTick < joinedAtTick) {
            throw new IllegalArgumentException("Invalid player encounter timing");
        }
        retentionDeadlineTick.ifPresent(deadline -> {
            if (deadline < joinedAtTick) {
                throw new IllegalArgumentException("Retention deadline cannot precede join time");
            }
        });
        if (participation != PlayerParticipationState.RETENTION_PENDING
                && retentionDeadlineTick.isPresent()) {
            throw new IllegalArgumentException("Only retention-pending players may have a deadline");
        }
    }
}
