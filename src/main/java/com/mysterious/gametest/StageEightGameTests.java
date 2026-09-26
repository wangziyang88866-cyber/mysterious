package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.BossLifecycleState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterRuntimeService;
import com.mysterious.encounter.PhaseTransitionResult;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.PhaseTransitionRuntimeService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.UUID;

/** State and crash-recovery gates for the Stage 8 transition framework. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageEightGameTests {
    private StageEightGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void transitionInvalidatesQueuedPhaseWork(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID bossId = registerBoss(fixture, 1L);
        var token = fixture.controller.capturePhaseTaskToken(fixture.encounterId);
        fixture.controller.beginPhaseTransition(fixture.encounterId, UUID.randomUUID(), bossId,
                EncounterPhase.PHASE_TWO, 2L);
        helper.assertTrue(!fixture.controller.isPhaseTaskTokenCurrent(token),
                "Beginning a transition must invalidate queued old-phase work");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void allDeadRecoveryCarrierIsPreparedAtomically(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID recoveryId = UUID.randomUUID();
        UUID transitionId = UUID.randomUUID();
        fixture.controller.beginPhaseTransitionWithRecoveryCarrier(fixture.encounterId, transitionId,
                recoveryId, Level.OVERWORLD, new BlockPos(8, 2, 8), EncounterPhase.PHASE_TWO, 5L);
        var snapshot = fixture.controller.requireEncounter(fixture.encounterId);
        helper.assertValueEqual(snapshot.lifecycle(), EncounterLifecycle.PHASE_TRANSITION,
                "All-dead recovery must enter the explicit transition state");
        helper.assertValueEqual(snapshot.activeTransition().orElseThrow().carrierBossId(), recoveryId,
                "The preallocated recovery UUID must be the only carrier");
        helper.assertValueEqual(snapshot.bosses().get(recoveryId).lifecycle(), BossLifecycleState.MISSING_PENDING,
                "The carrier must be durable before its entity is spawned");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void carrierFailureKeepsSameTransitionAndRecoveryIdentity(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID bossId = registerBoss(fixture, 1L);
        UUID transitionId = UUID.randomUUID();
        fixture.controller.beginPhaseTransition(fixture.encounterId, transitionId, bossId,
                EncounterPhase.PHASE_TWO, 2L);
        fixture.controller.markBossRemoved(fixture.encounterId, bossId, 3L);
        var transition = fixture.controller.requireEncounter(fixture.encounterId)
                .activeTransition().orElseThrow();
        helper.assertValueEqual(transition.transitionId(), transitionId,
                "Death/remove recovery must not create a second transition");
        helper.assertValueEqual(transition.carrierBossId(), transition.recoveryBossId(),
                "Recovery must claim the stable preallocated carrier UUID");
        fixture.controller.markBossRemoved(fixture.encounterId, transition.recoveryBossId(), 4L);
        var retried = fixture.controller.requireEncounter(fixture.encounterId)
                .activeTransition().orElseThrow();
        helper.assertValueEqual(retried.transitionId(), transitionId,
                "A failed recovery spawn must retry the same logical transition");
        helper.assertValueEqual(retried.carrierBossId(), transition.recoveryBossId(),
                "A failed recovery spawn must not allocate a second carrier identity");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void transitionRecoveryMetadataSurvivesPersistence(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID bossId = registerBoss(fixture, 1L);
        UUID transitionId = UUID.randomUUID();
        fixture.controller.beginPhaseTransition(fixture.encounterId, transitionId, bossId,
                EncounterPhase.PHASE_TWO, 2L);
        var root = EncounterSerializer.write(fixture.controller.snapshots());
        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(root))
                .get(fixture.encounterId);
        helper.assertValueEqual(restored.activeTransition(),
                fixture.controller.requireEncounter(fixture.encounterId).activeTransition(),
                "Transition recovery identity and location must survive restart");
        helper.assertValueEqual(restored.phaseGeneration(), 1L,
                "Phase task generation must survive restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void explicitRollbackIsIdempotent(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID bossId = registerBoss(fixture, 1L);
        UUID transitionId = UUID.randomUUID();
        fixture.controller.beginPhaseTransition(fixture.encounterId, transitionId, bossId,
                EncounterPhase.PHASE_TWO, 2L);
        helper.assertValueEqual(fixture.controller.rollbackPhaseTransition(
                fixture.encounterId, transitionId, 3L, "test"), PhaseTransitionResult.ROLLED_BACK,
                "Explicit rollback must reach a safe active state");
        helper.assertValueEqual(fixture.controller.requireEncounter(fixture.encounterId).lifecycle(),
                EncounterLifecycle.ACTIVE, "Rollback must restore the active lifecycle");
        helper.assertValueEqual(fixture.controller.rollbackPhaseTransition(
                fixture.encounterId, transitionId, 4L, "repeat"), PhaseTransitionResult.ROLLED_BACK,
                "Repeated rollback must be harmless");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void stalePhaseOneDeathCannotAdvancePhaseTwo(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID deadPhaseOneBoss = registerBoss(fixture, 1L);
        UUID survivor = registerBoss(fixture, 2L);
        fixture.controller.markBossDead(fixture.encounterId, deadPhaseOneBoss, 3L);
        UUID transitionId = UUID.randomUUID();
        fixture.controller.beginPhaseTransition(fixture.encounterId, transitionId, survivor,
                EncounterPhase.PHASE_TWO, 4L);
        fixture.controller.commitPhaseTransition(fixture.encounterId, transitionId, 5L);
        var snapshot = fixture.controller.requireEncounter(fixture.encounterId);
        helper.assertTrue(!EncounterRuntimeService.isCurrentLivingBoss(snapshot, deadPhaseOneBoss),
                "A terminal P1 body must not trigger another phase transition");
        helper.assertTrue(EncounterRuntimeService.isCurrentLivingBoss(snapshot, survivor),
                "The living P2 incarnation must remain authoritative");

        Fixture deathFixture = fixture();
        UUID first = registerBoss(deathFixture, 1L);
        UUID last = registerBoss(deathFixture, 2L);
        deathFixture.controller.markBossDead(deathFixture.encounterId, first, 3L);
        helper.assertTrue(!PhaseTransitionRuntimeService.shouldStartPhaseTwoAfterDeaths(
                        deathFixture.controller.requireEncounter(deathFixture.encounterId)),
                "P1 must not advance while any Amon incarnation remains alive");
        deathFixture.controller.markBossDead(deathFixture.encounterId, last, 4L);
        helper.assertTrue(PhaseTransitionRuntimeService.shouldStartPhaseTwoAfterDeaths(
                        deathFixture.controller.requireEncounter(deathFixture.encounterId)),
                "P1 must advance only after the final incarnation really dies");
        helper.succeed();
    }

    private static Fixture fixture() {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_ONE, 0L);
        return new Fixture(controller, encounterId);
    }

    private static UUID registerBoss(Fixture fixture, long tick) {
        UUID bossId = UUID.randomUUID();
        fixture.controller.registerBoss(fixture.encounterId, new BossRecord(bossId,
                EncounterPhase.PHASE_ONE, Level.OVERWORLD, BlockPos.ZERO, tick,
                BossLifecycleState.LOADED, Optional.empty()));
        return bossId;
    }

    private record Fixture(EncounterController controller, UUID encounterId) {
    }
}
