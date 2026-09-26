package com.mysterious.encounter;

import java.util.Optional;
import java.util.UUID;

/** Entity-side ownership link only; global state remains in EncounterController. */
public interface EncounterBoundEntity {
    Optional<UUID> mysterious$getEncounterId();

    void mysterious$bindEncounter(UUID encounterId);
}
