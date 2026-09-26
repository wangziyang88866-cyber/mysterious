package com.mysterious.spell;

import com.mysterious.combat.DamageDeliveryType;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BossSpellRegistry {
    private static final Map<ResourceLocation, BossSpellDefinition> DEFINITIONS = new LinkedHashMap<>();

    static {
        register(def(id("mysterious", "test_projectile"), 1,
                SpellCategory.TEST, DamageDeliveryType.PROJECTILE, 1));
        register(def(id("mysterious", "test_aoe"), 1,
                SpellCategory.TEST, DamageDeliveryType.AOE, 1));
        register(def(id("mysterious", "test_summon"), 1,
                SpellCategory.TEST, DamageDeliveryType.AOE, 3));

        // These are real ISS spells. Keeping their source IDs in the definition lets the runtime
        // execute the official MOB cast path instead of substituting generic vanilla damage.
        register(def(id("irons_spellbooks", "gravity_fissure"), 5,
                SpellCategory.INTRINSIC, DamageDeliveryType.AOE, 2));
        register(def(id("irons_spellbooks", "arcane_shackle"), 5,
                SpellCategory.INTRINSIC, DamageDeliveryType.BEAM, 1));
        register(def(id("irons_spellbooks", "black_hole"), 5,
                SpellCategory.INTRINSIC, DamageDeliveryType.DOT, 4));
        // Safe offensive legendary pool. Levels stay within each ISS spell's configured maximum.
        // Player-only travel/GUI spells remain excluded from the boss runtime.
        register(def(id("irons_spellbooks", "starfall"), 10,
                SpellCategory.LEGENDARY, DamageDeliveryType.AOE, 4));
        register(def(id("irons_spellbooks", "eldritch_blast"), 5,
                SpellCategory.LEGENDARY, DamageDeliveryType.PROJECTILE, 2));
        register(def(id("irons_spellbooks", "sculk_tentacles"), 4,
                SpellCategory.LEGENDARY, DamageDeliveryType.AOE, 4));
        register(def(id("irons_spellbooks", "sonic_boom"), 3,
                SpellCategory.LEGENDARY, DamageDeliveryType.BEAM, 2));
        register(def(id("irons_spellbooks", "raise_hell"), 5,
                SpellCategory.LEGENDARY, DamageDeliveryType.AOE, 4));
        register(def(id("irons_spellbooks", "abyssal_shroud"), 3,
                SpellCategory.LEGENDARY, DamageDeliveryType.DIRECT_MAGIC, 1));
    }

    private BossSpellRegistry() {
    }

    public static void register(BossSpellDefinition definition) {
        if (DEFINITIONS.putIfAbsent(definition.spellId(), definition) != null) {
            throw new IllegalStateException("Duplicate boss spell " + definition.spellId());
        }
    }

    public static BossSpellDefinition require(ResourceLocation id) {
        BossSpellDefinition definition = DEFINITIONS.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown boss spell " + id);
        return definition;
    }

    public static List<BossSpellDefinition> byCategory(SpellCategory category) {
        return DEFINITIONS.values().stream().filter(value -> value.category() == category).toList();
    }

    private static BossSpellDefinition def(ResourceLocation spellId, int spellLevel,
                                           SpellCategory category, DamageDeliveryType delivery, int cost) {
        return new BossSpellDefinition(spellId, spellLevel, category,
                SpellTargetType.SINGLE_ENTITY, 0, 40, 10, 100, "active", 20,
                false, 2, SpellInterruptPolicy.ON_PHASE_CHANGE, true, delivery, cost, true, true);
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
