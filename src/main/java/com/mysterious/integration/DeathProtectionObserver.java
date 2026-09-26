package com.mysterious.integration;

import com.mojang.logging.LogUtils;
import com.mysterious.mysterious;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingUseTotemEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-mutating proof that the future Boss Execution layer can observe vanilla
 * totems and cancellable death callbacks without bypassing either mechanism.
 */
@EventBusSubscriber(modid = mysterious.MODID)
public final class DeathProtectionObserver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong TOTEM_EVENTS = new AtomicLong();
    private static final AtomicLong CANCELLED_DEATH_EVENTS = new AtomicLong();

    private DeathProtectionObserver() {
    }

    @SubscribeEvent
    public static void onTotemUse(LivingUseTotemEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TOTEM_EVENTS.incrementAndGet();
            LOGGER.debug("[stage0] observed totem protection for player {}", player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDeath(LivingDeathEvent event) {
        if (event.isCanceled() && event.getEntity() instanceof ServerPlayer player) {
            CANCELLED_DEATH_EVENTS.incrementAndGet();
            LOGGER.debug("[stage0] observed cancelled death for player {}", player.getUUID());
        }
    }

    public static long observedTotemEvents() {
        return TOTEM_EVENTS.get();
    }

    public static long observedCancelledDeathEvents() {
        return CANCELLED_DEATH_EVENTS.get();
    }
}
