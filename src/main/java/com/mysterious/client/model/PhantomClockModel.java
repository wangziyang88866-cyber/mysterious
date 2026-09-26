package com.mysterious.client.model;

import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.mysterious;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

@SuppressWarnings("deprecation")
public class PhantomClockModel extends GeoModel<PhantomClockEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "geo/phantom_clock.geo.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "textures/entity/phantom_clock.png");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(mysterious.MODID, "animations/phantom_clock.animation.json");

    @Override
    public ResourceLocation getModelResource(
            PhantomClockEntity entity, @Nullable GeoRenderer<PhantomClockEntity> renderer) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(
            PhantomClockEntity entity, @Nullable GeoRenderer<PhantomClockEntity> renderer) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getModelResource(PhantomClockEntity entity) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(PhantomClockEntity entity) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(PhantomClockEntity entity) {
        return ANIMATION;
    }
}
