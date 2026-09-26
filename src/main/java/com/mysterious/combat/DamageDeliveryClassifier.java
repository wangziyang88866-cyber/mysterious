package com.mysterious.combat;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.Objects;

/** Conservative server-side classification. Unknown non-entity damage remains environmental. */
public final class DamageDeliveryClassifier {
    private DamageDeliveryClassifier() {
    }

    public static DamageDeliveryType classify(DamageSource source) {
        Objects.requireNonNull(source, "source");
        if (source.is(DamageTypeTags.IS_PROJECTILE) || source.getDirectEntity() instanceof Projectile) {
            return DamageDeliveryType.PROJECTILE;
        }
        if (source.getEntity() == null) {
            return DamageDeliveryType.ENVIRONMENT;
        }
        if (source.is(DamageTypeTags.IS_PLAYER_ATTACK)) {
            return DamageDeliveryType.MELEE;
        }
        String id = source.getMsgId();
        if (id.contains("indirectMagic") || id.contains("magic")) {
            return DamageDeliveryType.DIRECT_MAGIC;
        }
        if (id.contains("wither") || id.contains("poison")) {
            return DamageDeliveryType.DOT;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return DamageDeliveryType.AOE;
        }
        return source.isDirect() ? DamageDeliveryType.MELEE : DamageDeliveryType.AOE;
    }
}
