package com.mysterious.ownership;

import java.util.Objects;
import java.util.UUID;

/** Stable ownership key persisted in realm and battle save data. */
public record RealmOwner(UUID id, OwnershipKind kind) {
    public RealmOwner {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
    }
}
