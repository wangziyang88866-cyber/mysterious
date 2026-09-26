package com.mysterious.theft;

import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import com.mysterious.transaction.TransactionState;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rebuilds safe operation-preserving Boss modifiers from committed stolen armor. */
public final class StolenAttributeManager {
    private static final String PATH_PREFIX = "stolen/";

    private StolenAttributeManager() {
    }

    public static void rebuild(UUID encounterId, MinecraftServer server) {
        EncounterSnapshot snapshot = EncounterManager.controller(server).requireEncounter(encounterId);
        List<ModifierPlan> plans = buildPlans(snapshot, server);
        for (var boss : snapshot.bosses().values()) {
            Entity entity = findEntity(server, boss.entityId());
            if (!(entity instanceof AmonEntity amon)) {
                continue;
            }
            double healthRatio = amon.getMaxHealth() <= 0.0F ? 1.0D : amon.getHealth() / amon.getMaxHealth();
            clear(amon);
            plans.forEach(plan -> {
                AttributeInstance instance = amon.getAttribute(plan.attribute());
                if (instance != null) {
                    instance.addOrUpdateTransientModifier(plan.modifier());
                }
            });
            amon.setHealth((float) Math.max(1.0D, Math.min(amon.getMaxHealth(),
                    amon.getMaxHealth() * healthRatio)));
        }
    }

    public static void clearEncounter(UUID encounterId, MinecraftServer server) {
        EncounterManager.controller(server).findEncounter(encounterId).ifPresent(snapshot ->
                snapshot.bosses().values().forEach(record -> {
                    Entity entity = findEntity(server, record.entityId());
                    if (entity instanceof AmonEntity amon) {
                        clear(amon);
                    }
                }));
    }

    public static void clear(AmonEntity amon) {
        safeAttributes().forEach(attribute -> {
            AttributeInstance instance = amon.getAttribute(attribute);
            if (instance == null) {
                return;
            }
            new ArrayList<>(instance.getModifiers()).stream()
                    .filter(modifier -> modifier.id().getNamespace().equals(mysterious.MODID)
                            && modifier.id().getPath().startsWith(PATH_PREFIX))
                    .forEach(instance::removeModifier);
        });
    }

    private static List<ModifierPlan> buildPlans(EncounterSnapshot snapshot, MinecraftServer server) {
        List<ModifierPlan> result = new ArrayList<>();
        Map<Holder<Attribute>, Double> used = new HashMap<>();
        snapshot.escrow().values().stream()
                .filter(record -> record.slotType() == StolenSlotType.ARMOR)
                .filter(record -> record.state() == TransactionState.COMMITTED
                        || record.state() == TransactionState.RETURNING)
                .sorted(Comparator.comparing(record -> record.stolenItemId().toString()))
                .forEach(record -> {
                    ItemStack stack = record.item().decode(server.registryAccess());
                    EquipmentSlot slot = armorSlot(record.slotIndex());
                    stack.forEachModifier(slot, (attribute, modifier) -> {
                        double cap = capFor(attribute, modifier.operation());
                        if (cap <= 0.0D || !Double.isFinite(modifier.amount())) {
                            return;
                        }
                        double already = used.getOrDefault(attribute, 0.0D);
                        double remaining = Math.max(0.0D, cap - already);
                        double amount = Math.copySign(Math.min(Math.abs(modifier.amount()), remaining),
                                modifier.amount());
                        if (amount == 0.0D) {
                            return;
                        }
                        used.put(attribute, already + Math.abs(amount));
                        UUID key = UUID.nameUUIDFromBytes((record.stolenItemId() + ":" + attribute.getRegisteredName()
                                + ":" + modifier.id() + ":" + modifier.operation())
                                .getBytes(StandardCharsets.UTF_8));
                        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(mysterious.MODID,
                                PATH_PREFIX + key.toString().replace("-", ""));
                        result.add(new ModifierPlan(attribute,
                                new AttributeModifier(id, amount, modifier.operation())));
                    });
                });
        return List.copyOf(result);
    }

    private static List<Holder<Attribute>> safeAttributes() {
        return List.of(Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.KNOCKBACK_RESISTANCE,
                Attributes.MAX_HEALTH, Attributes.ATTACK_DAMAGE, Attributes.MOVEMENT_SPEED);
    }

    private static double capFor(Holder<Attribute> attribute, AttributeModifier.Operation operation) {
        double additiveCap;
        double base;
        if (attribute.equals(Attributes.ARMOR)) {
            additiveCap = 30.0D;
            base = 12.0D;
        } else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) {
            additiveCap = 20.0D;
            base = 1.0D;
        } else if (attribute.equals(Attributes.KNOCKBACK_RESISTANCE)) {
            additiveCap = 1.0D;
            base = 1.0D;
        } else if (attribute.equals(Attributes.MAX_HEALTH)) {
            additiveCap = 200.0D;
            base = 200.0D;
        } else if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
            additiveCap = 10.0D;
            base = 10.0D;
        } else if (attribute.equals(Attributes.MOVEMENT_SPEED)) {
            additiveCap = 0.14D;
            base = 0.28D;
        } else {
            return 0.0D;
        }
        return operation == AttributeModifier.Operation.ADD_VALUE ? additiveCap : additiveCap / base;
    }

    private static EquipmentSlot armorSlot(int index) {
        return switch (index) {
            case 0 -> EquipmentSlot.FEET;
            case 1 -> EquipmentSlot.LEGS;
            case 2 -> EquipmentSlot.CHEST;
            case 3 -> EquipmentSlot.HEAD;
            default -> throw new IllegalArgumentException("Invalid armor slot " + index);
        };
    }

    private static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private record ModifierPlan(Holder<Attribute> attribute, AttributeModifier modifier) {
    }
}
