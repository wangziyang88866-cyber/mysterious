package com.mysterious.entity;

import com.mysterious.encounter.EncounterBoundEntity;
import com.mysterious.config.MysteriousServerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class WormOfTimeEntity extends Monster implements GeoEntity, EncounterBoundEntity {
    private static final String ENCOUNTER_TAG = "MysteriousEncounterId";
    private static final String LAST_ATTACK_TAG = "MysteriousLastAttackTick";
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID encounterId;
    private long lastAttackTick;

    public WormOfTimeEntity(EntityType<? extends WormOfTimeEntity> type, Level level) { super(type, level); }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 10).add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3).add(Attributes.FOLLOW_RANGE, 24);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
    }

    /** Worms are encounter summons and always mirror Amon's resolved target, regardless of entity type. */
    public void mysterious$assignTarget(LivingEntity target) {
        if (target == null || !target.isAlive() || target instanceof EncounterBoundEntity || isAlliedTo(target)) {
            super.setTarget(null);
            return;
        }
        super.setTarget(target);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        long now = level().getGameTime();
        if (now - lastAttackTick < MysteriousServerConfig.wormAttackIntervalTicks()) return false;
        boolean hit = super.doHurtTarget(target);
        if (hit) lastAttackTick = now;
        return hit;
    }

    @Override public Optional<UUID> mysterious$getEncounterId() { return Optional.ofNullable(encounterId); }

    @Override public void mysterious$bindEncounter(UUID id) {
        UUID value = Objects.requireNonNull(id, "id");
        if (encounterId != null && !encounterId.equals(value)) throw new IllegalStateException("Worm already bound");
        encounterId = value;
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (encounterId != null) tag.putUUID(ENCOUNTER_TAG, encounterId);
        tag.putLong(LAST_ATTACK_TAG, lastAttackTick);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        encounterId = tag.hasUUID(ENCOUNTER_TAG) ? tag.getUUID(ENCOUNTER_TAG) : null;
        lastAttackTick = Math.max(0L, tag.getLong(LAST_ATTACK_TAG));
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "crawl", 0, state ->
                state.setAndContinue(RawAnimation.begin().thenLoop("animation.worm_of_time.crawl"))));
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
