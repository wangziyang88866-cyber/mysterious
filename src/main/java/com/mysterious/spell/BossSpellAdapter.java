package com.mysterious.spell;

import com.mysterious.combat.DamageDeliveryType;
import com.mysterious.integration.iss.IronsSpellAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import com.mysterious.encounter.EncounterSnapshot;
import com.mysterious.encounter.PlayerParticipationState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts ISS metadata to player-independent boss definitions; unsafe shapes fail closed. */
public final class BossSpellAdapter {
    private BossSpellAdapter() {
    }

    public static List<BossSpellDefinition> safePlayerCopyPool(EncounterSnapshot snapshot,
                                                                MinecraftServer server) {
        Map<String, IronsSpellAccess.SpellMetadata> spells = new LinkedHashMap<>();
        snapshot.players().values().forEach(participant -> {
            if (participant.participation() == PlayerParticipationState.LEFT) return;
            ServerPlayer player = server.getPlayerList().getPlayer(participant.playerId());
            if (player == null) return;
            IronsSpellAccess.equippedSpells(player).forEach(metadata -> spells.merge(metadata.id(), metadata,
                    (left, right) -> left.level() >= right.level() ? left : right));
        });
        return spells.values().stream().map(BossSpellAdapter::adapt).toList();
    }

    /** Every enabled base or expansion ISS spell at its configured maximum level for P2/P3. */
    public static List<BossSpellDefinition> fullLegendaryPool() {
        return IronsSpellAccess.enabledSpells().stream().map(BossSpellAdapter::adaptLegendary).toList();
    }

    private static BossSpellDefinition adapt(IronsSpellAccess.SpellMetadata metadata) {
        ResourceLocation source = ResourceLocation.parse(metadata.id());
        int castTicks = Math.max(1, metadata.castTicks());
        return new BossSpellDefinition(source, metadata.level(), SpellCategory.PLAYER_COPY,
                SpellTargetType.SINGLE_ENTITY, 0, 40, 0,
                Math.max(100, metadata.cooldownTicks()), "copied", castTicks,
                false, 1, SpellInterruptPolicy.ON_PHASE_CHANGE, true,
                DamageDeliveryType.DIRECT_MAGIC, 1, true, true);
    }

    private static BossSpellDefinition adaptLegendary(IronsSpellAccess.SpellMetadata metadata) {
        ResourceLocation source = ResourceLocation.parse(metadata.id());
        int castTicks = Math.max(1, metadata.castTicks());
        return new BossSpellDefinition(source, metadata.maxLevel(), SpellCategory.LEGENDARY,
                SpellTargetType.SINGLE_ENTITY, 0, 48, 0,
                Math.max(40, metadata.cooldownTicks()), "legendary_all", castTicks,
                false, 1, SpellInterruptPolicy.ON_PHASE_CHANGE, true,
                DamageDeliveryType.DIRECT_MAGIC, 1, true, true);
    }
}
