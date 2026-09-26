package com.mysterious.phase;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PhaseThreeState(long startedAtTick, long invulnerableUntilTick, long warningAtTick,
                              boolean warningSent, boolean timelineCompleted,
                              int wormsSpawned, long nextWormSpawnTick,
                              boolean secondFormActive, long nextClockSpawnTick,
                              Map<UUID, Integer> clockHits, Map<UUID, Long> lastExecutionTicks,
                              Map<UUID, ParasiteState> parasites) {
    public PhaseThreeState {
        clockHits = Map.copyOf(Objects.requireNonNull(clockHits, "clockHits"));
        lastExecutionTicks = Map.copyOf(Objects.requireNonNull(lastExecutionTicks, "lastExecutionTicks"));
        parasites = Map.copyOf(Objects.requireNonNull(parasites, "parasites"));
        if (startedAtTick < 0 || invulnerableUntilTick < startedAtTick || warningAtTick < startedAtTick
                || warningAtTick > invulnerableUntilTick || wormsSpawned < 0 || wormsSpawned > 8
                || nextWormSpawnTick < startedAtTick || nextClockSpawnTick < startedAtTick
                || clockHits.values().stream().anyMatch(value -> value == null || value < 0)
                || lastExecutionTicks.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Invalid phase-three state");
        }
    }

    /** Compatibility constructor for Stage 11/12 callers and V5 tests. */
    public PhaseThreeState(long startedAtTick, long invulnerableUntilTick, long warningAtTick,
                           boolean warningSent, boolean timelineCompleted,
                           int wormsSpawned, long nextWormSpawnTick,
                           Map<UUID, ParasiteState> parasites) {
        this(startedAtTick, invulnerableUntilTick, warningAtTick, warningSent, timelineCompleted,
                wormsSpawned, nextWormSpawnTick, false, add(startedAtTick, ClockRuntimeService.CLOCK_INITIAL_DELAY_TICKS),
                Map.of(), Map.of(), parasites);
    }

    public static PhaseThreeState initial(long tick) {
        return new PhaseThreeState(tick, add(tick, 800), add(tick, 740), false, false,
                0, add(tick, PhaseThreeRuntimeService.WORM_SPAWN_INTERVAL_TICKS), false,
                add(tick, ClockRuntimeService.CLOCK_INITIAL_DELAY_TICKS), Map.of(), Map.of(), Map.of());
    }

    /** Legacy storage field; it is now the second-form deadline and no longer grants invulnerability. */
    public long transformationAtTick() {
        return invulnerableUntilTick;
    }

    private static long add(long tick, int amount) {
        return tick > Long.MAX_VALUE - amount ? Long.MAX_VALUE : tick + amount;
    }
}
