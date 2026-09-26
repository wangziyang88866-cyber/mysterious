package com.mysterious.integration.curios;

import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Narrow compatibility boundary around the Curios inventory API. */
public final class CuriosAccess {
    private CuriosAccess() {
    }

    public static Optional<ICuriosItemHandler> inventory(LivingEntity entity) {
        return CuriosApi.getCuriosInventory(entity);
    }

    public static Map<String, Integer> slotCounts(LivingEntity entity) {
        return inventory(entity).map(handler -> {
            Map<String, Integer> result = new TreeMap<>();
            handler.getCurios().forEach((id, slots) -> result.put(id, slots.getSlots()));
            return Map.copyOf(result);
        }).orElseGet(Map::of);
    }
}
