package com.mysterious.arena;

/** Geometry and timing values frozen when an encounter is created. */
public record ArenaConfigSnapshot(
        int playerJoinRadius,
        int playerRetentionRadius,
        int playerHardExitRadius,
        int bossSoftLeashRadius,
        int bossHardLeashRadius,
        int verticalRadius,
        int joinGraceTicks,
        int retentionGraceTicks,
        int abandonedTimeoutTicks,
        int phaseOneRecoveryDelayTicks,
        double phaseOneRecoveryFractionPerSecond,
        int safePositionAttempts
) {
    public ArenaConfigSnapshot {
        requirePositive(playerJoinRadius, "playerJoinRadius");
        requirePositive(playerRetentionRadius, "playerRetentionRadius");
        requirePositive(playerHardExitRadius, "playerHardExitRadius");
        requirePositive(bossSoftLeashRadius, "bossSoftLeashRadius");
        requirePositive(bossHardLeashRadius, "bossHardLeashRadius");
        requirePositive(verticalRadius, "verticalRadius");
        requireNonNegative(joinGraceTicks, "joinGraceTicks");
        requireNonNegative(retentionGraceTicks, "retentionGraceTicks");
        requireNonNegative(abandonedTimeoutTicks, "abandonedTimeoutTicks");
        requireNonNegative(phaseOneRecoveryDelayTicks, "phaseOneRecoveryDelayTicks");
        if (!Double.isFinite(phaseOneRecoveryFractionPerSecond)
                || phaseOneRecoveryFractionPerSecond < 0.0D
                || phaseOneRecoveryFractionPerSecond > 1.0D) {
            throw new IllegalArgumentException("phaseOneRecoveryFractionPerSecond must be in [0, 1]");
        }
        requirePositive(safePositionAttempts, "safePositionAttempts");
        if (!(playerJoinRadius < playerRetentionRadius
                && playerRetentionRadius < playerHardExitRadius)) {
            throw new IllegalArgumentException("Expected join < retention < hard exit");
        }
        if (!(bossSoftLeashRadius < bossHardLeashRadius
                && bossHardLeashRadius < playerHardExitRadius)) {
            throw new IllegalArgumentException("Expected boss soft < boss hard < player hard exit");
        }
        if (phaseOneRecoveryDelayTicks > abandonedTimeoutTicks) {
            throw new IllegalArgumentException("P1 recovery delay cannot exceed abandoned timeout");
        }
    }

    public static ArenaConfigSnapshot defaults() {
        return new ArenaConfigSnapshot(64, 80, 96, 72, 88, 48,
                3 * 20, 10 * 20, 60 * 20, 20 * 20, 0.02D, 24);
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
