package com.mysterious.network;

import com.mysterious.encounter.BossLifecycleState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record ClientBossView(
        UUID entityId,
        BossLifecycleState state,
        ResourceLocation dimension,
        BlockPos lastKnownPosition,
        long lastConfirmedTick
) {
    public ClientBossView {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(lastKnownPosition, "lastKnownPosition");
        if (lastConfirmedTick < 0) {
            throw new IllegalArgumentException("lastConfirmedTick must be non-negative");
        }
    }

    static ClientBossView read(RegistryFriendlyByteBuf buffer) {
        return new ClientBossView(buffer.readUUID(), buffer.readEnum(BossLifecycleState.class),
                buffer.readResourceLocation(), buffer.readBlockPos(), buffer.readVarLong());
    }

    void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(entityId);
        buffer.writeEnum(state);
        buffer.writeResourceLocation(dimension);
        buffer.writeBlockPos(lastKnownPosition);
        buffer.writeVarLong(lastConfirmedTick);
    }
}
