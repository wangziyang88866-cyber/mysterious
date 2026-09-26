package com.mysterious.gametest;

import com.mysterious.encounter.CleanupState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterFinalizationResult;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterStartService;
import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.mysterious;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** Covers the explicit player entry/exit route; ordinary entity spawning remains independent. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageNineteenGameTests {
    private StageNineteenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void explicitStartCreatesBoundWaveAndStopFinalizes(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        EncounterController controller = new EncounterController();
        long tick = 5L;
        var started = EncounterStartService.start(controller, player.getUUID(), Level.OVERWORLD, BlockPos.ZERO,
                ArenaConfigSnapshot.defaults(), tick, encounterId -> registerFiveInitialBosses(controller, encounterId, tick));
        var active = controller.requireEncounter(started.encounterId());
        helper.assertValueEqual(active.phase(), EncounterPhase.PHASE_ONE,
                "Explicit start must enter phase one");
        helper.assertTrue(active.players().containsKey(player.getUUID()),
                "Starter must be joined before bosses are spawned");
        helper.assertValueEqual(active.bosses().size(), 5,
                "Explicit start must register all five initial Amon entities");
        for (var bossId : started.bossIds()) {
            helper.assertTrue(active.bosses().containsKey(bossId),
                    "Every returned initial boss must be registered");
        }

        var stopped = EncounterStartService.stop(controller, started.encounterId(), tick + 1L);
        helper.assertValueEqual(stopped, EncounterFinalizationResult.COMMITTED,
                "Explicit stop must use the normal cleanup transaction");
        var ended = controller.requireEncounter(started.encounterId());
        helper.assertTrue(ended.lifecycle().isTerminal() && ended.cleanupState() == CleanupState.COMMITTED,
                "Explicit stop must reach a terminal, committed cleanup state");
        controller.purgeEncounter(started.encounterId());
        helper.assertTrue(controller.findEncounter(started.encounterId()).isEmpty(),
                "A cleared encounter must be removed so another battle can start cleanly");
        helper.succeed();
    }

    private static List<UUID> registerFiveInitialBosses(EncounterController controller, UUID encounterId, long tick) {
        List<UUID> bosses = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        for (UUID bossId : bosses) {
            controller.registerBoss(encounterId, BossRecord.loaded(bossId, EncounterPhase.PHASE_ONE,
                    Level.OVERWORLD, BlockPos.ZERO, tick));
        }
        return bosses;
    }
}
