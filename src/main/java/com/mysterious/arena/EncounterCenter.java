package com.mysterious.arena;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** Immutable dimension-aware center and arena configuration for one encounter. */
public record EncounterCenter(
        ResourceKey<Level> dimension,
        BlockPos position,
        BlockPos safeAnchor,
        ArenaConfigSnapshot arena
) {
    public EncounterCenter {
        Objects.requireNonNull(dimension, "dimension");
        position = Objects.requireNonNull(position, "position").immutable();
        safeAnchor = Objects.requireNonNull(safeAnchor, "safeAnchor").immutable();
        Objects.requireNonNull(arena, "arena");
        long dx = (long) safeAnchor.getX() - position.getX();
        long dz = (long) safeAnchor.getZ() - position.getZ();
        if ((double) dx * dx + (double) dz * dz
                > (double) arena.bossHardLeashRadius() * arena.bossHardLeashRadius()
                || Math.abs((long) safeAnchor.getY() - position.getY()) > arena.verticalRadius()) {
            throw new IllegalArgumentException("Safe anchor must be inside the boss hard leash");
        }
    }

    public EncounterCenter(ResourceKey<Level> dimension, BlockPos position, ArenaConfigSnapshot arena) {
        this(dimension, position, position, arena);
    }
}
