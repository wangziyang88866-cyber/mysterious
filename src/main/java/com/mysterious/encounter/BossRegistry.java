package com.mysterious.encounter;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable only through EncounterController; snapshots are immutable. */
final class BossRegistry {
    private final Map<UUID, BossRecord> records = new LinkedHashMap<>();

    BossRegistrationResult register(BossRecord record) {
        Objects.requireNonNull(record, "record");
        BossRecord existing = records.putIfAbsent(record.entityId(), record);
        if (existing == null) {
            return BossRegistrationResult.REGISTERED;
        }
        if (!existing.equals(record)) {
            throw new IllegalStateException("Boss UUID is already registered with different state");
        }
        return BossRegistrationResult.ALREADY_REGISTERED;
    }

    boolean markLoaded(UUID entityId, EncounterPhase phase, ResourceKey<Level> dimension, BlockPos position, long tick) {
        BossRecord record = require(entityId);
        if (record.lifecycle().isTerminal()) {
            return false;
        }
        BossRecord updated = record.observe(phase, dimension, position, tick, BossLifecycleState.LOADED);
        records.put(entityId, updated);
        return !record.equals(updated);
    }

    boolean markUnloaded(UUID entityId, long tick) {
        return changeNonTerminalLifecycle(entityId, BossLifecycleState.UNLOADED, tick);
    }

    boolean markMissingPending(UUID entityId, long tick) {
        return changeNonTerminalLifecycle(entityId, BossLifecycleState.MISSING_PENDING, tick);
    }

    boolean markDead(UUID entityId, long tick) {
        BossRecord record = require(entityId);
        if (record.lifecycle() == BossLifecycleState.REMOVED || record.lifecycle() == BossLifecycleState.DEAD) {
            return false;
        }
        records.put(entityId, record.changeLifecycle(BossLifecycleState.DEAD, tick));
        return true;
    }

    boolean markRemoved(UUID entityId, long tick) {
        BossRecord record = require(entityId);
        if (record.lifecycle() == BossLifecycleState.REMOVED
                || record.lifecycle() == BossLifecycleState.DEAD) {
            return false;
        }
        records.put(entityId, record.changeLifecycle(BossLifecycleState.REMOVED, tick));
        return true;
    }

    void assignTransitionCarrier(UUID entityId, UUID transitionId) {
        BossRecord target = require(entityId);
        if (!target.isAlive()) {
            throw new IllegalStateException("Transition carrier must be alive");
        }
        boolean conflict = records.values().stream()
                .anyMatch(record -> !record.entityId().equals(entityId)
                        && record.carrierTransitionId().filter(transitionId::equals).isPresent());
        if (conflict) {
            throw new IllegalStateException("A phase transition can have only one carrier");
        }
        if (target.carrierTransitionId().isPresent()
                && !target.carrierTransitionId().orElseThrow().equals(transitionId)) {
            throw new IllegalStateException("Boss is already carrying another phase transition");
        }
        records.put(entityId, target.assignCarrier(transitionId));
    }

    void rearmRecoveryCarrier(BossRecord recovery, UUID transitionId) {
        BossRecord existing = require(recovery.entityId());
        if (!existing.lifecycle().isTerminal()) {
            throw new IllegalStateException("Recovery carrier is already alive");
        }
        records.put(recovery.entityId(), recovery.assignCarrier(transitionId));
    }

    void commitTransition(UUID transitionId, EncounterPhase phase) {
        boolean carrierFound = false;
        for (Map.Entry<UUID, BossRecord> entry : records.entrySet()) {
            BossRecord record = entry.getValue();
            if (record.carrierTransitionId().filter(transitionId::equals).isPresent()) {
                carrierFound = true;
            }
            entry.setValue(record.commitPhase(phase));
        }
        if (!carrierFound) {
            throw new IllegalStateException("Phase transition carrier disappeared before commit");
        }
    }

    void cancelTransition(UUID transitionId) {
        for (Map.Entry<UUID, BossRecord> entry : records.entrySet()) {
            BossRecord record = entry.getValue();
            if (record.carrierTransitionId().filter(transitionId::equals).isPresent()) {
                entry.setValue(record.clearCarrier(transitionId));
            }
        }
    }

    boolean hasLivingCarrier(UUID transitionId, UUID entityId) {
        BossRecord record = records.get(entityId);
        return record != null && record.isAlive()
                && record.carrierTransitionId().filter(transitionId::equals).isPresent();
    }

    int livingCount() {
        return (int) records.values().stream().filter(BossRecord::isAlive).count();
    }

    Map<UUID, BossRecord> snapshot() {
        return Map.copyOf(records);
    }

    void restore(Map<UUID, BossRecord> restored) {
        if (!records.isEmpty()) {
            throw new IllegalStateException("Boss registry is already populated");
        }
        records.putAll(restored);
    }

    private boolean changeNonTerminalLifecycle(UUID entityId, BossLifecycleState lifecycle, long tick) {
        BossRecord record = require(entityId);
        if (record.lifecycle().isTerminal()) {
            return false;
        }
        BossRecord updated = record.changeLifecycle(lifecycle, tick);
        records.put(entityId, updated);
        return !record.equals(updated);
    }

    private BossRecord require(UUID entityId) {
        BossRecord record = records.get(Objects.requireNonNull(entityId, "entityId"));
        if (record == null) {
            throw new IllegalArgumentException("Unknown boss " + entityId);
        }
        return record;
    }
}
