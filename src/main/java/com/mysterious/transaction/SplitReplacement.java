package com.mysterious.transaction;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

public record SplitReplacement(UUID sourceEntityId, UUID replacementBossId,
                               ResourceKey<Level> dimension, BlockPos position) {
    public SplitReplacement {
        Objects.requireNonNull(sourceEntityId, "sourceEntityId");
        Objects.requireNonNull(replacementBossId, "replacementBossId");
        Objects.requireNonNull(dimension, "dimension");
        position = Objects.requireNonNull(position, "position").immutable();
    }
}
