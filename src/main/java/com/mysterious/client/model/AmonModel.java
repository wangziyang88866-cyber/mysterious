package com.mysterious.client.model;

import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

@SuppressWarnings("deprecation")
public class AmonModel extends GeoModel<AmonEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "geo/amon.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "textures/entity/amon.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "animations/amon.animation.json");

    @Override
    public ResourceLocation getModelResource(AmonEntity entity, @Nullable GeoRenderer<AmonEntity> renderer) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AmonEntity entity, @Nullable GeoRenderer<AmonEntity> renderer) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getModelResource(AmonEntity entity) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(AmonEntity entity) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(AmonEntity entity) {
        return ANIMATION;
    }
}
