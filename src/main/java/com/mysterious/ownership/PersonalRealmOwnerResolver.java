package com.mysterious.ownership;

import net.minecraft.server.level.ServerPlayer;

public final class PersonalRealmOwnerResolver implements RealmOwnerResolver {
    @Override
    public RealmOwner resolve(ServerPlayer player) {
        return new RealmOwner(player.getUUID(), OwnershipKind.PLAYER);
    }
}
