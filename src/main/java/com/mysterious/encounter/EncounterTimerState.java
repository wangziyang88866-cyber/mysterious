package com.mysterious.encounter;

import java.util.Objects;
import java.util.OptionalLong;

/** Basic timers persisted before later mechanics add their own versioned state. */
public record EncounterTimerState(
        long phaseStartedAtTick,
        OptionalLong abandonedSinceTick,
        OptionalLong crossDimensionCooldownUntilTick
) {
    public EncounterTimerState {
        if (phaseStartedAtTick < 0) {
            throw new IllegalArgumentException("phaseStartedAtTick must be non-negative");
        }
        Objects.requireNonNull(abandonedSinceTick, "abandonedSinceTick");
        Objects.requireNonNull(crossDimensionCooldownUntilTick, "crossDimensionCooldownUntilTick");
        abandonedSinceTick.ifPresent(value -> requireNonNegative(value, "abandonedSinceTick"));
        crossDimensionCooldownUntilTick.ifPresent(
                value -> requireNonNegative(value, "crossDimensionCooldownUntilTick"));
    }

    public static EncounterTimerState initial(long createdAtTick) {
        return new EncounterTimerState(createdAtTick, OptionalLong.empty(), OptionalLong.empty());
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
