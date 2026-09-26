package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EndReason;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** State-level coverage for Stage 2 configuration and participation semantics. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageTwoGameTests {
    private StageTwoGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void arenaConfigurationRejectsInvalidRelationships(GameTestHelper helper) {
        try {
            new ArenaConfigSnapshot(80, 64, 96, 72, 88, 48,
                    60, 200, 1200, 400, 0.02D, 24);
            helper.fail("Join/retention/hard-exit ordering must be validated");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void participantHysteresisAndJoinGraceArePersisted(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID playerId = UUID.randomUUID();
        fixture.controller().joinPlayer(fixture.encounterId(), playerId, 10L);
        var joined = fixture.controller().requireEncounter(fixture.encounterId()).players().get(playerId);
        helper.assertValueEqual(joined.joinGraceUntilTick(), 70L, "Default join grace must last three seconds");

        fixture.controller().markPlayerRetentionPending(fixture.encounterId(), playerId, 20L);
        var pending = fixture.controller().requireEncounter(fixture.encounterId()).players().get(playerId);
        helper.assertValueEqual(pending.participation(), PlayerParticipationState.RETENTION_PENDING,
                "Leaving retention bounds must begin a delayed exit");
        helper.assertValueEqual(pending.retentionDeadlineTick().orElseThrow(), 220L,
                "Default retention delay must last ten seconds");

        fixture.controller().markPlayerActive(fixture.encounterId(), playerId);
        fixture.controller().markPlayerLeft(fixture.encounterId(), playerId, 30L);
        fixture.controller().joinPlayer(fixture.encounterId(), playerId, 40L);
        var rejoined = fixture.controller().requireEncounter(fixture.encounterId()).players().get(playerId);
        helper.assertValueEqual(rejoined.joinedAtTick(), 40L, "A rejoin must create a fresh join epoch");
        helper.assertValueEqual(rejoined.joinGraceUntilTick(), 100L, "A rejoin must restore join grace");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void abandonmentCanResumeWithoutResettingCombatState(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID bossId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        fixture.controller().registerBoss(fixture.encounterId(), BossRecord.loaded(
                bossId, EncounterPhase.PHASE_ONE, Level.OVERWORLD, BlockPos.ZERO, 1L));
        fixture.controller().joinPlayer(fixture.encounterId(), playerId, 2L);
        fixture.controller().markPlayerLeft(fixture.encounterId(), playerId, 3L);
        fixture.controller().markAbandonedPending(fixture.encounterId(), 3L);
        fixture.controller().joinPlayer(fixture.encounterId(), playerId, 4L);
        fixture.controller().resumeActive(fixture.encounterId());

        var resumed = fixture.controller().requireEncounter(fixture.encounterId());
        helper.assertValueEqual(resumed.lifecycle(), EncounterLifecycle.ACTIVE,
                "A valid rejoin must cancel abandoned-pending");
        helper.assertValueEqual(resumed.livingBossCount(), 1,
                "Resuming must preserve the boss registry");
        helper.assertValueEqual(resumed.phase(), EncounterPhase.PHASE_ONE,
                "Resuming must not reset the current phase");
        fixture.controller().markPlayerLeft(fixture.encounterId(), playerId, 5L);
        fixture.controller().markAbandonedPending(fixture.encounterId(), 5L);
        fixture.controller().finalizeEncounter(fixture.encounterId(), EndReason.ABANDONED, 1205L);
        helper.assertValueEqual(fixture.controller().requireEncounter(fixture.encounterId()).lifecycle(),
                EncounterLifecycle.ENDED_ABANDONED,
                "Finalizing abandoned-pending must clear its timer and produce a valid terminal snapshot");
        helper.succeed();
    }

    private static Fixture fixture() {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId,
                new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_ONE, 0L);
        return new Fixture(controller, encounterId);
    }

    private record Fixture(EncounterController controller, UUID encounterId) {
    }
}
