package com.mysterious.persistence;

import com.mysterious.arena.ArenaConfigSnapshot;
import com.mysterious.arena.EncounterCenter;
import com.mysterious.combat.EncounterCombatState;
import com.mysterious.combat.ThreatEntry;
import com.mysterious.encounter.BossLifecycleState;
import com.mysterious.encounter.AdvancedEncounterState;
import com.mysterious.encounter.CrossDimensionChaseState;
import com.mysterious.encounter.BossRecord;
import com.mysterious.encounter.CleanupState;
import com.mysterious.encounter.EncounterLifecycle;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.EncounterTermination;
import com.mysterious.encounter.EncounterTimerState;
import com.mysterious.encounter.EndReason;
import com.mysterious.encounter.PhaseTransitionRecord;
import com.mysterious.encounter.PlayerEncounterState;
import com.mysterious.encounter.PlayerParticipationState;
import com.mysterious.ownership.OwnershipKind;
import com.mysterious.ownership.RealmOwner;
import com.mysterious.phase.PhaseOneState;
import com.mysterious.phase.PhaseThreeState;
import com.mysterious.phase.ParasiteState;
import com.mysterious.spell.SpellInstanceState;
import com.mysterious.spell.SpellRuntimeState;
import com.mysterious.theft.EscrowRecord;
import com.mysterious.theft.PendingReturnRecord;
import com.mysterious.theft.SerializedItemStack;
import com.mysterious.theft.StolenSlotType;
import com.mysterious.transaction.SplitReplacement;
import com.mysterious.transaction.SplitTransaction;
import com.mysterious.transaction.TransactionState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Deterministic NBT codec for the Stage 4 persistence core. */
public final class EncounterSerializer {
    private EncounterSerializer() {
    }

    public static CompoundTag write(Map<UUID, EncounterSnapshot> encounters) {
        CompoundTag root = new CompoundTag();
        root.putInt("schemaVersion", EncounterDataVersions.CURRENT_SCHEMA_VERSION);
        root.putInt("dataVersion", EncounterDataVersions.CURRENT_DATA_VERSION);
        ListTag list = new ListTag();
        encounters.values().stream()
                .sorted(Comparator.comparing(snapshot -> snapshot.encounterId().toString()))
                .map(EncounterSerializer::writeEncounter)
                .forEach(list::add);
        root.put("encounters", list);
        root.put("pendingReturns", new ListTag());
        return root;
    }

    public static Map<UUID, EncounterSnapshot> read(CompoundTag root) {
        if (root.getInt("schemaVersion") != EncounterDataVersions.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Encounter data must be migrated before decoding");
        }
        if (root.getInt("dataVersion") != EncounterDataVersions.CURRENT_DATA_VERSION) {
            throw new IllegalArgumentException("Encounter data version must be migrated before decoding");
        }
        if (!root.contains("encounters", Tag.TAG_LIST)) {
            throw new IllegalStateException("Missing encounter list");
        }
        ListTag list = root.getList("encounters", Tag.TAG_COMPOUND);
        Map<UUID, EncounterSnapshot> encounters = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            EncounterSnapshot snapshot = readEncounter(list.getCompound(index));
            if (encounters.putIfAbsent(snapshot.encounterId(), snapshot) != null) {
                throw new IllegalStateException("Duplicate encounter " + snapshot.encounterId());
            }
        }
        return Map.copyOf(encounters);
    }

    private static CompoundTag writeEncounter(EncounterSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("encounterId", snapshot.encounterId());
        tag.putUUID("ownerId", snapshot.owner().id());
        tag.putString("ownerKind", snapshot.owner().kind().name());
        tag.put("center", writeCenter(snapshot.center()));
        tag.putLong("createdAtTick", snapshot.createdAtTick());
        tag.putLong("revision", snapshot.revision());
        tag.putString("lifecycle", snapshot.lifecycle().name());
        tag.putString("initialPhase", snapshot.initialPhase().name());
        tag.putString("phase", snapshot.phase().name());
        tag.putLong("phaseGeneration", snapshot.phaseGeneration());
        tag.putString("cleanupState", snapshot.cleanupState().name());
        tag.put("timers", writeTimers(snapshot.timers()));
        tag.put("combat", writeCombat(snapshot.combat()));
        tag.put("phaseOne", writePhaseOne(snapshot.phaseOne()));
        tag.put("advanced", writeAdvanced(snapshot.advanced()));

        ListTag bosses = new ListTag();
        snapshot.bosses().values().stream()
                .sorted(Comparator.comparing(record -> record.entityId().toString()))
                .map(EncounterSerializer::writeBoss)
                .forEach(bosses::add);
        tag.put("bosses", bosses);

        ListTag players = new ListTag();
        snapshot.players().values().stream()
                .sorted(Comparator.comparing(state -> state.playerId().toString()))
                .map(EncounterSerializer::writePlayer)
                .forEach(players::add);
        tag.put("players", players);

        ListTag escrow = new ListTag();
        snapshot.escrow().values().stream()
                .sorted(Comparator.comparing(record -> record.stolenItemId().toString()))
                .map(EncounterSerializer::writeEscrow)
                .forEach(escrow::add);
        tag.put("escrow", escrow);

        snapshot.activeTransition().ifPresent(value -> tag.put("activeTransition", writeTransition(value)));
        snapshot.lastCommittedTransitionId().ifPresent(value -> tag.putUUID("lastTransitionId", value));
        snapshot.termination().ifPresent(value -> tag.put("termination", writeTermination(value)));
        return tag;
    }

    private static EncounterSnapshot readEncounter(CompoundTag tag) {
        UUID encounterId = requireUuid(tag, "encounterId");
        RealmOwner owner = new RealmOwner(requireUuid(tag, "ownerId"),
                readEnum(OwnershipKind.class, tag, "ownerKind"));
        EncounterCenter center = readCenter(requireCompound(tag, "center"));
        long createdAtTick = requireNonNegative(requireLong(tag, "createdAtTick"), "createdAtTick");
        long revision = requireNonNegative(requireLong(tag, "revision"), "revision");
        EncounterLifecycle lifecycle = readEnum(EncounterLifecycle.class, tag, "lifecycle");
        EncounterPhase initialPhase = readEnum(EncounterPhase.class, tag, "initialPhase");
        EncounterPhase phase = readEnum(EncounterPhase.class, tag, "phase");
        long phaseGeneration = requireNonNegative(requireLong(tag, "phaseGeneration"), "phaseGeneration");
        CleanupState cleanup = readEnum(CleanupState.class, tag, "cleanupState");
        EncounterTimerState timers = readTimers(requireCompound(tag, "timers"));
        EncounterCombatState combat = readCombat(requireCompound(tag, "combat"));
        PhaseOneState phaseOne = readPhaseOne(requireCompound(tag, "phaseOne"));
        AdvancedEncounterState advanced = readAdvanced(requireCompound(tag, "advanced"));

        Map<UUID, BossRecord> bosses = readBosses(requireList(tag, "bosses"));
        Map<UUID, PlayerEncounterState> players = readPlayers(requireList(tag, "players"));
        Map<UUID, EscrowRecord> escrow = readEscrow(requireList(tag, "escrow"));
        Optional<PhaseTransitionRecord> activeTransition = optionalCompound(tag, "activeTransition")
                .map(EncounterSerializer::readTransition);
        Optional<UUID> lastTransition = optionalUuid(tag, "lastTransitionId");
        Optional<EncounterTermination> termination = optionalCompound(tag, "termination")
                .map(EncounterSerializer::readTermination);

        if (cleanup == CleanupState.IN_PROGRESS) {
            cleanup = CleanupState.PENDING;
            lifecycle = EncounterLifecycle.ENDED_ERROR_RECOVERY;
            if (termination.isPresent()) {
                EncounterTermination previous = termination.orElseThrow();
                termination = Optional.of(new EncounterTermination(
                        previous.finalizationId(), EndReason.ERROR_RECOVERY, previous.requestedAtTick()));
            }
            revision++;
        }
        validateTransition(lifecycle, activeTransition, bosses);
        return new EncounterSnapshot(encounterId, owner, center, createdAtTick, revision, lifecycle,
                initialPhase, phase, phaseGeneration, bosses, players, timers, combat, phaseOne, advanced, escrow,
                activeTransition, lastTransition,
                termination, cleanup);
    }

    public static void writePendingReturns(CompoundTag root, Map<UUID, PendingReturnRecord> records) {
        ListTag list = new ListTag();
        records.values().stream().sorted(Comparator.comparing(record -> record.stolenItemId().toString()))
                .map(EncounterSerializer::writePendingReturn).forEach(list::add);
        root.put("pendingReturns", list);
    }

    public static Map<UUID, PendingReturnRecord> readPendingReturns(CompoundTag root) {
        ListTag list = requireList(root, "pendingReturns");
        Map<UUID, PendingReturnRecord> records = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            PendingReturnRecord record = readPendingReturn(list.getCompound(index));
            if (records.putIfAbsent(record.stolenItemId(), record) != null) {
                throw new IllegalStateException("Duplicate pending return " + record.stolenItemId());
            }
        }
        return Map.copyOf(records);
    }

    private static CompoundTag writeCenter(EncounterCenter center) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", center.dimension().location().toString());
        putBlockPos(tag, center.position());
        CompoundTag safeAnchor = new CompoundTag();
        putBlockPos(safeAnchor, center.safeAnchor());
        tag.put("safeAnchor", safeAnchor);
        ArenaConfigSnapshot arena = center.arena();
        tag.putInt("playerJoinRadius", arena.playerJoinRadius());
        tag.putInt("playerRetentionRadius", arena.playerRetentionRadius());
        tag.putInt("playerHardExitRadius", arena.playerHardExitRadius());
        tag.putInt("bossSoftLeashRadius", arena.bossSoftLeashRadius());
        tag.putInt("bossHardLeashRadius", arena.bossHardLeashRadius());
        tag.putInt("verticalRadius", arena.verticalRadius());
        tag.putInt("joinGraceTicks", arena.joinGraceTicks());
        tag.putInt("retentionGraceTicks", arena.retentionGraceTicks());
        tag.putInt("abandonedTimeoutTicks", arena.abandonedTimeoutTicks());
        tag.putInt("phaseOneRecoveryDelayTicks", arena.phaseOneRecoveryDelayTicks());
        tag.putDouble("phaseOneRecoveryFractionPerSecond", arena.phaseOneRecoveryFractionPerSecond());
        tag.putInt("safePositionAttempts", arena.safePositionAttempts());
        return tag;
    }

    private static EncounterCenter readCenter(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.parse(requireString(tag, "dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ArenaConfigSnapshot arena = new ArenaConfigSnapshot(
                requireInt(tag, "playerJoinRadius"), requireInt(tag, "playerRetentionRadius"),
                requireInt(tag, "playerHardExitRadius"), requireInt(tag, "bossSoftLeashRadius"),
                requireInt(tag, "bossHardLeashRadius"), requireInt(tag, "verticalRadius"),
                requireInt(tag, "joinGraceTicks"), requireInt(tag, "retentionGraceTicks"),
                requireInt(tag, "abandonedTimeoutTicks"), requireInt(tag, "phaseOneRecoveryDelayTicks"),
                requireDouble(tag, "phaseOneRecoveryFractionPerSecond"),
                requireInt(tag, "safePositionAttempts"));
        return new EncounterCenter(dimension, readBlockPos(tag),
                readBlockPos(requireCompound(tag, "safeAnchor")), arena);
    }

    private static CompoundTag writeBoss(BossRecord record) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("entityId", record.entityId());
        tag.putString("phase", record.phase().name());
        tag.putString("dimension", record.lastKnownDimension().location().toString());
        putBlockPos(tag, record.lastKnownPosition());
        tag.putLong("lastConfirmedTick", record.lastConfirmedTick());
        tag.putString("lifecycle", record.lifecycle().name());
        record.carrierTransitionId().ifPresent(value -> tag.putUUID("carrierTransitionId", value));
        return tag;
    }

    private static Map<UUID, BossRecord> readBosses(ListTag list) {
        Map<UUID, BossRecord> bosses = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag tag = list.getCompound(index);
            UUID entityId = requireUuid(tag, "entityId");
            BossRecord record = new BossRecord(
                    entityId,
                    readEnum(EncounterPhase.class, tag, "phase"),
                    ResourceKey.create(Registries.DIMENSION,
                            ResourceLocation.parse(requireString(tag, "dimension"))),
                    readBlockPos(tag),
                    requireNonNegative(requireLong(tag, "lastConfirmedTick"), "lastConfirmedTick"),
                    readEnum(BossLifecycleState.class, tag, "lifecycle"),
                    optionalUuid(tag, "carrierTransitionId")
            );
            if (bosses.putIfAbsent(entityId, record) != null) {
                throw new IllegalStateException("Duplicate boss record " + entityId);
            }
        }
        return Map.copyOf(bosses);
    }

    private static CompoundTag writePlayer(PlayerEncounterState state) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("playerId", state.playerId());
        tag.putString("participation", state.participation().name());
        tag.putLong("joinedAtTick", state.joinedAtTick());
        tag.putLong("joinGraceUntilTick", state.joinGraceUntilTick());
        state.retentionDeadlineTick().ifPresent(value -> tag.putLong("retentionDeadlineTick", value));
        return tag;
    }

    private static Map<UUID, PlayerEncounterState> readPlayers(ListTag list) {
        Map<UUID, PlayerEncounterState> players = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag tag = list.getCompound(index);
            UUID playerId = requireUuid(tag, "playerId");
            PlayerEncounterState state = new PlayerEncounterState(
                    playerId,
                    readEnum(PlayerParticipationState.class, tag, "participation"),
                    requireNonNegative(requireLong(tag, "joinedAtTick"), "joinedAtTick"),
                    requireNonNegative(requireLong(tag, "joinGraceUntilTick"), "joinGraceUntilTick"),
                    optionalLong(tag, "retentionDeadlineTick"));
            if (players.putIfAbsent(playerId, state) != null) {
                throw new IllegalStateException("Duplicate player state " + playerId);
            }
        }
        return Map.copyOf(players);
    }

    private static CompoundTag writeTimers(EncounterTimerState state) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("phaseStartedAtTick", state.phaseStartedAtTick());
        state.abandonedSinceTick().ifPresent(value -> tag.putLong("abandonedSinceTick", value));
        state.crossDimensionCooldownUntilTick().ifPresent(
                value -> tag.putLong("crossDimensionCooldownUntilTick", value));
        return tag;
    }

    private static EncounterTimerState readTimers(CompoundTag tag) {
        return new EncounterTimerState(
                requireNonNegative(requireLong(tag, "phaseStartedAtTick"), "phaseStartedAtTick"),
                optionalLong(tag, "abandonedSinceTick"),
                optionalLong(tag, "crossDimensionCooldownUntilTick"));
    }

    private static CompoundTag writeCombat(EncounterCombatState state) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("lastThreatDecayTick", state.lastThreatDecayTick());
        state.currentTargetId().ifPresent(value -> tag.putUUID("currentTargetId", value));
        ListTag threats = new ListTag();
        state.threats().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    CompoundTag threat = new CompoundTag();
                    threat.putUUID("playerId", entry.getKey());
                    threat.putDouble("value", entry.getValue().value());
                    threat.putLong("lastActionTick", entry.getValue().lastActionTick());
                    threats.add(threat);
                });
        tag.put("threats", threats);
        return tag;
    }

    private static EncounterCombatState readCombat(CompoundTag tag) {
        Map<UUID, ThreatEntry> threats = new LinkedHashMap<>();
        ListTag list = requireList(tag, "threats");
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            UUID playerId = requireUuid(entry, "playerId");
            ThreatEntry threat = new ThreatEntry(requireDouble(entry, "value"),
                    requireNonNegative(requireLong(entry, "lastActionTick"), "lastActionTick"));
            if (threats.putIfAbsent(playerId, threat) != null) {
                throw new IllegalStateException("Duplicate threat player " + playerId);
            }
        }
        return new EncounterCombatState(threats, optionalUuid(tag, "currentTargetId"),
                requireNonNegative(requireLong(tag, "lastThreatDecayTick"), "lastThreatDecayTick"));
    }

    private static CompoundTag writePhaseOne(PhaseOneState state) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("splitConsumed", state.splitConsumed());
        tag.putLong("nextScanTick", state.nextScanTick());
        state.scannerBossId().ifPresent(value -> tag.putUUID("scannerBossId", value));
        state.splitTransaction().ifPresent(value -> tag.put("splitTransaction", writeSplitTransaction(value)));
        return tag;
    }

    private static PhaseOneState readPhaseOne(CompoundTag tag) {
        if (!tag.contains("splitConsumed", Tag.TAG_BYTE)) {
            throw new IllegalStateException("Missing boolean splitConsumed");
        }
        return new PhaseOneState(tag.getBoolean("splitConsumed"), optionalUuid(tag, "scannerBossId"),
                requireNonNegative(requireLong(tag, "nextScanTick"), "nextScanTick"),
                optionalCompound(tag, "splitTransaction").map(EncounterSerializer::readSplitTransaction));
    }

    private static CompoundTag writeAdvanced(AdvancedEncounterState state) {
        CompoundTag tag = new CompoundTag();
        tag.put("spells", writeSpells(state.spells()));
        state.phaseThree().ifPresent(value -> tag.put("phaseThree", writePhaseThree(value)));
        state.crossDimensionChase().ifPresent(value -> {
            CompoundTag chase = new CompoundTag();
            chase.putUUID("targetPlayerId", value.targetPlayerId());
            chase.putString("targetDimension", value.targetDimension().location().toString());
            chase.putLong("requestedAtTick", value.requestedAtTick());
            chase.putLong("executeAfterTick", value.executeAfterTick());
            chase.putBoolean("bossInTargetDimension", value.bossInTargetDimension());
            chase.putBoolean("returnPending", value.returnPending());
            tag.put("crossDimensionChase", chase);
        });
        return tag;
    }

    private static AdvancedEncounterState readAdvanced(CompoundTag tag) {
        return new AdvancedEncounterState(readSpells(requireCompound(tag, "spells")),
                optionalCompound(tag, "phaseThree").map(EncounterSerializer::readPhaseThree),
                optionalCompound(tag, "crossDimensionChase").map(chase -> new CrossDimensionChaseState(
                        requireUuid(chase, "targetPlayerId"),
                        ResourceKey.create(Registries.DIMENSION,
                                ResourceLocation.parse(requireString(chase, "targetDimension"))),
                        requireNonNegative(requireLong(chase, "requestedAtTick"), "requestedAtTick"),
                        requireNonNegative(requireLong(chase, "executeAfterTick"), "executeAfterTick"),
                        requireBoolean(chase, "bossInTargetDimension"),
                        requireBoolean(chase, "returnPending"))));
    }

    private static CompoundTag writeSpells(SpellRuntimeState state) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("nextCastTick", state.nextCastTick());
        tag.putLong("nextEnvironmentTick", state.nextEnvironmentTick());
        tag.putInt("reservedTemporaryEntities", state.reservedTemporaryEntities());
        state.activeCastInstanceId().ifPresent(value -> tag.putUUID("activeCastInstanceId", value));
        ListTag cooldowns = new ListTag();
        state.cooldownUntil().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    CompoundTag item = new CompoundTag();
                    item.putString("spellId", entry.getKey().toString());
                    item.putLong("until", entry.getValue());
                    cooldowns.add(item);
                });
        tag.put("cooldowns", cooldowns);
        ListTag instances = new ListTag();
        state.instances().values().stream().sorted(Comparator.comparing(value -> value.instanceId().toString()))
                .forEach(value -> {
                    CompoundTag item = new CompoundTag();
                    item.putUUID("instanceId", value.instanceId());
                    item.putString("spellId", value.spellId().toString());
                    item.putUUID("ownerBossId", value.ownerBossId());
                    item.putLong("startedAtTick", value.startedAtTick());
                    item.putLong("endsAtTick", value.endsAtTick());
                    item.putInt("reservedEntities", value.reservedEntities());
                    instances.add(item);
                });
        tag.put("instances", instances);
        return tag;
    }

    private static SpellRuntimeState readSpells(CompoundTag tag) {
        Map<ResourceLocation, Long> cooldowns = new LinkedHashMap<>();
        ListTag cooldownList = requireList(tag, "cooldowns");
        for (int index = 0; index < cooldownList.size(); index++) {
            CompoundTag item = cooldownList.getCompound(index);
            cooldowns.put(ResourceLocation.parse(requireString(item, "spellId")),
                    requireNonNegative(requireLong(item, "until"), "until"));
        }
        Map<UUID, SpellInstanceState> instances = new LinkedHashMap<>();
        ListTag instanceList = requireList(tag, "instances");
        for (int index = 0; index < instanceList.size(); index++) {
            CompoundTag item = instanceList.getCompound(index);
            SpellInstanceState value = new SpellInstanceState(requireUuid(item, "instanceId"),
                    ResourceLocation.parse(requireString(item, "spellId")), requireUuid(item, "ownerBossId"),
                    requireNonNegative(requireLong(item, "startedAtTick"), "startedAtTick"),
                    requireNonNegative(requireLong(item, "endsAtTick"), "endsAtTick"),
                    requireInt(item, "reservedEntities"));
            instances.put(value.instanceId(), value);
        }
        return new SpellRuntimeState(cooldowns, instances, optionalUuid(tag, "activeCastInstanceId"),
                requireNonNegative(requireLong(tag, "nextCastTick"), "nextCastTick"),
                requireNonNegative(requireLong(tag, "nextEnvironmentTick"), "nextEnvironmentTick"),
                requireInt(tag, "reservedTemporaryEntities"));
    }

    private static CompoundTag writePhaseThree(PhaseThreeState state) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("startedAtTick", state.startedAtTick());
        tag.putLong("invulnerableUntilTick", state.invulnerableUntilTick());
        tag.putLong("warningAtTick", state.warningAtTick());
        tag.putBoolean("warningSent", state.warningSent());
        tag.putBoolean("timelineCompleted", state.timelineCompleted());
        tag.putInt("wormsSpawned", state.wormsSpawned());
        tag.putLong("nextWormSpawnTick", state.nextWormSpawnTick());
        tag.putBoolean("secondFormActive", state.secondFormActive());
        tag.putLong("nextClockSpawnTick", state.nextClockSpawnTick());
        ListTag clockHits = new ListTag();
        state.clockHits().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    CompoundTag item = new CompoundTag();
                    item.putUUID("playerId", entry.getKey());
                    item.putInt("hits", entry.getValue());
                    clockHits.add(item);
                });
        tag.put("clockHits", clockHits);
        ListTag executions = new ListTag();
        state.lastExecutionTicks().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    CompoundTag item = new CompoundTag();
                    item.putUUID("playerId", entry.getKey());
                    item.putLong("tick", entry.getValue());
                    executions.add(item);
                });
        tag.put("lastExecutionTicks", executions);
        ListTag parasites = new ListTag();
        state.parasites().entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString)))
                .forEach(entry -> {
                    CompoundTag item = new CompoundTag();
                    item.putUUID("playerId", entry.getKey());
                    item.putInt("remainingTicks", entry.getValue().remainingTicks());
                    item.putInt("infectionTicks", entry.getValue().infectionTicks());
                    item.putLong("lastProcessedTick", entry.getValue().lastProcessedTick());
                    parasites.add(item);
                });
        tag.put("parasites", parasites);
        return tag;
    }

    private static PhaseThreeState readPhaseThree(CompoundTag tag) {
        Map<UUID, Integer> clockHits = new LinkedHashMap<>();
        ListTag clockList = requireList(tag, "clockHits");
        for (int index = 0; index < clockList.size(); index++) {
            CompoundTag item = clockList.getCompound(index);
            clockHits.put(requireUuid(item, "playerId"), requireInt(item, "hits"));
        }
        Map<UUID, Long> executions = new LinkedHashMap<>();
        ListTag executionList = requireList(tag, "lastExecutionTicks");
        for (int index = 0; index < executionList.size(); index++) {
            CompoundTag item = executionList.getCompound(index);
            executions.put(requireUuid(item, "playerId"),
                    requireNonNegative(requireLong(item, "tick"), "tick"));
        }
        Map<UUID, ParasiteState> parasites = new LinkedHashMap<>();
        ListTag list = requireList(tag, "parasites");
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            parasites.put(requireUuid(item, "playerId"), new ParasiteState(
                    requireInt(item, "remainingTicks"), requireInt(item, "infectionTicks"),
                    requireNonNegative(requireLong(item, "lastProcessedTick"), "lastProcessedTick")));
        }
        return new PhaseThreeState(requireNonNegative(requireLong(tag, "startedAtTick"), "startedAtTick"),
                requireNonNegative(requireLong(tag, "invulnerableUntilTick"), "invulnerableUntilTick"),
                requireNonNegative(requireLong(tag, "warningAtTick"), "warningAtTick"),
                tag.getBoolean("warningSent"), tag.getBoolean("timelineCompleted"),
                requireInt(tag, "wormsSpawned"),
                requireNonNegative(requireLong(tag, "nextWormSpawnTick"), "nextWormSpawnTick"),
                tag.getBoolean("secondFormActive"),
                requireNonNegative(requireLong(tag, "nextClockSpawnTick"), "nextClockSpawnTick"),
                clockHits, executions, parasites);
    }

    private static CompoundTag writeSplitTransaction(SplitTransaction transaction) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("transactionId", transaction.transactionId());
        tag.putUUID("scannerBossId", transaction.scannerBossId());
        tag.putString("state", transaction.state().name());
        tag.putLong("preparedAtTick", transaction.preparedAtTick());
        ListTag replacements = new ListTag();
        transaction.replacements().forEach(replacement -> {
            CompoundTag item = new CompoundTag();
            item.putUUID("sourceEntityId", replacement.sourceEntityId());
            item.putUUID("replacementBossId", replacement.replacementBossId());
            item.putString("dimension", replacement.dimension().location().toString());
            putBlockPos(item, replacement.position());
            replacements.add(item);
        });
        tag.put("replacements", replacements);
        return tag;
    }

    private static SplitTransaction readSplitTransaction(CompoundTag tag) {
        ListTag list = requireList(tag, "replacements");
        java.util.ArrayList<SplitReplacement> replacements = new java.util.ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag item = list.getCompound(index);
            replacements.add(new SplitReplacement(requireUuid(item, "sourceEntityId"),
                    requireUuid(item, "replacementBossId"), ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(requireString(item, "dimension"))), readBlockPos(item)));
        }
        return new SplitTransaction(requireUuid(tag, "transactionId"), requireUuid(tag, "scannerBossId"),
                replacements, readEnum(TransactionState.class, tag, "state"),
                requireNonNegative(requireLong(tag, "preparedAtTick"), "preparedAtTick"));
    }

    private static CompoundTag writeEscrow(EscrowRecord record) {
        CompoundTag tag = writeReturnFields(record.stolenItemId(), record.encounterId(), record.playerId(),
                record.slotType(), record.slotIndex(), record.item(), record.state(), record.returnTransactionId());
        tag.put("fingerprint", record.fingerprint().tag());
        tag.putInt("beforeCount", record.beforeCount());
        tag.putInt("expectedAfterCount", record.expectedAfterCount());
        tag.putUUID("attackEventId", record.attackEventId());
        return tag;
    }

    private static Map<UUID, EscrowRecord> readEscrow(ListTag list) {
        Map<UUID, EscrowRecord> records = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            CompoundTag tag = list.getCompound(index);
            EscrowRecord record = new EscrowRecord(requireUuid(tag, "stolenItemId"),
                    requireUuid(tag, "encounterId"), requireUuid(tag, "playerId"),
                    readEnum(StolenSlotType.class, tag, "slotType"), requireInt(tag, "slotIndex"),
                    new SerializedItemStack(requireCompound(tag, "item")),
                    new SerializedItemStack(requireCompound(tag, "fingerprint")),
                    requireInt(tag, "beforeCount"), requireInt(tag, "expectedAfterCount"),
                    requireUuid(tag, "attackEventId"), readEnum(TransactionState.class, tag, "state"),
                    optionalUuid(tag, "returnTransactionId"));
            if (records.putIfAbsent(record.stolenItemId(), record) != null) {
                throw new IllegalStateException("Duplicate escrow record " + record.stolenItemId());
            }
        }
        return Map.copyOf(records);
    }

    private static CompoundTag writePendingReturn(PendingReturnRecord record) {
        return writeReturnFields(record.stolenItemId(), record.encounterId(), record.playerId(),
                record.slotType(), record.slotIndex(), record.item(), record.state(), record.returnTransactionId());
    }

    private static PendingReturnRecord readPendingReturn(CompoundTag tag) {
        return new PendingReturnRecord(requireUuid(tag, "stolenItemId"), requireUuid(tag, "encounterId"),
                requireUuid(tag, "playerId"), readEnum(StolenSlotType.class, tag, "slotType"),
                requireInt(tag, "slotIndex"), new SerializedItemStack(requireCompound(tag, "item")),
                readEnum(TransactionState.class, tag, "state"), optionalUuid(tag, "returnTransactionId"));
    }

    private static CompoundTag writeReturnFields(UUID stolenId, UUID encounterId, UUID playerId,
                                                  StolenSlotType slotType, int slotIndex,
                                                  SerializedItemStack item, TransactionState state,
                                                  Optional<UUID> returnId) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("stolenItemId", stolenId);
        tag.putUUID("encounterId", encounterId);
        tag.putUUID("playerId", playerId);
        tag.putString("slotType", slotType.name());
        tag.putInt("slotIndex", slotIndex);
        tag.put("item", item.tag());
        tag.putString("state", state.name());
        returnId.ifPresent(value -> tag.putUUID("returnTransactionId", value));
        return tag;
    }

    private static CompoundTag writeTransition(PhaseTransitionRecord record) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("transitionId", record.transitionId());
        tag.putString("from", record.from().name());
        tag.putString("to", record.to().name());
        tag.putUUID("carrierBossId", record.carrierBossId());
        tag.putUUID("recoveryBossId", record.recoveryBossId());
        tag.putString("recoveryDimension", record.recoveryDimension().location().toString());
        tag.putInt("recoveryX", record.recoveryPosition().getX());
        tag.putInt("recoveryY", record.recoveryPosition().getY());
        tag.putInt("recoveryZ", record.recoveryPosition().getZ());
        tag.putLong("startedAtTick", record.startedAtTick());
        return tag;
    }

    private static PhaseTransitionRecord readTransition(CompoundTag tag) {
        return new PhaseTransitionRecord(
                requireUuid(tag, "transitionId"),
                readEnum(EncounterPhase.class, tag, "from"),
                readEnum(EncounterPhase.class, tag, "to"),
                requireUuid(tag, "carrierBossId"),
                requireUuid(tag, "recoveryBossId"),
                ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(requireString(tag, "recoveryDimension"))),
                new BlockPos(requireInt(tag, "recoveryX"), requireInt(tag, "recoveryY"),
                        requireInt(tag, "recoveryZ")),
                requireNonNegative(requireLong(tag, "startedAtTick"), "startedAtTick"));
    }

    private static CompoundTag writeTermination(EncounterTermination termination) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("finalizationId", termination.finalizationId());
        tag.putString("reason", termination.reason().name());
        tag.putLong("requestedAtTick", termination.requestedAtTick());
        return tag;
    }

    private static EncounterTermination readTermination(CompoundTag tag) {
        return new EncounterTermination(
                requireUuid(tag, "finalizationId"),
                readEnum(EndReason.class, tag, "reason"),
                requireNonNegative(requireLong(tag, "requestedAtTick"), "requestedAtTick"));
    }

    private static void validateTransition(
            EncounterLifecycle lifecycle,
            Optional<PhaseTransitionRecord> transition,
            Map<UUID, BossRecord> bosses
    ) {
        if (lifecycle == EncounterLifecycle.PHASE_TRANSITION && transition.isEmpty()) {
            throw new IllegalStateException("Transition lifecycle is missing transition metadata");
        }
        transition.ifPresent(record -> {
            BossRecord carrier = bosses.get(record.carrierBossId());
            if (carrier == null || !carrier.isAlive()
                    || carrier.carrierTransitionId().filter(record.transitionId()::equals).isEmpty()) {
                throw new IllegalStateException("Active transition has no valid carrier");
            }
        });
    }

    private static void putBlockPos(CompoundTag tag, BlockPos position) {
        tag.putInt("x", position.getX());
        tag.putInt("y", position.getY());
        tag.putInt("z", position.getZ());
    }

    private static BlockPos readBlockPos(CompoundTag tag) {
        return new BlockPos(requireInt(tag, "x"), requireInt(tag, "y"), requireInt(tag, "z"));
    }

    private static UUID requireUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) {
            throw new IllegalStateException("Missing UUID " + key);
        }
        return tag.getUUID(key);
    }

    private static CompoundTag requireCompound(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Missing compound " + key);
        }
        return tag.getCompound(key);
    }

    private static ListTag requireList(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            throw new IllegalStateException("Missing list " + key);
        }
        return tag.getList(key, Tag.TAG_COMPOUND);
    }

    private static Optional<CompoundTag> optionalCompound(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return Optional.empty();
        }
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Invalid compound " + key);
        }
        return Optional.of(tag.getCompound(key));
    }

    private static Optional<UUID> optionalUuid(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return Optional.empty();
        }
        if (!tag.hasUUID(key)) {
            throw new IllegalStateException("Invalid UUID " + key);
        }
        return Optional.of(tag.getUUID(key));
    }

    private static String requireString(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            throw new IllegalStateException("Missing string " + key);
        }
        return tag.getString(key);
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, CompoundTag tag, String key) {
        try {
            return Enum.valueOf(type, requireString(tag, key));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid " + key + " value", exception);
        }
    }

    private static long requireNonNegative(long value, String key) {
        if (value < 0) {
            throw new IllegalStateException(key + " must be non-negative");
        }
        return value;
    }

    private static int requireInt(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_INT)) {
            throw new IllegalStateException("Missing integer " + key);
        }
        return tag.getInt(key);
    }

    private static long requireLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            throw new IllegalStateException("Missing long " + key);
        }
        return tag.getLong(key);
    }

    private static double requireDouble(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_DOUBLE)) {
            throw new IllegalStateException("Missing double " + key);
        }
        return tag.getDouble(key);
    }

    private static boolean requireBoolean(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_BYTE)) {
            throw new IllegalStateException("Missing boolean " + key);
        }
        return tag.getBoolean(key);
    }

    private static OptionalLong optionalLong(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return OptionalLong.empty();
        }
        if (!tag.contains(key, Tag.TAG_LONG)) {
            throw new IllegalStateException("Invalid long " + key);
        }
        return OptionalLong.of(tag.getLong(key));
    }
}
