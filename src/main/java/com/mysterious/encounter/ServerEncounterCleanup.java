package com.mysterious.encounter;

import com.mysterious.phase.SealManager;
import com.mysterious.registry.ModEffects;
import com.mysterious.network.NetworkSyncService;
import com.mysterious.theft.EscrowReturnService;
import com.mysterious.theft.StolenAttributeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.ArrayList;

/** Idempotent server-side cleanup for the complete encounter runtime. */
final class ServerEncounterCleanup implements EncounterCleanup {
    private final MinecraftServer server;

    ServerEncounterCleanup(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void cleanup(EncounterSnapshot snapshot, EncounterTermination termination) {
        SealManager.clearEncounter(server, snapshot.encounterId());
        snapshot.players().keySet().forEach(playerId -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player != null) player.removeEffect(ModEffects.PARASITE);
        });
        StolenAttributeManager.clearEncounter(snapshot.encounterId(), server);
        EscrowReturnService.returnOrTransfer(server, snapshot);
        NetworkSyncService.clearEncounter(server, snapshot);
        for (BossRecord boss : snapshot.bosses().values()) {
            for (var level : server.getAllLevels()) {
                Entity entity = level.getEntity(boss.entityId());
                if (entity instanceof EncounterBoundEntity bound
                        && bound.mysterious$getEncounterId().filter(snapshot.encounterId()::equals).isPresent()) {
                    entity.discard();
                    break;
                }
            }
        }
        for (var level : server.getAllLevels()) {
            var owned = new ArrayList<Entity>();
            level.getAllEntities().forEach(entity -> {
                if (entity instanceof EncounterBoundEntity bound
                        && bound.mysterious$getEncounterId().filter(snapshot.encounterId()::equals).isPresent()) {
                    owned.add(entity);
                }
            });
            owned.forEach(Entity::discard);
        }
    }
}
