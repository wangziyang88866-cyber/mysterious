package com.mysterious.phase;

import com.mysterious.transaction.SplitTransaction;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persisted P1 coordinator state. The scanner has no gameplay privileges. */
public record PhaseOneState(boolean splitConsumed, Optional<UUID> scannerBossId,
                            long nextScanTick, Optional<SplitTransaction> splitTransaction) {
    public PhaseOneState {
        scannerBossId = Objects.requireNonNull(scannerBossId, "scannerBossId");
        splitTransaction = Objects.requireNonNull(splitTransaction, "splitTransaction");
        if (nextScanTick < 0) {
            throw new IllegalArgumentException("nextScanTick must be non-negative");
        }
        if (splitConsumed && splitTransaction.filter(transaction ->
                transaction.state() != com.mysterious.transaction.TransactionState.COMMITTED).isPresent()) {
            throw new IllegalArgumentException("Consumed split must be committed");
        }
    }

    public static PhaseOneState initial(long createdAtTick) {
        return new PhaseOneState(false, Optional.empty(), saturatedAdd(createdAtTick, 200L), Optional.empty());
    }

    private static long saturatedAdd(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }
}
