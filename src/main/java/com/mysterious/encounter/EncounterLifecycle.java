package com.mysterious.encounter;

/** Server-authoritative lifecycle for one Amon encounter. */
public enum EncounterLifecycle {
    ACTIVE(false),
    ABANDONED_PENDING(false),
    PHASE_TRANSITION(false),
    CROSS_DIMENSION_CHASE(false),
    ENDED_VICTORY(true),
    ENDED_ABANDONED(true),
    ENDED_ERROR_RECOVERY(true);

    private final boolean terminal;

    EncounterLifecycle(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
