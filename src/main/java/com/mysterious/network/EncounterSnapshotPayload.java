package com.mysterious.network;

import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EncounterSnapshotPayload(
        UUID encounterId,
        long revision,
        EncounterLifecycle lifecycle,
        EncounterPhase phase,
        ResourceLocation centerDimension,
        BlockPos center,
        int livingBossCount,
        List<ClientBossView> bosses
) implements CustomPacketPayload {
    public static final int MAX_BOSSES = 64;
    public static final Type<EncounterSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mysterious", "encounter_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EncounterSnapshotPayload> STREAM_CODEC =
            CustomPacketPayload.codec(EncounterSnapshotPayload::write, EncounterSnapshotPayload::read);

    public EncounterSnapshotPayload {
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(centerDimension, "centerDimension");
        Objects.requireNonNull(center, "center");
        bosses = List.copyOf(bosses);
        if (revision < 0 || livingBossCount < 0 || bosses.size() > MAX_BOSSES) {
            throw new IllegalArgumentException("Invalid encounter snapshot payload bounds");
        }
    }

    public static EncounterSnapshotPayload from(EncounterSnapshot snapshot) {
        List<ClientBossView> bosses = snapshot.bosses().values().stream()
                .sorted(Comparator.comparing(record -> record.entityId().toString()))
                .map(EncounterSnapshotPayload::viewOf)
                .toList();
        return new EncounterSnapshotPayload(snapshot.encounterId(), snapshot.revision(), snapshot.lifecycle(),
                snapshot.phase(), snapshot.center().dimension().location(), snapshot.center().position(),
                snapshot.livingBossCount(), bosses);
    }

    private static ClientBossView viewOf(BossRecord record) {
        return new ClientBossView(record.entityId(), record.lifecycle(), record.lastKnownDimension().location(),
                record.lastKnownPosition(), record.lastConfirmedTick());
    }

    private static EncounterSnapshotPayload read(RegistryFriendlyByteBuf buffer) {
        UUID encounterId = buffer.readUUID();
        long revision = buffer.readVarLong();
        EncounterLifecycle lifecycle = buffer.readEnum(EncounterLifecycle.class);
        EncounterPhase phase = buffer.readEnum(EncounterPhase.class);
        ResourceLocation dimension = buffer.readResourceLocation();
        BlockPos center = buffer.readBlockPos();
        int living = buffer.readVarInt();
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_BOSSES) {
            throw new IllegalArgumentException("Boss list exceeds network limit: " + size);
        }
        List<ClientBossView> bosses = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            bosses.add(ClientBossView.read(buffer));
        }
        return new EncounterSnapshotPayload(encounterId, revision, lifecycle, phase, dimension, center, living, bosses);
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(encounterId);
        buffer.writeVarLong(revision);
        buffer.writeEnum(lifecycle);
        buffer.writeEnum(phase);
        buffer.writeResourceLocation(centerDimension);
        buffer.writeBlockPos(center);
        buffer.writeVarInt(livingBossCount);
        buffer.writeVarInt(bosses.size());
        bosses.forEach(boss -> boss.write(buffer));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
