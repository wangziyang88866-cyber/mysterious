package com.mysterious.spell;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterController;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.combat.CombatRuntimeService;
import com.mysterious.entity.AmonEntity;
import com.mysterious.mysterious;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;

/** Registry-driven legendary spell lifecycle shared by P2 and P3. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class SpellRuntimeService {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MIN_LIGHTNING_INTERVAL_TICKS = 25;
    public static final int LIGHTNING_INTERVAL_VARIANCE_TICKS = 26;
    public static final int PHASE_TWO_MIN_CAST_INTERVAL_TICKS = 45;
    public static final int PHASE_TWO_CAST_INTERVAL_VARIANCE_TICKS = 31;
    public static final int PHASE_THREE_MIN_CAST_INTERVAL_TICKS = 20;
    public static final int PHASE_THREE_CAST_INTERVAL_VARIANCE_TICKS = 21;
    public static final double PHASE_THREE_CAST_TIME_SCALE = 0.55D;

    private SpellRuntimeService() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (EncounterManager.savedData(server).isSafeMode()) return;
        long tick = server.overworld().getGameTime();
        EncounterController controller = EncounterManager.controller(server);
        for (EncounterSnapshot snapshot : controller.snapshots().values()) {
            if (snapshot.lifecycle() != EncounterLifecycle.ACTIVE
                    || !castsInPhase(snapshot.phase())) {
                continue;
            }
            SpellRuntimeState state = SpellCastManager.tick(snapshot.advanced().spells(), tick);
            Random random = new Random(snapshot.encounterId().getLeastSignificantBits() ^ tick);
            if (tick >= state.nextCastTick()) {
                AmonEntity owner = livingBoss(snapshot, server).orElse(null);
                if (owner != null) {
                    var selected = SpellCastManager.selectLegendary(state, tick, random,
                            BossSpellAdapter.fullLegendaryPool());
                    if (selected.isPresent()) {
                        int before = state.instances().size();
                        double castTimeScale = castTimeScale(snapshot.phase());
                        state = SpellCastManager.prepare(state, selected.orElseThrow(), owner.getUUID(), tick,
                                castTimeScale);
                        if (state.instances().size() > before) execute(selected.orElseThrow(), snapshot, owner, server);
                    }
                }
                state = SpellCastManager.scheduleNext(state, tick + nextCastDelay(snapshot.phase(), random));
            }
            spawnEnvironmentParticles(snapshot, server, random, tick);
            if (tick >= state.nextEnvironmentTick()) {
                spawnRealLightning(snapshot, server, random);
                state = SpellCastManager.scheduleEnvironment(state, tick + MIN_LIGHTNING_INTERVAL_TICKS
                        + random.nextInt(LIGHTNING_INTERVAL_VARIANCE_TICKS));
            }
            // P3 mechanics update their own portion of AdvancedEncounterState in the same tick.
            // Re-read before committing so event subscriber order cannot overwrite that work.
            EncounterSnapshot current = controller.requireEncounter(snapshot.encounterId());
            controller.updateAdvancedState(snapshot.encounterId(),
                    new AdvancedEncounterState(state, current.advanced().phaseThree(),
                            current.advanced().crossDimensionChase()));
        }
    }

    public static boolean castsInPhase(EncounterPhase phase) {
        return phase == EncounterPhase.PHASE_TWO || phase == EncounterPhase.PHASE_THREE;
    }

    public static int nextCastDelay(EncounterPhase phase, Random random) {
        if (!castsInPhase(phase)) throw new IllegalArgumentException("Phase cannot schedule spells: " + phase);
        int minimum = phase == EncounterPhase.PHASE_THREE
                ? PHASE_THREE_MIN_CAST_INTERVAL_TICKS : PHASE_TWO_MIN_CAST_INTERVAL_TICKS;
        int variance = phase == EncounterPhase.PHASE_THREE
                ? PHASE_THREE_CAST_INTERVAL_VARIANCE_TICKS : PHASE_TWO_CAST_INTERVAL_VARIANCE_TICKS;
        return minimum + random.nextInt(variance);
    }

    public static double castTimeScale(EncounterPhase phase) {
        return phase == EncounterPhase.PHASE_THREE ? PHASE_THREE_CAST_TIME_SCALE : 1.0D;
    }

    private static Optional<AmonEntity> livingBoss(EncounterSnapshot snapshot, MinecraftServer server) {
        return snapshot.bosses().values().stream().filter(BossRecord::isAlive)
                .sorted(Comparator.comparing(value -> value.entityId().toString()))
                .map(value -> find(server, value.entityId())).filter(AmonEntity.class::isInstance)
                .map(AmonEntity.class::cast).findFirst();
    }

    private static void spawnRealLightning(EncounterSnapshot snapshot, MinecraftServer server, Random random) {
        AmonEntity owner = livingBoss(snapshot, server).orElse(null);
        ServerLevel level = owner != null && owner.level() instanceof ServerLevel current ? current : null;
        if (level == null) return;
        LivingEntity target = CombatRuntimeService.resolveCombatTarget(snapshot, owner, server).orElse(null);
        if (target == null || target.level() != level) return;
        var bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) return;
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = 2.0D + random.nextDouble() * 8.0D;
        bolt.moveTo(target.getX() + Math.cos(angle) * radius, target.getY(),
                target.getZ() + Math.sin(angle) * radius);
        level.addFreshEntity(bolt);
    }

    /** P2/P3 storm ambience is distributed through the battlefield around players, never attached to Amon. */
    private static void spawnEnvironmentParticles(EncounterSnapshot snapshot, MinecraftServer server,
                                                   Random random, long tick) {
        if (tick % 3L != 0L) return;
        for (var participant : snapshot.players().values()) {
            if (participant.participation() == com.mysterious.encounter.PlayerParticipationState.LEFT) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null || !player.isAlive()) continue;
            ServerLevel level = player.serverLevel();
            for (int i = 0; i < 3; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double radius = 8.0D + random.nextDouble() * 20.0D;
                double x = player.getX() + Math.cos(angle) * radius;
                double z = player.getZ() + Math.sin(angle) * radius;
                double y = player.getY() + 1.0D + random.nextDouble() * 9.0D;
                level.sendParticles(i == 0 ? ParticleTypes.ELECTRIC_SPARK
                                : i == 1 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.WITCH,
                        x, y, z, 2, 1.2D, 1.5D, 1.2D, 0.025D);
            }
        }
    }

    private static void execute(BossSpellDefinition definition, EncounterSnapshot snapshot,
                                AmonEntity owner, MinecraftServer server) {
        LivingEntity target = CombatRuntimeService.resolveCombatTarget(snapshot, owner, server).orElse(null);
        if (target == null) return;
        owner.setTarget(target);
        try {
            AbstractSpell spell = SpellRegistry.getSpell(definition.spellId());
            if (spell.getSpellResource().equals(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "none"))
                    || !spell.isEnabled()) {
                LOGGER.warn("[spell] skipped unavailable ISS spell encounterId={} spell={}",
                        snapshot.encounterId(), definition.spellId());
                return;
            }
            owner.mysterious$initiateCastSpell(spell, definition.spellLevel(), castTimeScale(snapshot.phase()));
        } catch (RuntimeException | LinkageError exception) {
            // Expansion spells may have their own optional classes or player-only preconditions.
            // One incompatible implementation cannot crash or lock the encounter spell scheduler.
            LOGGER.warn("[spell] registered spell rejected mob cast encounterId={} spell={}",
                    snapshot.encounterId(), definition.spellId(), exception);
            owner.cancelCast();
        }
    }

    private static Entity find(MinecraftServer server, java.util.UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }
}
