package com.mysterious;

import com.mysterious.registry.ModEntities;
import com.mysterious.registry.ModDataComponents;
import com.mysterious.registry.ModEffects;
import com.mysterious.network.ModNetwork;
import com.mysterious.config.MysteriousServerConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.common.Mod;

@Mod(mysterious.MODID)
public class mysterious {
    public static final String MODID = "mysterious";

    public mysterious(IEventBus modBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, MysteriousServerConfig.SPEC);
        ModDataComponents.DATA_COMPONENTS.register(modBus);
        ModEffects.EFFECTS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        modBus.addListener(ModEntities::registerAttributes);
        modBus.addListener(ModNetwork::registerPayloads);
    }
}
