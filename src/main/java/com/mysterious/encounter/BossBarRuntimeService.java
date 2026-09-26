package com.mysterious.encounter;

import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** One aggregate, phase-aware vanilla boss bar per active encounter. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class BossBarRuntimeService {
    private static final Map<MinecraftServer, Map<UUID, ServerBossEvent>> BARS = new WeakHashMap<>();

    private BossBarRuntimeService() {
    }

    @SubscribeEvent
    public static synchronized void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<UUID, ServerBossEvent> bars = BARS.computeIfAbsent(server, ignored -> new HashMap<>());
        Map<UUID, EncounterSnapshot> snapshots = EncounterManager.controller(server).snapshots();
        Set<UUID> active = new HashSet<>();

        for (EncounterSnapshot snapshot : snapshots.values()) {
            if (snapshot.lifecycle().isTerminal()) continue;
            active.add(snapshot.encounterId());
            ServerBossEvent bar = bars.computeIfAbsent(snapshot.encounterId(), ignored -> create(snapshot.phase()));
            configure(bar, snapshot, server);
        }

        for (UUID encounterId : Set.copyOf(bars.keySet())) {
            if (active.contains(encounterId)) continue;
            ServerBossEvent removed = bars.remove(encounterId);
            removed.removeAllPlayers();
            removed.setVisible(false);
        }
        if (bars.isEmpty()) BARS.remove(server);
    }

    private static ServerBossEvent create(EncounterPhase phase) {
        ServerBossEvent bar = new ServerBossEvent(Component.translatable(nameKey(phase), 1),
                color(phase), overlay(phase));
        bar.setVisible(true);
        return bar;
    }

    private static void configure(ServerBossEvent bar, EncounterSnapshot snapshot, MinecraftServer server) {
        Set<ServerPlayer> recipients = new HashSet<>();
        snapshot.players().values().stream()
                .filter(state -> state.participation() != PlayerParticipationState.LEFT)
                .map(state -> server.getPlayerList().getPlayer(state.playerId()))
                .filter(java.util.Objects::nonNull)
                .forEach(recipients::add);
        for (ServerPlayer existing : Set.copyOf(bar.getPlayers())) {
            if (!recipients.contains(existing)) bar.removePlayer(existing);
        }
        recipients.forEach(bar::addPlayer);

        int living = Math.max(1, snapshot.livingBossCount());
        bar.setName(Component.translatable(nameKey(snapshot.phase()), living));
        bar.setColor(color(snapshot.phase()));
        bar.setOverlay(overlay(snapshot.phase()));
        bar.setProgress(aggregateHealth(snapshot, server));
        bar.setDarkenScreen(snapshot.phase() == EncounterPhase.PHASE_THREE);
        bar.setCreateWorldFog(snapshot.phase() == EncounterPhase.PHASE_THREE);
        bar.setVisible(!recipients.isEmpty());
    }

    static float aggregateHealth(EncounterSnapshot snapshot, MinecraftServer server) {
        double current = 0.0D;
        double maximum = 0.0D;
        for (BossRecord record : snapshot.bosses().values()) {
            if (!record.isAlive()) continue;
            Entity entity = find(server, record.entityId());
            if (entity instanceof AmonEntity amon && amon.isAlive()) {
                current += Math.max(0.0F, amon.getHealth());
                maximum += Math.max(1.0F, amon.getMaxHealth());
            }
        }
        return maximum <= 0.0D ? 1.0F : (float) Math.clamp(current / maximum, 0.0D, 1.0D);
    }

    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved()) return entity;
        }
        return null;
    }

    private static String nameKey(EncounterPhase phase) {
        return switch (phase) {
            case PHASE_ONE -> "bossbar.mysterious.amon.phase_one";
            case PHASE_TWO -> "bossbar.mysterious.amon.phase_two";
            case PHASE_THREE -> "bossbar.mysterious.amon.phase_three";
        };
    }

    private static BossEvent.BossBarColor color(EncounterPhase phase) {
        return switch (phase) {
            case PHASE_ONE -> BossEvent.BossBarColor.PURPLE;
            case PHASE_TWO -> BossEvent.BossBarColor.BLUE;
            case PHASE_THREE -> BossEvent.BossBarColor.RED;
        };
    }

    private static BossEvent.BossBarOverlay overlay(EncounterPhase phase) {
        return phase == EncounterPhase.PHASE_ONE
                ? BossEvent.BossBarOverlay.NOTCHED_10 : BossEvent.BossBarOverlay.PROGRESS;
    }
}
