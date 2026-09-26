package com.mysterious.entity;

import com.mojang.logging.LogUtils;
import com.mysterious.encounter.EncounterBoundEntity;
import com.mysterious.encounter.EncounterRuntimeService;
import com.mysterious.phase.FlightController;
import com.mysterious.combat.AttackEventIdentity;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.magic.SpellSelectionManager;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.*;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class AmonEntity extends Monster implements GeoEntity, EncounterBoundEntity, IMagicEntity {
    private static final EntityDataAccessor<Boolean> SECOND_FORM =
            SynchedEntityData.defineId(AmonEntity.class, EntityDataSerializers.BOOLEAN);
    private static final String ENCOUNTER_ID_TAG = "MysteriousEncounterId";
    private static final String LAST_COMBAT_TICK_TAG = "MysteriousLastCombatTick";
    private static final String LAST_OUTGOING_HIT_TICK_TAG = "MysteriousLastOutgoingHitTick";
    private static final String TELEPORT_COOLDOWN_TAG = "MysteriousTeleportCooldownUntil";
    private static final String TELEPORT_LOCKOUT_TAG = "MysteriousTeleportLockoutUntil";
    private static final String LAST_HIT_EVENT_TAG = "MysteriousLastHitEvent";
    private static final String SECOND_FORM_TAG = "MysteriousSecondForm";
    private static final String ISS_MAGIC_TAG = "MysteriousIssMagic";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("battlemage_armor.attack");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.battlemage_armor.walk");
    private static final RawAnimation CAST = RawAnimation.begin().thenPlay("animation.battlemage_armor.cast");
    private static final RawAnimation FLY = RawAnimation.begin().thenLoop("animation.battlemage_armor.fly");
    private static final RawAnimation SUMMON = RawAnimation.begin().thenPlay("animation.battlemage_armor.summon");
    public static final int MELEE_ATTACK_INTERVAL_TICKS = 10;
    public static final double MELEE_ATTACK_RANGE = 4.5D;
    private UUID encounterId;
    private long lastCombatTick;
    private long lastOutgoingHitTick;
    private long teleportCooldownUntilTick;
    private long teleportLockoutUntilTick;
    private UUID lastHitTeleportEvent;
    private final MagicData magicData = new MagicData(true);
    private SpellData castingSpell;
    private boolean hasUsedSingleAttack;

    public AmonEntity(EntityType<? extends AmonEntity> type, Level level) {
        super(type, level);
        magicData.setSyncedData(new SyncedSpellData(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SECOND_FORM, false);
    }

    public static AttributeSupplier.Builder attributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 200).add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, 10).add(Attributes.FOLLOW_RANGE, 96).add(Attributes.ARMOR, 12)
                .add(AttributeRegistry.SPELL_POWER, 1.0D)
                .add(AttributeRegistry.CAST_TIME_REDUCTION, 1.0D)
                .add(AttributeRegistry.SUMMON_DAMAGE, 1.0D)
                .add(AttributeRegistry.FIRE_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.ICE_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.LIGHTNING_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.HOLY_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.ENDER_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.BLOOD_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.EVOCATION_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.NATURE_SPELL_POWER, 1.0D)
                .add(AttributeRegistry.ELDRITCH_SPELL_POWER, 1.0D);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new AmonMeleeAttackGoal(this, 1.15D, false));
        goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 12));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true,
                target -> target instanceof ServerPlayer player
                        && EncounterRuntimeService.mayAcquireTarget(this, player)));
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, LivingEntity.class, true,
                this::mysterious$mayAcquireFallbackTarget));
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof EncounterBoundEntity)
                && super.canAttack(target);
    }

    public boolean mysterious$mayAcquireFallbackTarget(LivingEntity target) {
        return !(target instanceof Player)
                && !(target instanceof EncounterBoundEntity)
                && target.isAlive()
                && !isAlliedTo(target)
                && canAttack(target);
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && !canAttack(target)) {
            return;
        }
        super.setTarget(target);
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        if (!level().isClientSide && level().getGameTime() < teleportLockoutUntilTick) {
            return false;
        }
        boolean hit = super.doHurtTarget(target);
        if (hit && !level().isClientSide) {
            mysterious$playAttackAnimation();
        }
        return hit;
    }

    public boolean mysterious$claimHitTeleport(AttackEventIdentity identity, long tick, int cooldownTicks) {
        if (identity.eventId().equals(lastHitTeleportEvent) || tick < teleportCooldownUntilTick) {
            return false;
        }
        lastHitTeleportEvent = identity.eventId();
        teleportCooldownUntilTick = tick > Long.MAX_VALUE - cooldownTicks ? Long.MAX_VALUE : tick + cooldownTicks;
        return true;
    }

    public void mysterious$markTeleported(long tick) {
        teleportLockoutUntilTick = tick > Long.MAX_VALUE - 20L ? Long.MAX_VALUE : tick + 20L;
    }

    public boolean mysterious$theftLocked(long tick) {
        return tick < teleportLockoutUntilTick;
    }

    /** Prevents the one-second attack lock from immediately scheduling another approach teleport. */
    public boolean mysterious$approachTeleportReady(long tick) {
        long lastTeleportTick = Math.max(0L, teleportLockoutUntilTick - 20L);
        return tick - Math.max(lastOutgoingHitTick, lastTeleportTick) >= 12L * 20L;
    }

    /** Shared spacing gate for both hit-react and approach teleports. */
    public boolean mysterious$normalTeleportReady(long tick, int minimumIntervalTicks) {
        long lastTeleportTick = Math.max(0L, teleportLockoutUntilTick - 20L);
        return tick - lastTeleportTick >= Math.max(0, minimumIntervalTicks);
    }

    public void mysterious$recordOutgoingHit(long tick) {
        lastOutgoingHitTick = tick;
        lastCombatTick = tick;
    }

    public long mysterious$lastOutgoingHitTick() {
        return lastOutgoingHitTick;
    }

    public long mysterious$lastCombatTick() {
        return lastCombatTick;
    }

    public boolean mysterious$isSecondForm() {
        return entityData.get(SECOND_FORM);
    }

    public void mysterious$setSecondForm(boolean active) {
        entityData.set(SECOND_FORM, active);
        setNoGravity(active);
        if (active) getNavigation().stop();
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 5, state -> {
            boolean flying = mysterious$isSecondForm();
            if (flying) return state.setAndContinue(FLY);
            if (mysterious$shouldPlayWalk(state.isMoving(), flying)) {
                return state.setAndContinue(WALK);
            }
            // An empty RawAnimation does not stop a looping GeckoLib clip. Returning STOP is what
            // actually releases the previous walk loop when navigation reaches its destination.
            state.getController().forceAnimationReset();
            return PlayState.STOP;
        }));
        controllers.add(new AnimationController<>(this, "actions", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", ATTACK)
                .triggerableAnim("cast", CAST)
                .triggerableAnim("summon", SUMMON));
    }

    public void mysterious$playAttackAnimation() {
        triggerAnim("actions", "attack");
    }

    public void mysterious$playCastAnimation() {
        triggerAnim("actions", "cast");
    }

    public void mysterious$playSummonAnimation() {
        triggerAnim("actions", "summon");
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (castingSpell == null) return;
        AbstractSpell spell = castingSpell.getSpell();
        int level = castingSpell.getLevel();
        try {
            magicData.handleCastDuration();
            if (magicData.isCasting()) spell.onServerCastTick(level(), level, this, magicData);
            if (getTarget() != null) getLookControl().setLookAt(getTarget(), 30.0F, 30.0F);
            if (magicData.getCastDurationRemaining() <= 0) {
                if (spell.getCastType() == CastType.LONG || spell.getCastType() == CastType.INSTANT) {
                    spell.onCast(level(), level, this, CastSource.MOB, magicData);
                }
                castComplete();
            } else if (spell.getCastType() == CastType.CONTINUOUS
                    && (magicData.getCastDurationRemaining() + 1) % 10 == 0) {
                spell.onCast(level(), level, this, CastSource.MOB, magicData);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("[spell] ISS mob cast failed boss={} spell={}", getUUID(), spell.getSpellId(), exception);
            cancelCast();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && mysterious$isSecondForm() && getTarget() != null) {
            // FlightController runs from the encounter tick, while vanilla look/body controls run
            // during the entity tick. Re-apply flight facing last so AI cannot restore a stale yaw.
            FlightController.faceMovement(this, getTarget().getEyePosition().subtract(position()));
        }
    }

    @Override
    public MagicData getMagicData() {
        return magicData;
    }

    @Override
    public void setSyncedSpellData(SyncedSpellData syncedSpellData) {
        boolean wasCasting = magicData.isCasting();
        magicData.setSyncedData(syncedSpellData);
        if (!wasCasting && syncedSpellData.isCasting()) mysterious$playCastAnimation();
        if (wasCasting && !syncedSpellData.isCasting()) castingSpell = null;
    }

    @Override
    public boolean isCasting() {
        return magicData.isCasting();
    }

    @Override
    public void initiateCastSpell(AbstractSpell spell, int spellLevel) {
        mysterious$initiateCastSpell(spell, spellLevel, 1.0D);
    }

    /** Starts an ISS spell through its normal lifecycle while allowing phase-specific cast pacing. */
    public void mysterious$initiateCastSpell(AbstractSpell spell, int spellLevel, double castTimeScale) {
        if (spell == null || spell.getSpellResource().equals(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "none"))
                || isCasting() || !Double.isFinite(castTimeScale) || castTimeScale <= 0.0D) return;
        int level = Math.max(spell.getMinLevel(), Math.min(spellLevel, spell.getMaxLevel()));
        if (!level().isClientSide && !spell.checkPreCastConditions(level(), level, this, magicData)) return;
        castingSpell = new SpellData(spell, level);
        int effectiveCastTicks = Math.max(1,
                (int) Math.round(spell.getEffectiveCastTime(level, this) * castTimeScale));
        magicData.initiateCast(spell, level, effectiveCastTicks,
                CastSource.MOB, SpellSelectionManager.MAINHAND);
        if (!level().isClientSide) spell.onServerPreCast(level(), level, this, magicData);
        mysterious$playCastAnimation();
    }

    @Override
    public void cancelCast() {
        if (castingSpell != null && !level().isClientSide) {
            castingSpell.getSpell().onServerCastComplete(level(), castingSpell.getLevel(), this, magicData, true);
        } else {
            magicData.resetCastingState();
        }
        castingSpell = null;
    }

    @Override
    public void castComplete() {
        if (castingSpell != null && !level().isClientSide) {
            castingSpell.getSpell().onServerCastComplete(level(), castingSpell.getLevel(), this, magicData, false);
        } else {
            magicData.resetCastingState();
        }
        castingSpell = null;
    }

    @Override public void notifyDangerousProjectile(Projectile projectile) { }
    @Override public boolean setTeleportLocationBehindTarget(int distance) { return false; }
    @Override public void setBurningDashDirectionData() { }
    @Override public boolean isDrinkingPotion() { return false; }
    @Override public boolean getHasUsedSingleAttack() { return hasUsedSingleAttack; }
    @Override public void setHasUsedSingleAttack(boolean used) { hasUsedSingleAttack = used; }
    @Override public void startDrinkingPotion() { }
    /** Kept pure so the Stage 16 regression gate can guarantee idle never loops the walk clip. */
    public static boolean mysterious$shouldPlayWalk(boolean moving, boolean secondForm) {
        return moving && !secondForm;
    }

    private static final class AmonMeleeAttackGoal extends MeleeAttackGoal {
        private final AmonEntity amon;

        private AmonMeleeAttackGoal(AmonEntity amon, double speedModifier, boolean followingTargetEvenIfNotSeen) {
            super(amon, speedModifier, followingTargetEvenIfNotSeen);
            this.amon = amon;
        }

        @Override
        protected int getAttackInterval() {
            return MELEE_ATTACK_INTERVAL_TICKS;
        }

        @Override
        protected boolean canPerformAttack(LivingEntity target) {
            return isTimeToAttack()
                    && amon.distanceToSqr(target) <= MELEE_ATTACK_RANGE * MELEE_ATTACK_RANGE
                    && amon.getSensing().hasLineOfSight(target);
        }
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    @Override
    public Optional<UUID> mysterious$getEncounterId() {
        return Optional.ofNullable(encounterId);
    }

    @Override
    public void mysterious$bindEncounter(UUID encounterId) {
        UUID requested = Objects.requireNonNull(encounterId, "encounterId");
        if (this.encounterId != null && !this.encounterId.equals(requested)) {
            throw new IllegalStateException("Amon is already bound to encounter " + this.encounterId);
        }
        this.encounterId = requested;
        long now = Math.max(0L, level().getGameTime());
        if (lastCombatTick == 0L) {
            lastCombatTick = now;
        }
        if (lastOutgoingHitTick == 0L) {
            lastOutgoingHitTick = now;
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (encounterId != null) {
            tag.putUUID(ENCOUNTER_ID_TAG, encounterId);
        }
        tag.putLong(LAST_COMBAT_TICK_TAG, lastCombatTick);
        tag.putLong(LAST_OUTGOING_HIT_TICK_TAG, lastOutgoingHitTick);
        tag.putLong(TELEPORT_COOLDOWN_TAG, teleportCooldownUntilTick);
        tag.putLong(TELEPORT_LOCKOUT_TAG, teleportLockoutUntilTick);
        if (lastHitTeleportEvent != null) {
            tag.putUUID(LAST_HIT_EVENT_TAG, lastHitTeleportEvent);
        }
        tag.putBoolean(SECOND_FORM_TAG, mysterious$isSecondForm());
        CompoundTag issMagic = new CompoundTag();
        magicData.saveNBTData(issMagic, level().registryAccess());
        tag.put(ISS_MAGIC_TAG, issMagic);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        encounterId = tag.hasUUID(ENCOUNTER_ID_TAG) ? tag.getUUID(ENCOUNTER_ID_TAG) : null;
        lastCombatTick = Math.max(0L, tag.getLong(LAST_COMBAT_TICK_TAG));
        lastOutgoingHitTick = Math.max(0L, tag.getLong(LAST_OUTGOING_HIT_TICK_TAG));
        teleportCooldownUntilTick = Math.max(0L, tag.getLong(TELEPORT_COOLDOWN_TAG));
        teleportLockoutUntilTick = Math.max(0L, tag.getLong(TELEPORT_LOCKOUT_TAG));
        lastHitTeleportEvent = tag.hasUUID(LAST_HIT_EVENT_TAG) ? tag.getUUID(LAST_HIT_EVENT_TAG) : null;
        mysterious$setSecondForm(tag.getBoolean(SECOND_FORM_TAG));
        if (tag.contains(ISS_MAGIC_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            magicData.loadNBTData(tag.getCompound(ISS_MAGIC_TAG), level().registryAccess());
            // Active casts are restarted by the encounter spell scheduler after recovery. Keeping a
            // half-restored ISS cast here would otherwise leave its client casting flag stuck.
            magicData.resetCastingState();
            castingSpell = null;
        }
    }
}
