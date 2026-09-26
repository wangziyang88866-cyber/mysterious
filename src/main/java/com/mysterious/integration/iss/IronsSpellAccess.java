package com.mysterious.integration.iss;

import com.mojang.logging.LogUtils;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellSlot;
import com.mysterious.integration.curios.CuriosAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/** Registry-wide ISS boundary; includes enabled spells contributed by expansion mods. */
public final class IronsSpellAccess {
    private static final Logger LOGGER = LogUtils.getLogger();
    private IronsSpellAccess() {
    }

    public static List<SpellMetadata> enabledSpells() {
        List<SpellMetadata> result = new ArrayList<>();
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            try {
                if (spell.getSpellResource().equals(net.minecraft.resources.ResourceLocation
                        .fromNamespaceAndPath("irons_spellbooks", "none")) || spell.getMaxLevel() < 1) continue;
                result.add(describe(spell, spell.getMaxLevel()));
            } catch (RuntimeException | LinkageError exception) {
                // A broken optional spell must not prevent other expansion spells from entering the pool.
                LOGGER.warn("[spell] skipped incompatible registered spell implementation={}",
                        spell.getClass().getName(), exception);
            }
        }
        return result.stream().sorted(Comparator.comparing(SpellMetadata::id)).toList();
    }

    /** Reads only spells the participant currently has in a usable container. */
    public static List<SpellMetadata> equippedSpells(ServerPlayer player) {
        Map<String, SpellMetadata> result = new LinkedHashMap<>();
        player.getHandSlots().forEach(stack -> collect(stack, true, player, result));
        player.getArmorSlots().forEach(stack -> collect(stack, true, player, result));
        player.getInventory().items.forEach(stack -> collect(stack, false, player, result));
        CuriosAccess.inventory(player).ifPresent(handler -> handler.getCurios().values().forEach(curio -> {
            var stacks = curio.getStacks();
            for (int slot = 0; slot < stacks.getSlots(); slot++) {
                collect(stacks.getStackInSlot(slot), true, player, result);
            }
        }));
        return result.values().stream().sorted(Comparator.comparing(SpellMetadata::id)).toList();
    }

    private static void collect(ItemStack stack, boolean equipped, ServerPlayer player,
                                Map<String, SpellMetadata> result) {
        if (!ISpellContainer.isSpellContainer(stack)) return;
        ISpellContainer container = ISpellContainer.get(stack);
        if (container == null || container.mustEquip() && !equipped) return;
        for (SpellSlot slot : container.getActiveSpells()) {
            AbstractSpell spell = slot.getSpell();
            if (!spell.isEnabled() || !spell.isLearned(player) || slot.getLevel() < spell.getMinLevel()) continue;
            SpellMetadata metadata = describe(spell, Math.min(slot.getLevel(), spell.getMaxLevel()));
            result.merge(metadata.id(), metadata,
                    (left, right) -> left.level() >= right.level() ? left : right);
        }
    }

    private static SpellMetadata describe(AbstractSpell spell, int level) {
        return new SpellMetadata(
                spell.getSpellId(),
                spell.getCastType(),
                spell.getMinLevel(),
                spell.getMaxLevel(),
                spell.getSpellCooldown(),
                level,
                spell.getCastTime(level)
        );
    }

    public record SpellMetadata(String id, CastType castType, int minLevel, int maxLevel,
                                int cooldownTicks, int level, int castTicks) {
    }
}
