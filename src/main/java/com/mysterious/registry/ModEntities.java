package com.mysterious.registry;

import com.mysterious.mysterious;
import com.mysterious.entity.AmonEntity;
import com.mysterious.entity.PhantomClockEntity;
import com.mysterious.entity.WormOfTimeEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, mysterious.MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<AmonEntity>> AMON = ENTITIES.register("amon",
            () -> EntityType.Builder.of(AmonEntity::new, MobCategory.MONSTER).sized(0.9f, 2.2f)
                    .clientTrackingRange(12).build("amon"));
    public static final DeferredHolder<EntityType<?>, EntityType<PhantomClockEntity>> PHANTOM_CLOCK = ENTITIES.register("phantom_clock",
            () -> EntityType.Builder.of(PhantomClockEntity::new, MobCategory.MISC).sized(4.0f, 5.0f).clientTrackingRange(16).build("phantom_clock"));
    public static final DeferredHolder<EntityType<?>, EntityType<WormOfTimeEntity>> WORM_OF_TIME = ENTITIES.register("worm_of_time",
            () -> EntityType.Builder.of(WormOfTimeEntity::new, MobCategory.MONSTER).sized(0.9f, 0.8f)
                    .fireImmune().clientTrackingRange(10).build("worm_of_time"));

    private ModEntities() {}

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(AMON.get(), AmonEntity.attributes().build());
        event.put(PHANTOM_CLOCK.get(), PhantomClockEntity.attributes().build());
        event.put(WORM_OF_TIME.get(), WormOfTimeEntity.attributes().build());
    }
}
