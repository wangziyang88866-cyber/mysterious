package com.mysterious.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/** Client packets carry intent only; authoritative coordinates, hits and phase data are never accepted. */
public record EncounterIntentPayload(UUID requestId, UUID encounterId, Intent intent) implements CustomPacketPayload {
    public enum Intent {
        REQUEST_SNAPSHOT
    }

    public static final Type<EncounterIntentPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "encounter_intent"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterIntentPayload> STREAM_CODEC =
            CustomPacketPayload.codec(EncounterIntentPayload::write, EncounterIntentPayload::read);

    public EncounterIntentPayload {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(intent, "intent");
    }

    private static EncounterIntentPayload read(RegistryFriendlyByteBuf buffer) {
        return new EncounterIntentPayload(buffer.readUUID(), buffer.readUUID(), buffer.readEnum(Intent.class));
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(requestId);
        buffer.writeUUID(encounterId);
        buffer.writeEnum(intent);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
