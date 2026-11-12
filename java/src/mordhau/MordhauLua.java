package mordhau;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;

@Exposer.LuaClass(name = "MordhauCombat")
public class MordhauLua {

    public MordhauLua() {
    }

    @LuaMethod(name = "Mordhau_getDirection", global = true)
    public static String getDirection() {
        return CombatEngine.getInstance().getSelectedDirection().id;
    }

    @LuaMethod(name = "Mordhau_getDirectionDisplayName", global = true)
    public static String getDirectionDisplayName() {
        return CombatEngine.getInstance().getSelectedDirection().displayName;
    }

    @LuaMethod(name = "Mordhau_getActiveDirection", global = true)
    public static String getActiveDirection() {
        return CombatEngine.getInstance().getActiveDirection().id;
    }

    @LuaMethod(name = "Mordhau_getSelectedAngleRad", global = true)
    public static float getSelectedAngleRad() {
        return CombatEngine.getInstance().getSelectedAngleRad();
    }

    @LuaMethod(name = "Mordhau_getActiveAngleRad", global = true)
    public static float getActiveAngleRad() {
        return CombatEngine.getInstance().getActiveAngleRad();
    }

    @LuaMethod(name = "Mordhau_setDirection", global = true)
    public static void setDirection(String dirName) {
        CombatEngine.getInstance().setSelectedDirection(AttackDirection.fromString(dirName));
    }

    @LuaMethod(name = "Mordhau_getPhase", global = true)
    public static String getPhase() {
        return CombatEngine.getInstance().getPhase().id;
    }

    @LuaMethod(name = "Mordhau_getPhaseProgress", global = true)
    public static float getPhaseProgress() {
        return CombatEngine.getInstance().getPhaseProgress();
    }

    @LuaMethod(name = "Mordhau_getStamina", global = true)
    public static float getStamina() {
        return CombatEngine.getInstance().getStamina();
    }

    @LuaMethod(name = "Mordhau_getMaxStamina", global = true)
    public static float getMaxStamina() {
        return CombatEngine.getInstance().getMaxStamina();
    }

    @LuaMethod(name = "Mordhau_getMoveSpeedMultiplier", global = true)
    public static float getMoveSpeedMultiplier() {
        IsoPlayer player = IsoPlayer.getInstance();
        return CombatEngine.getInstance().getMoveSpeedMultiplier(player);
    }

    @LuaMethod(name = "Mordhau_isMeleeModeActive", global = true)
    public static boolean isMeleeModeActive() {
        IsoPlayer player = IsoPlayer.getInstance();
        return CombatEngine.getInstance().isMeleeModeActive(player);
    }

    @LuaMethod(name = "Mordhau_isParrying", global = true)
    public static boolean isParrying() {
        return CombatEngine.getInstance().getPhase() == CombatPhase.PARRY;
    }

    @LuaMethod(name = "Mordhau_isRiposteAvailable", global = true)
    public static boolean isRiposteAvailable() {
        return CombatEngine.getInstance().isRiposteWindowOpen();
    }

    @LuaMethod(name = "Mordhau_isRiposteActive", global = true)
    public static boolean isRiposteActive() {
        return CombatEngine.getInstance().isRiposteActive();
    }

    @LuaMethod(name = "Mordhau_isChamberActive", global = true)
    public static boolean isChamberActive() {
        return CombatEngine.getInstance().isChamberActive();
    }

    @LuaMethod(name = "Mordhau_isAltGripMode", global = true)
    public static boolean isAltGripMode() {
        return CombatEngine.getInstance().isAltGripMode();
    }

    @LuaMethod(name = "Mordhau_getLastCombatFeedback", global = true)
    public static String getLastCombatFeedback() {
        return CombatEngine.getInstance().getLastFeedbackText();
    }

    @LuaMethod(name = "Mordhau_getLastCombatFeedbackAlpha", global = true)
    public static float getLastCombatFeedbackAlpha() {
        return CombatEngine.getInstance().getLastFeedbackAlpha();
    }

    @LuaMethod(name = "Mordhau_triggerStrike", global = true)
    public static void triggerStrike(String dirName) {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        AttackDirection dir = dirName != null ? AttackDirection.fromString(dirName) : CombatEngine.getInstance().getSelectedDirection();
        CombatEngine.getInstance().requestAttack(player, dir);
    }

    @LuaMethod(name = "Mordhau_triggerThrust", global = true)
    public static void triggerThrust() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        CombatEngine.getInstance().requestAttack(player, AttackDirection.THRUST);
    }

    @LuaMethod(name = "Mordhau_triggerParryOrFeint", global = true)
    public static void triggerParryOrFeint() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        CombatEngine.getInstance().requestParryOrFeint(player);
    }

    @LuaMethod(name = "Mordhau_triggerFeint", global = true)
    public static void triggerFeint() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        CombatEngine.getInstance().requestFeint(player);
    }

    @LuaMethod(name = "Mordhau_triggerKick", global = true)
    public static boolean triggerKick() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return false;
        }
        return CombatEngine.getInstance().requestKick(player);
    }

    @LuaMethod(name = "Mordhau_trySmartKick", global = true)
    public static boolean trySmartKick(boolean checkInteractYield) {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return false;
        }
        return CombatEngine.getInstance().trySmartKick(player, checkInteractYield);
    }

    @LuaMethod(name = "Mordhau_toggleAltGrip", global = true)
    public static void toggleAltGrip() {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        CombatEngine.getInstance().toggleAltGripMode(player);
    }

    @LuaMethod(name = "Mordhau_registerNpcAttack", global = true)
    public static void registerNpcAttack(IsoGameCharacter npc, String dirName, float durationSec) {
        if (npc == null) {
            return;
        }
        CombatEngine.getInstance().registerExternalAttack(npc, AttackDirection.fromString(dirName), durationSec);
    }

    @LuaMethod(name = "Mordhau_setMouseSensitivity", global = true)
    public static void setMouseSensitivity(float sens) {
        CombatEngine.getInstance().setDirectionSensitivity(sens);
    }

    @LuaMethod(name = "Mordhau_setInvertMouseX", global = true)
    public static void setInvertMouseX(boolean invert) {
        CombatEngine.getInstance().setInvertX(invert);
    }

    @LuaMethod(name = "Mordhau_setInvertMouseY", global = true)
    public static void setInvertMouseY(boolean invert) {
        CombatEngine.getInstance().setInvertY(invert);
    }

    @LuaMethod(name = "Mordhau_setWallHitStop", global = true)
    public static void setWallHitStop(boolean enabled) {
        CombatEngine.getInstance().setWallHitStopEnabled(enabled);
    }
}
