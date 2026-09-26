package com.mysterious.encounter;

import com.mysterious.arena.EncounterCenter;
import com.mysterious.config.MysteriousServerConfig;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.phase.PhaseOneRuntimeService;
import com.mysterious.entity.AmonEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** Explicit OP-facing entry point. A plain spawned Amon never implicitly creates global encounter state. */
public final class EncounterStartService {
    private EncounterStartService() {
    }

    public static StartResult start(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) throw new IllegalStateException("Player is not attached to a running server");
        return start(server, player.getUUID(), player.serverLevel().dimension(), player.blockPosition(),
                player.serverLevel().getGameTime());
    }

    /** Server-side command core, separated from the player wrapper so it is testable without a client connection. */
    public static StartResult start(MinecraftServer server, UUID playerId, ResourceKey<Level> dimension,
                                    BlockPos position, long tick) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        if (EncounterManager.savedData(server).isSafeMode()) {
            throw new IllegalStateException("Encounter start is disabled while persistence is in safe mode");
        }
        return start(EncounterManager.controller(server), playerId, dimension, position,
                MysteriousServerConfig.snapshot(), tick,
                encounterId -> PhaseOneRuntimeService.spawnInitialWave(server, encounterId));
    }

    /** Makes a player-issued vanilla summon the first, already-present body of a full five-body P1 wave. */
    public static StartResult startWithSummonedAmon(ServerPlayer owner, AmonEntity summoned) {
        MinecraftServer server = owner.getServer();
        if (server == null) throw new IllegalStateException("Player is not attached to a running server");
        if (summoned.level() != owner.serverLevel() || summoned.mysterious$getEncounterId().isPresent()) {
            throw new IllegalArgumentException("Summoned Amon must be unbound and in the owner's current level");
        }
        if (EncounterManager.savedData(server).isSafeMode()) {
            throw new IllegalStateException("Encounter start is disabled while persistence is in safe mode");
        }
        long tick = owner.serverLevel().getGameTime();
        return start(EncounterManager.controller(server), owner.getUUID(), owner.serverLevel().dimension(),
                summoned.blockPosition(), MysteriousServerConfig.snapshot(), tick, encounterId -> {
                    summoned.mysterious$bindEncounter(encounterId);
                    summoned.mysterious$playSummonAnimation();
                    EncounterManager.controller(server).registerBoss(encounterId,
                            BossRecord.loaded(summoned.getUUID(), EncounterPhase.PHASE_ONE,
                                    owner.serverLevel().dimension(), summoned.blockPosition(), tick));
                    List<UUID> bosses = new java.util.ArrayList<>();
                    bosses.add(summoned.getUUID());
                    bosses.addAll(PhaseOneRuntimeService.spawnInitialReinforcements(server, encounterId,
                            PhaseOneRuntimeService.INITIAL_BOSS_COUNT - 1));
                    return bosses;
                });
    }

    /**
     * State-machine core for the command route. Keeping the controller and spawner explicit permits isolated
     * transaction tests without mutating a GameTest server's shared SavedData.
     */
    public static StartResult start(EncounterController controller, UUID playerId, ResourceKey<Level> dimension,
                                    BlockPos position, com.mysterious.arena.ArenaConfigSnapshot arena, long tick,
                                    InitialWaveSpawner initialWaveSpawner) {
        if (tick < 0) throw new IllegalArgumentException("tick must be non-negative");
        if (controller.snapshots().values().stream().anyMatch(snapshot -> !snapshot.lifecycle().isTerminal()
                && snapshot.owner().kind() == OwnershipKind.PLAYER
                && snapshot.owner().id().equals(playerId))) {
            throw new IllegalStateException("This player already owns a running encounter");
        }
        UUID encounterId = UUID.randomUUID();
        EncounterCenter center = new EncounterCenter(dimension, position,
                arena);
        controller.createEncounter(encounterId, new RealmOwner(playerId, OwnershipKind.PLAYER),
                center, EncounterPhase.PHASE_ONE, tick);
        controller.joinPlayer(encounterId, playerId, tick);
        try {
            List<UUID> bosses = initialWaveSpawner.spawn(encounterId);
            return new StartResult(encounterId, bosses);
        } catch (RuntimeException exception) {
            try {
                controller.finalizeEncounter(encounterId, EndReason.ERROR_RECOVERY, tick);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /** OP cancellation is normalized through the same cleanup/escrow return path as abandonment. */
    public static EncounterFinalizationResult stop(MinecraftServer server, UUID encounterId, long tick) {
        if (EncounterManager.savedData(server).isSafeMode()) {
            throw new IllegalStateException("Encounter stop is disabled while persistence is in safe mode");
        }
        return stop(EncounterManager.controller(server), encounterId, tick);
    }

    public static EncounterFinalizationResult stop(EncounterController controller, UUID encounterId, long tick) {
        return controller.finalizeEncounter(encounterId, EndReason.ABANDONED, tick);
    }

    /** Finalizes (when necessary) and then removes the persisted encounter record. */
    public static void clear(MinecraftServer server, UUID encounterId, long tick) {
        if (EncounterManager.savedData(server).isSafeMode()) {
            throw new IllegalStateException("Encounter clear is disabled while persistence is in safe mode");
        }
        EncounterController controller = EncounterManager.controller(server);
        EncounterSnapshot snapshot = controller.requireEncounter(encounterId);
        if (!snapshot.lifecycle().isTerminal() || snapshot.cleanupState() != CleanupState.COMMITTED) {
            controller.finalizeEncounter(encounterId, EndReason.ABANDONED, tick);
        }
        controller.purgeEncounter(encounterId);
        EncounterManager.savedData(server).flushDurably(server);
    }

    @FunctionalInterface
    public interface InitialWaveSpawner {
        List<UUID> spawn(UUID encounterId);
    }

    public record StartResult(UUID encounterId, List<UUID> bossIds) {
        public StartResult {
            bossIds = List.copyOf(bossIds);
            if (bossIds.size() != PhaseOneRuntimeService.INITIAL_BOSS_COUNT) {
                throw new IllegalArgumentException("Initial encounter wave must contain five Amon entities");
            }
        }
    }
}
