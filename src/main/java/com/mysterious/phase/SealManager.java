package com.mysterious.phase;

import com.mysterious.integration.curios.CuriosAccess;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.inventory.ItemStackIdentity;
import com.mysterious.mysterious;
import com.mysterious.registry.ModDataComponents;
import com.mysterious.registry.ModItemTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

/** Persistent item-identity seals. Slot movement cannot detach the restriction. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class SealManager {
    public static final int DEFAULT_DURATION_TICKS = 8 * 20;
    public static final int MAX_ACTIVE_PER_PLAYER = 3;

    private SealManager() {
    }

    public static boolean trySeal(ServerPlayer player, UUID encounterId, long tick) {
        List<StackRef> refs = allStacks(player);
        if (refs.stream().filter(ref -> isActive(ref.get(), tick)).count() >= MAX_ACTIVE_PER_PLAYER) return false;
        List<StackRef> eligible = refs.stream().filter(ref -> eligible(ref.get(), tick)).toList();
        if (eligible.isEmpty()) return false;
        StackRef selected = eligible.get(player.getRandom().nextInt(eligible.size()));
        ItemStack stack = selected.get();
        seal(stack, encounterId, tick);
        // A newly sealed stack must extend an older cooldown belonging to another stack of the
        // same item type instead of waiting for that shorter cooldown to expire first.
        player.getCooldowns().addCooldown(stack.getItem(), remainingCooldownTicks(stack, tick));
        selected.set(stack);
        player.getInventory().setChanged();
        player.displayClientMessage(Component.translatable("message.mysterious.item_sealed", stack.getHoverName()), true);
        player.playNotifySound(SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.HOSTILE, 0.9F, 0.55F);
        return true;
    }

    public static UUID seal(ItemStack stack, UUID encounterId, long tick) {
        Objects.requireNonNull(encounterId, "encounterId");
        if (tick < 0 || stack.isEmpty()) {
            throw new IllegalArgumentException("A seal requires an item and a non-negative tick");
        }
        ItemStackIdentity.getOrCreateStackIdentity(stack);
        UUID sealId = UUID.randomUUID();
        stack.set(ModDataComponents.SEAL_ID.get(), sealId);
        stack.set(ModDataComponents.SEAL_ENCOUNTER_ID.get(), encounterId);
        stack.set(ModDataComponents.SEALED_UNTIL_TICK.get(), safeAdd(tick, DEFAULT_DURATION_TICKS));
        return sealId;
    }

    public static boolean isActive(ItemStack stack, long tick) {
        Long until = stack.get(ModDataComponents.SEALED_UNTIL_TICK.get());
        return until != null && until > tick && stack.has(ModDataComponents.SEAL_ID.get());
    }

    /** Remaining seal time expressed in the integer duration expected by vanilla item cooldowns. */
    public static int remainingCooldownTicks(ItemStack stack, long tick) {
        Long until = stack.get(ModDataComponents.SEALED_UNTIL_TICK.get());
        if (until == null || until <= tick || !stack.has(ModDataComponents.SEAL_ID.get())) return 0;
        return (int) Math.min(Integer.MAX_VALUE, until - tick);
    }

    public static void clear(ItemStack stack) {
        stack.remove(ModDataComponents.SEAL_ID.get());
        stack.remove(ModDataComponents.SEAL_ENCOUNTER_ID.get());
        stack.remove(ModDataComponents.SEALED_UNTIL_TICK.get());
    }

    public static void clearEncounter(MinecraftServer server, UUID encounterId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<Item> clearedItems = new HashSet<>();
            for (StackRef ref : allStacks(player)) {
                ItemStack stack = ref.get();
                if (encounterId.equals(stack.get(ModDataComponents.SEAL_ENCOUNTER_ID.get()))) {
                    clearedItems.add(stack.getItem());
                    clear(stack);
                }
            }
            clearedItems.stream().filter(item -> !hasActiveSeal(player, item, Long.MIN_VALUE))
                    .forEach(item -> player.getCooldowns().removeCooldown(item));
            player.getInventory().setChanged();
        }
    }

    /** Removes seals left on an offline player's item after their encounter was finalized. */
    public static void clearOrphaned(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        boolean changed = false;
        Set<Item> clearedItems = new HashSet<>();
        for (StackRef ref : allStacks(player)) {
            ItemStack stack = ref.get();
            UUID encounterId = stack.get(ModDataComponents.SEAL_ENCOUNTER_ID.get());
            if (encounterId != null && EncounterManager.controller(server).findEncounter(encounterId)
                    .map(snapshot -> snapshot.lifecycle().isTerminal()).orElse(true)) {
                clearedItems.add(stack.getItem());
                clear(stack);
                changed = true;
            }
        }
        if (changed) {
            clearedItems.stream().filter(item -> !hasActiveSeal(player, item, Long.MIN_VALUE))
                    .forEach(item -> player.getCooldowns().removeCooldown(item));
            player.getInventory().setChanged();
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        long tick = event.getServer().overworld().getGameTime();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            boolean changed = false;
            for (StackRef ref : allStacks(player)) {
                ItemStack stack = ref.get();
                Long until = stack.get(ModDataComponents.SEALED_UNTIL_TICK.get());
                if (until != null && until <= tick) {
                    clear(stack);
                    changed = true;
                } else if (isActive(stack, tick)) {
                    // Re-establish the client-visible cooldown after login, respawn or inventory
                    // reconstruction. Do not restart it every tick; that would freeze its progress.
                    applyCooldown(player, stack, tick);
                }
            }
            if (changed) player.getInventory().setChanged();
        }
    }

    @SubscribeEvent
    public static void blockRightClick(PlayerInteractEvent.RightClickItem event) {
        blockInteraction(event, true);
    }

    @SubscribeEvent
    public static void blockRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        blockInteraction(event, true);
    }

    @SubscribeEvent
    public static void blockEntityInteract(PlayerInteractEvent.EntityInteract event) {
        blockInteraction(event, true);
    }

    @SubscribeEvent
    public static void blockEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        blockInteraction(event, true);
    }

    @SubscribeEvent
    public static void blockLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        blockInteraction(event, false);
    }

    @SubscribeEvent
    public static void blockAttack(AttackEntityEvent event) {
        if (!event.getEntity().level().isClientSide
                && isActive(event.getEntity().getMainHandItem(), event.getEntity().level().getGameTime())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void blockUse(LivingEntityUseItemEvent.Start event) {
        if (!event.getEntity().level().isClientSide
                && isActive(event.getItem(), event.getEntity().level().getGameTime())) event.setCanceled(true);
    }

    private static boolean eligible(ItemStack stack, long tick) {
        return !stack.isEmpty() && !isActive(stack, tick)
                && !stack.is(ModItemTags.SEAL_BLACKLIST);
    }

    private static void applyCooldown(ServerPlayer player, ItemStack stack, long tick) {
        int remaining = remainingCooldownTicks(stack, tick);
        if (remaining > 0 && !player.getCooldowns().isOnCooldown(stack.getItem())) {
            player.getCooldowns().addCooldown(stack.getItem(), remaining);
        }
    }

    private static boolean hasActiveSeal(ServerPlayer player, Item item, long tick) {
        return allStacks(player).stream().map(StackRef::get)
                .anyMatch(stack -> stack.is(item) && (tick == Long.MIN_VALUE
                        ? stack.has(ModDataComponents.SEAL_ID.get()) : isActive(stack, tick)));
    }

    private static void blockInteraction(PlayerInteractEvent event, boolean setFailureResult) {
        if (event.getLevel().isClientSide || !isActive(event.getItemStack(), event.getLevel().getGameTime())) return;
        if (event instanceof net.neoforged.bus.api.ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
        }
        if (setFailureResult) {
            if (event instanceof PlayerInteractEvent.RightClickItem rightClick) {
                rightClick.setCancellationResult(InteractionResult.FAIL);
            } else if (event instanceof PlayerInteractEvent.RightClickBlock rightClick) {
                rightClick.setCancellationResult(InteractionResult.FAIL);
            } else if (event instanceof PlayerInteractEvent.EntityInteract interact) {
                interact.setCancellationResult(InteractionResult.FAIL);
            } else if (event instanceof PlayerInteractEvent.EntityInteractSpecific interact) {
                interact.setCancellationResult(InteractionResult.FAIL);
            }
        }
    }

    private static List<StackRef> allStacks(ServerPlayer player) {
        List<StackRef> refs = new ArrayList<>();
        for (int index = 0; index < player.getInventory().items.size(); index++) {
            int slot = index;
            refs.add(new StackRef(() -> player.getInventory().items.get(slot),
                    stack -> player.getInventory().items.set(slot, stack)));
        }
        for (int index = 0; index < player.getInventory().offhand.size(); index++) {
            int slot = index;
            refs.add(new StackRef(() -> player.getInventory().offhand.get(slot),
                    stack -> player.getInventory().offhand.set(slot, stack)));
        }
        CuriosAccess.inventory(player).ifPresent(handler -> handler.getCurios().values().forEach(curio -> {
            var stacks = curio.getStacks();
            for (int index = 0; index < stacks.getSlots(); index++) {
                int slot = index;
                refs.add(new StackRef(() -> stacks.getStackInSlot(slot), stack -> stacks.setStackInSlot(slot, stack)));
            }
        }));
        return refs;
    }

    private static long safeAdd(long value, int amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }

    private record StackRef(java.util.function.Supplier<ItemStack> getter,
                            java.util.function.Consumer<ItemStack> setter) {
        private StackRef {
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(setter, "setter");
        }

        ItemStack get() { return getter.get(); }
        void set(ItemStack stack) { setter.accept(stack); }
    }
}
