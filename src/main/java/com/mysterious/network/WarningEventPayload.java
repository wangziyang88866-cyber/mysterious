package com.mysterious.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record WarningEventPayload(
        UUID encounterId,
        UUID eventId,
        long revision,
        WarningCode warning,
        int leadTicks
) implements CustomPacketPayload {
    public static final Type<WarningEventPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "warning_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, WarningEventPayload> STREAM_CODEC =
            CustomPacketPayload.codec(WarningEventPayload::write, WarningEventPayload::read);

    public WarningEventPayload {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(warning, "warning");
        if (revision < 0 || leadTicks < 0 || leadTicks > 20 * 60) {
            throw new IllegalArgumentException("Invalid warning event bounds");
        }
    }

    private static WarningEventPayload read(RegistryFriendlyByteBuf buffer) {
        return new WarningEventPayload(buffer.readUUID(), buffer.readUUID(), buffer.readVarLong(),
                buffer.readEnum(WarningCode.class), buffer.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounterId);
        buffer.writeUUID(eventId);
        buffer.writeVarLong(revision);
        buffer.writeEnum(warning);
        buffer.writeVarInt(leadTicks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
