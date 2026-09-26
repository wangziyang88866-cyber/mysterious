package com.mysterious.transaction;

public enum TransactionState {
    PREPARED,
    COMMITTED,
    RETURNING,
    RETURNED,
    CANCELLED,
    CONFLICT;

    public boolean isTerminal() {
        return this == COMMITTED || this == RETURNED || this == CANCELLED || this == CONFLICT;
    }
}
