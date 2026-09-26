package com.mysterious.client;

import com.mysterious.mysterious;
import com.mysterious.client.renderer.AmonRenderer;
import com.mysterious.client.renderer.PhantomClockRenderer;
import com.mysterious.client.renderer.WormOfTimeRenderer;
import com.mysterious.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = mysterious.MODID, value = Dist.CLIENT)
public final class ModEntityRenderers {
    private ModEntityRenderers() {}

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AMON.get(), AmonRenderer::new);
        event.registerEntityRenderer(ModEntities.PHANTOM_CLOCK.get(), PhantomClockRenderer::new);
        event.registerEntityRenderer(ModEntities.WORM_OF_TIME.get(), WormOfTimeRenderer::new);
    }
}
