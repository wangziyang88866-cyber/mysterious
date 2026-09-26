package com.mysterious.inventory;

import com.mysterious.registry.ModDataComponents;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Assigns stable identity to a single physical item.
 *
 * <p>Identity changes stack compatibility, so callers must split a stack down
 * to one item before marking it. This makes the future seal inheritance policy
 * explicit instead of accidentally marking an entire stack.</p>
 */
public final class ItemStackIdentity {
    private ItemStackIdentity() {
    }

    public static Optional<UUID> get(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return Optional.ofNullable(stack.get(ModDataComponents.ITEM_INSTANCE_ID.get()));
    }

    public static UUID getOrCreate(ItemStack stack) {
        requireSingleItem(stack);
        return getOrCreateStackIdentity(stack);
    }

    /**
     * Assigns an identity to a whole stack lineage. This is reserved for temporary restrictions
     * such as seals, where a split must deliberately keep the same restriction on both children.
     */
    public static UUID getOrCreateStackIdentity(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) throw new IllegalArgumentException("Stack identity requires a non-empty item");
        return get(stack).orElseGet(() -> {
            UUID id = UUID.randomUUID();
            stack.set(ModDataComponents.ITEM_INSTANCE_ID.get(), id);
            return id;
        });
    }

    public static void clear(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        stack.remove(ModDataComponents.ITEM_INSTANCE_ID.get());
    }

    private static void requireSingleItem(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty() || stack.getCount() != 1) {
            throw new IllegalArgumentException("Item identity requires one non-empty item; split stacks before marking");
        }
    }
}
