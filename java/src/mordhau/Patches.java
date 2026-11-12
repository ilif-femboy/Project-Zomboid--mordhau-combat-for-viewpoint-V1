package mordhau;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.AttackType;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoMovingObject;
import zombie.iso.Vector2;

public class Patches {

    /**
     * 1. Restore modified bone local transforms BEFORE PZ blends multi-track animations each frame
     * (prevents cumulative drift on TwistableBoneTransform, identical to ViewpointAdvancedMovement).
     */
    @Patch(
            className = "zombie.core.skinnedmodel.animation.AnimationPlayer",
            methodName = "updateMultiTrackBoneTransforms",
            warmUp = true
    )
    public static class Patch_AnimationPlayer_restoreLocals {
        @Patch.OnEnter
        public static void enter(@Patch.This AnimationPlayer player) {
            try {
                MordhauPoseSolver.restoreLocals(player);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 2. Apply our 3D Procedural Weapon, Arm & Kick Kinematics AFTER ViewpointAdvancedMovement
     * (crouch / Q-E lean / jump) and Viewpoint (HeadLook) have posed the skeleton!
     */
    @Patch(
            className = "viewpoint.Hooks",
            methodName = "skeletonPosed"
    )
    public static class Patch_Viewpoint_skeletonPosed {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) AnimationPlayer player) {
            try {
                MordhauPoseSolver.onSkeletonPosed(player);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 3. Keep IsoPlayer in pure smooth locomotion (idle / movement / strafe / run) while holding
     * a melee weapon so vanilla SwipeStatePlayer & isometric aiming never hijack body turning or WASD!
     */
    @Patch(className = "zombie.characters.IsoPlayer", methodName = "UpdateInputState", warmUp = true)
    public static class Patch_IsoPlayer_UpdateInputState {
        @Patch.OnExit
        public static void exit(
                @Patch.This IsoPlayer self,
                @Patch.Argument(0) IsoPlayer.InputState inputState
        ) {
            if (self != null && self.isLocalPlayer() && inputState != null) {
                if (CombatEngine.getInstance().shouldSuppressVanillaMelee(self)) {
                    inputState.isAiming = false;
                    inputState.isCharging = false;
                    inputState.isChargingLt = false;
                    inputState.isAttacking = false;
                    inputState.melee = false;
                }
            }
        }
    }

    /**
     * 4. Suppress accidental vanilla pressedAttack() when MordhauCombat owns melee weapons.
     */
    @Patch(className = "zombie.CombatManager", methodName = "pressedAttack", warmUp = true)
    public static class Patch_CombatManager_pressedAttack {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoPlayer player) {
            if (player != null && player.isLocalPlayer()) {
                return CombatEngine.getInstance().shouldSuppressVanillaMelee(player);
            }
            return false;
        }
    }

    /**
     * 5. Tick MordhauCombat state machine, 360-degree mouse direction, Turn-Cap & 3D BladeTracer.
     */
    @Patch(className = "zombie.characters.IsoPlayer", methodName = "update", warmUp = true)
    public static class Patch_IsoPlayer_update {
        @Patch.OnExit
        public static void exit(@Patch.This IsoPlayer self) {
            if (self != null && self.isLocalPlayer()) {
                CombatEngine.getInstance().tick(self);
            }
        }
    }

    /**
     * 6. Apply gentle Mordhau combat movement pace during active swing / parry / kick.
     */
    @Patch(className = "zombie.characters.IsoPlayer", methodName = "getDeferredMovement", warmUp = true)
    public static class Patch_IsoPlayer_getDeferredMovement {
        @Patch.OnExit
        public static void exit(
                @Patch.This IsoPlayer self,
                @Patch.Return Vector2 result
        ) {
            if (self != null && self.isLocalPlayer() && result != null) {
                float mult = CombatEngine.getInstance().getMoveSpeedMultiplier(self);
                if (mult != 1.0f) {
                    result.x *= mult;
                    result.y *= mult;
                }
            }
        }
    }

    /**
     * 7. Allow melee hits against NPCs / Survivors in singleplayer.
     */
    @Patch(className = "zombie.CombatManager", methodName = "checkPVP", warmUp = true)
    public static class Patch_CombatManager_checkPVP {
        @Patch.OnExit
        public static void exit(
                @Patch.Argument(0) IsoMovingObject owner,
                @Patch.Argument(1) IsoMovingObject target,
                @Patch.Argument(2) boolean isMelee,
                @Patch.Return(readOnly = false) boolean ret
        ) {
            if (!ret && isMelee && CombatEngine.getInstance().shouldAllowMeleePvpOrNpc(owner, target)) {
                ret = true;
            }
        }
    }

    /**
     * 8. Detect external NPC / PvP character melee swings for Chambers & Parries.
     */
    @Patch(className = "zombie.CombatManager", methodName = "attackCollisionCheck", warmUp = true)
    public static class Patch_CombatManager_attackCollisionCheck {
        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) IsoGameCharacter owner,
                @Patch.Argument(1) HandWeapon weapon,
                @Patch.Argument(2) SwipeStatePlayer swipeState,
                @Patch.Argument(3) AttackType attackTypeMod
        ) {
            if (owner != null && !(owner instanceof IsoPlayer p && p.isLocalPlayer())) {
                if (weapon == null || !weapon.isRanged()) {
                    CombatEngine.getInstance().onExternalCharacterSwingCollide(owner, weapon);
                }
            }
        }
    }

    /**
     * 9. Intercept incoming NPC / PvP melee hits on the local player during active Parry / Chamber / Riposte.
     * Matches both 5-arg and 6-arg Hit(HandWeapon, IsoGameCharacter, ...) overloads while skipping BaseVehicle Hit.
     */
    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "Hit", warmUp = true)
    public static class Patch_IsoGameCharacter_Hit {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This IsoGameCharacter self,
                @Patch.Argument(0) HandWeapon weapon,
                @Patch.Argument(1) IsoGameCharacter wielder
        ) {
            if (!CombatEngine.getInstance().isApplyingTracerHit()
                    && CombatEngine.getInstance().tryParryCharacterHit(self, wielder, weapon)) {
                return true;
            }
            return false;
        }
    }

    /**
     * 10. Intercept zombie bite/scratch damage during active Parry / Chamber / Riposte.
     */
    @Patch(className = "zombie.characters.BodyDamage.BodyDamage", methodName = "AddRandomDamageFromZombie", warmUp = true)
    public static class Patch_BodyDamage_AddRandomDamageFromZombie {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This BodyDamage self,
                @Patch.Argument(0) IsoZombie zombie
        ) {
            if (self != null && self.getParentChar() instanceof IsoPlayer player) {
                if (CombatEngine.getInstance().tryParryZombieAttack(player, zombie)) {
                    return true;
                }
            }
            return false;
        }
    }

    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "testDefense", warmUp = true)
    public static class Patch_IsoGameCharacter_testDefense {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This IsoGameCharacter self,
                @Patch.Argument(0) IsoZombie zomb,
                @Patch.Local("parried") boolean parried
        ) {
            if (self instanceof IsoPlayer player) {
                if (CombatEngine.getInstance().tryParryZombieAttack(player, zomb)) {
                    parried = true;
                    return true;
                }
            }
            return false;
        }

        @Patch.OnExit
        public static void exit(
                @Patch.Local("parried") boolean parried,
                @Patch.Return(readOnly = false) boolean ret
        ) {
            if (parried) {
                ret = true;
            }
        }
    }
}
