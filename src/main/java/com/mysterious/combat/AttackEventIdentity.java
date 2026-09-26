package com.mysterious.combat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Deterministic identity reused by hit teleport and theft de-duplication. */
public record AttackEventIdentity(UUID eventId, UUID encounterId, UUID attackerId,
                                  UUID targetId, UUID directEntityId, long serverTick) {
    public AttackEventIdentity {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(encounterId, "encounterId");
        Objects.requireNonNull(attackerId, "attackerId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(directEntityId, "directEntityId");
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick must be non-negative");
        }
    }

    public static AttackEventIdentity of(UUID encounterId, UUID attackerId, UUID targetId,
                                         UUID directEntityId, long serverTick) {
        String key = encounterId + ":" + attackerId + ":" + targetId + ":" + directEntityId + ":" + serverTick;
        return new AttackEventIdentity(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
                encounterId, attackerId, targetId, directEntityId, serverTick);
    }
}
