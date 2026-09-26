package com.mysterious.encounter;

import com.mojang.logging.LogUtils;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.combat.EncounterCombatState;
import com.mysterious.combat.ThreatEntry;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.phase.PhaseOneState;
import com.mysterious.phase.PhaseTaskToken;
import com.mysterious.phase.PhaseThreeState;
import com.mysterious.spell.SpellCastManager;
import com.mysterious.theft.EscrowRecord;
import com.mysterious.transaction.SplitReplacement;
import com.mysterious.transaction.SplitTransaction;
import com.mysterious.transaction.TransactionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;

/**
 * The only mutation boundary for global encounter state. Entities may report
 * observations, but they never decide phases, deaths or cleanup themselves.
 */
public final class EncounterController {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<UUID, MutableEncounter> encounters = new LinkedHashMap<>();
    private final EncounterCleanup cleanup;
    private Runnable mutationListener = () -> { };
    private Runnable durabilityBarrier = () -> { };
    private boolean mutationEnabled = true;

    public EncounterController() {
        this(EncounterCleanup.NO_OP);
    }

    public EncounterController(EncounterCleanup cleanup) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public synchronized EncounterSnapshot createEncounter(
            UUID encounterId,
            RealmOwner owner,
            EncounterCenter center,
            EncounterPhase initialPhase,
            long createdAtTick
    ) {
        requireMutationEnabled();
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(initialPhase, "initialPhase");
        if (createdAtTick < 0) {
            throw new IllegalArgumentException("createdAtTick must be non-negative");
        }

        MutableEncounter existing = encounters.get(encounterId);
        if (existing != null) {
            if (!existing.owner.equals(owner)
                    || !existing.center.equals(center)
                    || existing.createdAtTick != createdAtTick
                    || existing.initialPhase != initialPhase) {
                throw new IllegalStateException("Encounter ID is already owned by different encounter data");
            }
            return existing.snapshot();
        }

        MutableEncounter created = new MutableEncounter(encounterId, owner, center, initialPhase, createdAtTick);
        encounters.put(encounterId, created);
        changed();
        LOGGER.info("[encounter] created encounterId={} owner={} phase={} tick={}",
                encounterId, owner.id(), initialPhase, createdAtTick);
        return created.snapshot();
    }

    public synchronized void restoreEncounter(EncounterSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MutableEncounter existing = encounters.get(snapshot.encounterId());
        if (existing != null) {
            if (!existing.snapshot().equals(snapshot)) {
                throw new IllegalStateException("Restored encounter conflicts with live state " + snapshot.encounterId());
            }
            return;
        }
        encounters.put(snapshot.encounterId(), new MutableEncounter(snapshot));
        LOGGER.info("[recovery] restored encounterId={} lifecycle={} phase={} revision={}",
                snapshot.encounterId(), snapshot.lifecycle(), snapshot.phase(), snapshot.revision());
    }

    public synchronized Map<UUID, EncounterSnapshot> snapshots() {
        Map<UUID, EncounterSnapshot> result = new LinkedHashMap<>();
        encounters.forEach((id, encounter) -> result.put(id, encounter.snapshot()));
        return Map.copyOf(result);
    }

    public synchronized void setMutationListener(Runnable listener) {
        mutationListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized void setDurabilityBarrier(Runnable barrier) {
        durabilityBarrier = Objects.requireNonNull(barrier, "barrier");
    }

    public synchronized void setMutationEnabled(boolean enabled) {
        mutationEnabled = enabled;
    }

    public synchronized Optional<EncounterSnapshot> findEncounter(UUID encounterId) {
        MutableEncounter encounter = encounters.get(Objects.requireNonNull(encounterId, "encounterId"));
        return encounter == null ? Optional.empty() : Optional.of(encounter.snapshot());
    }

    public synchronized EncounterSnapshot requireEncounter(UUID encounterId) {
        return requireMutable(encounterId).snapshot();
    }

    /** Removes a fully cleaned terminal record so an operator can reset stale encounter state. */
    public synchronized boolean purgeEncounter(UUID encounterId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireMutable(encounterId);
        if (!encounter.lifecycle.isTerminal() || encounter.cleanupState != CleanupState.COMMITTED) {
            throw new IllegalStateException("Encounter must finish cleanup before it can be purged");
        }
        encounters.remove(encounterId);
        changed();
        return true;
    }

    public synchronized BossRegistrationResult registerBoss(UUID encounterId, BossRecord record) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.lifecycle == EncounterLifecycle.PHASE_TRANSITION) {
            throw new IllegalStateException("Boss registration is closed during a phase transition");
        }
        if (record.phase() != encounter.phase) {
            throw new IllegalArgumentException("Boss phase must match its encounter phase");
        }
        if (record.lastConfirmedTick() < encounter.createdAtTick) {
            throw new IllegalArgumentException("Boss observation cannot precede encounter creation");
        }
        if (encounter.phase == EncounterPhase.PHASE_ONE && encounter.registry.livingCount() >= 10
                && !encounter.registry.snapshot().containsKey(record.entityId())) {
            throw new IllegalStateException("Phase one cannot exceed ten registered living bosses");
        }
        BossRegistrationResult result = encounter.registry.register(record);
        if (result == BossRegistrationResult.REGISTERED) {
            encounter.bumpRevision();
            changed();
            LOGGER.info("[registry] registered encounterId={} bossId={} phase={} tick={}",
                    encounterId, record.entityId(), record.phase(), record.lastConfirmedTick());
        }
        return result;
    }

    public synchronized void markBossLoaded(
            UUID encounterId,
            UUID entityId,
            ResourceKey<Level> dimension,
            BlockPos position,
            long tick
    ) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.registry.markLoaded(entityId, encounter.phase, dimension, position, tick)) {
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void markBossUnloaded(UUID encounterId, UUID entityId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.registry.markUnloaded(entityId, tick)) {
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void markBossMissingPending(UUID encounterId, UUID entityId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.registry.markMissingPending(entityId, tick)) {
            encounter.bumpRevision();
            changed();
            LOGGER.warn("[registry] missing-pending encounterId={} bossId={} tick={}", encounterId, entityId, tick);
        }
    }

    public synchronized void markBossDead(UUID encounterId, UUID entityId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.registry.markDead(entityId, tick)) {
            recoverTransitionIfCarrierTerminated(encounter, entityId, "dead", tick);
            encounter.bumpRevision();
            changed();
            LOGGER.info("[registry] death committed encounterId={} bossId={} phase={} tick={}",
                    encounterId, entityId, encounter.phase, tick);
        }
    }

    public synchronized void markBossRemoved(UUID encounterId, UUID entityId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireMutable(encounterId);
        if (encounter.registry.markRemoved(entityId, tick)) {
            recoverTransitionIfCarrierTerminated(encounter, entityId, "removed", tick);
            encounter.bumpRevision();
            changed();
            LOGGER.info("[registry] removal committed encounterId={} bossId={} phase={} tick={}",
                    encounterId, entityId, encounter.phase, tick);
        }
    }

    public synchronized void upsertPlayerState(UUID encounterId, PlayerEncounterState playerState) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (playerState.joinedAtTick() < encounter.createdAtTick) {
            throw new IllegalArgumentException("Player join cannot precede encounter creation");
        }
        PlayerEncounterState previous = encounter.players.put(playerState.playerId(), playerState);
        if (!playerState.equals(previous)) {
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void joinPlayer(UUID encounterId, UUID playerId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        Objects.requireNonNull(playerId, "playerId");
        long graceUntil = saturatedAdd(tick, encounter.center.arena().joinGraceTicks());
        PlayerEncounterState state = new PlayerEncounterState(playerId, PlayerParticipationState.ACTIVE,
                tick, graceUntil, OptionalLong.empty());
        PlayerEncounterState previous = encounter.players.put(playerId, state);
        if (!state.equals(previous)) {
            encounter.bumpRevision();
            changed();
            LOGGER.info("[participation] joined encounterId={} player={} phase={} tick={} graceUntil={} revision={}",
                    encounterId, playerId, encounter.phase, tick, graceUntil, encounter.revision);
        }
    }

    public synchronized void markPlayerRetentionPending(UUID encounterId, UUID playerId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        PlayerEncounterState previous = requirePlayer(encounter, playerId);
        if (previous.participation() == PlayerParticipationState.LEFT
                || previous.participation() == PlayerParticipationState.RETENTION_PENDING) {
            return;
        }
        PlayerEncounterState state = new PlayerEncounterState(playerId,
                PlayerParticipationState.RETENTION_PENDING, previous.joinedAtTick(),
                previous.joinGraceUntilTick(), OptionalLong.of(saturatedAdd(
                tick, encounter.center.arena().retentionGraceTicks())));
        encounter.players.put(playerId, state);
        encounter.bumpRevision();
        changed();
    }

    public synchronized void markPlayerActive(UUID encounterId, UUID playerId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        PlayerEncounterState previous = requirePlayer(encounter, playerId);
        if (previous.participation() == PlayerParticipationState.ACTIVE) {
            return;
        }
        if (previous.participation() == PlayerParticipationState.LEFT) {
            throw new IllegalStateException("A left player must re-enter join bounds before rejoining");
        }
        encounter.players.put(playerId, new PlayerEncounterState(playerId, PlayerParticipationState.ACTIVE,
                previous.joinedAtTick(), previous.joinGraceUntilTick(), OptionalLong.empty()));
        encounter.bumpRevision();
        changed();
    }

    public synchronized void markPlayerLeft(UUID encounterId, UUID playerId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        PlayerEncounterState previous = requirePlayer(encounter, playerId);
        if (previous.participation() == PlayerParticipationState.LEFT) {
            return;
        }
        encounter.players.put(playerId, new PlayerEncounterState(playerId, PlayerParticipationState.LEFT,
                previous.joinedAtTick(), Math.min(previous.joinGraceUntilTick(), tick), OptionalLong.empty()));
        encounter.bumpRevision();
        changed();
        LOGGER.info("[participation] left encounterId={} player={} phase={} tick={} revision={}",
                encounterId, playerId, encounter.phase, tick, encounter.revision);
    }

    public synchronized void endJoinGrace(UUID encounterId, UUID playerId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        PlayerEncounterState previous = requirePlayer(encounter, playerId);
        long graceUntil = Math.max(previous.joinedAtTick(), Math.min(previous.joinGraceUntilTick(), tick));
        if (graceUntil == previous.joinGraceUntilTick()) {
            return;
        }
        encounter.players.put(playerId, new PlayerEncounterState(playerId, previous.participation(),
                previous.joinedAtTick(), graceUntil, previous.retentionDeadlineTick()));
        encounter.bumpRevision();
        changed();
    }

    public synchronized void updateTimers(UUID encounterId, EncounterTimerState timers) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (timers.phaseStartedAtTick() < encounter.createdAtTick) {
            throw new IllegalArgumentException("Phase timer cannot precede encounter creation");
        }
        timers.abandonedSinceTick().ifPresent(value -> requireAtOrAfterCreation(encounter, value));
        timers.crossDimensionCooldownUntilTick().ifPresent(value -> requireAtOrAfterCreation(encounter, value));
        if (!encounter.timers.equals(timers)) {
            encounter.timers = Objects.requireNonNull(timers, "timers");
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void recordThreat(UUID encounterId, UUID playerId, double amount, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            return;
        }
        PlayerEncounterState player = requirePlayer(encounter, playerId);
        if (player.participation() == PlayerParticipationState.LEFT) {
            return;
        }
        Map<UUID, ThreatEntry> threats = new LinkedHashMap<>(encounter.combat.threats());
        ThreatEntry previous = threats.getOrDefault(playerId, new ThreatEntry(0.0D, tick));
        threats.put(playerId, previous.add(amount, tick));
        // The first real hit acquires a stable target immediately; the periodic threat pass can later switch it
        // only through the configured hysteresis rule.
        Optional<UUID> target = encounter.combat.currentTargetId().isPresent()
                ? encounter.combat.currentTargetId() : Optional.of(playerId);
        encounter.combat = new EncounterCombatState(threats, target,
                encounter.combat.lastThreatDecayTick());
        encounter.bumpRevision();
        changed();
    }

    public synchronized void updateThreatSelection(UUID encounterId, Map<UUID, ThreatEntry> threats,
                                                   Optional<UUID> currentTargetId, long decayTick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, decayTick);
        EncounterCombatState updated = new EncounterCombatState(threats, currentTargetId, decayTick);
        if (!updated.equals(encounter.combat)) {
            encounter.combat = updated;
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void assignPhaseOneScanner(UUID encounterId, UUID bossId, long nextScanTick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.phase != EncounterPhase.PHASE_ONE || encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            throw new IllegalStateException("Split scanner requires active phase one");
        }
        BossRecord boss = encounter.registry.snapshot().get(Objects.requireNonNull(bossId, "bossId"));
        if (boss == null || !boss.isAlive()) {
            throw new IllegalArgumentException("Split scanner must be a living registered boss");
        }
        PhaseOneState updated = new PhaseOneState(encounter.phaseOne.splitConsumed(), Optional.of(bossId),
                nextScanTick, encounter.phaseOne.splitTransaction());
        if (!updated.equals(encounter.phaseOne)) {
            encounter.phaseOne = updated;
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized SplitTransaction prepareSplit(UUID encounterId, UUID transactionId, UUID scannerBossId,
                                                      List<SplitReplacement> replacements, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.phase != EncounterPhase.PHASE_ONE || encounter.lifecycle != EncounterLifecycle.ACTIVE
                || encounter.phaseOne.splitConsumed()) {
            throw new IllegalStateException("Encounter is not accepting a phase-one split");
        }
        if (encounter.phaseOne.splitTransaction().isPresent()) {
            SplitTransaction current = encounter.phaseOne.splitTransaction().orElseThrow();
            if (current.transactionId().equals(transactionId)) {
                return current;
            }
            throw new IllegalStateException("Another split transaction already exists");
        }
        if (encounter.phaseOne.scannerBossId().filter(scannerBossId::equals).isEmpty()) {
            throw new IllegalArgumentException("Only the assigned scanner can prepare a split");
        }
        int capacity = 10 - encounter.registry.livingCount();
        if (replacements.isEmpty() || replacements.size() > capacity) {
            throw new IllegalArgumentException("Split plan exceeds the ten-boss budget");
        }
        SplitTransaction prepared = new SplitTransaction(transactionId, scannerBossId, replacements,
                TransactionState.PREPARED, tick);
        encounter.phaseOne = new PhaseOneState(false, Optional.of(scannerBossId), encounter.phaseOne.nextScanTick(),
                Optional.of(prepared));
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[split] prepared encounterId={} transactionId={} scanner={} planned={} tick={}",
                encounterId, transactionId, scannerBossId, replacements.size(), tick);
        return prepared;
    }

    public synchronized void commitSplit(UUID encounterId, UUID transactionId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        SplitTransaction transaction = encounter.phaseOne.splitTransaction()
                .orElseThrow(() -> new IllegalStateException("No split transaction exists"));
        if (!transaction.transactionId().equals(transactionId)) {
            throw new IllegalArgumentException("Split transaction ID does not match");
        }
        if (transaction.state() == TransactionState.COMMITTED) {
            return;
        }
        if (transaction.state() != TransactionState.PREPARED) {
            throw new IllegalStateException("Only a prepared split can commit");
        }
        boolean complete = transaction.replacements().stream().allMatch(result -> {
            BossRecord record = encounter.registry.snapshot().get(result.replacementBossId());
            return record != null && record.isAlive();
        });
        if (!complete) {
            throw new IllegalStateException("Split results are not fully registered");
        }
        SplitTransaction committed = transaction.withState(TransactionState.COMMITTED);
        encounter.phaseOne = new PhaseOneState(true, encounter.phaseOne.scannerBossId(),
                encounter.phaseOne.nextScanTick(), Optional.of(committed));
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[split] committed encounterId={} transactionId={} spawned={}",
                encounterId, transactionId, transaction.replacements().size());
    }

    public synchronized EscrowRecord prepareEscrow(UUID encounterId, EscrowRecord record) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (!encounterId.equals(record.encounterId()) || record.state() != TransactionState.PREPARED) {
            throw new IllegalArgumentException("Escrow record must be PREPARED for this encounter");
        }
        PlayerEncounterState owner = requirePlayer(encounter, record.playerId());
        if (owner.participation() == PlayerParticipationState.LEFT) {
            throw new IllegalStateException("Cannot steal from a player who left the encounter");
        }
        EscrowRecord duplicateEvent = encounter.escrow.values().stream()
                .filter(existing -> existing.attackEventId().equals(record.attackEventId()))
                .findFirst().orElse(null);
        if (duplicateEvent != null) {
            return duplicateEvent;
        }
        EscrowRecord previous = encounter.escrow.putIfAbsent(record.stolenItemId(), record);
        if (previous != null) {
            if (!previous.equals(record)) {
                throw new IllegalStateException("Escrow ID is already owned by different data");
            }
            return previous;
        }
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[escrow] prepared encounterId={} stolenItemId={} player={} attackEvent={}",
                encounterId, record.stolenItemId(), record.playerId(), record.attackEventId());
        return record;
    }

    public synchronized EscrowRecord setEscrowState(UUID encounterId, UUID stolenItemId,
                                                    TransactionState expected, TransactionState next) {
        requireMutationEnabled();
        MutableEncounter encounter = requireMutable(encounterId);
        EscrowRecord current = requireEscrow(encounter, stolenItemId);
        if (current.state() == next) {
            return current;
        }
        if (current.state() != expected) {
            throw new IllegalStateException("Escrow state is " + current.state() + ", expected " + expected);
        }
        EscrowRecord updated = current.withState(next);
        encounter.escrow.put(stolenItemId, updated);
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        return updated;
    }

    public synchronized EscrowRecord beginEscrowReturn(UUID encounterId, UUID stolenItemId, UUID returnId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireMutable(encounterId);
        EscrowRecord current = requireEscrow(encounter, stolenItemId);
        if (current.state() == TransactionState.RETURNED) {
            return current;
        }
        if (current.state() != TransactionState.COMMITTED && current.state() != TransactionState.RETURNING) {
            throw new IllegalStateException("Only committed escrow can be returned");
        }
        if (current.state() == TransactionState.RETURNING
                && current.returnTransactionId().filter(returnId::equals).isEmpty()) {
            throw new IllegalStateException("Another return transaction owns this record");
        }
        EscrowRecord updated = current.state() == TransactionState.RETURNING ? current : current.beginReturn(returnId);
        if (!updated.equals(current)) {
            encounter.escrow.put(stolenItemId, updated);
            encounter.bumpRevision();
            changed();
            durabilityBarrier.run();
        }
        return updated;
    }

    public synchronized void completeEscrowReturn(UUID encounterId, UUID stolenItemId, UUID returnId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireMutable(encounterId);
        EscrowRecord current = requireEscrow(encounter, stolenItemId);
        EscrowRecord updated = current.completeReturn(returnId);
        if (!updated.equals(current)) {
            encounter.escrow.put(stolenItemId, updated);
            encounter.bumpRevision();
            changed();
            durabilityBarrier.run();
        }
    }

    public synchronized void markAbandonedPending(UUID encounterId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        requireAtOrAfterCreation(encounter, tick);
        if (encounter.lifecycle == EncounterLifecycle.ACTIVE) {
            encounter.lifecycle = EncounterLifecycle.ABANDONED_PENDING;
            encounter.timers = new EncounterTimerState(encounter.timers.phaseStartedAtTick(),
                    java.util.OptionalLong.of(tick),
                    encounter.timers.crossDimensionCooldownUntilTick());
            encounter.bumpRevision();
            changed();
            return;
        }
        if (encounter.lifecycle != EncounterLifecycle.ABANDONED_PENDING) {
            throw new IllegalStateException("Only an active encounter can become abandoned-pending");
        }
    }

    public synchronized void beginCrossDimensionChase(UUID encounterId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.lifecycle != EncounterLifecycle.ACTIVE
                && encounter.lifecycle != EncounterLifecycle.CROSS_DIMENSION_CHASE) {
            throw new IllegalStateException("Cross-dimension chase requires an active encounter");
        }
        if (encounter.lifecycle == EncounterLifecycle.ACTIVE) {
            encounter.lifecycle = EncounterLifecycle.CROSS_DIMENSION_CHASE;
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized void resumeActive(UUID encounterId) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.lifecycle != EncounterLifecycle.ABANDONED_PENDING
                && encounter.lifecycle != EncounterLifecycle.CROSS_DIMENSION_CHASE
                && encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            throw new IllegalStateException("Encounter cannot resume from " + encounter.lifecycle);
        }
        if (encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            encounter.lifecycle = EncounterLifecycle.ACTIVE;
            encounter.timers = new EncounterTimerState(encounter.timers.phaseStartedAtTick(),
                    java.util.OptionalLong.empty(), encounter.timers.crossDimensionCooldownUntilTick());
            encounter.bumpRevision();
            changed();
        }
    }

    public synchronized PhaseTransitionResult beginPhaseTransition(
            UUID encounterId,
            UUID transitionId,
            UUID carrierBossId,
            EncounterPhase targetPhase,
            long tick
    ) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(carrierBossId, "carrierBossId");
        Objects.requireNonNull(targetPhase, "targetPhase");

        if (encounter.lastCommittedTransitionId.filter(transitionId::equals).isPresent()) {
            return PhaseTransitionResult.ALREADY_COMMITTED;
        }
        if (encounter.activeTransition.isPresent()) {
            PhaseTransitionRecord active = encounter.activeTransition.orElseThrow();
            if (active.transitionId().equals(transitionId)
                    && active.carrierBossId().equals(carrierBossId)
                    && active.to() == targetPhase) {
                return PhaseTransitionResult.ALREADY_STARTED;
            }
            throw new IllegalStateException("Another phase transition is already active");
        }
        if (encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            throw new IllegalStateException("Phase transition requires an active encounter");
        }
        if (!encounter.phase.canAdvanceTo(targetPhase)) {
            throw new IllegalArgumentException("Illegal phase transition " + encounter.phase + " -> " + targetPhase);
        }
        if (tick < encounter.timers.phaseStartedAtTick()) {
            throw new IllegalArgumentException("Transition cannot precede the current phase");
        }

        BossRecord carrier = encounter.registry.snapshot().get(carrierBossId);
        if (carrier == null || !carrier.isAlive()) {
            throw new IllegalArgumentException("Transition carrier must be a living registered boss");
        }
        UUID recoveryBossId = recoveryBossId(transitionId);
        PhaseTransitionRecord transition = new PhaseTransitionRecord(
                transitionId, encounter.phase, targetPhase, carrierBossId, recoveryBossId,
                carrier.lastKnownDimension(), carrier.lastKnownPosition(), tick);
        cancelOldPhaseWork(encounter);
        encounter.registry.assignTransitionCarrier(carrierBossId, transitionId);
        encounter.activeTransition = Optional.of(transition);
        encounter.lifecycle = EncounterLifecycle.PHASE_TRANSITION;
        encounter.phaseGeneration++;
        encounter.advanced = new AdvancedEncounterState(
                SpellCastManager.interruptAll(encounter.advanced.spells(), tick), encounter.advanced.phaseThree(),
                Optional.empty());
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[phase] claimed encounterId={} transitionId={} carrierBossId={} from={} to={} tick={}",
                encounterId, transitionId, carrierBossId, transition.from(), transition.to(), tick);
        return PhaseTransitionResult.STARTED;
    }

    /** Atomically starts a transition whose only legal carrier must be recreated. */
    public synchronized PhaseTransitionResult beginPhaseTransitionWithRecoveryCarrier(
            UUID encounterId, UUID transitionId, UUID recoveryBossId,
            ResourceKey<Level> dimension, BlockPos position, EncounterPhase targetPhase, long tick
    ) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(recoveryBossId, "recoveryBossId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(targetPhase, "targetPhase");
        if (encounter.lastCommittedTransitionId.filter(transitionId::equals).isPresent()) {
            return PhaseTransitionResult.ALREADY_COMMITTED;
        }
        if (encounter.activeTransition.isPresent()) {
            PhaseTransitionRecord active = encounter.activeTransition.orElseThrow();
            if (active.transitionId().equals(transitionId)) {
                return PhaseTransitionResult.ALREADY_STARTED;
            }
            throw new IllegalStateException("Another phase transition is already active");
        }
        if (encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            throw new IllegalStateException("Recovery transition requires an active encounter");
        }
        if (!encounter.phase.canAdvanceTo(targetPhase)) {
            throw new IllegalArgumentException("Illegal phase transition " + encounter.phase + " -> " + targetPhase);
        }
        if (tick < encounter.timers.phaseStartedAtTick()) {
            throw new IllegalArgumentException("Transition cannot precede the current phase");
        }
        BossRecord placeholder = new BossRecord(recoveryBossId, encounter.phase, dimension, position, tick,
                BossLifecycleState.MISSING_PENDING, Optional.empty());
        encounter.registry.register(placeholder);
        PhaseTransitionRecord transition = new PhaseTransitionRecord(transitionId, encounter.phase, targetPhase,
                recoveryBossId, recoveryBossId, dimension, position, tick);
        cancelOldPhaseWork(encounter);
        encounter.registry.assignTransitionCarrier(recoveryBossId, transitionId);
        encounter.activeTransition = Optional.of(transition);
        encounter.lifecycle = EncounterLifecycle.PHASE_TRANSITION;
        encounter.phaseGeneration++;
        encounter.advanced = new AdvancedEncounterState(
                SpellCastManager.interruptAll(encounter.advanced.spells(), tick), encounter.advanced.phaseThree(),
                Optional.empty());
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[phase] recovery-claimed encounterId={} transitionId={} carrierBossId={} from={} to={} tick={}",
                encounterId, transitionId, recoveryBossId, transition.from(), transition.to(), tick);
        return PhaseTransitionResult.STARTED;
    }

    public synchronized PhaseTransitionResult commitPhaseTransition(UUID encounterId, UUID transitionId, long tick) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        Objects.requireNonNull(transitionId, "transitionId");
        if (encounter.lastCommittedTransitionId.filter(transitionId::equals).isPresent()) {
            return PhaseTransitionResult.ALREADY_COMMITTED;
        }

        PhaseTransitionRecord transition = encounter.activeTransition
                .orElseThrow(() -> new IllegalStateException("No phase transition is active"));
        if (!transition.transitionId().equals(transitionId)) {
            throw new IllegalArgumentException("Transition ID does not match the active transition");
        }
        if (tick < transition.startedAtTick()) {
            throw new IllegalArgumentException("Transition commit cannot precede transition start");
        }
        if (!encounter.registry.hasLivingCarrier(transitionId, transition.carrierBossId())) {
            throw new IllegalStateException("The phase transition has no living carrier");
        }

        encounter.registry.commitTransition(transitionId, transition.to());
        encounter.phase = transition.to();
        if (transition.to() == EncounterPhase.PHASE_THREE && encounter.advanced.phaseThree().isEmpty()) {
            encounter.advanced = new AdvancedEncounterState(encounter.advanced.spells(),
                    Optional.of(PhaseThreeState.initial(tick)), Optional.empty());
        }
        encounter.activeTransition = Optional.empty();
        encounter.lastCommittedTransitionId = Optional.of(transitionId);
        encounter.lifecycle = EncounterLifecycle.ACTIVE;
        encounter.timers = new EncounterTimerState(
                Math.max(encounter.createdAtTick, tick),
                java.util.OptionalLong.empty(), encounter.timers.crossDimensionCooldownUntilTick());
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.info("[phase] committed encounterId={} transitionId={} phase={} revision={}",
                encounterId, transitionId, encounter.phase, encounter.revision);
        return PhaseTransitionResult.COMMITTED;
    }

    /** Explicit recovery-policy rollback. It never creates another transition implicitly. */
    public synchronized PhaseTransitionResult rollbackPhaseTransition(
            UUID encounterId, UUID transitionId, long tick, String reason
    ) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        PhaseTransitionRecord transition = encounter.activeTransition.orElse(null);
        if (transition == null) {
            return PhaseTransitionResult.ROLLED_BACK;
        }
        if (!transition.transitionId().equals(transitionId)) {
            throw new IllegalArgumentException("Transition ID does not match the active transition");
        }
        if (tick < transition.startedAtTick()) {
            throw new IllegalArgumentException("Rollback cannot precede transition start");
        }
        encounter.registry.cancelTransition(transitionId);
        encounter.activeTransition = Optional.empty();
        encounter.lifecycle = EncounterLifecycle.ACTIVE;
        encounter.phaseGeneration++;
        encounter.bumpRevision();
        changed();
        durabilityBarrier.run();
        LOGGER.warn("[phase] safe-rollback encounterId={} transitionId={} reason={} tick={}",
                encounterId, transitionId, reason, tick);
        return PhaseTransitionResult.ROLLED_BACK;
    }

    public synchronized PhaseTaskToken capturePhaseTaskToken(UUID encounterId) {
        MutableEncounter encounter = requireRunning(encounterId);
        if (encounter.lifecycle != EncounterLifecycle.ACTIVE) {
            throw new IllegalStateException("Phase work may only be captured while active");
        }
        return new PhaseTaskToken(encounterId, encounter.phase, encounter.phaseGeneration);
    }

    public synchronized boolean isPhaseTaskTokenCurrent(PhaseTaskToken token) {
        Objects.requireNonNull(token, "token");
        MutableEncounter encounter = encounters.get(token.encounterId());
        return encounter != null && encounter.lifecycle == EncounterLifecycle.ACTIVE
                && encounter.phase == token.phase() && encounter.phaseGeneration == token.generation();
    }

    public synchronized void updateAdvancedState(UUID encounterId, AdvancedEncounterState state) {
        requireMutationEnabled();
        MutableEncounter encounter = requireRunning(encounterId);
        if (!encounter.advanced.equals(state)) {
            encounter.advanced = Objects.requireNonNull(state, "state");
            encounter.bumpRevision();
            changed();
        }
    }

    public EncounterFinalizationResult finalizeEncounter(UUID encounterId, EndReason reason, long tick) {
        EncounterSnapshot cleanupSnapshot;
        EncounterTermination termination;

        synchronized (this) {
            requireMutationEnabled();
            MutableEncounter encounter = requireMutable(encounterId);
            Objects.requireNonNull(reason, "reason");
            if (tick < 0) {
                throw new IllegalArgumentException("tick must be non-negative");
            }
            if (tick < encounter.createdAtTick) {
                throw new IllegalArgumentException("Finalization cannot precede encounter creation");
            }
            if (encounter.cleanupState == CleanupState.COMMITTED) {
                return EncounterFinalizationResult.ALREADY_COMMITTED;
            }
            if (encounter.cleanupState == CleanupState.IN_PROGRESS) {
                return EncounterFinalizationResult.CLEANUP_IN_PROGRESS;
            }
            if (encounter.termination.isEmpty()) {
                encounter.activeTransition.ifPresent(transition ->
                        encounter.registry.cancelTransition(transition.transitionId()));
                encounter.activeTransition = Optional.empty();
                encounter.timers = new EncounterTimerState(encounter.timers.phaseStartedAtTick(),
                        OptionalLong.empty(), encounter.timers.crossDimensionCooldownUntilTick());
                encounter.advanced = new AdvancedEncounterState(encounter.advanced.spells(),
                        encounter.advanced.phaseThree(), Optional.empty());
                encounter.termination = Optional.of(new EncounterTermination(UUID.randomUUID(), reason, tick));
                encounter.lifecycle = reason.terminalLifecycle();
                encounter.bumpRevision();
            }
            encounter.cleanupState = CleanupState.IN_PROGRESS;
            encounter.bumpRevision();
            changed();
            try {
                durabilityBarrier.run();
            } catch (RuntimeException exception) {
                encounter.cleanupState = CleanupState.PENDING;
                encounter.lifecycle = EncounterLifecycle.ENDED_ERROR_RECOVERY;
                EncounterTermination failed = encounter.termination.orElseThrow();
                encounter.termination = Optional.of(new EncounterTermination(
                        failed.finalizationId(), EndReason.ERROR_RECOVERY, failed.requestedAtTick()));
                encounter.bumpRevision();
                changed();
                LOGGER.error("[finalize] durability barrier failed encounterId={} finalizationId={} tick={}",
                        encounterId, failed.finalizationId(), tick, exception);
                throw new EncounterFinalizationException(
                        "Encounter cleanup journal could not be persisted for " + encounterId, exception);
            }
            termination = encounter.termination.orElseThrow();
            cleanupSnapshot = encounter.snapshot();
        }

        try {
            cleanup.cleanup(cleanupSnapshot, termination);
        } catch (Exception exception) {
            synchronized (this) {
                MutableEncounter encounter = requireMutable(encounterId);
                encounter.cleanupState = CleanupState.PENDING;
                encounter.lifecycle = EncounterLifecycle.ENDED_ERROR_RECOVERY;
                EncounterTermination failed = encounter.termination.orElseThrow();
                encounter.termination = Optional.of(new EncounterTermination(
                        failed.finalizationId(), EndReason.ERROR_RECOVERY, failed.requestedAtTick()));
                encounter.bumpRevision();
                changed();
                LOGGER.error("[finalize] cleanup failed encounterId={} finalizationId={} tick={}",
                        encounterId, failed.finalizationId(), tick, exception);
            }
            throw new EncounterFinalizationException("Encounter cleanup failed for " + encounterId, exception);
        }

        synchronized (this) {
            MutableEncounter encounter = requireMutable(encounterId);
            encounter.cleanupState = CleanupState.COMMITTED;
            encounter.bumpRevision();
            changed();
            LOGGER.info("[finalize] committed encounterId={} finalizationId={} reason={} tick={} revision={}",
                    encounterId, termination.finalizationId(), termination.reason(), tick, encounter.revision);
        }
        return EncounterFinalizationResult.COMMITTED;
    }

    private MutableEncounter requireRunning(UUID encounterId) {
        MutableEncounter encounter = requireMutable(encounterId);
        if (encounter.lifecycle.isTerminal()) {
            throw new IllegalStateException("Encounter has already ended");
        }
        return encounter;
    }

    private MutableEncounter requireMutable(UUID encounterId) {
        MutableEncounter encounter = encounters.get(Objects.requireNonNull(encounterId, "encounterId"));
        if (encounter == null) {
            throw new IllegalArgumentException("Unknown encounter " + encounterId);
        }
        return encounter;
    }

    private static PlayerEncounterState requirePlayer(MutableEncounter encounter, UUID playerId) {
        PlayerEncounterState state = encounter.players.get(Objects.requireNonNull(playerId, "playerId"));
        if (state == null) {
            throw new IllegalArgumentException("Unknown encounter player " + playerId);
        }
        return state;
    }

    private static EscrowRecord requireEscrow(MutableEncounter encounter, UUID stolenItemId) {
        EscrowRecord record = encounter.escrow.get(Objects.requireNonNull(stolenItemId, "stolenItemId"));
        if (record == null) {
            throw new IllegalArgumentException("Unknown escrow record " + stolenItemId);
        }
        return record;
    }

    private static void recoverTransitionIfCarrierTerminated(MutableEncounter encounter, UUID entityId,
                                                              String result, long tick) {
        if (encounter.activeTransition.isEmpty()
                || !encounter.activeTransition.orElseThrow().carrierBossId().equals(entityId)) {
            return;
        }
        PhaseTransitionRecord transition = encounter.activeTransition.orElseThrow();
        BossRecord recovery = new BossRecord(transition.recoveryBossId(), encounter.phase,
                transition.recoveryDimension(), transition.recoveryPosition(), tick,
                BossLifecycleState.MISSING_PENDING, Optional.empty());
        if (entityId.equals(transition.recoveryBossId())) {
            encounter.registry.rearmRecoveryCarrier(recovery, transition.transitionId());
        } else {
            encounter.registry.register(recovery);
            encounter.registry.assignTransitionCarrier(recovery.entityId(), transition.transitionId());
        }
        encounter.activeTransition = Optional.of(transition.withCarrier(recovery.entityId()));
        LOGGER.warn("[phase] recovery-carrier-selected encounterId={} transitionId={} oldCarrier={} "
                        + "recoveryCarrier={} result={} tick={}", encounter.encounterId, transition.transitionId(),
                entityId, recovery.entityId(), result, tick);
    }

    private static UUID recoveryBossId(UUID transitionId) {
        return UUID.nameUUIDFromBytes(("mysterious:phase-recovery:" + transitionId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void cancelOldPhaseWork(MutableEncounter encounter) {
        if (encounter.phase == EncounterPhase.PHASE_ONE
                && encounter.phaseOne.splitTransaction().filter(split -> split.state() == TransactionState.PREPARED)
                .isPresent()) {
            SplitTransaction cancelled = encounter.phaseOne.splitTransaction().orElseThrow()
                    .withState(TransactionState.CANCELLED);
            encounter.phaseOne = new PhaseOneState(false, encounter.phaseOne.scannerBossId(),
                    encounter.phaseOne.nextScanTick(), Optional.of(cancelled));
        }
    }

    private static long saturatedAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }

    private void changed() {
        mutationListener.run();
    }

    private void requireMutationEnabled() {
        if (!mutationEnabled) {
            throw new IllegalStateException("Encounter mutation is disabled while persistence is in safe mode");
        }
    }

    private static void requireAtOrAfterCreation(MutableEncounter encounter, long tick) {
        if (tick < encounter.createdAtTick) {
            throw new IllegalArgumentException("Encounter event cannot precede encounter creation");
        }
    }

    private static final class MutableEncounter {
        private final UUID encounterId;
        private final RealmOwner owner;
        private final EncounterCenter center;
        private final long createdAtTick;
        private final EncounterPhase initialPhase;
        private final BossRegistry registry = new BossRegistry();
        private final Map<UUID, PlayerEncounterState> players = new LinkedHashMap<>();
        private final Map<UUID, EscrowRecord> escrow = new LinkedHashMap<>();
        private EncounterLifecycle lifecycle = EncounterLifecycle.ACTIVE;
        private EncounterPhase phase;
        private long phaseGeneration;
        private EncounterTimerState timers;
        private EncounterCombatState combat;
        private PhaseOneState phaseOne;
        private AdvancedEncounterState advanced;
        private long revision;
        private Optional<PhaseTransitionRecord> activeTransition = Optional.empty();
        private Optional<UUID> lastCommittedTransitionId = Optional.empty();
        private Optional<EncounterTermination> termination = Optional.empty();
        private CleanupState cleanupState = CleanupState.NOT_REQUESTED;

        private MutableEncounter(
                UUID encounterId,
                RealmOwner owner,
                EncounterCenter center,
                EncounterPhase phase,
                long createdAtTick
        ) {
            this.encounterId = encounterId;
            this.owner = owner;
            this.center = center;
            this.phase = phase;
            this.initialPhase = phase;
            this.createdAtTick = createdAtTick;
            this.timers = EncounterTimerState.initial(createdAtTick);
            this.combat = EncounterCombatState.initial(createdAtTick);
            this.phaseOne = PhaseOneState.initial(createdAtTick);
            this.advanced = AdvancedEncounterState.initial(createdAtTick);
        }

        private MutableEncounter(EncounterSnapshot snapshot) {
            this.encounterId = snapshot.encounterId();
            this.owner = snapshot.owner();
            this.center = snapshot.center();
            this.createdAtTick = snapshot.createdAtTick();
            this.initialPhase = snapshot.initialPhase();
            this.lifecycle = snapshot.lifecycle();
            if (snapshot.initialPhase().ordinal() > snapshot.phase().ordinal()) {
                throw new IllegalArgumentException("Initial phase cannot be after current phase");
            }
            this.phase = snapshot.phase();
            this.phaseGeneration = snapshot.phaseGeneration();
            this.players.putAll(snapshot.players());
            this.timers = snapshot.timers();
            this.combat = snapshot.combat();
            this.phaseOne = snapshot.phaseOne();
            this.advanced = snapshot.advanced();
            this.escrow.putAll(snapshot.escrow());
            this.revision = snapshot.revision();
            this.activeTransition = snapshot.activeTransition();
            this.lastCommittedTransitionId = snapshot.lastCommittedTransitionId();
            this.termination = snapshot.termination();
            this.cleanupState = snapshot.cleanupState();
            this.registry.restore(snapshot.bosses());
        }

        private void bumpRevision() {
            revision++;
        }

        private EncounterSnapshot snapshot() {
            return new EncounterSnapshot(encounterId, owner, center, createdAtTick, revision, lifecycle,
                    initialPhase, phase, phaseGeneration,
                    registry.snapshot(), players, timers, combat, phaseOne, advanced, escrow,
                    activeTransition, lastCommittedTransitionId,
                    termination, cleanupState);
        }
    }
}
