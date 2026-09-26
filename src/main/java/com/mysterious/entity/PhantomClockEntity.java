package com.mysterious.entity;

import com.mysterious.encounter.EncounterBoundEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

/** Visual encounter entity; its lifecycle can be managed by Amon's future skills. */
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Encounter-owned clock with a persistent absolute expiry tick. */
public class PhantomClockEntity extends PathfinderMob implements GeoEntity, EncounterBoundEntity {
    private static final String ENCOUNTER_TAG = "MysteriousEncounterId";
    private static final String EXPIRES_AT_TAG = "MysteriousExpiresAtTick";
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID encounterId;
    private long expiresAtTick;

    public PhantomClockEntity(EntityType<? extends PhantomClockEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
        setInvulnerable(true);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 1).add(Attributes.MOVEMENT_SPEED, 0);
    }

    public void mysterious$initialize(UUID encounterId, long expiresAtTick) {
        mysterious$bindEncounter(encounterId);
        if (expiresAtTick < 0) throw new IllegalArgumentException("expiresAtTick must be non-negative");
        this.expiresAtTick = expiresAtTick;
    }

    public long mysterious$expiresAtTick() {
        return expiresAtTick;
    }

    @Override public boolean isAttackable() { return false; }

    @Override public boolean skipAttackInteraction(Entity attacker) { return true; }

    @Override public boolean isInvulnerableTo(DamageSource source) { return true; }

    @Override public boolean isPushable() { return false; }

    /** Clock expiry is owned by ClockRuntimeService; even command-style kill paths cannot finish it early. */
    @Override public void kill() { }

    @Override public Optional<UUID> mysterious$getEncounterId() { return Optional.ofNullable(encounterId); }

    @Override public void mysterious$bindEncounter(UUID id) {
        UUID value = Objects.requireNonNull(id, "id");
        if (encounterId != null && !encounterId.equals(value)) throw new IllegalStateException("Clock already bound");
        encounterId = value;
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (encounterId != null) tag.putUUID(ENCOUNTER_TAG, encounterId);
        tag.putLong(EXPIRES_AT_TAG, expiresAtTick);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        encounterId = tag.hasUUID(ENCOUNTER_TAG) ? tag.getUUID(ENCOUNTER_TAG) : null;
        expiresAtTick = Math.max(0L, tag.getLong(EXPIRES_AT_TAG));
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "clock", 0, state ->
                state.setAndContinue(RawAnimation.begin().thenPlay("animation.giant_translucent_clock.one_cycle"))));
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
