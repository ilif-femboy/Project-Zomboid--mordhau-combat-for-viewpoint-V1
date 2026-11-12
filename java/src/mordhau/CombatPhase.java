package mordhau;

public enum CombatPhase {
    IDLE("idle", 1.0f),
    WINDUP("windup", 0.90f),
    RELEASE("release", 0.84f),
    RECOVERY("recovery", 0.94f),
    BOUNCE("bounce", 0.82f),
    PARRY("parry", 0.85f),
    PARRY_RECOVERY("parry_recovery", 0.92f),
    KICK("kick", 0.36f);

    public final String id;
    public final float moveSpeedMultiplier;

    CombatPhase(String id, float moveSpeedMultiplier) {
        this.id = id;
        this.moveSpeedMultiplier = moveSpeedMultiplier;
    }
}
