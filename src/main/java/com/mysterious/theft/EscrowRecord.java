package com.mysterious.theft;

import com.mysterious.transaction.TransactionState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One stolen physical item and the evidence needed for crash reconciliation. */
public record EscrowRecord(UUID stolenItemId, UUID encounterId, UUID playerId,
                           StolenSlotType slotType, int slotIndex,
                           SerializedItemStack item, SerializedItemStack fingerprint,
                           int beforeCount, int expectedAfterCount,
                           UUID attackEventId, TransactionState state,
                           Optional<UUID> returnTransactionId) {
    public EscrowRecord {
        Objects.requireNonNull(stolenItemId, "stolenItemId");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(slotType, "slotType");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(attackEventId, "attackEventId");
        Objects.requireNonNull(state, "state");
        returnTransactionId = Objects.requireNonNull(returnTransactionId, "returnTransactionId");
        if (slotIndex < 0 || beforeCount <= 0 || expectedAfterCount != beforeCount - 1) {
            throw new IllegalArgumentException("Invalid escrow slot/count evidence");
        }
        if ((state == TransactionState.RETURNING || state == TransactionState.RETURNED)
                != returnTransactionId.isPresent()) {
            throw new IllegalArgumentException("Return state and return transaction ID must agree");
        }
    }

    public EscrowRecord withState(TransactionState next) {
        if (next == TransactionState.RETURNING || next == TransactionState.RETURNED) {
            throw new IllegalArgumentException("Use beginReturn/completeReturn for return states");
        }
        return new EscrowRecord(stolenItemId, encounterId, playerId, slotType, slotIndex,
                item, fingerprint, beforeCount, expectedAfterCount, attackEventId, next, Optional.empty());
    }

    public EscrowRecord beginReturn(UUID transactionId) {
        if (state == TransactionState.RETURNED) {
            return this;
        }
        return new EscrowRecord(stolenItemId, encounterId, playerId, slotType, slotIndex,
                item, fingerprint, beforeCount, expectedAfterCount, attackEventId,
                TransactionState.RETURNING, Optional.of(transactionId));
    }

    public EscrowRecord completeReturn(UUID transactionId) {
        if (state == TransactionState.RETURNED) {
            return this;
        }
        if (state != TransactionState.RETURNING
                || returnTransactionId.filter(transactionId::equals).isEmpty()) {
            throw new IllegalStateException("Return transaction does not own this escrow record");
        }
        return new EscrowRecord(stolenItemId, encounterId, playerId, slotType, slotIndex,
                item, fingerprint, beforeCount, expectedAfterCount, attackEventId,
                TransactionState.RETURNED, Optional.of(transactionId));
    }
}
