package com.mysterious.encounter;

/** Every encounter-ending path is normalized to one of these reasons. */
public enum EndReason {
    VICTORY(EncounterLifecycle.ENDED_VICTORY),
    ABANDONED(EncounterLifecycle.ENDED_ABANDONED),
    ERROR_RECOVERY(EncounterLifecycle.ENDED_ERROR_RECOVERY);

    private final EncounterLifecycle terminalLifecycle;

    EndReason(EncounterLifecycle terminalLifecycle) {
        this.terminalLifecycle = terminalLifecycle;
    }

    public EncounterLifecycle terminalLifecycle() {
        return terminalLifecycle;
    }
}
