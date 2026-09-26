package com.mysterious.client.model;

import com.mysterious.entity.WormOfTimeEntity;
import com.mysterious.mysterious;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

@SuppressWarnings("deprecation")
public class WormOfTimeModel extends GeoModel<WormOfTimeEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "geo/worm_of_time.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "textures/entity/worm_of_time.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "animations/worm_of_time.animation.json");

    @Override
    public ResourceLocation getModelResource(
            WormOfTimeEntity entity, @Nullable GeoRenderer<WormOfTimeEntity> renderer) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(
            WormOfTimeEntity entity, @Nullable GeoRenderer<WormOfTimeEntity> renderer) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getModelResource(WormOfTimeEntity entity) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(WormOfTimeEntity entity) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(WormOfTimeEntity entity) {
        return ANIMATION;
    }
}
