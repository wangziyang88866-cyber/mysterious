package com.mysterious.network;

import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record EncounterDeltaPayload(
        UUID encounterId,
        long baseRevision,
        long revision,
        EncounterLifecycle lifecycle,
        EncounterPhase phase,
        int livingBossCount
) implements CustomPacketPayload {
    public static final Type<EncounterDeltaPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "encounter_delta"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterDeltaPayload> STREAM_CODEC =
            CustomPacketPayload.codec(EncounterDeltaPayload::write, EncounterDeltaPayload::read);

    public EncounterDeltaPayload {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(phase, "phase");
        if (baseRevision < 0 || revision <= baseRevision || livingBossCount < 0) {
            throw new IllegalArgumentException("Invalid encounter delta bounds");
        }
    }

    private static EncounterDeltaPayload read(RegistryFriendlyByteBuf buffer) {
        return new EncounterDeltaPayload(buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(),
                buffer.readEnum(EncounterLifecycle.class), buffer.readEnum(EncounterPhase.class), buffer.readVarInt());
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounterId);
        buffer.writeVarLong(baseRevision);
        buffer.writeVarLong(revision);
        buffer.writeEnum(lifecycle);
        buffer.writeEnum(phase);
        buffer.writeVarInt(livingBossCount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
