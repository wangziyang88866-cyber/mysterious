package com.mysterious.spell;

import com.mysterious.config.ReleaseCandidateBalance;

public final class EncounterEntityBudget {
    public static final int MAX_TEMPORARY_ENTITIES = ReleaseCandidateBalance.MAX_TEMPORARY_ENTITIES;
    public static final int MAX_ACTIVE_SPELL_ENTITIES = ReleaseCandidateBalance.MAX_ACTIVE_SPELL_ENTITIES;

    private EncounterEntityBudget() {
    }

    public static boolean canReserve(int currentlyReserved, int amount) {
        return amount >= 0 && currentlyReserved <= MAX_ACTIVE_SPELL_ENTITIES - amount
                && currentlyReserved <= MAX_TEMPORARY_ENTITIES - amount;
    }

    /** Shared cap used by spells and every other encounter-owned temporary entity. */
    public static boolean canReserveTemporary(int currentlyActive, int amount) {
        return amount >= 0 && currentlyActive <= MAX_TEMPORARY_ENTITIES - amount;
    }
}
