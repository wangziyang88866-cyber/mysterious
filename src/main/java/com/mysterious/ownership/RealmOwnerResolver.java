package com.mysterious.ownership;

import net.minecraft.server.level.ServerPlayer;

/**
 * Resolves the owner key for a player. The default implementation is personal;
 * the FTB Teams adapter replaces it only when that optional mod is present.
 */
public interface RealmOwnerResolver {
    RealmOwner resolve(ServerPlayer player);
}
