package com.mysterious.encounter;

/** Durable state of the unified cleanup entry point. */
public enum CleanupState {
    NOT_REQUESTED,
    PENDING,
    IN_PROGRESS,
    COMMITTED
}
