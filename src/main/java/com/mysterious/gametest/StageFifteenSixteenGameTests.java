package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.CrossDimensionChaseState;
import com.mysterious.encounter.CrossDimensionRuntimeService;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterTimerState;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.PhaseThreeState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageFifteenSixteenGameTests {
    private StageFifteenSixteenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void crossDimensionStateAndCooldownSurviveRestart(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, owner(), center(), EncounterPhase.PHASE_THREE, 0L);
        CrossDimensionChaseState chase = new CrossDimensionChaseState(UUID.randomUUID(), Level.NETHER,
                100L, 140L, true, false);
        controller.updateAdvancedState(encounterId, new AdvancedEncounterState(
                controller.requireEncounter(encounterId).advanced().spells(), Optional.of(secondForm()),
                Optional.of(chase)));
        controller.beginCrossDimensionChase(encounterId);
        controller.updateTimers(encounterId, new EncounterTimerState(0L, OptionalLong.empty(),
                OptionalLong.of(340L)));
        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(
                EncounterSerializer.write(controller.snapshots()))).get(encounterId);
        helper.assertValueEqual(restored.lifecycle(), EncounterLifecycle.CROSS_DIMENSION_CHASE,
                "Chase lifecycle must survive restart");
        helper.assertValueEqual(restored.advanced().crossDimensionChase().orElseThrow(), chase,
                "Target dimension and transaction timing must survive restart");
        helper.assertValueEqual(restored.timers().crossDimensionCooldownUntilTick().orElseThrow(), 340L,
                "Shared cross-dimension cooldown must survive restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void schemaSixMigratesToIdleCrossDimensionState(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, owner(), center(), EncounterPhase.PHASE_ONE, 0L);
        CompoundTag root = EncounterSerializer.write(controller.snapshots());
        root.putInt("schemaVersion", 6);
        root.putInt("dataVersion", 6);
        root.getList("encounters", Tag.TAG_COMPOUND).getCompound(0)
                .getCompound("advanced").remove("crossDimensionChase");
        CompoundTag migrated = EncounterMigrationRegistry.migrate(root);
        helper.assertValueEqual(migrated.getInt("schemaVersion"), 7,
                "V6 data must traverse the explicit V7 migration");
        helper.assertTrue(EncounterSerializer.read(migrated).get(encounterId)
                        .advanced().crossDimensionChase().isEmpty(),
                "Old saves must migrate to the canonical idle chase state");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void crossDimensionDefaultsAndIdleAnimationAreFrozen(GameTestHelper helper) {
        helper.assertValueEqual(CrossDimensionRuntimeService.WAIT_TICKS, 40,
                "Cross-dimension wait must be two seconds");
        helper.assertValueEqual(CrossDimensionRuntimeService.COOLDOWN_TICKS, 200,
                "Cross-dimension cooldown must be ten seconds");
        helper.assertValueEqual(CrossDimensionRuntimeService.SAFE_SEARCH_ATTEMPTS, 16,
                "Cross-dimension safe search must be bounded to sixteen attempts");
        helper.assertTrue(!AmonEntity.mysterious$shouldPlayWalk(false, false),
                "Idle first-form Amon must never loop the walk animation");
        helper.assertTrue(AmonEntity.mysterious$shouldPlayWalk(true, false),
                "Moving first-form Amon must still play walk");
        helper.assertTrue(!AmonEntity.mysterious$shouldPlayWalk(true, true),
                "Second form must use flight rather than walk");
        helper.assertValueEqual(AmonEntity.MELEE_ATTACK_INTERVAL_TICKS, 10,
                "Amon must attack twice as often as the vanilla twenty-tick cadence");
        helper.assertValueEqual(AmonEntity.MELEE_ATTACK_RANGE, 4.5D,
                "Amon melee reach must be increased to four and a half blocks");
        helper.succeed();
    }

    private static PhaseThreeState secondForm() {
        return new PhaseThreeState(0L, 800L, 740L, true, true,
                8, 900L, true, 900L, Map.of(), Map.of(), Map.of());
    }

    private static RealmOwner owner() {
        return new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER);
    }

    private static EncounterCenter center() {
        return new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults());
    }
}
