package com.mysterious.encounter;

import com.mysterious.arena.EncounterCenter;
import com.mysterious.combat.EncounterCombatState;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.phase.PhaseOneState;
import com.mysterious.theft.EscrowRecord;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable view used by later persistence, networking and debug layers. */
public record EncounterSnapshot(
        UUID encounterId,
        RealmOwner owner,
        EncounterCenter center,
        long createdAtTick,
        long revision,
        EncounterLifecycle lifecycle,
        EncounterPhase initialPhase,
        EncounterPhase phase,
        long phaseGeneration,
        Map<UUID, BossRecord> bosses,
        Map<UUID, PlayerEncounterState> players,
        EncounterTimerState timers,
        EncounterCombatState combat,
        PhaseOneState phaseOne,
        AdvancedEncounterState advanced,
        Map<UUID, EscrowRecord> escrow,
        Optional<PhaseTransitionRecord> activeTransition,
        Optional<UUID> lastCommittedTransitionId,
        Optional<EncounterTermination> termination,
        CleanupState cleanupState
) {
    public EncounterSnapshot {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(center, "center");
        if (createdAtTick < 0 || revision < 0 || phaseGeneration < 0) {
            throw new IllegalArgumentException("Encounter times and revision must be non-negative");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(initialPhase, "initialPhase");
        Objects.requireNonNull(phase, "phase");
        bosses = Map.copyOf(Objects.requireNonNull(bosses, "bosses"));
        players = Map.copyOf(Objects.requireNonNull(players, "players"));
        Objects.requireNonNull(timers, "timers");
        Objects.requireNonNull(combat, "combat");
        Objects.requireNonNull(phaseOne, "phaseOne");
        Objects.requireNonNull(advanced, "advanced");
        escrow = Map.copyOf(Objects.requireNonNull(escrow, "escrow"));
        activeTransition = Objects.requireNonNull(activeTransition, "activeTransition");
        lastCommittedTransitionId = Objects.requireNonNull(lastCommittedTransitionId, "lastCommittedTransitionId");
        termination = Objects.requireNonNull(termination, "termination");
        Objects.requireNonNull(cleanupState, "cleanupState");
        bosses.forEach((id, record) -> {
            if (!id.equals(record.entityId()) || record.phase() != phase
                    || record.lastConfirmedTick() < createdAtTick) {
                throw new IllegalArgumentException("Boss registry key/phase does not match encounter state");
            }
        });
        players.forEach((id, state) -> {
            if (!id.equals(state.playerId()) || state.joinedAtTick() < createdAtTick) {
                throw new IllegalArgumentException("Player registry key/time does not match encounter state");
            }
        });
        escrow.forEach((id, record) -> {
            if (!id.equals(record.stolenItemId()) || !encounterId.equals(record.encounterId())) {
                throw new IllegalArgumentException("Escrow key/encounter does not match snapshot");
            }
        });
        if (timers.phaseStartedAtTick() < createdAtTick) {
            throw new IllegalArgumentException("Phase timer cannot precede encounter creation");
        }
        if (phase.ordinal() < initialPhase.ordinal()) {
            throw new IllegalArgumentException("Encounter phase cannot precede its initial phase");
        }
        if ((lifecycle == EncounterLifecycle.ABANDONED_PENDING) != timers.abandonedSinceTick().isPresent()) {
            throw new IllegalArgumentException("Abandoned lifecycle and timer must agree");
        }
        timers.abandonedSinceTick().ifPresent(value -> {
            if (value < createdAtTick) {
                throw new IllegalArgumentException("Abandoned timer cannot precede encounter creation");
            }
        });
        timers.crossDimensionCooldownUntilTick().ifPresent(value -> {
            if (value < createdAtTick) {
                throw new IllegalArgumentException("Cross-dimension timer cannot precede encounter creation");
            }
        });
        if ((lifecycle == EncounterLifecycle.PHASE_TRANSITION) != activeTransition.isPresent()) {
            throw new IllegalArgumentException("Transition lifecycle and metadata must agree");
        }
        if (lifecycle == EncounterLifecycle.CROSS_DIMENSION_CHASE
                && advanced.crossDimensionChase().filter(CrossDimensionChaseState::bossInTargetDimension).isEmpty()) {
            throw new IllegalArgumentException("Cross-dimension lifecycle requires teleported chase metadata");
        }
        activeTransition.ifPresent(transition -> {
            if (transition.from() != phase || !transition.from().canAdvanceTo(transition.to())
                    || transition.startedAtTick() < timers.phaseStartedAtTick()) {
                throw new IllegalArgumentException("Transition metadata does not match current phase/timing");
            }
        });
        long carrierCount = bosses.values().stream().filter(BossRecord::isTransitionCarrier).count();
        if (activeTransition.isEmpty() && carrierCount != 0) {
            throw new IllegalArgumentException("Boss carrier exists without an active transition");
        }
        if (activeTransition.isPresent()) {
            PhaseTransitionRecord transition = activeTransition.orElseThrow();
            BossRecord carrier = bosses.get(transition.carrierBossId());
            if (carrierCount != 1 || carrier == null || !carrier.isAlive()
                    || carrier.carrierTransitionId().filter(transition.transitionId()::equals).isEmpty()) {
                throw new IllegalArgumentException("Active transition must have exactly one living carrier");
            }
        }
        if (lifecycle.isTerminal() != termination.isPresent()) {
            throw new IllegalArgumentException("Terminal lifecycle and termination metadata must agree");
        }
        termination.ifPresent(value -> {
            if (value.requestedAtTick() < createdAtTick) {
                throw new IllegalArgumentException("Termination cannot precede encounter creation");
            }
        });
        if (lifecycle.isTerminal() == (cleanupState == CleanupState.NOT_REQUESTED)) {
            throw new IllegalArgumentException("Cleanup state does not match lifecycle");
        }
        if (cleanupState == CleanupState.COMMITTED && !lifecycle.isTerminal()) {
            throw new IllegalArgumentException("Committed cleanup requires a terminal lifecycle");
        }
    }

    public int livingBossCount() {
        return (int) bosses.values().stream().filter(BossRecord::isAlive).count();
    }
}
