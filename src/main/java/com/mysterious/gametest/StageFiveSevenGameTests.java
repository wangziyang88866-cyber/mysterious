package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.combat.DamageDeliveryClassifier;
import com.mysterious.combat.DamageDeliveryType;
import com.mysterious.combat.ThreatEntry;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.PhaseOneRuntimeService;
import com.mysterious.entity.AmonEntity;
import com.mysterious.registry.ModEntities;
import com.mysterious.theft.EscrowRecord;
import com.mysterious.theft.PendingReturnRecord;
import com.mysterious.theft.SerializedItemStack;
import com.mysterious.theft.StolenSlotType;
import com.mysterious.theft.TheftRuntimeService;
import com.mysterious.transaction.SplitReplacement;
import com.mysterious.transaction.TransactionState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** State, transaction and persistence gates for implementation stages 5-7. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageFiveSevenGameTests {
    private StageFiveSevenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void targetSwitchRequiresTwentyPercentLead(GameTestHelper helper) {
        UUID current = UUID.randomUUID();
        UUID challenger = UUID.randomUUID();
        Map<UUID, ThreatEntry> below = Map.of(current, new ThreatEntry(100.0D, 1L),
                challenger, new ThreatEntry(119.0D, 1L));
        helper.assertValueEqual(CombatRuntimeService.selectTarget(below, Optional.of(current),
                Optional.of(challenger)).orElseThrow(), current,
                "A challenger below 120 percent must not cause target thrashing");
        Map<UUID, ThreatEntry> enough = Map.of(current, new ThreatEntry(100.0D, 1L),
                challenger, new ThreatEntry(120.0D, 1L));
        helper.assertValueEqual(CombatRuntimeService.selectTarget(enough, Optional.of(current),
                Optional.of(challenger)).orElseThrow(), challenger,
                "A challenger at 120 percent may take aggro");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void mobMeleeAndTeleportLockAllowTheftWindow(GameTestHelper helper) {
        Fixture targetFixture = fixture();
        AmonEntity amon = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        if (amon.mysterious$getEncounterId().isEmpty()) amon.mysterious$bindEncounter(targetFixture.encounterId);
        float healthBefore = amon.getHealth();
        helper.assertTrue(amon.hurt(amon.damageSources().generic(), 4.0F),
                "A bound P1 Amon must accept ordinary non-player damage");
        helper.assertTrue(amon.getHealth() < healthBefore,
                "Ordinary damage must reduce Amon health instead of being cleared as invulnerability");
        helper.assertTrue(!amon.fireImmune(), "Amon must not retain a hidden entity-type fire immunity");
        var zombie = helper.spawn(EntityType.ZOMBIE, 3, 2, 1);
        helper.assertTrue(amon.mysterious$mayAcquireFallbackTarget(zombie),
                "Without a selected player threat, Amon must be able to acquire other living creatures");
        amon.setTarget(zombie);
        helper.assertValueEqual(CombatRuntimeService.resolveCombatTarget(
                        targetFixture.controller.requireEncounter(targetFixture.encounterId), amon,
                        helper.getLevel().getServer()).orElseThrow(), zombie,
                "Spells, flight and summons must resolve Amon's actual non-player target");
        AmonEntity alliedEncounterEntity = helper.spawn(ModEntities.AMON.get(), 5, 2, 1);
        helper.assertTrue(!amon.mysterious$mayAcquireFallbackTarget(alliedEncounterEntity),
                "Fallback targeting must not make Amon attack another encounter entity");
        helper.assertValueEqual(DamageDeliveryClassifier.classify(amon.damageSources().mobAttack(amon)),
                DamageDeliveryType.MELEE, "Amon's direct attack must reach theft and seal handling");
        amon.mysterious$markTeleported(100L);
        helper.assertTrue(!amon.mysterious$approachTeleportReady(120L),
                "Approach teleport must not refresh as soon as the attack lock ends");
        helper.assertTrue(!amon.mysterious$normalTeleportReady(140L,
                        CombatRuntimeService.MIN_NORMAL_TELEPORT_INTERVAL_TICKS),
                "Normal teleports must share the longer minimum interval");
        helper.assertTrue(amon.mysterious$approachTeleportReady(340L),
                "Approach teleport may retry after the documented twelve-second window");
        helper.assertTrue(TheftRuntimeService.MIN_THEFT_CHANCE >= 0.60D,
                "Theft chance must be raised to at least sixty percent in every combat phase");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void splitPreparationIsDurableAndCommitIsSingleUse(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID scanner = UUID.randomUUID();
        fixture.controller.registerBoss(fixture.encounterId, BossRecord.loaded(scanner,
                EncounterPhase.PHASE_ONE, Level.OVERWORLD, BlockPos.ZERO, 1L));
        fixture.controller.assignPhaseOneScanner(fixture.encounterId, scanner, 200L);
        UUID replacementId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        AtomicReference<CompoundTag> durable = new AtomicReference<>();
        fixture.controller.setDurabilityBarrier(() ->
                durable.set(EncounterSerializer.write(fixture.controller.snapshots())));
        fixture.controller.prepareSplit(fixture.encounterId, transactionId, scanner,
                List.of(new SplitReplacement(UUID.randomUUID(), replacementId,
                        Level.OVERWORLD, new BlockPos(4, 0, 4))), 10L);
        var prepared = EncounterSerializer.read(EncounterMigrationRegistry.migrate(durable.get()))
                .get(fixture.encounterId).phaseOne().splitTransaction().orElseThrow();
        helper.assertValueEqual(prepared.state(), TransactionState.PREPARED,
                "The complete split plan must be durable before source removal");
        fixture.controller.registerBoss(fixture.encounterId, BossRecord.loaded(replacementId,
                EncounterPhase.PHASE_ONE, Level.OVERWORLD, new BlockPos(4, 0, 4), 11L));
        fixture.controller.commitSplit(fixture.encounterId, transactionId);
        fixture.controller.commitSplit(fixture.encounterId, transactionId);
        helper.assertTrue(fixture.controller.requireEncounter(fixture.encounterId).phaseOne().splitConsumed(),
                "A committed split must permanently consume the one batch");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void escrowPersistsEvidenceAndState(GameTestHelper helper) {
        Fixture fixture = fixture();
        UUID playerId = UUID.randomUUID();
        fixture.controller.joinPlayer(fixture.encounterId, playerId, 1L);
        ItemStack single = new ItemStack(Items.DIAMOND_CHESTPLATE);
        SerializedItemStack item = SerializedItemStack.of(single, helper.getLevel().registryAccess());
        UUID stolenId = UUID.randomUUID();
        EscrowRecord record = new EscrowRecord(stolenId, fixture.encounterId, playerId,
                StolenSlotType.ARMOR, 2, item, item, 1, 0, UUID.randomUUID(),
                TransactionState.PREPARED, Optional.empty());
        AtomicReference<CompoundTag> durable = new AtomicReference<>();
        fixture.controller.setDurabilityBarrier(() ->
                durable.set(EncounterSerializer.write(fixture.controller.snapshots())));
        fixture.controller.prepareEscrow(fixture.encounterId, record);
        EscrowRecord persisted = EncounterSerializer.read(EncounterMigrationRegistry.migrate(durable.get()))
                .get(fixture.encounterId).escrow().get(stolenId);
        helper.assertValueEqual(persisted, record,
                "PREPARED must preserve item, slot, fingerprint and before/after counts");
        fixture.controller.setEscrowState(fixture.encounterId, stolenId,
                TransactionState.PREPARED, TransactionState.COMMITTED);
        helper.assertValueEqual(fixture.controller.requireEncounter(fixture.encounterId)
                .escrow().get(stolenId).state(), TransactionState.COMMITTED,
                "Removal evidence must advance to committed exactly once");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void globalPendingReturnOutlivesEncounterImage(GameTestHelper helper) {
        UUID stolenId = UUID.randomUUID();
        PendingReturnRecord record = new PendingReturnRecord(stolenId, UUID.randomUUID(), UUID.randomUUID(),
                StolenSlotType.MAIN_INVENTORY, 9,
                SerializedItemStack.of(new ItemStack(Items.DIAMOND), helper.getLevel().registryAccess()),
                TransactionState.COMMITTED, Optional.empty());
        CompoundTag root = EncounterSerializer.write(Map.of());
        EncounterSerializer.writePendingReturns(root, Map.of(stolenId, record));
        CompoundTag migrated = EncounterMigrationRegistry.migrate(root);
        helper.assertValueEqual(EncounterSerializer.readPendingReturns(migrated).get(stolenId), record,
                "Global pending returns must not depend on an active encounter record");
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

    private record Fixture(EncounterController controller, UUID encounterId) {
    }
}
