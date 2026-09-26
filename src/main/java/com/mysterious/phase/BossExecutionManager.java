package com.mysterious.phase;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Single server-authoritative entry point for threshold-based boss executions. */
public final class BossExecutionManager {
    public static final int PARASITE_THRESHOLD = 30 * 20;
    public static final int CLOCK_THRESHOLD = 20;
    private static final Logger LOGGER = LogUtils.getLogger();

    private BossExecutionManager() {
    }

    public static PhaseThreeState trigger(PhaseThreeState state, Player player,
                                          Set<BossExecutionReason> requestedReasons, long tick) {
        if (requestedReasons.isEmpty() || player.isCreative() || player.isSpectator() || !player.isAlive()
                || state.lastExecutionTicks().getOrDefault(player.getUUID(), -1L) == tick) {
            return state;
        }
        EnumSet<BossExecutionReason> reasons = EnumSet.copyOf(requestedReasons);
        Map<UUID, Long> executionTicks = new LinkedHashMap<>(state.lastExecutionTicks());
        executionTicks.put(player.getUUID(), tick);

        // Magic remains compatible with vanilla totems and cancellable NeoForge death hooks.
        boolean accepted = player.hurt(player.damageSources().magic(), Float.MAX_VALUE);
        if (!accepted) executionTicks.remove(player.getUUID());

        Map<UUID, ParasiteState> parasites = new LinkedHashMap<>(state.parasites());
        Map<UUID, Integer> clockHits = new LinkedHashMap<>(state.clockHits());
        if (accepted) {
            if (reasons.contains(BossExecutionReason.PARASITE)) {
                ParasiteState parasite = parasites.get(player.getUUID());
                if (parasite != null) parasites.put(player.getUUID(), parasite.resetInfection(tick));
            }
            if (reasons.contains(BossExecutionReason.CLOCK)) clockHits.put(player.getUUID(), 0);
        }
        if (accepted && player.isAlive()) {
            LOGGER.info("[boss-execution] protection consumed player={} reasons={} tick={}",
                    player.getUUID(), reasons, tick);
        } else if (accepted) {
            LOGGER.info("[boss-execution] committed player={} reasons={} tick={}", player.getUUID(), reasons, tick);
        }
        return new PhaseThreeState(state.startedAtTick(), state.invulnerableUntilTick(), state.warningAtTick(),
                state.warningSent(), state.timelineCompleted(), state.wormsSpawned(), state.nextWormSpawnTick(),
                state.secondFormActive(), state.nextClockSpawnTick(), clockHits, executionTicks, parasites);
    }
}
