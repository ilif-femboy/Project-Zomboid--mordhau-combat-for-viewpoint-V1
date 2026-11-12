package mordhau;

import zombie.characters.BodyDamage.BodyPartType;

import java.util.concurrent.ThreadLocalRandom;

public enum AttackDirection {
    LEFT("left", "UI_Mordhau_Dir_Left", 1.05f, 6.0f, 1.00f, 1.00f, 0.08f),
    RIGHT("right", "UI_Mordhau_Dir_Right", 1.05f, 6.0f, 1.00f, 1.00f, 0.08f),
    OVERHEAD("overhead", "UI_Mordhau_Dir_Overhead", 1.32f, 22.0f, 1.05f, 1.02f, 0.25f),
    UNDERHAND("underhand", "UI_Mordhau_Dir_Underhand", 1.12f, 12.0f, 0.95f, 1.00f, 0.15f),
    THRUST("thrust", "UI_Mordhau_Dir_Thrust", 1.20f, 18.0f, 0.85f, 1.06f, 0.40f);

    public final String id;
    public final String displayName;
    public final float damageMultiplier;
    public final float bonusCritChance;
    public final float knockbackMultiplier;
    public final float rangeMultiplier;
    public final float baseArmorPenetration;

    AttackDirection(
            String id,
            String displayName,
            float damageMultiplier,
            float bonusCritChance,
            float knockbackMultiplier,
            float rangeMultiplier,
            float baseArmorPenetration
    ) {
        this.id = id;
        this.displayName = displayName;
        this.damageMultiplier = damageMultiplier;
        this.bonusCritChance = bonusCritChance;
        this.knockbackMultiplier = knockbackMultiplier;
        this.rangeMultiplier = rangeMultiplier;
        this.baseArmorPenetration = baseArmorPenetration;
    }

    public boolean isThrust() {
        return this == THRUST;
    }

    public boolean isSwing() {
        return this != THRUST;
    }

    /**
     * Classifies a continuous screen-space angle (in radians: 0 = Right, PI/-PI = Left,
     * -PI/2 = Overhead, +PI/2 = Underhand) into the closest directional sector.
     * Note: actual 3D weapon kinematics & blade tracing use the exact continuous angleRad!
     */
    public static AttackDirection fromAngleRad(float angleRad) {
        float c = (float) Math.cos(angleRad);
        float s = (float) Math.sin(angleRad);
        if (Math.abs(c) >= Math.abs(s) * 0.82f) {
            return c >= 0.0f ? RIGHT : LEFT;
        }
        return s < 0.0f ? OVERHEAD : UNDERHAND;
    }

    public float defaultAngleRad() {
        switch (this) {
            case RIGHT:
                return 0.0f;
            case LEFT:
                return (float) Math.PI;
            case OVERHEAD:
                return -(float) (Math.PI * 0.5);
            case UNDERHAND:
                return (float) (Math.PI * 0.5);
            default:
                return 0.0f;
        }
    }

    public int selectFallbackBodyPartIndex() {
        int roll = ThreadLocalRandom.current().nextInt(100);
        switch (this) {
            case OVERHEAD:
                if (roll < 65) return BodyPartType.ToIndex(BodyPartType.Head);
                if (roll < 85) return BodyPartType.ToIndex(BodyPartType.Neck);
                return BodyPartType.ToIndex(BodyPartType.Torso_Upper);
            case THRUST:
                if (roll < 45) return BodyPartType.ToIndex(BodyPartType.Torso_Upper);
                if (roll < 75) return BodyPartType.ToIndex(BodyPartType.Neck);
                return BodyPartType.ToIndex(BodyPartType.Head);
            case LEFT:
                if (roll < 40) return BodyPartType.ToIndex(BodyPartType.UpperArm_R);
                if (roll < 70) return BodyPartType.ToIndex(BodyPartType.ForeArm_R);
                return BodyPartType.ToIndex(BodyPartType.Torso_Upper);
            case RIGHT:
                if (roll < 40) return BodyPartType.ToIndex(BodyPartType.UpperArm_L);
                if (roll < 70) return BodyPartType.ToIndex(BodyPartType.ForeArm_L);
                return BodyPartType.ToIndex(BodyPartType.Torso_Upper);
            case UNDERHAND:
                if (roll < 35) return BodyPartType.ToIndex(BodyPartType.Groin);
                if (roll < 65) return BodyPartType.ToIndex(BodyPartType.Torso_Lower);
                if (roll < 82) return BodyPartType.ToIndex(BodyPartType.UpperLeg_L);
                return BodyPartType.ToIndex(BodyPartType.UpperLeg_R);
            default:
                return BodyPartType.ToIndex(BodyPartType.Torso_Upper);
        }
    }

    public static AttackDirection fromString(String name) {
        if (name == null) {
            return RIGHT;
        }
        for (AttackDirection dir : values()) {
            if (dir.id.equalsIgnoreCase(name) || dir.name().equalsIgnoreCase(name)) {
                return dir;
            }
        }
        return RIGHT;
    }
}
