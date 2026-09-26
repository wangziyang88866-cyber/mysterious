package com.mysterious.theft;

import com.mojang.logging.LogUtils;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.combat.DamageDeliveryClassifier;
import com.mysterious.combat.DamageDeliveryType;
import com.mysterious.config.MysteriousServerConfig;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerEncounterState;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.entity.AmonEntity;
import com.mysterious.inventory.ItemStackIdentity;
import com.mysterious.mysterious;
import com.mysterious.registry.ModItemTags;
import com.mysterious.transaction.TransactionState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Stage 7 authoritative theft transaction entry point and PREPARED recovery. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class TheftRuntimeService {
    public static final double MIN_THEFT_CHANCE = 0.60D;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<net.minecraft.server.MinecraftServer, Map<UUID, Long>> PROTECTION = new WeakHashMap<>();

    private TheftRuntimeService() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMeleeDamage(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getSource().getEntity() instanceof AmonEntity amon)
                || !(amon.level() instanceof ServerLevel level)
                || amon.mysterious$getEncounterId().isEmpty()
                || DamageDeliveryClassifier.classify(event.getSource()) != DamageDeliveryType.MELEE) {
            return;
        }
        long tick = level.getGameTime();
        if (amon.mysterious$theftLocked(tick)) {
            return;
        }
        UUID encounterId = amon.mysterious$getEncounterId().orElseThrow();
        EncounterController controller = EncounterManager.controller(level.getServer());
        EncounterSnapshot snapshot = controller.findEncounter(encounterId).orElse(null);
        PlayerEncounterState participant = snapshot == null ? null : snapshot.players().get(player.getUUID());
        if (!isTheftEnabled(snapshot) || participant == null
                || participant.participation() == PlayerParticipationState.LEFT
                || tick < participant.joinGraceUntilTick()
                || protectedUntil(level.getServer(), player.getUUID()) > tick
                || amon.getRandom().nextDouble() >= effectiveTheftChance()) {
            return;
        }
        var identity = CombatRuntimeService.identity(encounterId, amon, player,
                event.getSource().getDirectEntity(), tick);
        attempt(controller, snapshot, player, identity.eventId(), tick, amon);
    }

    public static boolean isTheftEnabled(EncounterSnapshot snapshot) {
        if (snapshot == null || snapshot.lifecycle() != EncounterLifecycle.ACTIVE) return false;
        return snapshot.phase() == EncounterPhase.PHASE_ONE
                || snapshot.phase() == EncounterPhase.PHASE_TWO
                || snapshot.phase() == EncounterPhase.PHASE_THREE;
    }

    public static double effectiveTheftChance() {
        return Math.max(MIN_THEFT_CHANCE, MysteriousServerConfig.theftChance());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EncounterController controller = EncounterManager.controller(player.getServer());
        controller.snapshots().values().forEach(snapshot -> recoverPrepared(controller, snapshot, player));
        EscrowReturnService.returnPending(player);
    }

    private static void attempt(EncounterController controller, EncounterSnapshot snapshot, ServerPlayer player,
                                UUID attackEventId, long tick, AmonEntity amon) {
        if (snapshot.escrow().values().stream().anyMatch(record -> record.attackEventId().equals(attackEventId))) {
            return;
        }
        SlotCandidate candidate = chooseCandidate(player, amon);
        if (candidate == null) {
            return;
        }
        ItemStack source = candidate.stack();
        int before = source.getCount();
        ItemStack fingerprintStack = source.copyWithCount(1);
        ItemStack stolen = source.copyWithCount(1);
        UUID stolenItemId = ItemStackIdentity.getOrCreate(stolen);
        EscrowRecord prepared = new EscrowRecord(stolenItemId, snapshot.encounterId(), player.getUUID(),
                candidate.type(), candidate.index(), SerializedItemStack.of(stolen, player.registryAccess()),
                SerializedItemStack.of(fingerprintStack, player.registryAccess()), before, before - 1,
                attackEventId, TransactionState.PREPARED, java.util.Optional.empty());
        EscrowRecord accepted = controller.prepareEscrow(snapshot.encounterId(), prepared);
        if (!accepted.stolenItemId().equals(stolenItemId) || accepted.state() != TransactionState.PREPARED) {
            return;
        }

        ItemStack current = candidate.get(player.getInventory());
        ItemStack fingerprint = prepared.fingerprint().decode(player.registryAccess());
        if (current.getCount() != before || !ItemStack.isSameItemSameComponents(current, fingerprint)) {
            controller.setEscrowState(snapshot.encounterId(), stolenItemId,
                    TransactionState.PREPARED, TransactionState.CANCELLED);
            return;
        }
        current.shrink(1);
        player.getInventory().setChanged();
        controller.setEscrowState(snapshot.encounterId(), stolenItemId,
                TransactionState.PREPARED, TransactionState.COMMITTED);
        player.displayClientMessage(Component.translatable("message.mysterious.item_stolen", stolen.getHoverName()), true);
        player.playNotifySound(SoundEvents.ITEM_BREAK, SoundSource.HOSTILE, 0.9F, 0.7F);
        setProtectedUntil(player.getServer(), player.getUUID(), saturatedAdd(tick,
                MysteriousServerConfig.theftProtectionTicks()));
        if (candidate.type() == StolenSlotType.ARMOR) {
            StolenAttributeManager.rebuild(snapshot.encounterId(), player.getServer());
        }
        LOGGER.info("[theft] committed encounterId={} stolenItemId={} player={} slot={}:{} attackEvent={}",
                snapshot.encounterId(), stolenItemId, player.getUUID(), candidate.type(), candidate.index(),
                attackEventId);
    }

    static void recoverPrepared(EncounterController controller, EncounterSnapshot snapshot, ServerPlayer player) {
        snapshot.escrow().values().stream()
                .filter(record -> record.playerId().equals(player.getUUID()))
                .filter(record -> record.state() == TransactionState.PREPARED)
                .forEach(record -> {
                    ItemStack current = slot(record).get(player.getInventory());
                    ItemStack fingerprint = record.fingerprint().decode(player.registryAccess());
                    TransactionState resolved;
                    if (ItemStack.isSameItemSameComponents(current, fingerprint)
                            && current.getCount() == record.beforeCount()) {
                        resolved = TransactionState.CANCELLED;
                    } else if (ItemStack.isSameItemSameComponents(current, fingerprint)
                            && current.getCount() == record.expectedAfterCount()) {
                        resolved = TransactionState.COMMITTED;
                    } else if (record.beforeCount() == 1 && current.isEmpty()) {
                        resolved = TransactionState.COMMITTED;
                    } else {
                        resolved = TransactionState.CONFLICT;
                    }
                    controller.setEscrowState(snapshot.encounterId(), record.stolenItemId(),
                            TransactionState.PREPARED, resolved);
                    LOGGER.warn("[escrow] recovered encounterId={} stolenItemId={} result={}",
                            snapshot.encounterId(), record.stolenItemId(), resolved);
                });
        StolenAttributeManager.rebuild(snapshot.encounterId(), player.getServer());
    }

    private static SlotCandidate chooseCandidate(ServerPlayer player, AmonEntity amon) {
        Inventory inventory = player.getInventory();
        List<SlotCandidate> armor = new ArrayList<>();
        for (int index = 0; index < inventory.armor.size(); index++) {
            SlotCandidate candidate = new SlotCandidate(StolenSlotType.ARMOR, index, inventory.armor.get(index));
            if (isEligible(candidate.stack())) {
                armor.add(candidate);
            }
        }
        List<SlotCandidate> main = new ArrayList<>();
        for (int index = Inventory.getSelectionSize(); index < inventory.items.size(); index++) {
            SlotCandidate candidate = new SlotCandidate(StolenSlotType.MAIN_INVENTORY, index,
                    inventory.items.get(index));
            if (isEligible(candidate.stack())) {
                main.add(candidate);
            }
        }
        List<SlotCandidate> pool;
        if (armor.isEmpty()) {
            pool = main;
        } else if (main.isEmpty()) {
            pool = armor;
        } else {
            pool = amon.getRandom().nextBoolean() ? armor : main;
        }
        return pool.isEmpty() ? null : pool.get(amon.getRandom().nextInt(pool.size()));
    }

    private static boolean isEligible(ItemStack stack) {
        if (stack.isEmpty() || stack.is(ModItemTags.THEFT_BLACKLIST)) {
            return false;
        }
        var protectedIds = MysteriousServerConfig.theftProtectedEnchantments();
        return stack.getEnchantments().keySet().stream().map(holder -> holder.unwrapKey()
                        .map(key -> key.location().toString()).orElse(""))
                .noneMatch(protectedIds::contains);
    }

    private static SlotCandidate slot(EscrowRecord record) {
        return new SlotCandidate(record.slotType(), record.slotIndex(), ItemStack.EMPTY);
    }

    private static synchronized long protectedUntil(net.minecraft.server.MinecraftServer server, UUID playerId) {
        return PROTECTION.getOrDefault(server, Map.of()).getOrDefault(playerId, 0L);
    }

    private static synchronized void setProtectedUntil(net.minecraft.server.MinecraftServer server,
                                                        UUID playerId, long tick) {
        PROTECTION.computeIfAbsent(server, ignored -> new java.util.HashMap<>()).put(playerId, tick);
    }

    private static long saturatedAdd(long tick, int amount) {
        return tick > Long.MAX_VALUE - amount ? Long.MAX_VALUE : tick + amount;
    }

    private record SlotCandidate(StolenSlotType type, int index, ItemStack stack) {
        private ItemStack get(Inventory inventory) {
            return switch (type) {
                case ARMOR -> index < inventory.armor.size() ? inventory.armor.get(index) : ItemStack.EMPTY;
                case MAIN_INVENTORY -> index < inventory.items.size() ? inventory.items.get(index) : ItemStack.EMPTY;
            };
        }
    }
}
