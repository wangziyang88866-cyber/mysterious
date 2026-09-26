package com.mysterious.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Removes one encounter projection from the client without carrying any rendering-library state. */
public record EncounterClearPayload(UUID encounterId, long revision) implements CustomPacketPayload {
    public static final Type<EncounterClearPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "encounter_clear"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterClearPayload> STREAM_CODEC =
            CustomPacketPayload.codec(EncounterClearPayload::write, EncounterClearPayload::read);

    public EncounterClearPayload {
        Objects.requireNonNull(encounterId, "encounterId");
        if (revision < 0L) throw new IllegalArgumentException("revision must be non-negative");
    }

    private static EncounterClearPayload read(RegistryFriendlyByteBuf buffer) {
        return new EncounterClearPayload(buffer.readUUID(), buffer.readVarLong());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounterId);
        buffer.writeVarLong(revision);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
