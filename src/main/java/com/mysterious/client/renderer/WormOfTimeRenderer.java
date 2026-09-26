package com.mysterious.client.renderer;

import com.mysterious.client.model.WormOfTimeModel;
import com.mysterious.entity.WormOfTimeEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class WormOfTimeRenderer extends GeoEntityRenderer<WormOfTimeEntity> {
    public WormOfTimeRenderer(EntityRendererProvider.Context context) { super(context, new WormOfTimeModel()); this.shadowRadius = 0.3f; }
}
