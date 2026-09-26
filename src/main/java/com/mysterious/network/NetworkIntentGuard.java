package com.mysterious.network;

import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.ownership.OwnershipKind;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Revalidates every C2S intent from authoritative server state and suppresses replayed request IDs. */
final class NetworkIntentGuard {
    private static final int MAX_RECENT_REQUESTS = 256;
    private static final Map<UUID, RecentRequests> RECENT = new HashMap<>();

    private NetworkIntentGuard() {
    }

    static synchronized boolean accept(ServerPlayer player, EncounterIntentPayload request,
                                       EncounterSnapshot encounter) {
        boolean owner = encounter.owner().kind() == OwnershipKind.PLAYER
                && encounter.owner().id().equals(player.getUUID());
        boolean participant = encounter.players().get(player.getUUID()) != null
                && encounter.players().get(player.getUUID()).participation() != PlayerParticipationState.LEFT;
        if (!owner && !participant) {
            // Team membership must be resolved by the future FTB Teams adapter; never guess client authority.
            return false;
        }
        return RECENT.computeIfAbsent(player.getUUID(), ignored -> new RecentRequests())
                .add(request.requestId());
    }

    private static final class RecentRequests {
        private final Set<UUID> set = new HashSet<>();
        private final ArrayDeque<UUID> order = new ArrayDeque<>();

        boolean add(UUID requestId) {
            if (!set.add(requestId)) {
                return false;
            }
            order.addLast(requestId);
            while (order.size() > MAX_RECENT_REQUESTS) {
                set.remove(order.removeFirst());
            }
            return true;
        }
    }
}
