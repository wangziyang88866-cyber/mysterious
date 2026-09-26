package com.mysterious.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.EncounterManager;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.EncounterStartService;
import com.mysterious.encounter.EncounterFinalizationResult;
import com.mysterious.encounter.PlayerEncounterState;
import com.mysterious.mysterious;
import com.mysterious.network.ModNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Comparator;
import java.util.UUID;

/** Read-only operational surface for the state categories required by the implementation plan. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class AmonDebugCommands {
    private static final DynamicCommandExceptionType INVALID_ID =
            new DynamicCommandExceptionType(value -> Component.literal("无效或不存在的 Encounter ID: " + value));

    private AmonDebugCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mysterious")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start").executes(context -> startEncounter(context.getSource())))
                .then(Commands.literal("stop").then(Commands.argument("encounter_id", StringArgumentType.word())
                        .executes(context -> stopEncounter(context.getSource(), StringArgumentType.getString(
                                context, "encounter_id")))))
                .then(Commands.literal("clear")
                        .executes(context -> clearEncounters(context.getSource()))
                        .then(Commands.argument("encounter_id", StringArgumentType.word())
                                .executes(context -> clearEncounter(context.getSource(), StringArgumentType.getString(
                                        context, "encounter_id")))))
                .then(Commands.literal("debug")
                        .then(Commands.literal("encounters").executes(context -> listEncounters(context.getSource())))
                        .then(encounterCommand("encounter", AmonDebugCommands::showEncounter))
                        .then(encounterCommand("players", AmonDebugCommands::showPlayers))
                        .then(encounterCommand("bosses", AmonDebugCommands::showBosses))
                        .then(encounterCommand("phase", AmonDebugCommands::showPhase))
                        .then(encounterCommand("entities", AmonDebugCommands::showEntities))
                        .then(Commands.literal("transactions").executes(context -> showTransactions(context.getSource())))
                        .then(Commands.literal("escrow").executes(context -> showEscrow(context.getSource())))
                        .then(Commands.literal("pendingreturns").executes(context -> showPendingReturns(context.getSource())))
                        .then(Commands.literal("spells").executes(context -> showSpells(context.getSource())))
                        .then(Commands.literal("network").executes(context -> showNetwork(context.getSource())))
                        .then(Commands.literal("recovery").executes(context -> showRecovery(context.getSource())))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> encounterCommand(
            String name, EncounterPrinter printer) {
        return Commands.literal(name).then(Commands.argument("encounter_id", StringArgumentType.word())
                .executes(context -> printer.print(context.getSource(), requireEncounter(context.getSource(),
                        StringArgumentType.getString(context, "encounter_id")))));
    }

    private static int listEncounters(CommandSourceStack source) {
        var snapshots = EncounterManager.controller(source.getServer()).snapshots().values().stream()
                .sorted(Comparator.comparing(snapshot -> snapshot.encounterId().toString())).toList();
        line(source, "encounters=" + snapshots.size());
        snapshots.forEach(snapshot -> line(source, snapshot.encounterId() + " lifecycle=" + snapshot.lifecycle()
                + " phase=" + snapshot.phase() + " rev=" + snapshot.revision()
                + " livingBosses=" + snapshot.livingBossCount()));
        return snapshots.size();
    }

    private static int startEncounter(CommandSourceStack source) throws CommandSyntaxException {
        try {
            var result = EncounterStartService.start(source.getPlayerOrException());
            line(source, "Encounter started id=" + result.encounterId() + " phase=PHASE_ONE bosses="
                    + result.bossIds().size() + "; use /mysterious debug phase " + result.encounterId());
            return result.bossIds().size();
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal("Unable to start encounter: " + exception.getMessage()));
            return 0;
        }
    }

    private static int stopEncounter(CommandSourceStack source, String value) throws CommandSyntaxException {
        EncounterSnapshot snapshot = requireEncounter(source, value);
        try {
            EncounterFinalizationResult result = EncounterStartService.stop(source.getServer(), snapshot.encounterId(),
                    source.getLevel().getGameTime());
            line(source, "Encounter stop result=" + result + " id=" + snapshot.encounterId());
            return 1;
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal("Unable to stop encounter: " + exception.getMessage()));
            return 0;
        }
    }

    private static int clearEncounter(CommandSourceStack source, String value) throws CommandSyntaxException {
        EncounterSnapshot snapshot = requireEncounter(source, value);
        try {
            EncounterStartService.clear(source.getServer(), snapshot.encounterId(),
                    source.getLevel().getGameTime());
            line(source, "Encounter cleared id=" + snapshot.encounterId());
            return 1;
        } catch (IllegalStateException exception) {
            source.sendFailure(Component.literal("Unable to clear encounter: " + exception.getMessage()));
            return 0;
        }
    }

    private static int clearEncounters(CommandSourceStack source) {
        var ids = EncounterManager.controller(source.getServer()).snapshots().keySet().stream().toList();
        int cleared = 0;
        for (UUID id : ids) {
            try {
                EncounterStartService.clear(source.getServer(), id, source.getLevel().getGameTime());
                cleared++;
            } catch (IllegalStateException exception) {
                source.sendFailure(Component.literal("Unable to clear encounter " + id + ": "
                        + exception.getMessage()));
            }
        }
        line(source, "Encounters cleared=" + cleared);
        return cleared;
    }

    private static int showSpells(CommandSourceStack source) {
        var snapshots = EncounterManager.controller(source.getServer()).snapshots().values();
        int instances = snapshots.stream().mapToInt(value -> value.advanced().spells().instances().size()).sum();
        int reserved = snapshots.stream().mapToInt(
                value -> value.advanced().spells().reservedTemporaryEntities()).sum();
        line(source, "spellEncounters=" + snapshots.size() + " activeInstances=" + instances
                + " reservedEntities=" + reserved);
        snapshots.forEach(snapshot -> line(source, "encounterId=" + snapshot.encounterId()
                + " nextCast=" + snapshot.advanced().spells().nextCastTick()
                + " nextEnvironment=" + snapshot.advanced().spells().nextEnvironmentTick()
                + " instances=" + snapshot.advanced().spells().instances().size()));
        return instances;
    }

    private static int showEncounter(CommandSourceStack source, EncounterSnapshot snapshot) {
        line(source, "encounterId=" + snapshot.encounterId() + " owner=" + snapshot.owner().kind() + ":"
                + snapshot.owner().id() + " lifecycle=" + snapshot.lifecycle() + " phase=" + snapshot.phase()
                + " revision=" + snapshot.revision());
        line(source, "createdAtTick=" + snapshot.createdAtTick() + " players=" + snapshot.players().size()
                + " bosses=" + snapshot.bosses().size() + " cleanup=" + snapshot.cleanupState());
        snapshot.termination().ifPresent(termination -> line(source, "termination id="
                + termination.finalizationId() + " reason=" + termination.reason()
                + " requestedAtTick=" + termination.requestedAtTick()));
        return 1;
    }

    private static int showPlayers(CommandSourceStack source, EncounterSnapshot snapshot) {
        line(source, "encounterId=" + snapshot.encounterId() + " players=" + snapshot.players().size());
        snapshot.players().values().stream().sorted(Comparator.comparing(state -> state.playerId().toString()))
                .forEach(state -> printPlayer(source, state));
        return snapshot.players().size();
    }

    private static void printPlayer(CommandSourceStack source, PlayerEncounterState state) {
        line(source, state.playerId() + " state=" + state.participation() + " joinedAtTick=" + state.joinedAtTick()
                + " joinGraceUntil=" + state.joinGraceUntilTick()
                + " retentionDeadline=" + (state.retentionDeadlineTick().isPresent()
                ? state.retentionDeadlineTick().getAsLong() : "none"));
    }

    private static int showBosses(CommandSourceStack source, EncounterSnapshot snapshot) {
        line(source, "encounterId=" + snapshot.encounterId() + " bosses=" + snapshot.bosses().size());
        snapshot.bosses().values().stream().sorted(Comparator.comparing(record -> record.entityId().toString()))
                .forEach(record -> printBoss(source, record));
        return snapshot.bosses().size();
    }

    private static void printBoss(CommandSourceStack source, BossRecord record) {
        line(source, record.entityId() + " lifecycle=" + record.lifecycle() + " phase=" + record.phase()
                + " dimension=" + record.lastKnownDimension().location() + " pos=" + record.lastKnownPosition()
                + " tick=" + record.lastConfirmedTick() + " transition="
                + record.carrierTransitionId().map(UUID::toString).orElse("none"));
    }

    private static int showPhase(CommandSourceStack source, EncounterSnapshot snapshot) {
        line(source, "encounterId=" + snapshot.encounterId() + " initial=" + snapshot.initialPhase()
                + " current=" + snapshot.phase() + " generation=" + snapshot.phaseGeneration()
                + " phaseStartedAtTick="
                + snapshot.timers().phaseStartedAtTick() + " activeTransition="
                + snapshot.activeTransition().map(record -> record.transitionId().toString()).orElse("none")
                + " lastCommitted=" + snapshot.lastCommittedTransitionId().map(UUID::toString).orElse("none"));
        snapshot.activeTransition().ifPresent(record -> line(source,
                "carrier=" + record.carrierBossId() + " recoveryCarrier=" + record.recoveryBossId()
                        + " recoveryDimension=" + record.recoveryDimension().location()
                        + " recoveryPos=" + record.recoveryPosition()));
        snapshot.advanced().phaseThree().ifPresent(state -> line(source,
                "p3 transformAt=" + state.transformationAtTick() + " invulnerable=false"
                        + " warningSent=" + state.warningSent() + " timelineCompleted=" + state.timelineCompleted()
                        + " secondForm=" + state.secondFormActive() + " worms=" + state.wormsSpawned()
                        + " nextWorm=" + state.nextWormSpawnTick() + " nextClock=" + state.nextClockSpawnTick()
                        + " parasites=" + state.parasites().size() + " clockCounters=" + state.clockHits().size()
                        + " executionDedupe=" + state.lastExecutionTicks().size()));
        snapshot.advanced().crossDimensionChase().ifPresent(state -> line(source,
                "crossDimension target=" + state.targetPlayerId()
                        + " dimension=" + state.targetDimension().location()
                        + " requested=" + state.requestedAtTick() + " executeAfter=" + state.executeAfterTick()
                        + " teleported=" + state.bossInTargetDimension()
                        + " returnPending=" + state.returnPending()
                        + " cooldownUntil=" + snapshot.timers().crossDimensionCooldownUntilTick()
                        .stream().mapToObj(Long::toString).findFirst().orElse("none")));
        return 1;
    }

    private static int showEntities(CommandSourceStack source, EncounterSnapshot snapshot) {
        line(source, "Encounter 绑定实体由持久化 BossRegistry 报告；未以实体查询失败推断死亡。");
        return showBosses(source, snapshot);
    }

    private static int showNetwork(CommandSourceStack source) {
        var diagnostics = com.mysterious.network.NetworkSyncService.diagnostics(source.getServer());
        line(source, "protocol=" + ModNetwork.PROTOCOL_VERSION
                + " S2C=snapshot,delta,phase,warning,encounter_clear C2S=intent-only"
                + " recipientStreams=" + diagnostics.activeRecipientStreams()
                + " observedEncounters=" + diagnostics.observedEncounters());
        return 1;
    }

    private static int showRecovery(CommandSourceStack source) {
        var data = EncounterManager.savedData(source.getServer());
        long missing = EncounterManager.controller(source.getServer()).snapshots().values().stream()
                .flatMap(snapshot -> snapshot.bosses().values().stream())
                .filter(record -> record.lifecycle() == com.mysterious.encounter.BossLifecycleState.MISSING_PENDING)
                .count();
        line(source, "safeMode=" + data.isSafeMode() + " persistedEncounters=" + data.encounterCount()
                + " missingPending=" + missing + " error=" + data.recoveryError().orElse("none"));
        return data.isSafeMode() ? 0 : 1;
    }

    private static int showTransactions(CommandSourceStack source) {
        var snapshots = EncounterManager.controller(source.getServer()).snapshots().values();
        long splitCount = snapshots.stream().filter(snapshot -> snapshot.phaseOne().splitTransaction().isPresent()).count();
        line(source, "splitTransactions=" + splitCount);
        snapshots.stream().filter(snapshot -> snapshot.phaseOne().splitTransaction().isPresent())
                .forEach(snapshot -> {
                    var transaction = snapshot.phaseOne().splitTransaction().orElseThrow();
                    line(source, "encounterId=" + snapshot.encounterId() + " transactionId="
                            + transaction.transactionId() + " state=" + transaction.state()
                            + " planned=" + transaction.replacements().size());
                });
        return (int) splitCount;
    }

    private static int showEscrow(CommandSourceStack source) {
        var snapshots = EncounterManager.controller(source.getServer()).snapshots().values();
        int count = snapshots.stream().mapToInt(snapshot -> snapshot.escrow().size()).sum();
        line(source, "escrowRecords=" + count);
        snapshots.forEach(snapshot -> snapshot.escrow().values().forEach(record -> line(source,
                "encounterId=" + snapshot.encounterId() + " stolenItemId=" + record.stolenItemId()
                        + " player=" + record.playerId() + " state=" + record.state()
                        + " slot=" + record.slotType() + ":" + record.slotIndex())));
        return count;
    }

    private static int showPendingReturns(CommandSourceStack source) {
        var records = EncounterManager.savedData(source.getServer()).pendingReturns().snapshot();
        line(source, "pendingReturnRecords=" + records.size());
        records.values().forEach(record -> line(source, "stolenItemId=" + record.stolenItemId()
                + " encounterId=" + record.encounterId() + " player=" + record.playerId()
                + " state=" + record.state()));
        return records.size();
    }

    private static int stageStatus(CommandSourceStack source, String category, String status) {
        line(source, category + ": " + status);
        return 1;
    }

    private static EncounterSnapshot requireEncounter(CommandSourceStack source, String value)
            throws CommandSyntaxException {
        try {
            UUID id = UUID.fromString(value);
            return EncounterManager.controller(source.getServer()).findEncounter(id)
                    .orElseThrow(() -> INVALID_ID.create(value));
        } catch (IllegalArgumentException exception) {
            throw INVALID_ID.create(value);
        }
    }

    private static void line(CommandSourceStack source, String value) {
        source.sendSuccess(() -> Component.literal(value), false);
    }

    @FunctionalInterface
    private interface EncounterPrinter {
        int print(CommandSourceStack source, EncounterSnapshot snapshot);
    }
}
