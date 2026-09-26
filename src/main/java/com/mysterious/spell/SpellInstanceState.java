package com.mysterious.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record SpellInstanceState(UUID instanceId, ResourceLocation spellId, UUID ownerBossId,
                                 long startedAtTick, long endsAtTick, int reservedEntities) {
    public SpellInstanceState {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(spellId, "spellId");
        Objects.requireNonNull(ownerBossId, "ownerBossId");
        if (startedAtTick < 0 || endsAtTick < startedAtTick || reservedEntities < 0) {
            throw new IllegalArgumentException("Invalid spell instance");
        }
    }
}
