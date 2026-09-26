package com.mysterious.combat;

/** Stable delivery semantics shared by combat, spells and later immunity rules. */
public enum DamageDeliveryType {
    MELEE,
    PROJECTILE,
    BEAM,
    AOE,
    DOT,
    DIRECT_MAGIC,
    ENVIRONMENT
}
