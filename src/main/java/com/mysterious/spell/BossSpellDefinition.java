package com.mysterious.spell;

import com.mysterious.combat.DamageDeliveryType;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Immutable, player-independent contract executed by the boss spell runtime. */
public record BossSpellDefinition(
        ResourceLocation spellId,
        int spellLevel,
        SpellCategory category,
        SpellTargetType targetType,
        double minimumRange,
        double maximumRange,
        int prepareTicks,
        int cooldownTicks,
        String castGroup,
        int castDurationTicks,
        boolean allowConcurrent,
        int maxConcurrentInstances,
        SpellInterruptPolicy interruptPolicy,
        boolean globalCastLock,
        DamageDeliveryType damageDeliveryType,
        int entityBudgetCost,
        boolean recoverable,
        boolean cleanupOnPhaseChange
) {
    public BossSpellDefinition {
        Objects.requireNonNull(spellId, "spellId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(castGroup, "castGroup");
        Objects.requireNonNull(interruptPolicy, "interruptPolicy");
        Objects.requireNonNull(damageDeliveryType, "damageDeliveryType");
        if (!Double.isFinite(minimumRange) || !Double.isFinite(maximumRange)
                || spellLevel < 1 || minimumRange < 0 || maximumRange < minimumRange || prepareTicks < 0
                || cooldownTicks < 0 || castDurationTicks < 0 || maxConcurrentInstances < 1
                || entityBudgetCost < 0) {
            throw new IllegalArgumentException("Invalid boss spell definition bounds");
        }
    }
}
