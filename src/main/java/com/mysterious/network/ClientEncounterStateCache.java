package com.mysterious.network;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.List;

/** Client projection. Server authority is retained by requiring contiguous revisions for deltas. */
public final class ClientEncounterStateCache {
    public enum ApplyResult {
        APPLIED,
        DUPLICATE,
        STALE,
        NEEDS_RESYNC
    }

    private static final int MAX_EVENT_IDS = 512;
    private static final ClientEncounterStateCache INSTANCE = new ClientEncounterStateCache();

    private final Map<UUID, EncounterSnapshotPayload> encounters = new HashMap<>();
    private final Map<UUID, SeenEvents> seenEvents = new HashMap<>();
    private final Set<UUID> resyncPending = new HashSet<>();

    public static ClientEncounterStateCache instance() {
        return INSTANCE;
    }

    public synchronized ApplyResult apply(EncounterSnapshotPayload snapshot) {
        EncounterSnapshotPayload current = encounters.get(snapshot.encounterId());
        if (current != null && snapshot.revision() < current.revision()) {
            return ApplyResult.STALE;
        }
        if (current != null && snapshot.revision() == current.revision()) {
            if (current.equals(snapshot)) {
                return ApplyResult.DUPLICATE;
            }
            return resyncPending.add(snapshot.encounterId()) ? ApplyResult.NEEDS_RESYNC : ApplyResult.DUPLICATE;
        }
        encounters.put(snapshot.encounterId(), snapshot);
        resyncPending.remove(snapshot.encounterId());
        return ApplyResult.APPLIED;
    }

    public synchronized ApplyResult apply(EncounterDeltaPayload delta) {
        EncounterSnapshotPayload current = encounters.get(delta.encounterId());
        if (current != null && delta.revision() <= current.revision()) {
            return ApplyResult.STALE;
        }
        if (current == null || current.revision() != delta.baseRevision()) {
            return resyncPending.add(delta.encounterId()) ? ApplyResult.NEEDS_RESYNC : ApplyResult.DUPLICATE;
        }
        encounters.put(delta.encounterId(), new EncounterSnapshotPayload(delta.encounterId(), delta.revision(),
                delta.lifecycle(), delta.phase(), current.centerDimension(), current.center(),
                delta.livingBossCount(), current.bosses()));
        return ApplyResult.APPLIED;
    }

    public synchronized ApplyResult acceptEvent(UUID encounterId, UUID eventId, long revision) {
        EncounterSnapshotPayload current = encounters.get(encounterId);
        if (current != null && revision < current.revision()) {
            return ApplyResult.STALE;
        }
        return seenEvents.computeIfAbsent(encounterId, ignored -> new SeenEvents()).add(eventId)
                ? ApplyResult.APPLIED : ApplyResult.DUPLICATE;
    }

    public synchronized Optional<EncounterSnapshotPayload> find(UUID encounterId) {
        return Optional.ofNullable(encounters.get(encounterId));
    }

    public synchronized List<EncounterSnapshotPayload> snapshots() {
        return List.copyOf(encounters.values());
    }

    public synchronized void remove(UUID encounterId) {
        encounters.remove(encounterId);
        seenEvents.remove(encounterId);
        resyncPending.remove(encounterId);
    }

    public synchronized void clear() {
        encounters.clear();
        seenEvents.clear();
        resyncPending.clear();
    }

    private static final class SeenEvents {
        private final Set<UUID> set = new HashSet<>();
        private final ArrayDeque<UUID> order = new ArrayDeque<>();

        boolean add(UUID id) {
            if (!set.add(id)) {
                return false;
            }
            order.addLast(id);
            while (order.size() > MAX_EVENT_IDS) {
                set.remove(order.removeFirst());
            }
            return true;
        }
    }
}
