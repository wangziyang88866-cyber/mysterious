package com.mysterious.persistence;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.theft.GlobalPendingReturnStore;
import com.mysterious.theft.PendingReturnRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Versioned normal-state persistence. Transaction journals are added by later stages. */
public final class EncounterSavedData extends SavedData {
    public static final String FILE_ID = "mysterious_encounters";
    public static final Factory<EncounterSavedData> FACTORY =
            new Factory<>(EncounterSavedData::new, EncounterSavedData::load);
    private static final Logger LOGGER = LogUtils.getLogger();

    private Map<UUID, EncounterSnapshot> encounters = Map.of();
    private final GlobalPendingReturnStore pendingReturns = new GlobalPendingReturnStore();
    private boolean safeMode;
    private String recoveryError = "";
    private CompoundTag preservedSource;

    public static EncounterSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public static EncounterSavedData load(CompoundTag source, HolderLookup.Provider registries) {
        try {
            CompoundTag migrated = EncounterMigrationRegistry.migrate(source);
            EncounterSavedData data = new EncounterSavedData();
            data.encounters = EncounterSerializer.read(migrated);
            data.pendingReturns.restore(EncounterSerializer.readPendingReturns(migrated));
            return data;
        } catch (RuntimeException exception) {
            EncounterSavedData data = new EncounterSavedData();
            data.safeMode = true;
            data.recoveryError = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            data.preservedSource = source.copy();
            LOGGER.error("[recovery] encounter data entered safe mode; source is preserved", exception);
            return data;
        }
    }

    public void restoreInto(EncounterController controller) {
        if (safeMode) {
            LOGGER.error("[recovery] skipped encounter restore while in safe mode: {}", recoveryError);
            return;
        }
        encounters.values().forEach(controller::restoreEncounter);
    }

    public void captureFrom(EncounterController controller) {
        if (safeMode) {
            LOGGER.error("[recovery] refused to overwrite preserved encounter data while in safe mode");
            return;
        }
        encounters = controller.snapshots();
        setDirty();
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public Optional<String> recoveryError() {
        return recoveryError.isEmpty() ? Optional.empty() : Optional.of(recoveryError);
    }

    public int encounterCount() {
        return encounters.size();
    }

    public GlobalPendingReturnStore pendingReturns() {
        return pendingReturns;
    }

    public int pendingReturnCount() {
        return pendingReturns.snapshot().size();
    }

    /**
     * Waits for NeoForge's asynchronous atomic writer and verifies the exact image from disk.
     * Callers must treat failure as a failed durability barrier and must not continue a destructive operation.
     */
    public void flushDurably(MinecraftServer server) {
        if (safeMode) {
            throw new IllegalStateException("Cannot durably flush encounter data while in safe mode");
        }
        server.overworld().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
        Path file = server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve(FILE_ID + ".dat");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Durable encounter image was not created at " + file);
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            verifyDurablePayload(root.getCompound("data"), encounters, pendingReturns.snapshot());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to verify durable encounter image", exception);
        }
    }

    /**
     * Compares the exact serialized image instead of decoding it through restart recovery rules.
     * In particular, decoding intentionally converts CLEANUP/IN_PROGRESS to ERROR_RECOVERY; using
     * that decoded value for a same-process durability check would reject every valid cleanup journal.
     */
    public static void verifyDurablePayload(CompoundTag diskPayload,
                                            Map<UUID, EncounterSnapshot> encounters,
                                            Map<UUID, PendingReturnRecord> pendingReturns) {
        CompoundTag expected = EncounterSerializer.write(encounters);
        EncounterSerializer.writePendingReturns(expected, pendingReturns);
        if (!expected.equals(diskPayload)) {
            throw new IllegalStateException("Durable encounter image does not match authoritative memory state");
        }
        // Still validate that the image is structurally readable. Recovery transformations apply only
        // to this validation result and never participate in the byte-for-byte authority comparison.
        CompoundTag migrated = EncounterMigrationRegistry.migrate(diskPayload.copy());
        EncounterSerializer.read(migrated);
        EncounterSerializer.readPendingReturns(migrated);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (safeMode && preservedSource != null) {
            return preservedSource.copy();
        }
        CompoundTag root = EncounterSerializer.write(encounters);
        EncounterSerializer.writePendingReturns(root, pendingReturns.snapshot());
        return root;
    }
}
