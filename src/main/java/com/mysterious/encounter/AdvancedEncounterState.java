package com.mysterious.encounter;

import com.mysterious.phase.PhaseThreeState;
import com.mysterious.spell.SpellRuntimeState;

import java.util.Objects;
import java.util.Optional;

public record AdvancedEncounterState(SpellRuntimeState spells, Optional<PhaseThreeState> phaseThree,
                                     Optional<CrossDimensionChaseState> crossDimensionChase) {
    public AdvancedEncounterState {
        Objects.requireNonNull(spells, "spells");
        phaseThree = Objects.requireNonNull(phaseThree, "phaseThree");
        crossDimensionChase = Objects.requireNonNull(crossDimensionChase, "crossDimensionChase");
    }

    public AdvancedEncounterState(SpellRuntimeState spells, Optional<PhaseThreeState> phaseThree) {
        this(spells, phaseThree, Optional.empty());
    }

    public static AdvancedEncounterState initial(long tick) {
        return new AdvancedEncounterState(SpellRuntimeState.initial(tick), Optional.empty(), Optional.empty());
    }
}
