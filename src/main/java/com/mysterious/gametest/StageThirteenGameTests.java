package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.entity.AmonEntity;
import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.BossExecutionManager;
import com.mysterious.phase.BossExecutionReason;
import com.mysterious.phase.ClockRuntimeService;
import com.mysterious.phase.ParasiteState;
import com.mysterious.phase.PhaseThreeState;
import com.mysterious.registry.ModEntities;
import com.mysterious.spell.SpellRuntimeState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageThirteenGameTests {
    private StageThirteenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void clockOwnershipAndExpirySurviveNbt(GameTestHelper helper) {
        UUID encounterId = UUID.randomUUID();
        PhantomClockEntity source = helper.spawn(ModEntities.PHANTOM_CLOCK.get(), 1, 2, 1);
        source.mysterious$initialize(encounterId, 600L);
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(source.save(tag), "Encounter clock must serialize");
        PhantomClockEntity restored = ModEntities.PHANTOM_CLOCK.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "Clock must be constructible");
        restored.load(tag);
        helper.assertValueEqual(restored.mysterious$getEncounterId().orElseThrow(), encounterId,
                "Clock ownership must survive restart");
        helper.assertValueEqual(restored.mysterious$expiresAtTick(), 600L,
                "Absolute expiry must survive restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void clockCountersAndExecutionDedupeSurviveNbt(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_THREE, 0L);
        PhaseThreeState state = new PhaseThreeState(0L, 800L, 740L, true, true,
                8, 900L, false, 1000L, Map.of(playerId, 19), Map.of(playerId, 77L),
                Map.of(playerId, new ParasiteState(20, 599, 77L)));
        controller.updateAdvancedState(encounterId,
                new AdvancedEncounterState(SpellRuntimeState.initial(0L), Optional.of(state)));
        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(
                EncounterSerializer.write(controller.snapshots()))).get(encounterId)
                .advanced().phaseThree().orElseThrow();
        helper.assertValueEqual(restored, state,
                "Clock counters, execution dedupe and parasite threshold state must survive restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void expiredClockDamagesAmonsNonPlayerTarget(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults()),
                EncounterPhase.PHASE_THREE, 0L);
        AmonEntity amon = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        amon.mysterious$bindEncounter(encounterId);
        controller.registerBoss(encounterId, BossRecord.loaded(amon.getUUID(), EncounterPhase.PHASE_THREE,
                helper.getLevel().dimension(), amon.blockPosition(), 1L));
        var zombie = helper.spawn(EntityType.ZOMBIE, 3, 2, 1);
        amon.setTarget(zombie);
        PhantomClockEntity clock = helper.spawn(ModEntities.PHANTOM_CLOCK.get(), 5, 2, 1);
        clock.mysterious$initialize(encounterId, 100L);
        float before = zombie.getHealth();
        PhaseThreeState state = new PhaseThreeState(0L, 800L, 740L, true, false,
                0, 900L, false, 1000L, Map.of(), Map.of(), Map.of());
        ClockRuntimeService.tick(controller.requireEncounter(encounterId), state,
                helper.getLevel().getServer(), 100L);
        helper.assertTrue(zombie.getHealth() < before,
                "Every expired clock must damage Amon's current non-player target");
        helper.assertTrue(clock.isRemoved(), "An expired clock must be consumed after its hit");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void stageThirteenThresholdsAndBoundsAreStable(GameTestHelper helper) {
        helper.assertValueEqual(BossExecutionManager.PARASITE_THRESHOLD, 600,
                "Parasite execution threshold must remain thirty seconds");
        helper.assertValueEqual(BossExecutionManager.CLOCK_THRESHOLD, 20,
                "Clock execution threshold must remain personal and equal to twenty");
        helper.assertValueEqual(ClockRuntimeService.incrementClockHit(19), 20,
                "Every expired clock hit must advance the player to the execution threshold");
        helper.assertValueEqual(ClockRuntimeService.incrementClockHit(Integer.MAX_VALUE), Integer.MAX_VALUE,
                "Clock execution progress must saturate instead of overflowing");
        UUID selected = UUID.randomUUID();
        UUID nearer = UUID.randomUUID();
        Map<UUID, Double> distantTargets = new LinkedHashMap<>();
        distantTargets.put(selected, 10_000.0D);
        distantTargets.put(nearer, 4.0D);
        helper.assertValueEqual(ClockRuntimeService.selectClockTargetId(Optional.of(selected), distantTargets)
                        .orElseThrow(), selected,
                "Every clock must hit the selected combat target even when it spawned far away");
        helper.assertValueEqual(ClockRuntimeService.selectClockTargetId(Optional.empty(), distantTargets)
                        .orElseThrow(), nearer,
                "A clock without a selected target must fall back to the nearest eligible participant");
        helper.assertValueEqual(ClockRuntimeService.CLOCK_SEARCH_ATTEMPTS, 12,
                "Clock placement must remain bounded");
        helper.assertValueEqual(ClockRuntimeService.CLOCK_LIFETIME_TICKS, 600,
                "Clock lifetime must remain thirty seconds");
        PhantomClockEntity clock = helper.spawn(ModEntities.PHANTOM_CLOCK.get(), 1, 2, 1);
        helper.assertTrue(!clock.isAttackable(), "A phantom clock must reject player attack interaction");
        clock.kill();
        helper.assertTrue(!clock.isRemoved(), "Kill-style damage paths must not remove a timed phantom clock");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void totemConsumesExecutionAndResetsOnlyItsThreshold(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        UUID playerId = player.getUUID();
        PhaseThreeState state = new PhaseThreeState(0L, 800L, 740L, true, true,
                8, 900L, false, 1000L, Map.of(playerId, 7), Map.of(),
                Map.of(playerId, new ParasiteState(100, 600, 40L)));
        PhaseThreeState protectedState = BossExecutionManager.trigger(state, player,
                Set.of(BossExecutionReason.PARASITE), 50L);
        helper.assertTrue(player.isAlive(), "A vanilla totem must be allowed to protect against Boss Execution");
        helper.assertValueEqual(protectedState.parasites().get(playerId).infectionTicks(), 0,
                "Totem protection must reset the parasite lethal accumulator");
        helper.assertValueEqual(protectedState.clockHits().get(playerId), 7,
                "Unrelated clock progress must remain intact");
        PhaseThreeState duplicate = BossExecutionManager.trigger(protectedState, player,
                Set.of(BossExecutionReason.PARASITE), 50L);
        helper.assertValueEqual(duplicate, protectedState,
                "A second Execution in the same server tick must be a no-op");
        helper.assertTrue(player.isAlive(), "Same-tick dedupe must not consume a second life");
        helper.succeed();
    }
}
