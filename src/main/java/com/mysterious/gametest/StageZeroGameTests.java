package com.mysterious.gametest;

import com.mysterious.entity.AmonEntity;
import com.mysterious.integration.curios.CuriosAccess;
import com.mysterious.integration.iss.IronsSpellAccess;
import com.mysterious.inventory.ItemStackIdentity;
import com.mysterious.mysterious;
import com.mysterious.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Automated smoke coverage for the compatibility spikes required by Stage 0. */
@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageZeroGameTests {
    private StageZeroGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void entitySpawnAndNbtRoundTrip(GameTestHelper helper) {
        AmonEntity source = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        source.setHealth(123.0F);

        CompoundTag tag = new CompoundTag();
        helper.assertTrue(source.save(tag), "Amon must serialize to entity NBT");

        AmonEntity restored = ModEntities.AMON.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "Amon entity type must create on a dedicated server");
        restored.load(tag);
        helper.assertValueEqual(restored.getHealth(), 123.0F, "Amon health must survive an NBT round trip");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void itemIdentitySurvivesSerialization(GameTestHelper helper) {
        ItemStack source = new ItemStack(Items.DIAMOND);
        UUID identity = ItemStackIdentity.getOrCreate(source);
        CompoundTag encoded = (CompoundTag) source.save(helper.getLevel().registryAccess());
        ItemStack restored = ItemStack.parseOptional(helper.getLevel().registryAccess(), encoded);

        helper.assertValueEqual(ItemStackIdentity.get(restored).orElseThrow(), identity,
                "Item identity must survive ItemStack serialization");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void dependencyApisAreReadable(GameTestHelper helper) {
        helper.assertTrue(!IronsSpellAccess.enabledSpells().isEmpty(), "ISS must expose enabled spells");
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(CuriosAccess.inventory(player).isPresent(), "Curios must expose a player inventory handler");
        helper.succeed();
    }
}
