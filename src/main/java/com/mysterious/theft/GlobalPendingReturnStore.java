package com.mysterious.theft;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-global durable ledger for returns whose encounter may already be finalized. */
public final class GlobalPendingReturnStore {
    private final Map<UUID, PendingReturnRecord> records = new LinkedHashMap<>();
    private Runnable mutationListener = () -> { };
    private Runnable durabilityBarrier = () -> { };

    public synchronized void restore(Map<UUID, PendingReturnRecord> restored) {
        if (!records.isEmpty()) {
            throw new IllegalStateException("Pending return store is already populated");
        }
        records.putAll(Objects.requireNonNull(restored, "restored"));
    }

    public synchronized Map<UUID, PendingReturnRecord> snapshot() {
        return Map.copyOf(records);
    }

    public synchronized void setMutationListener(Runnable listener) {
        mutationListener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized void setDurabilityBarrier(Runnable barrier) {
        durabilityBarrier = Objects.requireNonNull(barrier, "barrier");
    }

    /** Idempotently transfers ownership from encounter escrow before encounter deletion. */
    public synchronized void transfer(EscrowRecord escrow) {
        PendingReturnRecord candidate = PendingReturnRecord.from(escrow);
        PendingReturnRecord previous = records.putIfAbsent(candidate.stolenItemId(), candidate);
        if (previous != null && !previous.equals(candidate)) {
            throw new IllegalStateException("Pending return ID is owned by different item data");
        }
        if (previous == null) {
            mutationListener.run();
            durabilityBarrier.run();
        }
    }

    public synchronized PendingReturnRecord beginReturn(UUID stolenItemId, UUID transactionId) {
        PendingReturnRecord current = require(stolenItemId);
        PendingReturnRecord updated = current.beginReturn(transactionId);
        if (!updated.equals(current)) {
            records.put(stolenItemId, updated);
            mutationListener.run();
            durabilityBarrier.run();
        }
        return updated;
    }

    public synchronized void completeReturn(UUID stolenItemId, UUID transactionId) {
        PendingReturnRecord current = require(stolenItemId);
        PendingReturnRecord updated = current.completeReturn(transactionId);
        if (!updated.equals(current)) {
            records.put(stolenItemId, updated);
            mutationListener.run();
            durabilityBarrier.run();
        }
    }

    public synchronized Map<UUID, PendingReturnRecord> forPlayer(UUID playerId) {
        Map<UUID, PendingReturnRecord> result = new LinkedHashMap<>();
        records.forEach((id, record) -> {
            if (record.playerId().equals(playerId)) {
                result.put(id, record);
            }
        });
        return Map.copyOf(result);
    }

    private PendingReturnRecord require(UUID id) {
        PendingReturnRecord record = records.get(Objects.requireNonNull(id, "id"));
        if (record == null) {
            throw new IllegalArgumentException("Unknown pending return " + id);
        }
        return record;
    }
}
