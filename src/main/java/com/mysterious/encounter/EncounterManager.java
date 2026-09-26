package com.mysterious.encounter;

import com.mysterious.mysterious;
import com.mysterious.persistence.EncounterSavedData;
import com.mysterious.network.NetworkSyncService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Owns the single EncounterController associated with each running server. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class EncounterManager {
    private static final Map<MinecraftServer, RuntimeState> RUNTIMES = new WeakHashMap<>();

    private EncounterManager() {
    }

    public static synchronized EncounterController controller(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return RUNTIMES.computeIfAbsent(server, EncounterManager::createRuntime).controller();
    }

    public static synchronized EncounterSavedData savedData(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return RUNTIMES.computeIfAbsent(server, EncounterManager::createRuntime).savedData();
    }

    @SubscribeEvent
    public static synchronized void onServerStopping(ServerStoppingEvent event) {
        RuntimeState runtime = RUNTIMES.get(event.getServer());
        if (runtime != null) {
            runtime.savedData().captureFrom(runtime.controller());
            runtime.savedData().flushDurably(event.getServer());
        }
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        RUNTIMES.remove(event.getServer());
        NetworkSyncService.clearServer(event.getServer());
    }

    private static RuntimeState createRuntime(MinecraftServer server) {
        EncounterSavedData savedData = EncounterSavedData.get(server);
        EncounterController controller = new EncounterController(new ServerEncounterCleanup(server));
        savedData.restoreInto(controller);
        controller.setMutationEnabled(!savedData.isSafeMode());
        controller.setMutationListener(() -> {
            savedData.captureFrom(controller);
            NetworkSyncService.publish(server, controller.snapshots());
        });
        controller.setDurabilityBarrier(() -> {
            savedData.captureFrom(controller);
            savedData.flushDurably(server);
        });
        savedData.pendingReturns().setMutationListener(savedData::setDirty);
        savedData.pendingReturns().setDurabilityBarrier(() -> {
            savedData.captureFrom(controller);
            savedData.flushDurably(server);
        });
        return new RuntimeState(controller, savedData);
    }

    private record RuntimeState(EncounterController controller, EncounterSavedData savedData) {
    }
}
