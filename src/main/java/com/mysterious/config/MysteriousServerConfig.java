package com.mysterious.config;

import com.mojang.logging.LogUtils;
import com.mysterious.arena.ArenaConfigSnapshot;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Server-side defaults. Values that affect an encounter are frozen into its arena snapshot. */
public final class MysteriousServerConfig {
    public static final ModConfigSpec SPEC;
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ModConfigSpec.IntValue PLAYER_JOIN_RADIUS;
    private static final ModConfigSpec.IntValue PLAYER_RETENTION_RADIUS;
    private static final ModConfigSpec.IntValue PLAYER_HARD_EXIT_RADIUS;
    private static final ModConfigSpec.IntValue BOSS_SOFT_LEASH_RADIUS;
    private static final ModConfigSpec.IntValue BOSS_HARD_LEASH_RADIUS;
    private static final ModConfigSpec.IntValue VERTICAL_RADIUS;
    private static final ModConfigSpec.IntValue JOIN_GRACE_TICKS;
    private static final ModConfigSpec.IntValue RETENTION_GRACE_TICKS;
    private static final ModConfigSpec.IntValue ABANDONED_TIMEOUT_TICKS;
    private static final ModConfigSpec.IntValue PHASE_ONE_RECOVERY_DELAY_TICKS;
    private static final ModConfigSpec.DoubleValue PHASE_ONE_RECOVERY_FRACTION_PER_SECOND;
    private static final ModConfigSpec.IntValue SAFE_POSITION_ATTEMPTS;
    private static final ModConfigSpec.DoubleValue HIT_TELEPORT_CHANCE;
    private static final ModConfigSpec.IntValue HIT_TELEPORT_COOLDOWN_TICKS;
    private static final ModConfigSpec.DoubleValue THEFT_CHANCE;
    private static final ModConfigSpec.IntValue THEFT_PROTECTION_TICKS;
    private static final ModConfigSpec.IntValue WORM_ATTACK_INTERVAL_TICKS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> THEFT_PROTECTED_ENCHANTMENTS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("Values in this section are copied into each new encounter and do not change on reload.")
                .push("encounterArena");
        PLAYER_JOIN_RADIUS = builder.defineInRange("playerJoinRadius", 64, 1, 2048);
        PLAYER_RETENTION_RADIUS = builder.defineInRange("playerRetentionRadius", 80, 1, 2048);
        PLAYER_HARD_EXIT_RADIUS = builder.defineInRange("playerHardExitRadius", 96, 1, 2048);
        BOSS_SOFT_LEASH_RADIUS = builder.defineInRange("bossSoftLeashRadius", 72, 1, 2048);
        BOSS_HARD_LEASH_RADIUS = builder.defineInRange("bossHardLeashRadius", 88, 1, 2048);
        VERTICAL_RADIUS = builder.defineInRange("verticalRadius", 48, 1, 512);
        JOIN_GRACE_TICKS = builder.defineInRange("joinGraceTicks", 3 * 20, 0, 20 * 60);
        RETENTION_GRACE_TICKS = builder.defineInRange("retentionGraceTicks", 10 * 20, 0, 20 * 60);
        ABANDONED_TIMEOUT_TICKS = builder.defineInRange("abandonedTimeoutTicks", 60 * 20, 1, 20 * 60 * 60);
        PHASE_ONE_RECOVERY_DELAY_TICKS = builder.defineInRange(
                "phaseOneRecoveryDelayTicks", 20 * 20, 0, 20 * 60 * 60);
        PHASE_ONE_RECOVERY_FRACTION_PER_SECOND = builder.defineInRange(
                "phaseOneRecoveryFractionPerSecond", 0.02D, 0.0D, 1.0D);
        SAFE_POSITION_ATTEMPTS = builder.defineInRange("safePositionAttempts", 24, 1, 128);
        builder.pop();
        builder.comment("Live combat safety rules. Existing encounters use the latest server value.")
                .push("combat");
        HIT_TELEPORT_CHANCE = builder.defineInRange("hitTeleportChance", 0.10D, 0.0D, 1.0D);
        HIT_TELEPORT_COOLDOWN_TICKS = builder.defineInRange("hitTeleportCooldownTicks", 2 * 20, 0, 20 * 60);
        THEFT_CHANCE = builder.defineInRange("theftChance", 0.60D, 0.0D, 1.0D);
        THEFT_PROTECTION_TICKS = builder.defineInRange("theftProtectionTicks", 20, 0, 20 * 60);
        WORM_ATTACK_INTERVAL_TICKS = builder.defineInRange("wormAttackIntervalTicks", 2 * 20, 1, 20 * 60);
        THEFT_PROTECTED_ENCHANTMENTS = builder.defineListAllowEmpty("theftProtectedEnchantments", List.of(),
                value -> value instanceof String id && net.minecraft.resources.ResourceLocation.tryParse(id) != null);
        builder.pop();
        SPEC = builder.build();
    }

    private MysteriousServerConfig() {
    }

    /** Invalid cross-field relationships fail safely to the documented defaults. */
    public static ArenaConfigSnapshot snapshot() {
        try {
            return new ArenaConfigSnapshot(
                    PLAYER_JOIN_RADIUS.get(), PLAYER_RETENTION_RADIUS.get(), PLAYER_HARD_EXIT_RADIUS.get(),
                    BOSS_SOFT_LEASH_RADIUS.get(), BOSS_HARD_LEASH_RADIUS.get(), VERTICAL_RADIUS.get(),
                    JOIN_GRACE_TICKS.get(), RETENTION_GRACE_TICKS.get(), ABANDONED_TIMEOUT_TICKS.get(),
                    PHASE_ONE_RECOVERY_DELAY_TICKS.get(),
                    PHASE_ONE_RECOVERY_FRACTION_PER_SECOND.get(), SAFE_POSITION_ATTEMPTS.get());
        } catch (IllegalArgumentException exception) {
            LOGGER.error("[config] invalid encounter arena relationships; using safe defaults", exception);
            return ArenaConfigSnapshot.defaults();
        }
    }

    public static double hitTeleportChance() {
        return HIT_TELEPORT_CHANCE.get();
    }

    public static int hitTeleportCooldownTicks() {
        return HIT_TELEPORT_COOLDOWN_TICKS.get();
    }

    public static double theftChance() {
        return THEFT_CHANCE.get();
    }

    public static int theftProtectionTicks() {
        return THEFT_PROTECTION_TICKS.get();
    }

    public static int wormAttackIntervalTicks() {
        return WORM_ATTACK_INTERVAL_TICKS.get();
    }

    public static Set<String> theftProtectedEnchantments() {
        return THEFT_PROTECTED_ENCHANTMENTS.get().stream().map(String::valueOf).collect(Collectors.toUnmodifiableSet());
    }
}
