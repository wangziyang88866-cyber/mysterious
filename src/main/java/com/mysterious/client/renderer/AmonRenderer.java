package com.mysterious.client.renderer;

import com.mysterious.client.model.AmonModel;
import com.mysterious.entity.AmonEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class AmonRenderer extends GeoEntityRenderer<AmonEntity> {
    public AmonRenderer(EntityRendererProvider.Context context) { super(context, new AmonModel()); this.shadowRadius = 0.6f; }
}
