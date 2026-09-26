package com.mysterious.theft;

import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.inventory.ItemStackIdentity;
import com.mysterious.transaction.TransactionState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Idempotent original-slot -> inventory -> drop return flow for encounter and global ledgers. */
public final class EscrowReturnService {
    private EscrowReturnService() {
    }

    public static void returnOrTransfer(MinecraftServer server, EncounterSnapshot snapshot) {
        EncounterController controller = EncounterManager.controller(server);
        for (EscrowRecord record : snapshot.escrow().values()) {
            if (record.state() != TransactionState.COMMITTED && record.state() != TransactionState.RETURNING) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(record.playerId());
            if (player == null) {
                EncounterManager.savedData(server).pendingReturns().transfer(record);
                continue;
            }
            UUID returnId = record.returnTransactionId().orElseGet(UUID::randomUUID);
            EscrowRecord returning = controller.beginEscrowReturn(snapshot.encounterId(),
                    record.stolenItemId(), returnId);
            if (!containsItem(player, record.stolenItemId())) {
                insert(player, returning.slotType(), returning.slotIndex(),
                        returning.item().decode(player.registryAccess()));
            }
            controller.completeEscrowReturn(snapshot.encounterId(), record.stolenItemId(), returnId);
        }
    }

    public static void returnPending(ServerPlayer player) {
        GlobalPendingReturnStore store = EncounterManager.savedData(player.getServer()).pendingReturns();
        for (PendingReturnRecord record : store.forPlayer(player.getUUID()).values()) {
            if (record.state() == TransactionState.RETURNED) {
                continue;
            }
            UUID returnId = record.returnTransactionId().orElseGet(UUID::randomUUID);
            PendingReturnRecord returning = store.beginReturn(record.stolenItemId(), returnId);
            if (!containsItem(player, record.stolenItemId())) {
                insert(player, returning.slotType(), returning.slotIndex(),
                        returning.item().decode(player.registryAccess()));
            }
            store.completeReturn(record.stolenItemId(), returnId);
        }
    }

    private static boolean containsItem(ServerPlayer player, UUID stolenItemId) {
        Inventory inventory = player.getInventory();
        boolean inventoryContains = inventory.items.stream().anyMatch(stack -> hasIdentity(stack, stolenItemId))
                || inventory.armor.stream().anyMatch(stack -> hasIdentity(stack, stolenItemId))
                || inventory.offhand.stream().anyMatch(stack -> hasIdentity(stack, stolenItemId));
        if (inventoryContains) {
            return true;
        }
        for (var level : player.getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity item && hasIdentity(item.getItem(), stolenItemId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasIdentity(ItemStack stack, UUID identity) {
        return !stack.isEmpty() && ItemStackIdentity.get(stack).filter(identity::equals).isPresent();
    }

    private static void insert(ServerPlayer player, StolenSlotType type, int index, ItemStack item) {
        Inventory inventory = player.getInventory();
        ItemStack original = switch (type) {
            case ARMOR -> index < inventory.armor.size() ? inventory.armor.get(index) : ItemStack.EMPTY;
            case MAIN_INVENTORY -> index < inventory.items.size() ? inventory.items.get(index) : ItemStack.EMPTY;
        };
        if (original.isEmpty()) {
            switch (type) {
                case ARMOR -> {
                    if (index < inventory.armor.size()) inventory.armor.set(index, item);
                    else fallback(player, item);
                }
                case MAIN_INVENTORY -> {
                    if (index < inventory.items.size()) inventory.items.set(index, item);
                    else fallback(player, item);
                }
            }
            inventory.setChanged();
            return;
        }
        fallback(player, item);
    }

    private static void fallback(ServerPlayer player, ItemStack item) {
        if (!player.getInventory().add(item)) {
            player.drop(item, false, false);
        }
        player.getInventory().setChanged();
    }
}
