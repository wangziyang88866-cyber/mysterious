package com.mysterious.gametest;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.combat.DamageDeliveryType;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.entity.WormOfTimeEntity;
import com.mysterious.mysterious;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.persistence.EncounterMigrationRegistry;
import com.mysterious.persistence.EncounterSerializer;
import com.mysterious.phase.ParasiteState;
import com.mysterious.phase.PhaseThreeRuntimeService;
import com.mysterious.phase.PhaseThreeState;
import com.mysterious.phase.PhaseTransitionRuntimeService;
import com.mysterious.registry.ModEntities;
import com.mysterious.spell.BossSpellDefinition;
import com.mysterious.spell.BossSpellAdapter;
import com.mysterious.spell.BossSpellRegistry;
import com.mysterious.spell.EncounterEntityBudget;
import com.mysterious.spell.SpellCastManager;
import com.mysterious.spell.SpellCategory;
import com.mysterious.spell.SpellInterruptPolicy;
import com.mysterious.spell.SpellRuntimeState;
import com.mysterious.spell.SpellTargetType;
import com.mysterious.integration.iss.IronsSpellAccess;
import com.mysterious.spell.SpellRuntimeService;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stage 9-12 framework, persistence and entity invariants. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageNineTwelveGameTests {
    private StageNineTwelveGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void spellBudgetRejectsOverflowWithoutQueue(GameTestHelper helper) {
        helper.assertTrue(EncounterEntityBudget.canReserve(60, 4), "Budget must allow its exact boundary");
        helper.assertTrue(!EncounterEntityBudget.canReserve(64, 1), "Budget must reject overflow immediately");
        helper.assertTrue(EncounterEntityBudget.canReserveTemporary(127, 1),
                "Shared temporary budget must allow its exact boundary");
        helper.assertTrue(!EncounterEntityBudget.canReserveTemporary(128, 1),
                "Worms and clocks must not bypass the shared temporary budget");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void testSpellsCoverProjectileAoeAndSummon(GameTestHelper helper) {
        helper.assertValueEqual(BossSpellRegistry.require(id("test_projectile")).damageDeliveryType(),
                DamageDeliveryType.PROJECTILE, "Projectile test definition must use the shared taxonomy");
        helper.assertValueEqual(BossSpellRegistry.require(id("test_aoe")).damageDeliveryType(),
                DamageDeliveryType.AOE, "AOE test definition must use the shared taxonomy");
        helper.assertValueEqual(BossSpellRegistry.require(id("test_summon")).entityBudgetCost(), 3,
                "Summon test definition must reserve every planned entity");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void p2DefinitionsResolveToOfficialIssSpells(GameTestHelper helper) {
        BossSpellRegistry.byCategory(SpellCategory.INTRINSIC).forEach(definition ->
                helper.assertValueEqual(SpellRegistry.getSpell(definition.spellId()).getSpellResource(),
                        definition.spellId(), "Every intrinsic P2 definition must resolve to a real ISS spell"));
        var amon = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        helper.assertTrue(amon instanceof IMagicEntity,
                "Amon must implement the ISS mob-casting contract");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void legendaryPoolContainsMultipleOfficialIssSpells(GameTestHelper helper) {
        var legendary = BossSpellRegistry.byCategory(SpellCategory.LEGENDARY);
        helper.assertTrue(legendary.size() >= 6,
                "Amon must have a varied safe legendary spell pool");
        legendary.forEach(definition -> {
            var spell = SpellRegistry.getSpell(definition.spellId());
            helper.assertValueEqual(spell.getSpellResource(), definition.spellId(),
                    "Every legendary definition must resolve to a real ISS spell");
            helper.assertTrue(definition.spellLevel() >= 1,
                    "Every legendary definition must request a positive spell level");
        });
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void p2UsesEveryEnabledSpellAtLegendaryLevel(GameTestHelper helper) {
        var enabled = IronsSpellAccess.enabledSpells();
        var pool = BossSpellAdapter.fullLegendaryPool();
        helper.assertValueEqual(pool.size(), enabled.size(),
                "P2 must expose every enabled ISS spell");
        pool.forEach(definition -> {
            var spell = SpellRegistry.getSpell(definition.spellId());
            helper.assertValueEqual(definition.category(), SpellCategory.LEGENDARY,
                    "Every P2 spell must use the legendary category");
            helper.assertValueEqual(definition.targetType(), SpellTargetType.SINGLE_ENTITY,
                    "Legendary spells must accept Amon's current living-entity target");
            helper.assertValueEqual(definition.spellLevel(), spell.getMaxLevel(),
                    "Every P2 spell must cast at its configured maximum level");
        });
        helper.assertTrue(SpellRuntimeService.MIN_LIGHTNING_INTERVAL_TICKS <= 25
                        && SpellRuntimeService.MIN_LIGHTNING_INTERVAL_TICKS
                        + SpellRuntimeService.LIGHTNING_INTERVAL_VARIANCE_TICKS - 1 <= 50,
                "P2 lightning must recur every 1.25 to 2.5 seconds");
        helper.assertTrue(!SpellRuntimeService.castsInPhase(EncounterPhase.PHASE_ONE),
                "P1 must not run the legendary spell scheduler");
        helper.assertTrue(SpellRuntimeService.castsInPhase(EncounterPhase.PHASE_TWO),
                "P2 must run the legendary spell scheduler");
        helper.assertTrue(SpellRuntimeService.castsInPhase(EncounterPhase.PHASE_THREE),
                "P3 must share the complete legendary spell scheduler");
        helper.assertTrue(SpellRuntimeService.PHASE_THREE_MIN_CAST_INTERVAL_TICKS
                        + SpellRuntimeService.PHASE_THREE_CAST_INTERVAL_VARIANCE_TICKS - 1
                        < SpellRuntimeService.PHASE_TWO_MIN_CAST_INTERVAL_TICKS,
                "P3 cast scheduling must be strictly faster than P2");
        helper.assertTrue(SpellRuntimeService.castTimeScale(EncounterPhase.PHASE_THREE) < 1.0D,
                "P3 must shorten the ISS effective cast time");
        helper.assertValueEqual(SpellRuntimeService.castTimeScale(EncounterPhase.PHASE_TWO), 1.0D,
                "P2 must preserve the spell's normal effective cast time");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void castLifecycleEnforcesLockCooldownAndCleanup(GameTestHelper helper) {
        SpellRuntimeState initial = SpellRuntimeState.initial(0L);
        BossSpellDefinition spell = BossSpellRegistry.require(id("test_projectile"));
        SpellRuntimeState prepared = SpellCastManager.prepare(initial, spell, UUID.randomUUID(), 1L);
        helper.assertValueEqual(prepared.instances().size(), 1, "Prepare must track one owned instance");
        helper.assertValueEqual(SpellCastManager.prepare(prepared, spell, UUID.randomUUID(), 2L), prepared,
                "Global lock/cooldown must reject a duplicate cast");
        SpellRuntimeState finished = SpellCastManager.tick(prepared, 40L);
        helper.assertValueEqual(finished.instances().size(), 0, "Expired instances must release their budget");
        helper.assertValueEqual(finished.reservedTemporaryEntities(), 0, "Cleanup must release reservations");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void p2ToP3CommitCreatesRestartableTimeline(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        UUID bossId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                center(), EncounterPhase.PHASE_TWO, 0L);
        controller.registerBoss(encounterId, BossRecord.loaded(bossId, EncounterPhase.PHASE_TWO,
                Level.OVERWORLD, BlockPos.ZERO, 1L));
        UUID transitionId = UUID.randomUUID();
        controller.beginPhaseTransition(encounterId, transitionId, bossId, EncounterPhase.PHASE_THREE, 10L);
        controller.commitPhaseTransition(encounterId, transitionId, 11L);
        var timeline = controller.requireEncounter(encounterId).advanced().phaseThree().orElseThrow();
        helper.assertValueEqual(timeline.transformationAtTick(), 811L,
                "P3 commit must persist the complete forty-second transformation timeline");
        helper.assertValueEqual(timeline.warningAtTick(), 751L,
                "P3 warning must be scheduled three seconds before completion");
        var snapshot = controller.requireEncounter(encounterId);
        helper.assertValueEqual(timeline.nextWormSpawnTick(), 31L,
                "The first time worm must be scheduled after one second");
        helper.assertValueEqual(timeline.nextClockSpawnTick(), 31L,
                "The first phantom clock must be scheduled after one second");

        EncounterController recoveryController = new EncounterController();
        UUID recoveryEncounter = UUID.randomUUID();
        UUID deadP2 = UUID.randomUUID();
        recoveryController.createEncounter(recoveryEncounter,
                new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                center(), EncounterPhase.PHASE_TWO, 0L);
        recoveryController.registerBoss(recoveryEncounter, BossRecord.loaded(deadP2,
                EncounterPhase.PHASE_TWO, Level.OVERWORLD, BlockPos.ZERO, 1L));
        recoveryController.markBossDead(recoveryEncounter, deadP2, 2L);
        helper.assertTrue(PhaseTransitionRuntimeService.shouldStartPhaseThreeAfterDeath(
                        recoveryController.requireEncounter(recoveryEncounter)),
                "A persisted P2 death must remain recoverable into P3 on the next server tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void p3OpeningUsesDamageCapAndDeathRespawnPolicy(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        UUID bossId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                center(), EncounterPhase.PHASE_TWO, 0L);
        controller.registerBoss(encounterId, BossRecord.loaded(bossId, EncounterPhase.PHASE_TWO,
                Level.OVERWORLD, BlockPos.ZERO, 1L));
        UUID transitionId = UUID.randomUUID();
        controller.beginPhaseTransition(encounterId, transitionId, bossId, EncounterPhase.PHASE_THREE, 10L);
        controller.commitPhaseTransition(encounterId, transitionId, 11L);
        var opening = controller.requireEncounter(encounterId);
        helper.assertValueEqual(CombatRuntimeService.capEffectiveDamage(5.0F), 5.0F,
                "Ordinary post-mitigation damage must remain unchanged");
        helper.assertValueEqual(CombatRuntimeService.capEffectiveDamage(200.0F),
                CombatRuntimeService.MAX_EFFECTIVE_DAMAGE_PER_HIT,
                "Every phase must cap one effective hit at ten health points");
        controller.markBossDead(encounterId, bossId, 12L);
        opening = controller.requireEncounter(encounterId);
        helper.assertTrue(PhaseThreeRuntimeService.shouldReviveOpeningBoss(opening),
                "A real P3 opening death must request a fresh incarnation");

        PhaseThreeState state = opening.advanced().phaseThree().orElseThrow();
        PhaseThreeState transformed = new PhaseThreeState(state.startedAtTick(), state.invulnerableUntilTick(),
                state.warningAtTick(), true, true, state.wormsSpawned(), state.nextWormSpawnTick(), true,
                state.nextClockSpawnTick(), state.clockHits(), state.lastExecutionTicks(), state.parasites());
        controller.updateAdvancedState(encounterId, new AdvancedEncounterState(opening.advanced().spells(),
                Optional.of(transformed), opening.advanced().crossDimensionChase()));
        helper.assertTrue(!PhaseThreeRuntimeService.shouldReviveOpeningBoss(
                        controller.requireEncounter(encounterId)),
                "A second-form death must remain final instead of spawning another incarnation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void parasiteAddsToRemainingDuration(GameTestHelper helper) {
        ParasiteState state = new ParasiteState(60, 40, 10L).addDuration(100, 20L);
        helper.assertValueEqual(state.remainingTicks(), 160,
                "A repeated parasite hit must add five seconds instead of refreshing");
        helper.assertValueEqual(state.infectionTicks(), 40,
                "Adding duration must not fabricate elapsed infection time");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void advancedStateSurvivesNbtRoundTrip(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                center(), EncounterPhase.PHASE_ONE, 0L);
        SpellRuntimeState state = SpellCastManager.prepare(SpellRuntimeState.initial(0L),
                BossSpellRegistry.require(id("test_summon")), UUID.randomUUID(), 1L);
        controller.updateAdvancedState(encounterId, new AdvancedEncounterState(state, Optional.empty()));
        var restored = EncounterSerializer.read(EncounterMigrationRegistry.migrate(
                EncounterSerializer.write(controller.snapshots()))).get(encounterId);
        helper.assertValueEqual(restored.advanced(), controller.requireEncounter(encounterId).advanced(),
                "Spell instances, locks, cooldowns and budgets must survive restart");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void schemaFourMigratesAdvancedStateExplicitly(GameTestHelper helper) {
        EncounterController controller = new EncounterController();
        UUID encounterId = UUID.randomUUID();
        controller.createEncounter(encounterId, new RealmOwner(UUID.randomUUID(), OwnershipKind.PLAYER),
                center(), EncounterPhase.PHASE_ONE, 0L);
        CompoundTag old = EncounterSerializer.write(controller.snapshots());
        old.putInt("schemaVersion", 4);
        old.putInt("dataVersion", 4);
        old.getList("encounters", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("advanced");
        CompoundTag migrated = EncounterMigrationRegistry.migrate(old);
        helper.assertValueEqual(migrated.getInt("schemaVersion"), 7,
                "V4 must traverse the explicit migration chain to the current schema");
        helper.assertTrue(EncounterSerializer.read(migrated).get(encounterId).advanced() != null,
                "Migration must supply a valid advanced runtime state");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void wormOwnershipAndCooldownSurviveNbt(GameTestHelper helper) {
        UUID encounterId = UUID.randomUUID();
        WormOfTimeEntity source = helper.spawn(ModEntities.WORM_OF_TIME.get(), 1, 2, 1);
        source.mysterious$bindEncounter(encounterId);
        var zombie = helper.spawn(EntityType.ZOMBIE, 3, 2, 1);
        source.mysterious$assignTarget(zombie);
        helper.assertValueEqual(source.getTarget(), zombie,
                "Encounter worms must attack Amon's current non-player target");
        CompoundTag tag = new CompoundTag();
        helper.assertTrue(source.save(tag), "Encounter-owned worm must serialize");
        WormOfTimeEntity restored = ModEntities.WORM_OF_TIME.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "Worm entity must be constructible");
        restored.load(tag);
        helper.assertValueEqual(restored.mysterious$getEncounterId().orElseThrow(), encounterId,
                "Worm encounter ownership must survive restart");
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mysterious", path);
    }

    private static EncounterCenter center() {
        return new EncounterCenter(Level.OVERWORLD, BlockPos.ZERO, ArenaConfigSnapshot.defaults());
    }
}
