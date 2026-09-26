package com.mysterious.combat;

public record ThreatEntry(double value, long lastActionTick) {
    public ThreatEntry {
        if (!Double.isFinite(value) || value < 0.0D || lastActionTick < 0) {
            throw new IllegalArgumentException("Invalid threat entry");
        }
    }

    public ThreatEntry add(double amount, long tick) {
        if (!Double.isFinite(amount) || amount < 0.0D || tick < lastActionTick) {
            throw new IllegalArgumentException("Invalid threat update");
        }
        return new ThreatEntry(value + amount, tick);
    }
}
