package com.mysterious.gametest;

import com.mysterious.entity.AmonEntity;
import com.mysterious.inventory.ItemStackIdentity;
import com.mysterious.mysterious;
import com.mysterious.phase.FlightController;
import com.mysterious.phase.PhaseTransitionRuntimeService;
import com.mysterious.phase.SealManager;
import com.mysterious.encounter.EncounterPhase;
import com.mysterious.registry.ModDataComponents;
import com.mysterious.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

@GameTestHolder(mysterious.MODID)
@PrefixGameTestTemplate(false)
public final class StageFourteenGameTests {
    private StageFourteenGameTests() {
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void specialHealthAdjustmentCannotKill(GameTestHelper helper) {
        helper.assertValueEqual(FlightController.adjustedPlayerHealth(20.0F), 10.0F,
                "Transformation must halve current health directly");
        helper.assertValueEqual(FlightController.adjustedPlayerHealth(1.0F), 1.0F,
                "Transformation must never reduce health below one");
        helper.assertValueEqual(FlightController.SPEED_PER_TICK, 0.55D,
                "Second form flight must use fixed speed without acceleration");
        helper.assertTrue(Math.abs(FlightController.yawFor(new Vec3(0.0D, 0.0D, 1.0D))) < 0.001F,
                "Southward flight must face south");
        helper.assertTrue(Math.abs(FlightController.yawFor(new Vec3(1.0D, 0.0D, 0.0D)) + 90.0F) < 0.001F,
                "Eastward flight must face east");
        helper.assertValueEqual(FlightController.approachRotation(0.0F, 90.0F, 24.0F), 24.0F,
                "Flight turns must update visibly while remaining smooth");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void secondFormSurvivesEntityNbt(GameTestHelper helper) {
        AmonEntity source = helper.spawn(ModEntities.AMON.get(), 1, 2, 1);
        source.mysterious$setSecondForm(true);
        var tag = new net.minecraft.nbt.CompoundTag();
        helper.assertTrue(source.save(tag), "Second-form Amon must serialize");
        AmonEntity restored = ModEntities.AMON.get().create(helper.getLevel());
        helper.assertTrue(restored != null, "Amon must be constructible");
        restored.load(tag);
        helper.assertTrue(restored.mysterious$isSecondForm(), "Second form must survive restart");
        helper.assertTrue(restored.isNoGravity(), "Restored second form must remain in flight mode");
        restored.setHealth(1.0F);
        PhaseTransitionRuntimeService.prepareCarrierForPhase(restored, EncounterPhase.PHASE_THREE);
        helper.assertValueEqual(restored.getHealth(), restored.getMaxHealth(),
                "P3 must begin at full health so ordinary opening damage remains effective");
        helper.assertTrue(!restored.mysterious$isSecondForm(),
                "P3 must begin as the mobile ground form before the timed flight transformation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void sealFollowsItemIdentityAndExpires(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        UUID encounterId = UUID.randomUUID();
        UUID sealId = SealManager.seal(stack, encounterId, 100L);
        UUID itemId = ItemStackIdentity.get(stack).orElseThrow();
        CompoundTag encoded = (CompoundTag) stack.save(helper.getLevel().registryAccess());
        ItemStack restored = ItemStack.parseOptional(helper.getLevel().registryAccess(), encoded);
        helper.assertValueEqual(ItemStackIdentity.get(restored).orElseThrow(), itemId,
                "Seal must follow the physical item rather than its slot");
        helper.assertValueEqual(restored.get(ModDataComponents.SEAL_ID.get()), sealId,
                "Seal ID must survive ItemStack serialization");
        helper.assertValueEqual(restored.get(ModDataComponents.SEAL_ENCOUNTER_ID.get()), encounterId,
                "Cleanup ownership must survive ItemStack serialization");
        helper.assertTrue(SealManager.isActive(restored, 259L), "Seal must remain active for eight seconds");
        helper.assertValueEqual(SealManager.remainingCooldownTicks(restored, 100L), 160,
                "A fresh seal must expose its complete duration as a vanilla cooldown");
        helper.assertValueEqual(SealManager.remainingCooldownTicks(restored, 259L), 1,
                "The cooldown must count down against the same absolute seal deadline");
        helper.assertTrue(!SealManager.isActive(restored, 260L), "Seal must expire at its absolute deadline");
        helper.assertValueEqual(SealManager.remainingCooldownTicks(restored, 260L), 0,
                "Expired seals must not leave a cooldown duration");
        helper.succeed();
    }

    @GameTest(templateNamespace = "minecraft", template = "bastion/blocks/air")
    public static void sealSupportsStackableInventoryItems(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.ENDER_PEARL, 16);
        SealManager.seal(stack, UUID.randomUUID(), 100L);
        helper.assertValueEqual(stack.getCount(), 16,
                "Sealing a stack must not remove or split its contents");
        helper.assertTrue(SealManager.isActive(stack, 101L),
                "Stackable hotbar and inventory items must be sealable");
        helper.succeed();
    }
}
