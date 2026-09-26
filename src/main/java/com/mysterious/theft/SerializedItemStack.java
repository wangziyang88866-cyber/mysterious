package com.mysterious.theft;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** Defensive immutable-by-copy NBT image suitable for snapshot equality and persistence. */
public final class SerializedItemStack {
    private final CompoundTag tag;

    public SerializedItemStack(CompoundTag tag) {
        this.tag = Objects.requireNonNull(tag, "tag").copy();
    }

    public static SerializedItemStack of(ItemStack stack, HolderLookup.Provider registries) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty() || stack.getCount() != 1) {
            throw new IllegalArgumentException("Escrow snapshot must contain exactly one item");
        }
        Tag encoded = stack.save(registries);
        if (!(encoded instanceof CompoundTag compound)) {
            throw new IllegalStateException("ItemStack did not encode as a compound");
        }
        return new SerializedItemStack(compound);
    }

    public ItemStack decode(HolderLookup.Provider registries) {
        return ItemStack.parse(registries, tag.copy())
                .orElseThrow(() -> new IllegalStateException("Unable to decode escrow item"));
    }

    public CompoundTag tag() {
        return tag.copy();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SerializedItemStack image && tag.equals(image.tag);
    }

    @Override
    public int hashCode() {
        return tag.hashCode();
    }

    @Override
    public String toString() {
        return tag.toString();
    }
}
