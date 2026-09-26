package com.mysterious.registry;

import com.mysterious.mysterious;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT,
            mysterious.MODID);
    public static final DeferredHolder<MobEffect, MobEffect> PARASITE = EFFECTS.register("parasite",
            () -> new ParasiteEffect(MobEffectCategory.HARMFUL, 0x332244));

    private ModEffects() {
    }

    private static final class ParasiteEffect extends MobEffect {
        private ParasiteEffect(MobEffectCategory category, int color) {
            super(category, color);
        }
    }
}
