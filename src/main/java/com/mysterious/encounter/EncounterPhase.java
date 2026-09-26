package com.mysterious.encounter;

/** Stable phase identity. Transition progress belongs to EncounterLifecycle. */
public enum EncounterPhase {
    PHASE_ONE,
    PHASE_TWO,
    PHASE_THREE;

    public boolean canAdvanceTo(EncounterPhase target) {
        return switch (this) {
            case PHASE_ONE -> target == PHASE_TWO;
            case PHASE_TWO -> target == PHASE_THREE;
            case PHASE_THREE -> false;
        };
    }
}
