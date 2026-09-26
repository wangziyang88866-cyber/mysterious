package com.mysterious.encounter;

/**
 * Unified finalization hook. Implementations must use termination.finalizationId
 * as their idempotency key when persistent cleanup steps are added.
 */
@FunctionalInterface
public interface EncounterCleanup {
    EncounterCleanup NO_OP = (snapshot, termination) -> { };

    void cleanup(EncounterSnapshot snapshot, EncounterTermination termination) throws Exception;
}
