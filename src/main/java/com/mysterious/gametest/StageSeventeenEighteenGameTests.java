package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.config.ReleaseCandidateBalance;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterDataVersions;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.ClockRuntimeService;
import com.mysterious.phase.PhaseThreeRuntimeService;
import com.mysterious.spell.EncounterEntityBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageSeventeenEighteenGameTests {
    private StageSeventeenEighteenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void fourPlayerThreatStressRemainsBoundedAndRestartable(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_ONE, 0L);
        List<UUID> players = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            UUID player = UUID.randomUUID();
            players.add(player);
            controller.joinPlayer(encounterId, player, 0L);
        }
        for (int tick = 1; tick <= 1000; tick++) {
            controller.recordThreat(encounterId, players.get(tick % players.size()), 1.0D, tick);
        }
        var snapshot = controller.requireEncounter(encounterId);
        helper.assertValueEqual(snapshot.combat().threats().size(), 4,
                "High-frequency four-player traffic must not leak threat entries");
        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(
                EncounterSerializer.write(controller.snapshots()))).get(encounterId);
        helper.assertValueEqual(restored.combat(), snapshot.combat(),
                "Stress state must remain deterministic across restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void resourceCapsRejectOverflowWithoutBacklog(GameTestHelper helper) {
        for (int iteration = 0; iteration < 1000; iteration++) {
            helper.assertTrue(!EncounterEntityBudget.canReserveTemporary(
                            ReleaseCandidateBalance.MAX_TEMPORARY_ENTITIES, 1),
                    "Full temporary budget must reject every repeated request");
            helper.assertTrue(!EncounterEntityBudget.canReserve(
                            ReleaseCandidateBalance.MAX_ACTIVE_SPELL_ENTITIES, 1),
                    "Full spell budget must reject every repeated request");
        }
        helper.assertValueEqual(ClockRuntimeService.MAX_ACTIVE_CLOCKS, 16,
                "Clock cap must match the RC safety line");
        helper.assertValueEqual(PhaseThreeRuntimeService.MAX_WORMS, 8,
                "Worm cap must match the RC safety line");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void releaseCandidateVersionsAndBalanceAreFrozen(GameTestHelper helper) {
        helper.assertValueEqual(ReleaseCandidateBalance.BASELINE, "RC1-2026-09-24",
                "Release baseline must change explicitly");
        helper.assertValueEqual(EncounterDataVersions.CURRENT_SCHEMA_VERSION, 7,
                "RC save schema must remain V7");
        helper.assertValueEqual(EncounterDataVersions.CURRENT_DATA_VERSION, 7,
                "RC data version must remain V7");
        helper.assertValueEqual(ReleaseCandidateBalance.BLACK_EDGE_RAMP_TICKS, 1200,
                "Black edge must reach maximum over sixty seconds");
        helper.assertValueEqual(ReleaseCandidateBalance.FLIGHT_SPEED_PER_TICK, 0.55D,
                "Flight speed must remain the approved fixed value");
        helper.succeed();
    }
}
