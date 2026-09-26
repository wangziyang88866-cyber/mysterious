package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.CleanupState;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterFinalizationException;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EndReason;
import com.mysterious.encounter.PlayerEncounterState;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.mysterious;
import com.mysterious.network.ClientEncounterStateCache;
import com.mysterious.network.EncounterDeltaPayload;
import com.mysterious.network.EncounterSnapshotPayload;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterDataVersions;
import com.mysterious.persistence.EncounterSavedData;
import com.mysterious.persistence.EncounterSerializer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Contract coverage for Stage 3 networking and Stage 4 basic persistence/recovery. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageThreeFourGameTests {
    private StageThreeFourGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void persistenceRoundTripPreservesAuthoritativeSnapshot(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID bossId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(ownerId, OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 5L);
        controller.registerBoss(encounterId,
                BossRecord.loaded(bossId, EncounterPhase.PHASE_ONE, Level.OVERWORLD, new BlockPos(2, 3, 4), 6L));
        controller.upsertPlayerState(encounterId, new PlayerEncounterState(ownerId,
                PlayerParticipationState.RETENTION_PENDING, 5L, 65L, OptionalLong.of(205L)));
        controller.markAbandonedPending(encounterId, 100L);

        CompoundTag encoded = EncounterSerializer.write(controller.snapshots());
        Map<UUID, ?> decoded = EncounterSerializer.read(EncounterMigrationRegistry.migrate(encoded));
        helper.assertValueEqual(decoded.get(encounterId), controller.requireEncounter(encounterId),
                "All basic encounter state must survive an NBT round trip");

        EncounterController runtimeController = EncounterManager.controller(helper.getLevel().getServer());
        EncounterSavedData runtimeData = EncounterManager.savedData(helper.getLevel().getServer());
        runtimeData.captureFrom(runtimeController);
        runtimeData.flushDurably(helper.getLevel().getServer());
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void interruptedCleanupRestoresToRetryableErrorState(GameTestHelper helper) {
        AtomicReference<CompoundTag> durableImage = new AtomicReference<>();
        EncounterController controller = new EncounterController((snapshot, termination) -> {
            throw new IllegalStateException("simulated process interruption");
        });
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 0L);
        controller.setDurabilityBarrier(() -> {
            CompoundTag image = EncounterSerializer.write(controller.snapshots());
            EncounterSavedData.verifyDurablePayload(image, controller.snapshots(), Map.of());
            durableImage.set(image);
        });
        try {
            controller.finalizeEncounter(encounterId, EndReason.VICTORY, 20L);
            helper.fail("Injected cleanup interruption must be visible");
            return;
        } catch (EncounterFinalizationException expected) {
            // Reload the image written before external cleanup began.
        }

        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(durableImage.get()))
                .get(encounterId);
        helper.assertValueEqual(restored.lifecycle(), EncounterLifecycle.ENDED_ERROR_RECOVERY,
                "An in-progress cleanup must fail closed after restart");
        helper.assertValueEqual(restored.cleanupState(), CleanupState.PENDING,
                "Interrupted cleanup must remain retryable");
        helper.assertValueEqual(restored.termination().orElseThrow().finalizationId(),
                controller.requireEncounter(encounterId).termination().orElseThrow().finalizationId(),
                "Recovery must preserve the finalization idempotency key");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void durabilityFailureDoesNotBeginExternalCleanup(GameTestHelper helper) {
        AtomicReference<Boolean> cleanupCalled = new AtomicReference<>(false);
        EncounterController controller = new EncounterController((snapshot, termination) -> cleanupCalled.set(true));
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 0L);
        controller.setDurabilityBarrier(() -> {
            throw new IllegalStateException("simulated disk failure");
        });

        try {
            controller.finalizeEncounter(encounterId, EndReason.ABANDONED, 20L);
            helper.fail("A failed durability barrier must abort finalization");
            return;
        } catch (EncounterFinalizationException expected) {
            // Expected: the encounter remains retryable and no external destructive cleanup ran.
        }

        var recovered = controller.requireEncounter(encounterId);
        helper.assertTrue(!cleanupCalled.get(), "External cleanup must not run before durable journaling succeeds");
        helper.assertValueEqual(recovered.lifecycle(), EncounterLifecycle.ENDED_ERROR_RECOVERY,
                "A disk failure must fail closed without crashing the server tick");
        helper.assertValueEqual(recovered.cleanupState(), CleanupState.PENDING,
                "A disk failure must leave cleanup retryable");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void unsupportedSchemaEntersNonDestructiveSafeMode(GameTestHelper helper) {
        CompoundTag future = new CompoundTag();
        future.putInt("schemaVersion", 999);
        future.putString("sentinel", "preserve-me");
        EncounterSavedData loaded = EncounterSavedData.load(future, helper.getLevel().registryAccess());
        helper.assertTrue(loaded.isSafeMode(), "Future schema must enter admin-visible safe mode");
        CompoundTag saved = loaded.save(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertValueEqual(saved.getString("sentinel"), "preserve-me",
                "Safe mode must preserve rather than overwrite unknown data");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void schemaOneMigratesArenaRecoveryFieldsExplicitly(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 0L);
        CompoundTag old = EncounterSerializer.write(controller.snapshots());
        old.putInt("schemaVersion", 1);
        old.putInt("dataVersion", 1);
        CompoundTag oldCenter = old.getList("encounters", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("center");
        oldCenter.remove("safeAnchor");
        oldCenter.remove("phaseOneRecoveryFractionPerSecond");
        oldCenter.remove("safePositionAttempts");

        CompoundTag migrated = EncounterMigrationRegistry.migrate(old);
        var restored = EncounterSerializer.read(migrated).get(encounterId);
        helper.assertValueEqual(migrated.getInt("schemaVersion"), EncounterDataVersions.CURRENT_SCHEMA_VERSION,
                "Schema migration must advance through the current version");
        helper.assertValueEqual(restored.center().safeAnchor(), restored.center().position(),
                "V1 centers must receive an explicit safe-anchor fallback");
        helper.assertValueEqual(restored.center().arena().phaseOneRecoveryFractionPerSecond(), 0.02D,
                "V1 encounters must receive the documented recovery rate");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void snapshotPayloadCodecAndRevisionRulesAreDeterministic(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 0L);
        EncounterSnapshotPayload original = EncounterSnapshotPayload.from(controller.requireEncounter(encounterId));
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                helper.getLevel().registryAccess(), ConnectionType.NEOFORGE);
        EncounterSnapshotPayload.STREAM_CODEC.encode(buffer, original);
        EncounterSnapshotPayload decoded = EncounterSnapshotPayload.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(decoded, original, "Snapshot payload codec must round-trip exactly");

        ClientEncounterStateCache cache = new ClientEncounterStateCache();
        helper.assertValueEqual(cache.apply(decoded), ClientEncounterStateCache.ApplyResult.APPLIED,
                "First snapshot must initialize the client projection");
        EncounterDeltaPayload gap = new EncounterDeltaPayload(encounterId, decoded.revision() + 1,
                decoded.revision() + 2, EncounterLifecycle.ACTIVE, EncounterPhase.PHASE_ONE, 0);
        helper.assertValueEqual(cache.apply(gap), ClientEncounterStateCache.ApplyResult.NEEDS_RESYNC,
                "A non-contiguous delta must request a full resync");
        EncounterDeltaPayload next = new EncounterDeltaPayload(encounterId, decoded.revision(),
                decoded.revision() + 1, EncounterLifecycle.ACTIVE, EncounterPhase.PHASE_ONE, 0);
        helper.assertValueEqual(cache.apply(next), ClientEncounterStateCache.ApplyResult.APPLIED,
                "A contiguous delta must advance the projection");
        helper.assertValueEqual(cache.apply(next), ClientEncounterStateCache.ApplyResult.STALE,
                "A repeated delta must not replay state");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void transitionCannotCommitBeforeItStarts(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        UUID bossId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER), center(),
                EncounterPhase.PHASE_ONE, 0L);
        controller.registerBoss(encounterId,
                BossRecord.loaded(bossId, EncounterPhase.PHASE_ONE, Level.OVERWORLD, BlockPos.ZERO, 1L));
        controller.beginPhaseTransition(encounterId, transitionId, bossId, EncounterPhase.PHASE_TWO, 10L);
        try {
            controller.commitPhaseTransition(encounterId, transitionId, 9L);
            helper.fail("Transition commit must reject backwards server time");
            return;
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    private static EncounterCenter center() {
        return new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults());
    }
}
