package com.mysterious.phase;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.registry.ModEntities;
import com.mysterious.transaction.SplitReplacement;
import com.mysterious.transaction.SplitTransaction;
import com.mysterious.transaction.TransactionState;
import com.mysterious.theft.StolenAttributeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stage 6 P1 scanner and crash-reconcilable split execution. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class PhaseOneRuntimeService {
    public static final int INITIAL_BOSS_COUNT = 5;
    public static final int MAX_BOSS_COUNT = 10;
    public static final int SCAN_INTERVAL_TICKS = 10 * 20;
    private static final Logger LOGGER = LogUtils.getLogger();

    private PhaseOneRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) {
            return;
        }
        long tick = server.overworld().getGameTime();
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.phase() != EncounterPhase.PHASE_ONE
                    || snapshot.lifecycle() != EncounterLifecycle.ACTIVE
                    || snapshot.phaseOne().splitConsumed()) {
                continue;
            }
            PhaseTaskToken taskToken = controller.capturePhaseTaskToken(snapshot.encounterId());
            SplitTransaction pending = snapshot.phaseOne().splitTransaction()
                    .filter(transaction -> transaction.state() == TransactionState.PREPARED).orElse(null);
            if (pending != null) {
                if (controller.isPhaseTaskTokenCurrent(taskToken)) {
                    reconcile(controller, snapshot, pending, server, tick);
                }
                continue;
            }
            UUID scanner = chooseScanner(snapshot);
            if (scanner == null) {
                continue;
            }
            if (snapshot.phaseOne().scannerBossId().filter(scanner::equals).isEmpty()) {
                controller.assignPhaseOneScanner(snapshot.encounterId(), scanner,
                        saturatedAdd(tick, SCAN_INTERVAL_TICKS));
                continue;
            }
            if (tick < snapshot.phaseOne().nextScanTick()) {
                continue;
            }
            if (controller.isPhaseTaskTokenCurrent(taskToken)) {
                scan(controller, controller.requireEncounter(snapshot.encounterId()), server, scanner, tick);
            }
        }
    }

    /** Explicit encounter-start hook; callers create the encounter before invoking it. */
    public static List<UUID> spawnInitialWave(MinecraftServer server, UUID encounterId) {
        EncounterController controller = EncounterManager.controller(server);
        EncounterSnapshot snapshot = controller.requireEncounter(encounterId);
        if (snapshot.phase() != EncounterPhase.PHASE_ONE || !snapshot.bosses().isEmpty()) {
            throw new IllegalStateException("Initial P1 wave requires an empty phase-one registry");
        }
        return spawnInitialReinforcements(server, encounterId, INITIAL_BOSS_COUNT);
    }

    /** Completes a P1 opening wave after its first body was created by a player-facing summon command. */
    public static List<UUID> spawnInitialReinforcements(MinecraftServer server, UUID encounterId, int count) {
        EncounterController controller = EncounterManager.controller(server);
        EncounterSnapshot snapshot = controller.requireEncounter(encounterId);
        if (snapshot.phase() != EncounterPhase.PHASE_ONE || count <= 0
                || snapshot.bosses().size() + count != INITIAL_BOSS_COUNT) {
            throw new IllegalStateException("Initial P1 reinforcement count must complete exactly five bosses");
        }
        ServerLevel level = server.getLevel(snapshot.center().dimension());
        if (level == null) {
            throw new IllegalStateException("Encounter dimension is not loaded");
        }
        List<UUID> spawned = new ArrayList<>();
        for (int index = snapshot.bosses().size(); index < INITIAL_BOSS_COUNT; index++) {
            double angle = Math.PI * 2.0D * index / INITIAL_BOSS_COUNT;
            BlockPos position = BlockPos.containing(snapshot.center().position().getX() + Math.cos(angle) * 6.0D,
                    snapshot.center().position().getY(), snapshot.center().position().getZ() + Math.sin(angle) * 6.0D);
            AmonEntity amon = createBoss(level, encounterId, UUID.randomUUID(), position);
            controller.registerBoss(encounterId, BossRecord.loaded(amon.getUUID(), EncounterPhase.PHASE_ONE,
                    level.dimension(), position, level.getGameTime()));
            spawned.add(amon.getUUID());
        }
        return List.copyOf(spawned);
    }

    private static void scan(EncounterController controller, EncounterSnapshot snapshot,
                             MinecraftServer server, UUID scannerId, long tick) {
        Entity scannerEntity = findEntity(server, scannerId);
        if (!(scannerEntity instanceof AmonEntity scanner) || !(scanner.level() instanceof ServerLevel level)) {
            controller.assignPhaseOneScanner(snapshot.encounterId(), chooseScanner(snapshot),
                    saturatedAdd(tick, SCAN_INTERVAL_TICKS));
            return;
        }
        int capacity = MAX_BOSS_COUNT - snapshot.livingBossCount();
        if (capacity <= 0) {
            controller.assignPhaseOneScanner(snapshot.encounterId(), scannerId,
                    saturatedAdd(tick, SCAN_INTERVAL_TICKS));
            return;
        }
        List<LivingEntity> candidates = level.getEntitiesOfClass(LivingEntity.class,
                        scanner.getBoundingBox().inflate(16.0D), PhaseOneRuntimeService::isReplaceable)
                .stream().sorted(Comparator.comparing(entity -> entity.getUUID().toString()))
                .limit(capacity).toList();
        if (candidates.isEmpty()) {
            controller.assignPhaseOneScanner(snapshot.encounterId(), scannerId,
                    saturatedAdd(tick, SCAN_INTERVAL_TICKS));
            return;
        }
        List<SplitReplacement> plan = candidates.stream().map(candidate -> new SplitReplacement(
                candidate.getUUID(), UUID.randomUUID(), level.dimension(), candidate.blockPosition())).toList();
        SplitTransaction transaction = controller.prepareSplit(snapshot.encounterId(), UUID.randomUUID(),
                scannerId, plan, tick);
        reconcile(controller, controller.requireEncounter(snapshot.encounterId()), transaction, server, tick);
    }

    private static void reconcile(EncounterController controller, EncounterSnapshot snapshot,
                                  SplitTransaction transaction, MinecraftServer server, long tick) {
        for (SplitReplacement replacement : transaction.replacements()) {
            Entity existing = findEntity(server, replacement.replacementBossId());
            BossRecord registered = controller.requireEncounter(snapshot.encounterId())
                    .bosses().get(replacement.replacementBossId());
            if (existing instanceof AmonEntity amon) {
                if (registered == null) {
                    controller.registerBoss(snapshot.encounterId(), BossRecord.loaded(amon.getUUID(),
                            EncounterPhase.PHASE_ONE, amon.level().dimension(), amon.blockPosition(), tick));
                }
                continue;
            }
            ServerLevel level = server.getLevel(replacement.dimension());
            if (level == null) {
                return;
            }
            Entity source = level.getEntity(replacement.sourceEntityId());
            if (source != null && !source.isRemoved()) {
                source.kill();
            }
            AmonEntity created = createBoss(level, snapshot.encounterId(),
                    replacement.replacementBossId(), replacement.position());
            if (registered == null) {
                controller.registerBoss(snapshot.encounterId(), BossRecord.loaded(created.getUUID(),
                        EncounterPhase.PHASE_ONE, level.dimension(), replacement.position(), tick));
            } else {
                controller.markBossLoaded(snapshot.encounterId(), created.getUUID(), level.dimension(),
                        created.blockPosition(), tick);
            }
        }
        StolenAttributeManager.rebuild(snapshot.encounterId(), server);
        controller.commitSplit(snapshot.encounterId(), transaction.transactionId());
    }

    private static AmonEntity createBoss(ServerLevel level, UUID encounterId, UUID bossId, BlockPos position) {
        AmonEntity amon = Optional.ofNullable(ModEntities.AMON.get().create(level))
                .orElseThrow(() -> new IllegalStateException("Unable to create Amon entity"));
        amon.setUUID(bossId);
        amon.mysterious$bindEncounter(encounterId);
        amon.moveTo(position, 0.0F, 0.0F);
        if (!level.addFreshEntity(amon)) {
            throw new IllegalStateException("Unable to add split Amon " + bossId);
        }
        amon.mysterious$playSummonAnimation();
        return amon;
    }

    private static UUID chooseScanner(EncounterSnapshot snapshot) {
        return snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .sorted(Comparator.comparing(record -> record.entityId().toString()))
                .map(BossRecord::entityId).findFirst().orElse(null);
    }

    private static boolean isReplaceable(LivingEntity entity) {
        return entity.isAlive() && (entity.getType() == EntityType.VILLAGER
                || entity.getType() == EntityType.VINDICATOR
                || entity.getType() == EntityType.EVOKER
                || entity.getType() == EntityType.WITCH);
    }

    private static Entity findEntity(MinecraftServer server, UUID entityId) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static long saturatedAdd(long tick, int amount) {
        return tick > Long.MAX_VALUE - amount ? Long.MAX_VALUE : tick + amount;
    }
}
