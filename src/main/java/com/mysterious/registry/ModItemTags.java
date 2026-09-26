package com.mysterious.registry;

import com.mysterious.mysterious;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/** Datapack/compatibility extension points for inventory safety rules. */
public final class ModItemTags {
    public static final TagKey<Item> THEFT_BLACKLIST = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "theft_blacklist"));
    public static final TagKey<Item> SEAL_BLACKLIST = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "seal_blacklist"));

    private ModItemTags() {
    }
}
