package com.mysterious.config;

/** Frozen Stage 18 release-candidate defaults; changes require balance notes and regression updates. */
public final class ReleaseCandidateBalance {
    public static final String BASELINE = "RC1-2026-09-24";
    public static final int CROSS_DIMENSION_WAIT_TICKS = 40;
    public static final int CROSS_DIMENSION_COOLDOWN_TICKS = 200;
    public static final int CROSS_DIMENSION_SEARCH_ATTEMPTS = 16;
    public static final double CROSS_DIMENSION_MIN_RADIUS = 6.0D;
    public static final double CROSS_DIMENSION_MAX_RADIUS = 10.0D;
    public static final int BLACK_EDGE_RAMP_TICKS = 1200;
    public static final double FLIGHT_SPEED_PER_TICK = 0.55D;
    public static final int MAX_TEMPORARY_ENTITIES = 128;
    public static final int MAX_ACTIVE_SPELL_ENTITIES = 64;
    public static final int MAX_ACTIVE_CLOCKS = 16;
    public static final int MAX_ACTIVE_WORMS = 8;

    private ReleaseCandidateBalance() {
    }
}
