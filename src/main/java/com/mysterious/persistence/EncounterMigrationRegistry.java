package com.mysterious.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Explicit, contiguous schema migration chain. Unknown future schemas fail closed. */
public final class EncounterMigrationRegistry {
    private static final Map<Integer, UnaryOperator<CompoundTag>> MIGRATIONS = Map.of(
            0, EncounterMigrationRegistry::migrateUnversionedToV1,
            1, EncounterMigrationRegistry::migrateSchemaV1ToV2,
            2, EncounterMigrationRegistry::migrateSchemaV2ToV3,
            3, EncounterMigrationRegistry::migrateSchemaV3ToV4,
            4, EncounterMigrationRegistry::migrateSchemaV4ToV5,
            5, EncounterMigrationRegistry::migrateSchemaV5ToV6,
            6, EncounterMigrationRegistry::migrateSchemaV6ToV7
    );
    private static final Map<Integer, UnaryOperator<CompoundTag>> DATA_MIGRATIONS = Map.of(
            0, EncounterMigrationRegistry::migrateDataV0ToV1,
            1, EncounterMigrationRegistry::migrateDataV1ToV2,
            2, EncounterMigrationRegistry::migrateDataV2ToV3,
            3, EncounterMigrationRegistry::migrateDataV3ToV4,
            4, EncounterMigrationRegistry::migrateDataV4ToV5,
            5, EncounterMigrationRegistry::migrateDataV5ToV6,
            6, EncounterMigrationRegistry::migrateDataV6ToV7
    );

    private EncounterMigrationRegistry() {
    }

    public static CompoundTag migrate(CompoundTag source) {
        CompoundTag migrated = source.copy();
        if (migrated.contains("schemaVersion") && !migrated.contains("schemaVersion", Tag.TAG_INT)) {
            throw new IllegalStateException("schemaVersion must be an integer");
        }
        int version = migrated.contains("schemaVersion", Tag.TAG_INT) ? migrated.getInt("schemaVersion") : 0;
        if (version > EncounterDataVersions.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("Encounter data uses unsupported future schema " + version);
        }
        while (version < EncounterDataVersions.CURRENT_SCHEMA_VERSION) {
            UnaryOperator<CompoundTag> migration = MIGRATIONS.get(version);
            if (migration == null) {
                throw new IllegalStateException("Missing encounter migration from schema " + version);
            }
            migrated = migration.apply(migrated);
            int next = migrated.getInt("schemaVersion");
            if (next != version + 1) {
                throw new IllegalStateException("Encounter migration chain did not advance exactly one version");
            }
            version = next;
        }
        if (migrated.contains("dataVersion") && !migrated.contains("dataVersion", Tag.TAG_INT)) {
            throw new IllegalStateException("dataVersion must be an integer");
        }
        int dataVersion = migrated.contains("dataVersion", Tag.TAG_INT) ? migrated.getInt("dataVersion") : 0;
        if (dataVersion > EncounterDataVersions.CURRENT_DATA_VERSION) {
            throw new IllegalStateException("Encounter data uses unsupported future data version " + dataVersion);
        }
        while (dataVersion < EncounterDataVersions.CURRENT_DATA_VERSION) {
            UnaryOperator<CompoundTag> migration = DATA_MIGRATIONS.get(dataVersion);
            if (migration == null) {
                throw new IllegalStateException("Missing encounter data migration from version " + dataVersion);
            }
            migrated = migration.apply(migrated);
            int next = migrated.getInt("dataVersion");
            if (next != dataVersion + 1) {
                throw new IllegalStateException("Encounter data migration did not advance exactly one version");
            }
            dataVersion = next;
        }
        return migrated;
    }

    private static CompoundTag migrateUnversionedToV1(CompoundTag source) {
        CompoundTag migrated = source.copy();
        if (!migrated.contains("encounters")) {
            migrated.put("encounters", new ListTag());
        }
        migrated.putInt("schemaVersion", 1);
        migrated.putInt("dataVersion", 1);
        return migrated;
    }

    private static CompoundTag migrateSchemaV1ToV2(CompoundTag source) {
        CompoundTag migrated = source.copy();
        ListTag encounters = migrated.getList("encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < encounters.size(); index++) {
            CompoundTag encounter = encounters.getCompound(index);
            if (!encounter.contains("center", Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("Cannot migrate encounter without center");
            }
            CompoundTag center = encounter.getCompound("center");
            if (!center.contains("safeAnchor", Tag.TAG_COMPOUND)) {
                CompoundTag anchor = new CompoundTag();
                anchor.putInt("x", center.getInt("x"));
                anchor.putInt("y", center.getInt("y"));
                anchor.putInt("z", center.getInt("z"));
                center.put("safeAnchor", anchor);
            }
            if (!center.contains("phaseOneRecoveryFractionPerSecond", Tag.TAG_DOUBLE)) {
                center.putDouble("phaseOneRecoveryFractionPerSecond", 0.02D);
            }
            if (!center.contains("safePositionAttempts", Tag.TAG_INT)) {
                center.putInt("safePositionAttempts", 24);
            }
        }
        migrated.putInt("schemaVersion", 2);
        return migrated;
    }

    private static CompoundTag migrateDataV0ToV1(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 1);
        return migrated;
    }

    private static CompoundTag migrateDataV1ToV2(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 2);
        return migrated;
    }

    private static CompoundTag migrateSchemaV2ToV3(CompoundTag source) {
        CompoundTag migrated = source.copy();
        ListTag encounters = migrated.getList("encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < encounters.size(); index++) {
            CompoundTag encounter = encounters.getCompound(index);
            long createdAtTick = encounter.getLong("createdAtTick");
            if (!encounter.contains("combat", Tag.TAG_COMPOUND)) {
                CompoundTag combat = new CompoundTag();
                combat.putLong("lastThreatDecayTick", createdAtTick);
                combat.put("threats", new ListTag());
                encounter.put("combat", combat);
            }
            if (!encounter.contains("phaseOne", Tag.TAG_COMPOUND)) {
                CompoundTag phaseOne = new CompoundTag();
                phaseOne.putBoolean("splitConsumed", false);
                phaseOne.putLong("nextScanTick", createdAtTick > Long.MAX_VALUE - 200L
                        ? Long.MAX_VALUE : createdAtTick + 200L);
                encounter.put("phaseOne", phaseOne);
            }
            if (!encounter.contains("escrow", Tag.TAG_LIST)) {
                encounter.put("escrow", new ListTag());
            }
        }
        if (!migrated.contains("pendingReturns", Tag.TAG_LIST)) {
            migrated.put("pendingReturns", new ListTag());
        }
        migrated.putInt("schemaVersion", 3);
        return migrated;
    }

    private static CompoundTag migrateDataV2ToV3(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 3);
        return migrated;
    }

    private static CompoundTag migrateSchemaV3ToV4(CompoundTag source) {
        CompoundTag migrated = source.copy();
        ListTag encounters = migrated.getList("encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < encounters.size(); index++) {
            CompoundTag encounter = encounters.getCompound(index);
            if (!encounter.contains("phaseGeneration", Tag.TAG_LONG)) {
                encounter.putLong("phaseGeneration", 0L);
            }
            if (!encounter.contains("activeTransition", Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag transition = encounter.getCompound("activeTransition");
            UUID transitionId = transition.getUUID("transitionId");
            transition.putUUID("recoveryBossId", UUID.nameUUIDFromBytes(
                    ("mysterious:phase-recovery:" + transitionId)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            UUID carrierId = transition.getUUID("carrierBossId");
            ListTag bosses = encounter.getList("bosses", Tag.TAG_COMPOUND);
            for (int bossIndex = 0; bossIndex < bosses.size(); bossIndex++) {
                CompoundTag boss = bosses.getCompound(bossIndex);
                if (boss.hasUUID("entityId") && boss.getUUID("entityId").equals(carrierId)) {
                    transition.putString("recoveryDimension", boss.getString("dimension"));
                    transition.putInt("recoveryX", boss.getInt("x"));
                    transition.putInt("recoveryY", boss.getInt("y"));
                    transition.putInt("recoveryZ", boss.getInt("z"));
                    break;
                }
            }
        }
        migrated.putInt("schemaVersion", 4);
        return migrated;
    }

    private static CompoundTag migrateDataV3ToV4(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 4);
        return migrated;
    }

    private static CompoundTag migrateSchemaV4ToV5(CompoundTag source) {
        CompoundTag migrated = source.copy();
        ListTag encounters = migrated.getList("encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < encounters.size(); index++) {
            CompoundTag encounter = encounters.getCompound(index);
            if (!encounter.contains("advanced", Tag.TAG_COMPOUND)) {
                CompoundTag advanced = new CompoundTag();
                CompoundTag spells = new CompoundTag();
                spells.putLong("nextCastTick", encounter.getLong("createdAtTick"));
                spells.putLong("nextEnvironmentTick", encounter.getLong("createdAtTick"));
                spells.putInt("reservedTemporaryEntities", 0);
                spells.put("cooldowns", new ListTag());
                spells.put("instances", new ListTag());
                advanced.put("spells", spells);
                encounter.put("advanced", advanced);
            }
        }
        migrated.putInt("schemaVersion", 5);
        return migrated;
    }

    private static CompoundTag migrateDataV4ToV5(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 5);
        return migrated;
    }

    private static CompoundTag migrateSchemaV5ToV6(CompoundTag source) {
        CompoundTag migrated = source.copy();
        ListTag encounters = migrated.getList("encounters", Tag.TAG_COMPOUND);
        for (int index = 0; index < encounters.size(); index++) {
            CompoundTag encounter = encounters.getCompound(index);
            if (!encounter.contains("advanced", Tag.TAG_COMPOUND)) continue;
            CompoundTag advanced = encounter.getCompound("advanced");
            if (!advanced.contains("phaseThree", Tag.TAG_COMPOUND)) continue;
            CompoundTag phase = advanced.getCompound("phaseThree");
            phase.putBoolean("secondFormActive", false);
            long started = phase.getLong("startedAtTick");
            phase.putLong("nextClockSpawnTick", started > Long.MAX_VALUE - 100L ? Long.MAX_VALUE : started + 100L);
            phase.put("clockHits", new ListTag());
            phase.put("lastExecutionTicks", new ListTag());
        }
        migrated.putInt("schemaVersion", 6);
        return migrated;
    }

    private static CompoundTag migrateDataV5ToV6(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 6);
        return migrated;
    }

    private static CompoundTag migrateSchemaV6ToV7(CompoundTag source) {
        CompoundTag migrated = source.copy();
        // Cross-dimension metadata is optional; absence is the canonical idle state.
        migrated.putInt("schemaVersion", 7);
        return migrated;
    }

    private static CompoundTag migrateDataV6ToV7(CompoundTag source) {
        CompoundTag migrated = source.copy();
        migrated.putInt("dataVersion", 7);
        return migrated;
    }
}
