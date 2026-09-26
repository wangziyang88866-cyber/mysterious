package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.BossLifecycleState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.BossRegistrationResult;
import com.mysterious.encounter.CleanupState;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterFinalizationException;
import com.mysterious.encounter.EncounterFinalizationResult;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EndReason;
import com.mysterious.encounter.PhaseTransitionResult;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** State-machine coverage required by Stage 1. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageOneGameTests {
    private StageOneGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void repeatedBossRegistrationIsIdempotent(GameTestHelper helper) {
        EncounterFixture fixture = fixture(new EncounterController());
        BossRecord record = boss(fixture.bossId(), 1L);

        helper.assertValueEqual(
                fixture.controller().registerBoss(fixture.encounterId(), record),
                BossRegistrationResult.REGISTERED,
                "First registration must create the BossRecord"
        );
        helper.assertValueEqual(
                fixture.controller().registerBoss(fixture.encounterId(), record),
                BossRegistrationResult.ALREADY_REGISTERED,
                "Repeated registration must be a no-op"
        );
        helper.assertValueEqual(
                fixture.controller().requireEncounter(fixture.encounterId()).bosses().size(),
                1,
                "Repeated registration must not duplicate a boss"
        );
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void unloadThenReloadPreservesBossIdentity(GameTestHelper helper) {
        EncounterFixture fixture = registeredFixture(new EncounterController());
        fixture.controller().markBossUnloaded(fixture.encounterId(), fixture.bossId(), 2L);
        helper.assertValueEqual(state(fixture), BossLifecycleState.UNLOADED,
                "Chunk unload must have an explicit non-terminal state");

        fixture.controller().markBossLoaded(
                fixture.encounterId(), fixture.bossId(), Level.OVERWORLD, new BlockPos(5, 3, 7), 3L);
        BossRecord restored = fixture.controller().requireEncounter(fixture.encounterId())
                .bosses().get(fixture.bossId());
        helper.assertValueEqual(restored.lifecycle(), BossLifecycleState.LOADED,
                "Reload must restore the same BossRecord");
        helper.assertValueEqual(restored.lastKnownPosition(), new BlockPos(5, 3, 7),
                "Reload must refresh the last known position");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void missingPendingIsNotDeath(GameTestHelper helper) {
        EncounterFixture fixture = registeredFixture(new EncounterController());
        fixture.controller().markBossMissingPending(fixture.encounterId(), fixture.bossId(), 2L);

        var snapshot = fixture.controller().requireEncounter(fixture.encounterId());
        helper.assertValueEqual(snapshot.bosses().get(fixture.bossId()).lifecycle(),
                BossLifecycleState.MISSING_PENDING,
                "A temporarily unresolvable entity must remain missing-pending");
        helper.assertValueEqual(snapshot.livingBossCount(), 1,
                "Missing-pending bosses must still count as alive");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void repeatedFinalizeCommitsCleanupOnce(GameTestHelper helper) {
        AtomicInteger cleanupCalls = new AtomicInteger();
        EncounterController controller = new EncounterController((snapshot, termination) -> cleanupCalls.incrementAndGet());
        EncounterFixture fixture = fixture(controller);

        helper.assertValueEqual(
                controller.finalizeEncounter(fixture.encounterId(), EndReason.VICTORY, 20L),
                EncounterFinalizationResult.COMMITTED,
                "First finalize call must commit cleanup"
        );
        helper.assertValueEqual(
                controller.finalizeEncounter(fixture.encounterId(), EndReason.VICTORY, 21L),
                EncounterFinalizationResult.ALREADY_COMMITTED,
                "Repeated finalize call must be idempotent"
        );
        var snapshot = controller.requireEncounter(fixture.encounterId());
        helper.assertValueEqual(cleanupCalls.get(), 1, "Cleanup must execute exactly once");
        helper.assertValueEqual(snapshot.lifecycle(), EncounterLifecycle.ENDED_VICTORY,
                "Victory must end in the victory lifecycle");
        helper.assertValueEqual(snapshot.cleanupState(), CleanupState.COMMITTED,
                "Cleanup must be visibly committed");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void phaseTransitionHasOneCarrierAndOneCommit(GameTestHelper helper) {
        EncounterFixture fixture = registeredFixture(new EncounterController());
        UUID secondBossId = UUID.randomUUID();
        fixture.controller().registerBoss(fixture.encounterId(), boss(secondBossId, 2L));
        UUID transitionId = UUID.randomUUID();

        helper.assertValueEqual(
                fixture.controller().beginPhaseTransition(
                        fixture.encounterId(), transitionId, fixture.bossId(), EncounterPhase.PHASE_TWO, 10L),
                PhaseTransitionResult.STARTED,
                "First transition claim must start"
        );
        helper.assertValueEqual(
                fixture.controller().beginPhaseTransition(
                        fixture.encounterId(), transitionId, fixture.bossId(), EncounterPhase.PHASE_TWO, 10L),
                PhaseTransitionResult.ALREADY_STARTED,
                "Repeated transition claim must not create another carrier"
        );
        helper.assertValueEqual(
                fixture.controller().commitPhaseTransition(fixture.encounterId(), transitionId, 11L),
                PhaseTransitionResult.COMMITTED,
                "First transition commit must succeed"
        );
        helper.assertValueEqual(
                fixture.controller().commitPhaseTransition(fixture.encounterId(), transitionId, 12L),
                PhaseTransitionResult.ALREADY_COMMITTED,
                "Repeated transition commit must be idempotent"
        );

        var snapshot = fixture.controller().requireEncounter(fixture.encounterId());
        helper.assertValueEqual(snapshot.phase(), EncounterPhase.PHASE_TWO,
                "Committed transition must advance the encounter phase");
        helper.assertValueEqual(snapshot.lifecycle(), EncounterLifecycle.ACTIVE,
                "Committed transition must return the encounter to active");
        helper.assertTrue(!snapshot.bosses().get(fixture.bossId()).isTransitionCarrier(),
                "Committed transition must release the carrier claim");
        helper.assertValueEqual(snapshot.bosses().get(secondBossId).phase(), EncounterPhase.PHASE_TWO,
                "Every registry record must advance with the authoritative encounter phase");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void phaseTransitionRejectsSkippedPhase(GameTestHelper helper) {
        EncounterFixture fixture = registeredFixture(new EncounterController());
        try {
            fixture.controller().beginPhaseTransition(fixture.encounterId(), UUID.randomUUID(),
                    fixture.bossId(), EncounterPhase.PHASE_THREE, 10L);
            helper.fail("Phase one must not skip directly to phase three");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void carrierDeathSelectsStableRecoveryCarrier(GameTestHelper helper) {
        EncounterFixture fixture = registeredFixture(new EncounterController());
        UUID transitionId = UUID.randomUUID();
        fixture.controller().beginPhaseTransition(fixture.encounterId(), transitionId,
                fixture.bossId(), EncounterPhase.PHASE_TWO, 10L);
        fixture.controller().markBossDead(fixture.encounterId(), fixture.bossId(), 11L);

        var snapshot = fixture.controller().requireEncounter(fixture.encounterId());
        helper.assertValueEqual(snapshot.lifecycle(), EncounterLifecycle.PHASE_TRANSITION,
                "A terminal carrier must keep the original transition recoverable");
        helper.assertTrue(snapshot.activeTransition().isPresent(),
                "A terminal carrier must preserve transition metadata");
        helper.assertValueEqual(snapshot.phase(), EncounterPhase.PHASE_ONE,
                "Recovery must preserve the old phase until commit");
        var transition = snapshot.activeTransition().orElseThrow();
        helper.assertValueEqual(transition.carrierBossId(), transition.recoveryBossId(),
                "Carrier death must select the preallocated recovery identity");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void cleanupFailureRetriesSameFinalization(GameTestHelper helper) {
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicReference<UUID> finalizationId = new AtomicReference<>();
        EncounterController controller = new EncounterController((snapshot, termination) -> {
            UUID previous = finalizationId.getAndSet(termination.finalizationId());
            if (previous != null && !previous.equals(termination.finalizationId())) {
                throw new AssertionError("Cleanup retry changed its finalization ID");
            }
            if (cleanupCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("injected cleanup failure");
            }
        });
        EncounterFixture fixture = fixture(controller);

        try {
            controller.finalizeEncounter(fixture.encounterId(), EndReason.VICTORY, 20L);
            helper.fail("Injected cleanup failure must reach the controller boundary");
            return;
        } catch (EncounterFinalizationException expected) {
            // The next call must retry the same logical finalization.
        }

        var failed = controller.requireEncounter(fixture.encounterId());
        helper.assertValueEqual(failed.lifecycle(), EncounterLifecycle.ENDED_ERROR_RECOVERY,
                "Cleanup failure must enter error recovery");
        helper.assertValueEqual(failed.cleanupState(), CleanupState.PENDING,
                "Failed cleanup must remain retryable");
        helper.assertValueEqual(
                controller.finalizeEncounter(fixture.encounterId(), EndReason.VICTORY, 21L),
                EncounterFinalizationResult.COMMITTED,
                "Cleanup retry must commit"
        );
        helper.assertValueEqual(cleanupCalls.get(), 2, "Cleanup must run once per attempt");
        helper.assertValueEqual(
                controller.requireEncounter(fixture.encounterId()).termination().orElseThrow().finalizationId(),
                finalizationId.get(),
                "Cleanup retry must preserve its idempotency key"
        );
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void amonEncounterBindingSurvivesNbt(GameTestHelper helper) {
        UUID encounterId = UUID.randomUUID();
        AmonEntity source = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        source.mysterious$bindEncounter(encounterId);

        CompoundTag tag = new CompoundTag();
        helper.assertTrue(source.save(tag), "Bound Amon must serialize");
        AmonEntity restored = ModEntities.AMON.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "Amon entity type must create during binding test");
        restored.load(tag);
        helper.assertValueEqual(restored.mysterious$getEncounterId().orElseThrow(), encounterId,
                "Amon encounter ownership must survive an NBT round trip");
        helper.succeed();
    }

    private static EncounterFixture registeredFixture(EncounterController controller) {
        EncounterFixture fixture = fixture(controller);
        controller.registerBoss(fixture.encounterId(), boss(fixture.bossId(), 1L));
        return fixture;
    }

    private static EncounterFixture fixture(EncounterController controller) {
        UUID encounterId = UUID.randomUUID();
        UUID bossId = UUID.randomUUID();
        controller.createEncounter(
                encounterId,
                new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_ONE,
                0L
        );
        return new EncounterFixture(controller, encounterId, bossId);
    }

    private static BossRecord boss(UUID bossId, long tick) {
        return BossRecord.loaded(bossId, EncounterPhase.PHASE_ONE, Level.OVERWORLD, BlockPos.ZERO, tick);
    }

    private static BossLifecycleState state(EncounterFixture fixture) {
        return fixture.controller().requireEncounter(fixture.encounterId())
                .bosses().get(fixture.bossId()).lifecycle();
    }

    private record EncounterFixture(EncounterController controller, UUID encounterId, UUID bossId) {
    }
}
