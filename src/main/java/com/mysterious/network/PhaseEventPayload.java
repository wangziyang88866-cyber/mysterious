package com.mysterious.network;

import com.mysterious.encounter.EncounterPhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record PhaseEventPayload(
        UUID encounterId,
        UUID eventId,
        long revision,
        UUID transitionId,
        EncounterPhase from,
        EncounterPhase to,
        boolean committed
) implements CustomPacketPayload {
    public static final Type<PhaseEventPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "phase_event"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PhaseEventPayload> STREAM_CODEC =
            CustomPacketPayload.codec(PhaseEventPayload::write, PhaseEventPayload::read);

    public PhaseEventPayload {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (revision < 0 || from == to) {
            throw new IllegalArgumentException("Invalid phase event");
        }
    }

    private static PhaseEventPayload read(RegistryFriendlyByteBuf buffer) {
        return new PhaseEventPayload(buffer.readUUID(), buffer.readUUID(), buffer.readVarLong(), buffer.readUUID(),
                buffer.readEnum(EncounterPhase.class), buffer.readEnum(EncounterPhase.class), buffer.readBoolean());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounterId);
        buffer.writeUUID(eventId);
        buffer.writeVarLong(revision);
        buffer.writeUUID(transitionId);
        buffer.writeEnum(from);
        buffer.writeEnum(to);
        buffer.writeBoolean(committed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
