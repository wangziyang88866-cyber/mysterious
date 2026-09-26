package com.mysterious.integration;

import com.mojang.logging.LogUtils;
import com.mysterious.integration.curios.CuriosAccess;
import com.mysterious.integration.iss.IronsSpellAccess;
import com.mysterious.mysterious;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.util.List;

/** Emits a compact, server-authoritative compatibility baseline into latest.log. */
@EventBusSubscriber(modid = mysterious.MODID)
public final class StageZeroCompatibilityProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> REQUIRED_MODS = List.of(
            "neoforge", "geckolib", "curios", "irons_spellbooks"
    );

    private StageZeroCompatibilityProbe() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        String versions = REQUIRED_MODS.stream()
                .map(id -> id + "=" + versionOf(id))
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        int enabledSpells = IronsSpellAccess.enabledSpells().size();
        LOGGER.info("[stage0] dedicated-server compatibility ready; java={}, {}, enabledIssSpells={}",
                Runtime.version().feature(), versions, enabledSpells);
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOGGER.debug("[stage0] Curios slots for {}: {}", player.getUUID(), CuriosAccess.slotCounts(player));
        }
    }

    private static String versionOf(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("MISSING");
    }
}
