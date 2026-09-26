package com.mysterious.client.renderer;

import com.mysterious.client.model.PhantomClockModel;
import com.mysterious.entity.PhantomClockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class PhantomClockRenderer extends GeoEntityRenderer<PhantomClockEntity> {
    public PhantomClockRenderer(EntityRendererProvider.Context context) { super(context, new PhantomClockModel()); this.shadowRadius = 0; }

    @Override public RenderType getRenderType(PhantomClockEntity entity, ResourceLocation texture,
                                              @Nullable MultiBufferSource buffers, float partialTick) {
        return RenderType.entityTranslucent(texture);
    }
}
