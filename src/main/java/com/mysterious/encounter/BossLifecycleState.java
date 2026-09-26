package com.mysterious.encounter;

/**
 * Registry knowledge about a boss. Unloaded and missing bosses remain alive;
 * only an explicit death or legal removal commits a terminal state.
 */
public enum BossLifecycleState {
    LOADED(false),
    UNLOADED(false),
    MISSING_PENDING(false),
    DEAD(true),
    REMOVED(true);

    private final boolean terminal;

    BossLifecycleState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
