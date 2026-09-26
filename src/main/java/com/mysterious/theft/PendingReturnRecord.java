package com.mysterious.theft;

import com.mysterious.transaction.TransactionState;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Global ledger entry that outlives its source encounter. */
public record PendingReturnRecord(UUID stolenItemId, UUID encounterId, UUID playerId,
                                  StolenSlotType slotType, int slotIndex,
                                  SerializedItemStack item, TransactionState state,
                                  Optional<UUID> returnTransactionId) {
    public PendingReturnRecord {
        Objects.requireNonNull(stolenItemId, "stolenItemId");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(slotType, "slotType");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(state, "state");
        returnTransactionId = Objects.requireNonNull(returnTransactionId, "returnTransactionId");
        if (slotIndex < 0 || (state != TransactionState.COMMITTED
                && state != TransactionState.RETURNING && state != TransactionState.RETURNED)) {
            throw new IllegalArgumentException("Invalid pending return record");
        }
        if ((state == TransactionState.RETURNING || state == TransactionState.RETURNED)
                != returnTransactionId.isPresent()) {
            throw new IllegalArgumentException("Return state and transaction ID must agree");
        }
    }

    public static PendingReturnRecord from(EscrowRecord record) {
        return new PendingReturnRecord(record.stolenItemId(), record.encounterId(), record.playerId(),
                record.slotType(), record.slotIndex(), record.item(), TransactionState.COMMITTED, Optional.empty());
    }

    public PendingReturnRecord beginReturn(UUID transactionId) {
        return state == TransactionState.RETURNED ? this : new PendingReturnRecord(
                stolenItemId, encounterId, playerId, slotType, slotIndex, item,
                TransactionState.RETURNING, Optional.of(transactionId));
    }

    public PendingReturnRecord completeReturn(UUID transactionId) {
        if (state == TransactionState.RETURNED) {
            return this;
        }
        if (state != TransactionState.RETURNING
                || returnTransactionId.filter(transactionId::equals).isEmpty()) {
            throw new IllegalStateException("Return transaction does not own this pending record");
        }
        return new PendingReturnRecord(stolenItemId, encounterId, playerId, slotType, slotIndex,
                item, TransactionState.RETURNED, Optional.of(transactionId));
    }
}
