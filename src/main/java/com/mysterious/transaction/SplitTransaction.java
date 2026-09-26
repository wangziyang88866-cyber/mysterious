package com.mysterious.transaction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable plan for the one permitted P1 replacement batch. */
public record SplitTransaction(UUID transactionId, UUID scannerBossId,
                               List<SplitReplacement> replacements,
                               TransactionState state, long preparedAtTick) {
    public SplitTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(scannerBossId, "scannerBossId");
        replacements = List.copyOf(Objects.requireNonNull(replacements, "replacements"));
        Objects.requireNonNull(state, "state");
        if (replacements.isEmpty() || replacements.size() > 10 || preparedAtTick < 0) {
            throw new IllegalArgumentException("Invalid split transaction");
        }
        long distinctSources = replacements.stream().map(SplitReplacement::sourceEntityId).distinct().count();
        long distinctResults = replacements.stream().map(SplitReplacement::replacementBossId).distinct().count();
        if (distinctSources != replacements.size() || distinctResults != replacements.size()) {
            throw new IllegalArgumentException("Split replacements must have unique source/result IDs");
        }
    }

    public SplitTransaction withState(TransactionState next) {
        return new SplitTransaction(transactionId, scannerBossId, replacements, next, preparedAtTick);
    }
}
