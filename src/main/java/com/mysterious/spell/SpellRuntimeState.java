package com.mysterious.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SpellRuntimeState(Map<ResourceLocation, Long> cooldownUntil,
                                Map<UUID, SpellInstanceState> instances,
                                Optional<UUID> activeCastInstanceId,
                                long nextCastTick,
                                long nextEnvironmentTick,
                                int reservedTemporaryEntities) {
    public SpellRuntimeState {
        cooldownUntil = Map.copyOf(Objects.requireNonNull(cooldownUntil, "cooldownUntil"));
        instances = Map.copyOf(Objects.requireNonNull(instances, "instances"));
        activeCastInstanceId = Objects.requireNonNull(activeCastInstanceId, "activeCastInstanceId");
        if (nextCastTick < 0 || nextEnvironmentTick < 0 || reservedTemporaryEntities < 0) {
            throw new IllegalArgumentException("Invalid spell runtime state");
        }
        if (activeCastInstanceId.isPresent() && !instances.containsKey(activeCastInstanceId.orElseThrow())) {
            throw new IllegalArgumentException("Active cast is not tracked");
        }
    }

    public static SpellRuntimeState initial(long tick) {
        return new SpellRuntimeState(Map.of(), Map.of(), Optional.empty(), tick, tick, 0);
    }
}
