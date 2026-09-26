package com.mysterious.network;

import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PhaseTransitionRecord;
import com.mysterious.encounter.PlayerParticipationState;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Explicit server-to-client publication boundary for snapshots, deltas and one-shot events. */
public final class NetworkSyncService {
    private static final Map<MinecraftServer, SyncState> STATES = new WeakHashMap<>();

    private NetworkSyncService() {
    }

    public static void sendSnapshot(ServerPlayer player, EncounterSnapshot snapshot) {
        PacketDistributor.sendToPlayer(Objects.requireNonNull(player, "player"),
                EncounterSnapshotPayload.from(Objects.requireNonNull(snapshot, "snapshot")));
    }

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(Objects.requireNonNull(player, "player"),
                Objects.requireNonNull(payload, "payload"));
    }

    /** Publishes authoritative changes and clears clients that are no longer participants. */
    public static synchronized void publish(MinecraftServer server, Map<UUID, EncounterSnapshot> snapshots) {
        SyncState state = STATES.computeIfAbsent(server, ignored -> new SyncState());
        Set<PlayerEncounterKey> currentRecipients = new HashSet<>();

        for (EncounterSnapshot snapshot : snapshots.values()) {
            EncounterSnapshotPayload payload = EncounterSnapshotPayload.from(snapshot);
            if (snapshot.lifecycle().isTerminal()) {
                state.lastEncounter.put(snapshot.encounterId(), snapshot);
                continue;
            }
            for (var playerState : snapshot.players().values()) {
                if (playerState.participation() == PlayerParticipationState.LEFT) {
                    continue;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(playerState.playerId());
                if (player == null) {
                    continue;
                }
                PlayerEncounterKey key = new PlayerEncounterKey(player.getUUID(), snapshot.encounterId());
                currentRecipients.add(key);
                EncounterSnapshotPayload previous = state.sent.get(key);
                if (previous == null || previous.revision() >= payload.revision()
                        || !sameDeltaShape(previous, payload)) {
                    if (previous == null || !previous.equals(payload)) {
                        sendSnapshot(player, snapshot);
                    }
                } else {
                    send(player, new EncounterDeltaPayload(snapshot.encounterId(), previous.revision(),
                            payload.revision(), snapshot.lifecycle(), snapshot.phase(), snapshot.livingBossCount()));
                }
                state.sent.put(key, payload);
            }
            publishPhaseEvent(state, server, snapshot, currentRecipients);
            state.lastEncounter.put(snapshot.encounterId(), snapshot);
        }

        for (PlayerEncounterKey key : Set.copyOf(state.sent.keySet())) {
            if (currentRecipients.contains(key)) {
                continue;
            }
            EncounterSnapshotPayload previous = state.sent.remove(key);
            ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
            if (player != null) {
                long revision = snapshots.containsKey(key.encounterId())
                        ? snapshots.get(key.encounterId()).revision() : previous.revision();
                send(player, new EncounterClearPayload(key.encounterId(), revision));
            }
        }
    }

    public static synchronized void resyncPlayer(MinecraftServer server, ServerPlayer player,
                                                  Map<UUID, EncounterSnapshot> snapshots) {
        SyncState state = STATES.computeIfAbsent(server, ignored -> new SyncState());
        snapshots.values().stream()
                .filter(snapshot -> snapshot.players().get(player.getUUID()) != null)
                .filter(snapshot -> snapshot.players().get(player.getUUID()).participation()
                        != PlayerParticipationState.LEFT)
                .forEach(snapshot -> {
                    sendSnapshot(player, snapshot);
                    state.sent.put(new PlayerEncounterKey(player.getUUID(), snapshot.encounterId()),
                            EncounterSnapshotPayload.from(snapshot));
                });
    }

    public static synchronized void clearServer(MinecraftServer server) {
        STATES.remove(server);
    }

    public static synchronized Diagnostics diagnostics(MinecraftServer server) {
        SyncState state = STATES.get(server);
        return state == null ? new Diagnostics(0, 0)
                : new Diagnostics(state.sent.size(), state.lastEncounter.size());
    }

    public static synchronized void clearEncounter(MinecraftServer server, EncounterSnapshot snapshot) {
        SyncState state = STATES.computeIfAbsent(server, ignored -> new SyncState());
        for (var playerState : snapshot.players().values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerState.playerId());
            if (player != null) {
                send(player, new EncounterClearPayload(snapshot.encounterId(), snapshot.revision()));
            }
            state.sent.remove(new PlayerEncounterKey(playerState.playerId(), snapshot.encounterId()));
        }
    }

    private static boolean sameDeltaShape(EncounterSnapshotPayload previous, EncounterSnapshotPayload current) {
        return previous.encounterId().equals(current.encounterId())
                && previous.centerDimension().equals(current.centerDimension())
                && previous.center().equals(current.center())
                && previous.bosses().equals(current.bosses());
    }

    private static void publishPhaseEvent(SyncState state, MinecraftServer server, EncounterSnapshot current,
                                          Set<PlayerEncounterKey> recipients) {
        EncounterSnapshot previous = state.lastEncounter.get(current.encounterId());
        if (previous == null) {
            return;
        }
        PhaseTransitionRecord transition = current.activeTransition().orElse(null);
        boolean committed = false;
        if (transition == null && current.lastCommittedTransitionId().isPresent()
                && !current.lastCommittedTransitionId().equals(previous.lastCommittedTransitionId())
                && previous.activeTransition().isPresent()) {
            transition = previous.activeTransition().orElseThrow();
            committed = true;
        } else if (transition == null || current.activeTransition().equals(previous.activeTransition())) {
            return;
        }
        String kind = committed ? "phase-commit" : "phase-claim";
        PhaseEventPayload event = new PhaseEventPayload(current.encounterId(),
                eventId(transition.transitionId(), kind, current.revision()), current.revision(),
                transition.transitionId(), transition.from(), transition.to(), committed);
        for (PlayerEncounterKey key : recipients) {
            if (key.encounterId().equals(current.encounterId())) {
                ServerPlayer player = server.getPlayerList().getPlayer(key.playerId());
                if (player != null) {
                    send(player, event);
                }
            }
        }
    }

    private static UUID eventId(UUID source, String kind, long revision) {
        return UUID.nameUUIDFromBytes((source + ":" + kind + ":" + revision)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record PlayerEncounterKey(UUID playerId, UUID encounterId) {
    }

    public record Diagnostics(int activeRecipientStreams, int observedEncounters) {
    }

    private static final class SyncState {
        private final Map<PlayerEncounterKey, EncounterSnapshotPayload> sent = new HashMap<>();
        private final Map<UUID, EncounterSnapshot> lastEncounter = new HashMap<>();
    }
}
