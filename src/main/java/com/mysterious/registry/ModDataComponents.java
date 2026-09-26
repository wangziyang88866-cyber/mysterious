package com.mysterious.registry;

import com.mojang.serialization.Codec;
import com.mysterious.mysterious;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;

/** Persistent and network-synchronized item identity used by later seal logic. */
public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, mysterious.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> ITEM_INSTANCE_ID =
            DATA_COMPONENTS.registerComponentType("item_instance_id", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> SEAL_ID =
            DATA_COMPONENTS.registerComponentType("seal_id", builder -> builder
                    .persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> SEAL_ENCOUNTER_ID =
            DATA_COMPONENTS.registerComponentType("seal_encounter_id", builder -> builder
                    .persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> SEALED_UNTIL_TICK =
            DATA_COMPONENTS.registerComponentType("sealed_until_tick", builder -> builder
                    .persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG));

    private ModDataComponents() {
    }
}
