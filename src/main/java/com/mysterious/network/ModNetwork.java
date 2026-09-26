package com.mysterious.network;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;

public final class ModNetwork {
    public static final String PROTOCOL_VERSION = "3";
    private static final Logger LOGGER = LogUtils.getLogger();

    private ModNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(EncounterSnapshotPayload.TYPE, EncounterSnapshotPayload.STREAM_CODEC,
                        ModNetwork::handleSnapshot)
                .playToClient(EncounterDeltaPayload.TYPE, EncounterDeltaPayload.STREAM_CODEC,
                        ModNetwork::handleDelta)
                .playToClient(PhaseEventPayload.TYPE, PhaseEventPayload.STREAM_CODEC,
                        ModNetwork::handlePhaseEvent)
                .playToClient(WarningEventPayload.TYPE, WarningEventPayload.STREAM_CODEC,
                        ModNetwork::handleWarning)
                .playToClient(EncounterClearPayload.TYPE, EncounterClearPayload.STREAM_CODEC,
                        ModNetwork::handleEncounterClear)
                .playToServer(EncounterIntentPayload.TYPE, EncounterIntentPayload.STREAM_CODEC,
                        ModNetwork::handleIntent);
    }

    private static void handleSnapshot(EncounterSnapshotPayload payload, IPayloadContext context) {
        ClientEncounterStateCache.instance().apply(payload);
    }

    private static void handleDelta(EncounterDeltaPayload payload, IPayloadContext context) {
        ClientEncounterStateCache.ApplyResult result = ClientEncounterStateCache.instance().apply(payload);
        if (result == ClientEncounterStateCache.ApplyResult.NEEDS_RESYNC) {
            LOGGER.debug("[network] delta gap encounterId={} baseRevision={} revision={}",
                    payload.encounterId(), payload.baseRevision(), payload.revision());
            PacketDistributor.sendToServer(new EncounterIntentPayload(
                    UUID.randomUUID(), payload.encounterId(), EncounterIntentPayload.Intent.REQUEST_SNAPSHOT));
        }
    }

    private static void handlePhaseEvent(PhaseEventPayload payload, IPayloadContext context) {
        ClientEncounterStateCache.instance().acceptEvent(payload.encounterId(), payload.eventId(), payload.revision());
    }

    private static void handleWarning(WarningEventPayload payload, IPayloadContext context) {
        ClientEncounterStateCache.instance().acceptEvent(payload.encounterId(), payload.eventId(), payload.revision());
    }

    private static void handleEncounterClear(EncounterClearPayload payload, IPayloadContext context) {
        ClientEncounterStateCache.instance().remove(payload.encounterId());
    }

    private static void handleIntent(EncounterIntentPayload request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (EncounterManager.savedData(player.getServer()).isSafeMode()) {
            LOGGER.warn("[network] denied requestId={} player={} reason=safe-mode",
                    request.requestId(), player.getUUID());
            return;
        }
        Optional<EncounterSnapshot> encounter = EncounterManager.controller(player.getServer())
                .findEncounter(request.encounterId());
        if (encounter.isEmpty() || !NetworkIntentGuard.accept(player, request, encounter.orElseThrow())) {
            LOGGER.warn("[network] denied requestId={} encounterId={} player={}",
                    request.requestId(), request.encounterId(), player.getUUID());
            return;
        }
        context.reply(EncounterSnapshotPayload.from(encounter.orElseThrow()));
        LOGGER.debug("[network] accepted requestId={} encounterId={} player={} revision={}",
                request.requestId(), request.encounterId(), player.getUUID(), encounter.orElseThrow().revision());
    }
}
