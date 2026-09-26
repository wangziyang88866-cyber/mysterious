package com.mysterious.phase;

public record ParasiteState(int remainingTicks, int infectionTicks, long lastProcessedTick) {
    public ParasiteState {
        if (remainingTicks < 0 || infectionTicks < 0 || lastProcessedTick < 0) {
            throw new IllegalArgumentException("Invalid parasite state");
        }
    }

    public ParasiteState addDuration(int ticks, long now) {
        if (ticks < 0) throw new IllegalArgumentException("ticks must be non-negative");
        int remaining = remainingTicks > Integer.MAX_VALUE - ticks ? Integer.MAX_VALUE : remainingTicks + ticks;
        return new ParasiteState(remaining, infectionTicks, Math.max(lastProcessedTick, now));
    }

    public ParasiteState resetInfection(long now) {
        return new ParasiteState(remainingTicks, 0, Math.max(lastProcessedTick, now));
    }
}
