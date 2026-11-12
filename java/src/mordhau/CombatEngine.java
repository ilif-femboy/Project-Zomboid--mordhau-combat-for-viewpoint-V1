package mordhau;

import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.CharacterStat;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.input.Mouse;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.inventory.types.WeaponType;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.Vector2;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.scripting.objects.MoodleType;
import zombie.ui.MoodlesUI;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class CombatEngine {

    private static final CombatEngine INSTANCE = new CombatEngine();

    public static CombatEngine getInstance() {
        return INSTANCE;
    }

    // State machine
    private CombatPhase phase = CombatPhase.IDLE;
    private float phaseTimer = 0.0f;
    private float phaseDuration = 0.0f;

    // Continuous 360/240-degree directions (0 = Right, PI = Left, -PI/2 = Overhead, +PI/2 = Underhand)
    private AttackDirection selectedDirection = AttackDirection.RIGHT;
    private AttackDirection activeDirection = AttackDirection.RIGHT;
    private float selectedAngleRad = 0.0f;
    private float activeAngleRad = 0.0f;
    private float dirMomentumX = 1.0f;
    private float dirMomentumY = 0.0f;

    // Stamina (Mordhau-style combat stamina pool 0..100)
    private float stamina = 100.0f;
    private final float maxStamina = 100.0f;
    private float staminaRegenDelay = 0.0f;

    // Options & Modes
    private boolean altGripMode = false;
    private boolean wallHitStopEnabled = true;

    // Flags & windows
    private boolean feintedCurrentSwing = false;
    private boolean morphedCurrentSwing = false;
    private boolean riposteActive = false;
    private boolean chamberActive = false;
    private boolean kickImpactApplied = false;
    private boolean applyingTracerHit = false;
    private float riposteWindowTimer = 0.0f;
    private float feintLockoutTimer = 0.0f;
    private float kickCooldownTimer = 0.0f;
    private float wheelThrustDebounce = 0.0f;

    // Accel / Drag & Turn-Cap
    private float accelDragFactor = 1.0f;
    private float swingAccumMotion = 0.0f;
    private boolean announcedAccelDrag = false;

    // 3D Swept-Blade Tracer
    private final BladeTracer bladeTracer = new BladeTracer();

    // Mouse / Viewpoint reflection & tracking
    private float directionSensitivity = 1.0f;
    private boolean invertX = false;
    private boolean invertY = false;
    private boolean viewpointChecked = false;
    private Field vpYawField = null;
    private Field vpPitchField = null;
    private Field vpCapturedField = null;
    private Field vpViewEnabledField = null;
    private Field vpThirdPersonField = null;
    private Field vpLootShownField = null;
    private Field vpLootFlagField = null;
    private Method vpMouseTargetsOnMethod = null;
    private Method vpMouseTargetsPickedMethod = null;
    private Method vpMouseTargetsVehicleMethod = null;

    private float lastVpYaw = 0.0f;
    private float lastVpPitch = 0.0f;
    private boolean hasLastVpAngles = false;
    private int lastMouseX = 0;
    private int lastMouseY = 0;
    private boolean hasLastMouse = false;
    private float lastFrameDXDeg = 0.0f;
    private float lastFrameDYDeg = 0.0f;

    // Input edge detection
    private boolean wasLmbDown = false;
    private boolean wasRmbDown = false;
    private boolean wasMmbDown = false;
    private int lastWheel = 0;
    private boolean hasLastWheel = false;

    // Center combat callouts ("parry", "riposte", "chamber", "feint", "morph", "kick", "bounce", etc.)
    private String lastFeedbackText = "";
    private float feedbackTimer = 0.0f;

    // Hitmarker & Damage popup
    private float hitmarkerTimer = 0.0f;
    private boolean hitmarkerKill = false;
    private int lastHitDamagePoints = 0;
    private float damagePopupTimer = 0.0f;
    private boolean lastHitWasKill = false;

    // Last engaged enemy for bottom-center target health bar
    private IsoGameCharacter lastTargetEnemy = null;
    private float lastTargetTimer = 0.0f;

    // External (PvP / NPC) attack tracking for Chambers & Parries
    private static class IncomingAttack {
        AttackDirection direction;
        float angleRad;
        float expiresAtSec;
    }

    private final Map<IsoGameCharacter, IncomingAttack> incomingAttacks = new WeakHashMap<>();
    private final Map<IsoZombie, Float> recentlyDeflectedZombies = new WeakHashMap<>();
    private float elapsedTimeSec = 0.0f;

    private CombatEngine() {
    }

    public CombatPhase getPhase() {
        return phase;
    }

    public float getPhaseProgress() {
        if (phaseDuration <= 0.0001f) {
            return 0.0f;
        }
        return PZMath.clamp(phaseTimer / phaseDuration, 0.0f, 1.0f);
    }

    public AttackDirection getSelectedDirection() {
        return selectedDirection;
    }

    public float getSelectedAngleRad() {
        return selectedAngleRad;
    }

    public float getActiveAngleRad() {
        return activeAngleRad;
    }

    public void setSelectedDirection(AttackDirection dir) {
        if (dir != null && dir.isSwing()) {
            this.selectedDirection = dir;
            this.selectedAngleRad = dir.defaultAngleRad();
            this.dirMomentumX = (float) Math.cos(this.selectedAngleRad) * 2.0f;
            this.dirMomentumY = (float) Math.sin(this.selectedAngleRad) * 2.0f;
        }
    }

    public AttackDirection getActiveDirection() {
        return activeDirection;
    }

    public float getStamina() {
        return stamina;
    }

    public float getMaxStamina() {
        return maxStamina;
    }

    public boolean isAltGripMode() {
        return altGripMode;
    }

    public boolean isWallHitStopEnabled() {
        return wallHitStopEnabled;
    }

    public void setWallHitStopEnabled(boolean enabled) {
        this.wallHitStopEnabled = enabled;
    }

    public boolean isApplyingTracerHit() {
        return applyingTracerHit;
    }

    public void setApplyingTracerHit(boolean applying) {
        this.applyingTracerHit = applying;
    }

    public boolean isRiposteWindowOpen() {
        return riposteWindowTimer > 0.0f;
    }

    public boolean isRiposteActive() {
        return riposteActive;
    }

    public boolean isChamberActive() {
        return chamberActive;
    }

    public String getLastFeedbackText() {
        return feedbackTimer > 0.0f ? lastFeedbackText : "";
    }

    public float getLastFeedbackAlpha() {
        return PZMath.clamp(feedbackTimer / 0.9f, 0.0f, 1.0f);
    }

    public void setDirectionSensitivity(float sens) {
        this.directionSensitivity = PZMath.clamp(sens, 0.1f, 5.0f);
    }

    public void setInvertX(boolean invertX) {
        this.invertX = invertX;
    }

    public void setInvertY(boolean invertY) {
        this.invertY = invertY;
    }

    private void showFeedback(String text, float duration) {
        this.lastFeedbackText = text;
        this.feedbackTimer = duration;
    }

    public boolean isMeleeModeActive(IsoPlayer player) {
        if (player == null || player.isDead() || player.getVehicle() != null) {
            return false;
        }
        InventoryItem item = player.getPrimaryHandItem();
        if (item instanceof HandWeapon weapon) {
            return !weapon.isRanged() && !weapon.isBareHands();
        }
        return false;
    }

    public boolean isLocalPlayerActiveWeapon(HandWeapon weapon) {
        if (weapon == null || weapon.isRanged() || weapon.isBareHands()) {
            return false;
        }
        IsoPlayer player = IsoPlayer.getInstance();
        return player != null && player.getPrimaryHandItem() == weapon && isMeleeModeActive(player);
    }

    public boolean shouldSuppressVanillaMelee(IsoPlayer player) {
        if (!isMeleeModeActive(player)) {
            return false;
        }
        // Allow ground stomp only if player explicitly holds AimAtFloor with no active Mordhau swing
        return !player.isAimAtFloor();
    }

    public float getMoveSpeedMultiplier(IsoPlayer player) {
        if (player == null) {
            return 1.0f;
        }
        if (phase == CombatPhase.KICK) {
            return CombatPhase.KICK.moveSpeedMultiplier;
        }
        if (!isMeleeModeActive(player)) {
            return 1.0f;
        }
        return phase.moveSpeedMultiplier;
    }

    public boolean shouldAllowMeleePvpOrNpc(IsoMovingObject owner, IsoMovingObject target) {
        if (owner == target || !(owner instanceof IsoGameCharacter) || !(target instanceof IsoGameCharacter)) {
            return false;
        }
        return !GameClient.client;
    }

    public void onExternalCharacterSwingCollide(IsoGameCharacter owner, HandWeapon weapon) {
        if (owner == null || owner instanceof IsoZombie) {
            return;
        }
        if (!incomingAttacks.containsKey(owner)) {
            AttackDirection inferredDir = AttackDirection.RIGHT;
            float varX = owner.getVariableFloat("AttackVariationX", 0.0f);
            if (varX < -0.3f) {
                inferredDir = AttackDirection.LEFT;
            }
            registerExternalAttack(owner, inferredDir, 0.50f);
        }
    }

    public void registerExternalAttack(IsoGameCharacter attacker, AttackDirection dir, float durationSec) {
        if (attacker == null) {
            return;
        }
        IncomingAttack ia = new IncomingAttack();
        ia.direction = dir != null ? dir : AttackDirection.RIGHT;
        ia.angleRad = ia.direction.defaultAngleRad();
        ia.expiresAtSec = elapsedTimeSec + Math.max(0.25f, durationSec);
        incomingAttacks.put(attacker, ia);
    }

    public float getViewYawRadians(IsoPlayer player, float fallbackAngleRad) {
        initViewpointReflection();
        if (vpYawField != null && vpViewEnabledField != null) {
            try {
                if (vpViewEnabledField.getBoolean(null)) {
                    boolean thirdP = vpThirdPersonField != null && vpThirdPersonField.getBoolean(null);
                    if (!thirdP) {
                        return vpYawField.getFloat(null);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return fallbackAngleRad;
    }

    public float getViewPitchRadians() {
        initViewpointReflection();
        if (vpPitchField != null && vpViewEnabledField != null) {
            try {
                if (vpViewEnabledField.getBoolean(null)) {
                    return vpPitchField.getFloat(null);
                }
            } catch (Throwable ignored) {
            }
        }
        return 0.0f;
    }

    /**
     * Toggles Mordhau Alt-Grip Mode ("R"):
     * - Blades / Spears: 3D Half-Swording / Short-Shaft grip (left hand moves onto mid-blade/shaft, faster strikes, +22% armor pen)
     * - Axes / Hammers / Blunt: 3D 180-deg Spike / Poll flip (+25% armor pen, stronger piercing poke)
     */
    public void toggleAltGripMode(IsoPlayer player) {
        if (player == null || !isMeleeModeActive(player)) {
            return;
        }
        altGripMode = !altGripMode;
        MordhauPoseSolver.notifySmoothTransition();
        InventoryItem item = player.getPrimaryHandItem();
        HandWeapon hw = (item instanceof HandWeapon w) ? w : null;
        if (altGripMode) {
            if (isBladeWeapon(hw) || isSpearWeapon(hw)) {
                showFeedback("grip_halfsword", 1.0f);
            } else {
                showFeedback("grip_spike", 1.0f);
            }
        } else {
            showFeedback("grip_normal", 0.9f);
        }
    }

    /**
     * Smart F Kick:
     * When Kick shares the F key with Interact / Viewpoint LootMenu, yields cleanly if the player
     * is aiming right at a door, window, light switch, vehicle, or active loot container with no closer live enemy!
     */
    public boolean trySmartKick(IsoPlayer player, boolean checkInteractYield) {
        if (player == null || player.isDead() || player.getVehicle() != null) {
            return false;
        }
        if (checkInteractYield && shouldYieldKickToInteraction(player)) {
            return false;
        }
        return requestKick(player);
    }

    private boolean shouldYieldKickToInteraction(IsoPlayer player) {
        initViewpointReflection();

        // Check if there is a live hostile right in front of us in kick range!
        float nearestEnemyDist = 99.0f;
        IsoCell cell = player.getCell();
        if (cell != null) {
            for (IsoMovingObject obj : cell.getObjectList()) {
                if (obj == player || !(obj instanceof IsoGameCharacter ch) || ch.isDead()) {
                    continue;
                }
                float d = IsoUtils.DistanceTo(player.getX(), player.getY(), ch.getX(), ch.getY());
                if (d < nearestEnemyDist && isTargetInFrontArc(player, ch, 0.25f)) {
                    nearestEnemyDist = d;
                }
            }
        }

        // 1. Check Viewpoint LootMenu (looting a container or corpse on the floor)
        if (vpLootShownField != null || vpLootFlagField != null) {
            try {
                boolean shown = vpLootShownField != null && vpLootShownField.getBoolean(null);
                boolean loot = vpLootFlagField != null && vpLootFlagField.getBoolean(null);
                if ((shown || loot) && nearestEnemyDist > 1.45f) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Check Viewpoint MouseTargets (Door, Window, WindowFrame, Thumpable, Vehicle under 3D crosshair)
        if (vpMouseTargetsOnMethod != null && vpMouseTargetsPickedMethod != null) {
            try {
                boolean on = (Boolean) vpMouseTargetsOnMethod.invoke(null);
                if (on) {
                    for (int kind = 0; kind <= 3; kind++) {
                        Object picked = vpMouseTargetsPickedMethod.invoke(null, kind);
                        if (picked instanceof IsoObject isoObj && isoObj.getSquare() != null) {
                            float d = IsoUtils.DistanceTo(
                                    player.getX(), player.getY(),
                                    isoObj.getSquare().getX() + 0.5f, isoObj.getSquare().getY() + 0.5f
                            );
                            if (d <= 1.95f && d < nearestEnemyDist + 0.25f) {
                                return true;
                            }
                        }
                    }
                    if (vpMouseTargetsVehicleMethod != null) {
                        Object veh = vpMouseTargetsVehicleMethod.invoke(null);
                        if (veh instanceof IsoMovingObject vObj) {
                            float d = IsoUtils.DistanceTo(player.getX(), player.getY(), vObj.getX(), vObj.getY());
                            if (d <= 2.6f && nearestEnemyDist > 1.6f) {
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Fallback check on immediate grid square in front for Door / Window / LightSwitch
        if (cell != null && nearestEnemyDist > 1.55f) {
            float yaw = getViewYawRadians(player, player.getDirectionAngleRadians());
            int z = PZMath.fastfloor(player.getZ());
            IsoGridSquare curSq = player.getSquare();
            IsoGridSquare fwdSq = cell.getGridSquare(
                    PZMath.fastfloor(player.getX() + (float) Math.cos(yaw) * 0.95f),
                    PZMath.fastfloor(player.getY() + (float) Math.sin(yaw) * 0.95f),
                    z
            );
            if (curSq != null && fwdSq != null && curSq != fwdSq) {
                if (curSq.getDoorTo(fwdSq) != null || curSq.getWindowTo(fwdSq) != null) {
                    return true;
                }
            }
            if (fwdSq != null && fwdSq.getObjects() != null) {
                for (int i = 0; i < fwdSq.getObjects().size(); i++) {
                    IsoObject o = fwdSq.getObjects().get(i);
                    if (o instanceof IsoLightSwitch || o instanceof IsoDoor || o instanceof IsoWindow || o instanceof IsoThumpable) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Performs a Procedural 3D First-Person Right-Leg Mordhau Kick!
     * Works BOTH armed and unarmed, without triggering SwipeStatePlayer!
     */
    public boolean requestKick(IsoPlayer player) {
        if (player == null || player.isDead() || player.getVehicle() != null || kickCooldownTimer > 0.0f) {
            return false;
        }
        if (!player.getCharacterActions().isEmpty() || player.isClimbing() || player.isOnFloor() || player.isSitOnGround()) {
            return false;
        }
        if (player.getVariableBoolean("VPAM_Prone") || player.getVariableBoolean("VPAM_ProneRise")) {
            return false;
        }
        if (phase != CombatPhase.IDLE && phase != CombatPhase.WINDUP && phase != CombatPhase.RECOVERY) {
            return false;
        }
        // Check both Mordhau stamina AND vanilla PZ Endurance so unarmed/armed kick can NEVER be spammed infinitely!
        if (player.getStats() != null && player.getStats().get(CharacterStat.ENDURANCE) <= 0.12f) {
            if (player.getMoodles() != null && MoodlesUI.getInstance() != null) {
                MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
            }
            showFeedback("nostamina", 0.8f);
            return false;
        }
        if (!consumeStamina(player, 19.0f)) {
            showFeedback("nostamina", 0.8f);
            return false;
        }
        staminaRegenDelay = 1.45f;
        if (player.getStats() != null) {
            player.getStats().remove(CharacterStat.ENDURANCE, 0.035f);
            if (player.getMoodles() != null && player.getMoodles().getMoodleLevel(MoodleType.ENDURANCE) > 0 && MoodlesUI.getInstance() != null) {
                MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
            }
        }

        MordhauPoseSolver.notifySmoothTransition();
        bladeTracer.reset();
        feintedCurrentSwing = false;
        morphedCurrentSwing = false;
        riposteActive = false;
        chamberActive = false;
        kickImpactApplied = false;
        kickCooldownTimer = 1.15f;

        player.setRunning(false);
        player.setSprinting(false);
        enterPhase(CombatPhase.KICK, 0.60f);
        if (player.getEmitter() != null) {
            player.getEmitter().playSoundImpl("ClothesRustle", null);
        }
        showFeedback("kick", 0.65f);
        return true;
    }

    private void applyKickImpact(IsoPlayer player) {
        IsoCell cell = player.getCell();
        if (cell == null) {
            return;
        }
        HandWeapon kickWeapon = player.getUseHandWeapon();
        boolean hitSomeone = false;
        float yaw = getViewYawRadians(player, player.getDirectionAngleRadians());
        float dirX = (float) Math.cos(yaw);
        float dirY = (float) Math.sin(yaw);

        for (IsoMovingObject obj : cell.getObjectList()) {
            if (obj == player || !(obj instanceof IsoGameCharacter enemy) || enemy.isDead()) {
                continue;
            }
            if (Math.abs(player.getZ() - enemy.getZ()) > 0.55f) {
                continue;
            }
            float dist = IsoUtils.DistanceTo(player.getX(), player.getY(), enemy.getX(), enemy.getY());
            if (dist <= 1.62f && isTargetInFrontArc(player, enemy, 0.32f)) {
                hitSomeone = true;

                // Trigger a genuine, short PZ B42 micro-stun (0 damage, no knockdown, short flinch!)
                if (!enemy.isOnFloor()) {
                    float savedKnockback = player.knockbackAttackMod;
                    boolean savedCrit = player.isCriticalHit();
                    applyingTracerHit = true;
                    try {
                        player.setCriticalHit(false);
                        player.knockbackAttackMod = 0.18f;
                        enemy.setHitFromAngle(dirX * enemy.getForwardDirectionX() + dirY * enemy.getForwardDirectionY());
                        if (enemy instanceof IsoZombie z) {
                            z.setEatBodyTarget(null, false);
                            z.setAttackOutcome("fail");
                            z.setPlayerAttackPosition(z.testDotSide(player));
                            z.setHitFromBehind(player.isBehind(z));
                            z.setStaggerTimeMod(0.25f);
                        }
                        if (kickWeapon != null) {
                            enemy.Hit(kickWeapon, player, 0.0f, true, 0.15f);
                        }
                        if (enemy instanceof IsoZombie z) {
                            z.setKnockedDown(false);
                            z.setStaggerBack(true);
                            z.setStaggerTimeMod(0.25f);
                            String hr = z.getHitReaction();
                            if (hr == null || hr.isEmpty() || "default".equalsIgnoreCase(hr)) {
                                z.setHitReaction("BiteDefended");
                            }
                        } else {
                            enemy.setHitReaction("BiteDefended");
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        applyingTracerHit = false;
                        player.knockbackAttackMod = savedKnockback;
                        player.setCriticalHit(savedCrit);
                    }
                }

                // Subtle 10cm body-weight pushback
                float pushX = dirX * 0.10f;
                float pushY = dirY * 0.10f;
                IsoGridSquare curSq = enemy.getSquare();
                IsoGridSquare dstSq = cell.getGridSquare(
                        PZMath.fastfloor(enemy.getX() + pushX),
                        PZMath.fastfloor(enemy.getY() + pushY),
                        PZMath.fastfloor(enemy.getZ())
                );
                if (curSq != null && dstSq != null && !curSq.isBlockedTo(dstSq)) {
                    enemy.setX(enemy.getX() + pushX);
                    enemy.setY(enemy.getY() + pushY);
                }

                lastTargetEnemy = enemy;
                lastTargetTimer = 4.0f;
                hitmarkerTimer = 0.22f;
                hitmarkerKill = false;
            }
        }
        if (hitSomeone) {
            if (player.getEmitter() != null) {
                player.getEmitter().playSoundImpl("ShoveZombie", null);
            }
            player.addWorldSoundUnlessInvisible(6, 6, false);
        }
    }

    public void onWallBounce(IsoPlayer player, HandWeapon weapon) {
        MordhauPoseSolver.notifySmoothTransition();
        bladeTracer.reset();
        riposteActive = false;
        chamberActive = false;
        enterPhase(CombatPhase.BOUNCE, 0.28f);
        showFeedback("bounce", 0.65f);
    }

    public void tick(IsoPlayer player) {
        if (player == null || !player.isLocalPlayer()) {
            return;
        }
        float dt = GameTime.getInstance() != null ? GameTime.getInstance().getRealworldSecondsSinceLastUpdate() : (1.0f / 60.0f);
        if (dt <= 0.0f || dt > 0.25f) {
            dt = 1.0f / 60.0f;
        }
        elapsedTimeSec += dt;

        if (feedbackTimer > 0.0f) feedbackTimer = Math.max(0.0f, feedbackTimer - dt);
        if (hitmarkerTimer > 0.0f) hitmarkerTimer = Math.max(0.0f, hitmarkerTimer - dt);
        if (damagePopupTimer > 0.0f) damagePopupTimer = Math.max(0.0f, damagePopupTimer - dt);
        if (wheelThrustDebounce > 0.0f) wheelThrustDebounce = Math.max(0.0f, wheelThrustDebounce - dt);

        if (lastTargetTimer > 0.0f) {
            lastTargetTimer = Math.max(0.0f, lastTargetTimer - dt);
            if (lastTargetEnemy != null && lastTargetEnemy.isDead()) {
                if (!lastHitWasKill && damagePopupTimer > 0.0f) {
                    lastHitWasKill = true;
                    hitmarkerKill = true;
                    hitmarkerTimer = 0.45f;
                    stamina = Math.min(maxStamina, stamina + 20.0f);
                }
            }
        }
        if (riposteWindowTimer > 0.0f) riposteWindowTimer = Math.max(0.0f, riposteWindowTimer - dt);
        if (feintLockoutTimer > 0.0f) feintLockoutTimer = Math.max(0.0f, feintLockoutTimer - dt);
        if (kickCooldownTimer > 0.0f) kickCooldownTimer = Math.max(0.0f, kickCooldownTimer - dt);

        if (staminaRegenDelay > 0.0f) {
            staminaRegenDelay = Math.max(0.0f, staminaRegenDelay - dt);
        } else if (phase == CombatPhase.IDLE && stamina < maxStamina) {
            stamina = Math.min(maxStamina, stamina + 18.0f * dt);
        }

        // Allow unarmed KICK phase to finish cleanly even when no melee weapon is held!
        boolean meleeActive = isMeleeModeActive(player);
        if (!meleeActive && phase != CombatPhase.KICK) {
            resetToIdle();
            syncModData(player, false);
            return;
        }

        if (meleeActive || phase == CombatPhase.KICK) {
            updateMouseDirectionAndTurnCap(dt);
        }
        if (meleeActive) {
            updateAccelAndDrag(player, dt);
        }
        updatePhaseState(player, dt);
        if (meleeActive) {
            pollMouseInputs(player);
            if (phase == CombatPhase.PARRY) {
                scanAndDeflectNearbyZombies(player);
            }
            updateNearestTargetEnemy(player);
        }

        syncModData(player, meleeActive);
    }

    private void updateNearestTargetEnemy(IsoPlayer player) {
        if (lastTargetEnemy != null && !lastTargetEnemy.isDead() && lastTargetTimer > 0.0f) {
            float d = IsoUtils.DistanceTo(player.getX(), player.getY(), lastTargetEnemy.getX(), lastTargetEnemy.getY());
            if (d <= 4.5f) {
                return;
            }
        }
        IsoCell cell = player.getCell();
        if (cell == null) {
            return;
        }
        IsoGameCharacter best = null;
        float bestDist = 3.8f;
        for (IsoMovingObject obj : cell.getObjectList()) {
            if (obj == player || !(obj instanceof IsoGameCharacter ch) || ch.isDead()) {
                continue;
            }
            float d = IsoUtils.DistanceTo(player.getX(), player.getY(), ch.getX(), ch.getY());
            if (d < bestDist && isTargetInFrontArc(player, ch, 0.55f)) {
                bestDist = d;
                best = ch;
            }
        }
        if (best != null) {
            lastTargetEnemy = best;
            lastTargetTimer = 1.5f;
        }
    }

    private void syncModData(IsoPlayer player, boolean meleeActive) {
        try {
            KahluaTable modData = player.getModData();
            if (modData == null) {
                return;
            }
            modData.rawset("mordhau_melee_active", meleeActive);
            modData.rawset("mordhau_dir", selectedDirection.id);
            modData.rawset("mordhau_dir_name", selectedDirection.displayName);
            modData.rawset("mordhau_active_dir", activeDirection.id);
            modData.rawset("mordhau_angle_rad", (double) selectedAngleRad);
            modData.rawset("mordhau_active_angle_rad", (double) activeAngleRad);
            modData.rawset("mordhau_phase", phase.id);
            modData.rawset("mordhau_progress", (double) getPhaseProgress());
            modData.rawset("mordhau_stamina", (double) stamina);
            modData.rawset("mordhau_max_stamina", (double) maxStamina);
            modData.rawset("mordhau_riposte", isRiposteWindowOpen());
            modData.rawset("mordhau_feedback", getLastFeedbackText());
            modData.rawset("mordhau_feedback_alpha", (double) getLastFeedbackAlpha());
            modData.rawset("mordhau_accel_drag", (double) accelDragFactor);
            modData.rawset("mordhau_alt_grip", altGripMode);
            modData.rawset("mordhau_hitmarker_alpha", (double) PZMath.clamp(hitmarkerTimer / 0.25f, 0.0f, 1.0f));
            modData.rawset("mordhau_hitmarker_kill", hitmarkerKill);
            modData.rawset("mordhau_dmg_points", (double) lastHitDamagePoints);
            modData.rawset("mordhau_dmg_alpha", (double) PZMath.clamp(damagePopupTimer / 1.1f, 0.0f, 1.0f));
            modData.rawset("mordhau_dmg_kill", lastHitWasKill);

            if (lastTargetEnemy != null && lastTargetTimer > 0.0f && !lastTargetEnemy.isDead()) {
                String tName = "zombie";
                if (lastTargetEnemy instanceof IsoPlayer p) {
                    tName = p.getDisplayName() != null ? p.getDisplayName() : "survivor";
                }
                modData.rawset("mordhau_target_name", tName);
                modData.rawset("mordhau_target_hp", (double) PZMath.clamp(lastTargetEnemy.getHealth(), 0.0f, 1.0f));
            } else {
                modData.rawset("mordhau_target_name", "");
                modData.rawset("mordhau_target_hp", 0.0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void initViewpointReflection() {
        if (viewpointChecked) {
            return;
        }
        viewpointChecked = true;
        try {
            Class<?> lookClass = Class.forName("viewpoint.input.Look");
            vpYawField = lookClass.getDeclaredField("yaw");
            vpPitchField = lookClass.getDeclaredField("pitch");
            vpCapturedField = lookClass.getDeclaredField("captured");
            vpYawField.setAccessible(true);
            vpPitchField.setAccessible(true);
            vpCapturedField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> viewClass = Class.forName("viewpoint.core.View");
            vpViewEnabledField = viewClass.getDeclaredField("enabled");
            vpThirdPersonField = viewClass.getDeclaredField("thirdPerson");
            vpViewEnabledField.setAccessible(true);
            vpThirdPersonField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> lootClass = Class.forName("viewpoint.interact.LootMenu");
            vpLootShownField = lootClass.getDeclaredField("shown");
            vpLootFlagField = lootClass.getDeclaredField("loot");
            vpLootShownField.setAccessible(true);
            vpLootFlagField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> mtClass = Class.forName("viewpoint.interact.MouseTargets");
            vpMouseTargetsOnMethod = mtClass.getDeclaredMethod("on");
            vpMouseTargetsPickedMethod = mtClass.getDeclaredMethod("picked", int.class);
            vpMouseTargetsVehicleMethod = mtClass.getDeclaredMethod("vehicle");
            vpMouseTargetsOnMethod.setAccessible(true);
            vpMouseTargetsPickedMethod.setAccessible(true);
            vpMouseTargetsVehicleMethod.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static float wrapRad(float r) {
        while (r > (float) Math.PI) r -= (float) (Math.PI * 2.0);
        while (r < -(float) Math.PI) r += (float) (Math.PI * 2.0);
        return r;
    }

    /**
     * Reads Viewpoint Look.yaw / Look.pitch (in RADIANS!) and converts deltas accurately to degrees
     * for responsive, buttery-smooth 360/240-degree continuous directional arrow control,
     * plus Mordhau Turn-Cap during RELEASE!
     */
    private void updateMouseDirectionAndTurnCap(float dt) {
        initViewpointReflection();

        float dxDeg = 0.0f;
        float dyDeg = 0.0f;
        boolean usedViewpoint = false;

        if (vpYawField != null && vpPitchField != null && vpCapturedField != null) {
            try {
                boolean captured = vpCapturedField.getBoolean(null);
                if (captured) {
                    float yawRad = vpYawField.getFloat(null);
                    float pitchRad = vpPitchField.getFloat(null);
                    if (hasLastVpAngles) {
                        float dYawRad = wrapRad(yawRad - lastVpYaw);
                        float dPitchRad = pitchRad - lastVpPitch;

                        // Mordhau Turn-Cap during active RELEASE or KICK: prevents unrealistic 720-deg spins
                        // while still allowing full, smooth Mordhau Accels and Drags!
                        if (phase == CombatPhase.RELEASE || phase == CombatPhase.KICK) {
                            float maxRateRad = (phase == CombatPhase.KICK) ? 1.9f : 2.65f; // ~152 deg/sec
                            float maxStep = Math.max(0.01f, maxRateRad * dt);
                            if (Math.abs(dYawRad) > maxStep || Math.abs(dPitchRad) > maxStep) {
                                dYawRad = PZMath.clamp(dYawRad, -maxStep, maxStep);
                                dPitchRad = PZMath.clamp(dPitchRad, -maxStep, maxStep);
                                yawRad = wrapRad(lastVpYaw + dYawRad);
                                pitchRad = PZMath.clamp(lastVpPitch + dPitchRad, -1.52f, 1.52f);
                                vpYawField.setFloat(null, yawRad);
                                vpPitchField.setFloat(null, pitchRad);
                            }
                        }

                        dxDeg = (float) Math.toDegrees(dYawRad);
                        dyDeg = -(float) Math.toDegrees(dPitchRad);
                    }
                    lastVpYaw = yawRad;
                    lastVpPitch = pitchRad;
                    hasLastVpAngles = true;
                    usedViewpoint = true;
                } else {
                    hasLastVpAngles = false;
                }
            } catch (Throwable ignored) {
                usedViewpoint = false;
            }
        }

        if (!usedViewpoint) {
            int mx = Mouse.getXA();
            int my = Mouse.getYA();
            if (hasLastMouse) {
                dxDeg = (mx - lastMouseX) * 0.18f;
                dyDeg = (my - lastMouseY) * 0.18f;
            }
            lastMouseX = mx;
            lastMouseY = my;
            hasLastMouse = true;
        }

        if (invertX) dxDeg = -dxDeg;
        if (invertY) dyDeg = -dyDeg;

        lastFrameDXDeg = dxDeg;
        lastFrameDYDeg = dyDeg;

        float scaledDX = dxDeg * directionSensitivity;
        float scaledDY = dyDeg * directionSensitivity;

        // Smooth bounded 2D direction momentum vector:
        // Clamping vector radius to 2.6 deg ensures any mouse flick in a new direction
        // immediately steers the 360-degree arrow with zero sluggish buildup or 90-deg snapping!
        if (Math.abs(scaledDX) > 0.001f || Math.abs(scaledDY) > 0.001f) {
            dirMomentumX += scaledDX;
            dirMomentumY += scaledDY;
            float mag = (float) Math.hypot(dirMomentumX, dirMomentumY);
            if (mag >= 0.65f) {
                float targetAngle = (float) Math.atan2(dirMomentumY, dirMomentumX);
                float angleStep = wrapRad(targetAngle - selectedAngleRad);
                // Fast responsive glide toward exact continuous angle
                selectedAngleRad = wrapRad(selectedAngleRad + angleStep * Math.min(1.0f, dt * 38.0f + 0.42f));
                selectedDirection = AttackDirection.fromAngleRad(selectedAngleRad);
            }
            float maxRadius = 2.6f;
            if (mag > maxRadius) {
                dirMomentumX *= (maxRadius / mag);
                dirMomentumY *= (maxRadius / mag);
            }
        }
    }

    private void updateAccelAndDrag(IsoPlayer player, float dt) {
        if (phase != CombatPhase.WINDUP && phase != CombatPhase.RELEASE) {
            accelDragFactor = 1.0f;
            swingAccumMotion = 0.0f;
            return;
        }
        if (activeDirection == AttackDirection.THRUST) {
            accelDragFactor = 1.0f;
            return;
        }

        // Travel direction of swing on screen: (-cos(activeAngleRad), +sin(activeAngleRad) in screen Y-down):
        // Wait: activeAngleRad = 0 (starts Right +X, swings Left -X -> turning Left dx < 0 is ACCEL!)
        // activeAngleRad = -PI/2 (Overhead, starts Top -Y, swings Down +Y -> looking Down dy > 0 is ACCEL!)
        // Notice: -cos(0)*dx + (-sin(0))*dy = -dx (> 0 when dx < 0!)
        //         -cos(-PI/2)*dx + (-sin(-PI/2))*dy = +dy (> 0 when dy > 0!)
        float travelScreenX = -(float) Math.cos(activeAngleRad);
        float travelScreenY = -(float) Math.sin(activeAngleRad);
        float alignedDelta = travelScreenX * lastFrameDXDeg + travelScreenY * lastFrameDYDeg;

        float weight = getWeaponWeight(player);
        float inertiaScale = PZMath.clamp(0.85f + (weight - 1.5f) * 0.10f, 0.72f, 1.30f);
        swingAccumMotion = PZMath.clamp(
                swingAccumMotion * Math.max(0.0f, 1.0f - dt * 4.2f) + alignedDelta * 0.038f * inertiaScale,
                -0.28f,
                0.28f
        );
        accelDragFactor = PZMath.clamp(1.0f + swingAccumMotion, 0.74f, 1.26f);

        if (!announcedAccelDrag && phase == CombatPhase.RELEASE) {
            if (accelDragFactor >= 1.17f) {
                announcedAccelDrag = true;
                showFeedback("accel", 0.55f);
            } else if (accelDragFactor <= 0.83f) {
                announcedAccelDrag = true;
                showFeedback("drag", 0.55f);
            }
        }
    }

    private boolean isMouseCapturedForCombat() {
        initViewpointReflection();
        if (vpCapturedField != null) {
            try {
                return vpCapturedField.getBoolean(null);
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private void pollMouseInputs(IsoPlayer player) {
        if (player.isDead() || player.isKnockedDown() || player.isGettingUp() || player.isSitOnGround()) {
            return;
        }
        if (!isMouseCapturedForCombat()) {
            wasLmbDown = Mouse.isButtonDown(0);
            wasRmbDown = Mouse.isButtonDown(1);
            wasMmbDown = Mouse.isButtonDown(2);
            lastWheel = Mouse.getWheelState();
            hasLastWheel = true;
            return;
        }

        boolean lmb = Mouse.isButtonDown(0);
        boolean rmb = Mouse.isButtonDown(1);
        boolean mmb = Mouse.isButtonDown(2);
        int wheel = Mouse.getWheelState();

        if (lmb && !wasLmbDown) {
            requestAttack(player, selectedDirection, selectedAngleRad);
        }
        if (rmb && !wasRmbDown) {
            requestParryOrFeint(player);
        }
        if (mmb && !wasMmbDown) {
            requestAttack(player, AttackDirection.THRUST, selectedAngleRad);
        }
        if (hasLastWheel && wheel != lastWheel) {
            if (wheel > lastWheel) {
                if (wheelThrustDebounce <= 0.0f || (phase == CombatPhase.WINDUP && activeDirection.isSwing())) {
                    wheelThrustDebounce = 0.09f;
                    requestAttack(player, AttackDirection.THRUST, selectedAngleRad);
                }
            } else {
                // Wheel Down: Mordhau Overhead strike (or Morph into Overhead!)
                float overheadAng = -(float) (Math.PI * 0.5);
                selectedDirection = AttackDirection.OVERHEAD;
                selectedAngleRad = overheadAng;
                dirMomentumX = 0.0f;
                dirMomentumY = -2.0f;
                requestAttack(player, AttackDirection.OVERHEAD, overheadAng);
            }
        }

        wasLmbDown = lmb;
        wasRmbDown = rmb;
        wasMmbDown = mmb;
        lastWheel = wheel;
        hasLastWheel = true;
    }

    private void updatePhaseState(IsoPlayer player, float dt) {
        if (phase == CombatPhase.IDLE) {
            return;
        }
        float effectiveDt = (phase == CombatPhase.WINDUP || phase == CombatPhase.RELEASE) ? dt * accelDragFactor : dt;
        phaseTimer += effectiveDt;

        InventoryItem item = player.getPrimaryHandItem();
        HandWeapon weapon = (item instanceof HandWeapon hw && !hw.isRanged() && !hw.isBareHands()) ? hw : null;

        switch (phase) {
            case WINDUP:
                if (phaseTimer >= phaseDuration) {
                    enterPhase(CombatPhase.RELEASE, computeReleaseDuration(player));
                    if (weapon != null) {
                        bladeTracer.beginRelease(player, weapon);
                    }
                }
                break;
            case RELEASE:
                if (weapon != null && !feintedCurrentSwing) {
                    bladeTracer.traceReleaseFrame(player, weapon, this, getPhaseProgress());
                    // Note: traceReleaseFrame may transition phase to BOUNCE on wall impact!
                    if (phase != CombatPhase.RELEASE) {
                        return;
                    }
                }
                if (phaseTimer >= phaseDuration) {
                    bladeTracer.reset();
                    enterPhase(CombatPhase.RECOVERY, computeRecoveryDuration(player));
                }
                break;
            case RECOVERY:
            case BOUNCE:
                if (phaseTimer >= phaseDuration) {
                    resetToIdle();
                }
                break;
            case PARRY:
                if (phaseTimer >= phaseDuration) {
                    enterPhase(CombatPhase.PARRY_RECOVERY, 0.22f);
                }
                break;
            case PARRY_RECOVERY:
                if (phaseTimer >= phaseDuration) {
                    resetToIdle();
                }
                break;
            case KICK:
                if (!kickImpactApplied && getPhaseProgress() >= 0.34f) {
                    kickImpactApplied = true;
                    applyKickImpact(player);
                }
                if (phaseTimer >= phaseDuration) {
                    resetToIdle();
                }
                break;
            default:
                break;
        }
    }

    private void enterPhase(CombatPhase next, float duration) {
        this.phase = next;
        this.phaseTimer = 0.0f;
        this.phaseDuration = Math.max(0.05f, duration);
    }

    private void resetToIdle() {
        this.phase = CombatPhase.IDLE;
        this.phaseTimer = 0.0f;
        this.phaseDuration = 0.0f;
        this.feintedCurrentSwing = false;
        this.morphedCurrentSwing = false;
        this.riposteActive = false;
        this.chamberActive = false;
        this.kickImpactApplied = false;
        this.accelDragFactor = 1.0f;
        this.swingAccumMotion = 0.0f;
        this.announcedAccelDrag = false;
        this.bladeTracer.reset();
    }

    private float getWeaponWeight(IsoPlayer player) {
        InventoryItem item = player != null ? player.getPrimaryHandItem() : null;
        if (item instanceof HandWeapon hw) {
            return PZMath.clamp(hw.getActualWeight(), 0.3f, 6.0f);
        }
        return 1.5f;
    }

    private float getWeaponSpeedFactor(IsoPlayer player) {
        InventoryItem item = player != null ? player.getPrimaryHandItem() : null;
        if (item instanceof HandWeapon hw) {
            float baseSpeed = hw.getBaseSpeed();
            float weight = PZMath.clamp(hw.getActualWeight(), 0.3f, 6.0f);
            float weightMod = PZMath.clamp(1.10f - (weight - 1.2f) * 0.065f, 0.72f, 1.20f);
            float gripMod = altGripMode ? 1.15f : 1.0f;
            return PZMath.clamp(baseSpeed * weightMod * gripMod, 0.55f, 1.70f);
        }
        return 1.05f;
    }

    private float computeWindupDuration(IsoPlayer player, boolean isRiposte, boolean isChamber) {
        float speed = getWeaponSpeedFactor(player);
        // Authentic Mordhau windup (~400-520ms normal, ~280-360ms riposte/chamber)
        // Gives clear visual coiling + comfortable window for Feints and Morphs!
        float base = 0.45f / speed;
        if (isRiposte) {
            base *= 0.68f;
        } else if (isChamber) {
            base *= 0.74f;
        }
        return PZMath.clamp(base, 0.24f, 0.62f);
    }

    private float computeReleaseDuration(IsoPlayer player) {
        float speed = getWeaponSpeedFactor(player);
        float base = (activeDirection == AttackDirection.THRUST ? 0.30f : 0.35f) / speed;
        return PZMath.clamp(base, 0.22f, 0.48f);
    }

    private float computeRecoveryDuration(IsoPlayer player) {
        float speed = getWeaponSpeedFactor(player);
        return PZMath.clamp(0.32f / speed, 0.20f, 0.48f);
    }

    private boolean consumeStamina(IsoPlayer player, float amount) {
        if (stamina < amount) {
            stamina = 0.0f;
            staminaRegenDelay = 1.4f;
            return false;
        }
        stamina = Math.max(0.0f, stamina - amount);
        staminaRegenDelay = 1.0f;
        return true;
    }

    public void requestAttack(IsoPlayer player, AttackDirection dir) {
        float ang = (dir != null && dir.isSwing()) ? selectedAngleRad : selectedAngleRad;
        if (dir != null && dir.isSwing() && AttackDirection.fromAngleRad(selectedAngleRad) != dir) {
            ang = dir.defaultAngleRad();
        }
        requestAttack(player, dir, ang);
    }

    public void requestAttack(IsoPlayer player, AttackDirection dir, float strikeAngleRad) {
        if (player == null || !isMeleeModeActive(player) || feintLockoutTimer > 0.0f) {
            return;
        }
        if (!player.getCharacterActions().isEmpty() || player.isClimbing() || player.isOnFloor() || player.isSitOnGround()) {
            return;
        }
        if (player.getVariableBoolean("VPAM_Prone") || player.getVariableBoolean("VPAM_ProneRise")) {
            return;
        }
        if (dir == null) {
            dir = selectedDirection;
        }

        // Seamless Bidirectional Mordhau MORPH during WINDUP:
        // - Thrust -> Slash (Wheel Up then LMB / Wheel Down!)
        // - Slash -> Thrust (LMB then Wheel Up / MMB!)
        // - Angle A -> Angle B (changing swing angle > 45 deg!)
        if (phase == CombatPhase.WINDUP && !morphedCurrentSwing && getPhaseProgress() <= 0.86f) {
            boolean typeSwitch = (dir.isThrust() != activeDirection.isThrust());
            boolean angleSwitch = dir.isSwing() && activeDirection.isSwing()
                    && Math.abs(wrapRad(strikeAngleRad - activeAngleRad)) > 0.75f;
            if (typeSwitch || angleSwitch) {
                if (!consumeStamina(player, 6.0f)) {
                    showFeedback("nostamina", 0.8f);
                    return;
                }
                MordhauPoseSolver.notifySmoothTransition();
                morphedCurrentSwing = true;
                activeDirection = dir;
                if (dir.isSwing()) {
                    activeAngleRad = strikeAngleRad;
                    selectedDirection = AttackDirection.fromAngleRad(strikeAngleRad);
                    selectedAngleRad = strikeAngleRad;
                }
                // Re-open windup comfortably so the morphed attack coils & releases cleanly!
                phaseDuration = computeWindupDuration(player, riposteActive, false);
                phaseTimer = phaseDuration * 0.14f;
                swingAccumMotion = 0.0f;
                accelDragFactor = 1.0f;
                showFeedback("morph", 0.80f);
                return;
            }
        }

        boolean canStart = (phase == CombatPhase.IDLE)
                || (riposteWindowTimer > 0.0f && (phase == CombatPhase.PARRY || phase == CombatPhase.PARRY_RECOVERY))
                || (phase == CombatPhase.RECOVERY && getPhaseProgress() >= 0.55f);
        if (!canStart) {
            return;
        }

        boolean didChamber = tryChamberIncomingAttack(player, dir, strikeAngleRad);
        boolean didRiposte = !didChamber && (riposteWindowTimer > 0.0f);

        float weight = getWeaponWeight(player);
        float weightStamScale = PZMath.clamp(0.85f + weight * 0.10f, 0.85f, 1.38f);
        float stamCost = (didChamber ? 10.0f : (didRiposte ? 5.0f : 7.0f)) * weightStamScale;
        if (!consumeStamina(player, stamCost)) {
            showFeedback("nostamina", 0.8f);
            return;
        }

        MordhauPoseSolver.notifySmoothTransition();
        bladeTracer.reset();

        this.activeDirection = dir;
        this.activeAngleRad = strikeAngleRad;
        this.feintedCurrentSwing = false;
        this.morphedCurrentSwing = false;
        this.riposteActive = didRiposte;
        this.chamberActive = didChamber;
        this.riposteWindowTimer = 0.0f;
        this.accelDragFactor = 1.0f;
        this.swingAccumMotion = 0.0f;
        this.announcedAccelDrag = false;

        // Stop sprinting when swinging (while keeping full free WASD walk/jog/strafe movement!)
        player.setSprinting(false);

        if (didChamber) {
            showFeedback("chamber", 0.95f);
            playDeflectClangSound(player);
        } else if (didRiposte) {
            showFeedback("riposte", 0.95f);
        }

        enterPhase(CombatPhase.WINDUP, computeWindupDuration(player, didRiposte, didChamber));
    }

    public void requestParryOrFeint(IsoPlayer player) {
        if (player == null || !isMeleeModeActive(player)) {
            return;
        }
        // During WINDUP: RMB performs Mordhau Feint!
        if (phase == CombatPhase.WINDUP && getPhaseProgress() <= 0.88f) {
            requestFeint(player);
            return;
        }
        // Otherwise: RMB performs timed directional Parry!
        if (phase == CombatPhase.IDLE || phase == CombatPhase.RECOVERY || phase == CombatPhase.BOUNCE) {
            if (!consumeStamina(player, 7.0f)) {
                showFeedback("nostamina", 0.8f);
                return;
            }
            MordhauPoseSolver.notifySmoothTransition();
            bladeTracer.reset();
            feintedCurrentSwing = false;
            riposteActive = false;
            chamberActive = false;
            player.setSprinting(false);
            enterPhase(CombatPhase.PARRY, 0.44f);
            scanAndDeflectNearbyZombies(player);
        }
    }

    public void requestFeint(IsoPlayer player) {
        if (player == null || phase != CombatPhase.WINDUP) {
            return;
        }
        if (!consumeStamina(player, 8.0f)) {
            showFeedback("nostamina", 0.8f);
            return;
        }
        MordhauPoseSolver.notifySmoothTransition();
        feintedCurrentSwing = true;
        resetToIdle();
        feintLockoutTimer = 0.12f;
        showFeedback("feint", 0.70f);
    }

    public void onSuccessfulMeleeHit(IsoPlayer player, IsoGameCharacter victim, HandWeapon weapon, float dealtDmg) {
        // Mordhau stamina reward on landing a blow!
        stamina = Math.min(maxStamina, stamina + 7.5f);
    }

    private boolean isTargetInFrontArc(IsoGameCharacter defender, IsoMovingObject attacker, float minCos) {
        if (defender == null || attacker == null) {
            return false;
        }
        float yaw = (defender instanceof IsoPlayer p && p.isLocalPlayer())
                ? getViewYawRadians(p, p.getDirectionAngleRadians())
                : defender.getDirectionAngleRadians();
        float lx = (float) Math.cos(yaw);
        float ly = (float) Math.sin(yaw);
        float dx = attacker.getX() - defender.getX();
        float dy = attacker.getY() - defender.getY();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) {
            return true;
        }
        dx /= len;
        dy /= len;
        return (lx * dx + ly * dy) >= minCos;
    }

    /**
     * Mordhau Chambering — now works reliably against BOTH Zombies AND Players/NPCs!
     */
    private boolean tryChamberIncomingAttack(IsoPlayer player, AttackDirection myDir, float myAngleRad) {
        IsoCell cell = player.getCell();
        if (cell == null) {
            return false;
        }
        for (IsoMovingObject obj : cell.getObjectList()) {
            if (obj == player || !(obj instanceof IsoGameCharacter enemy) || enemy.isDead()) {
                continue;
            }
            if (Math.abs(player.getZ() - enemy.getZ()) > 0.55f) {
                continue;
            }
            float dist = IsoUtils.DistanceTo(player.getX(), player.getY(), enemy.getX(), enemy.getY());
            if (dist > 2.35f || !isTargetInFrontArc(player, enemy, 0.20f)) {
                continue;
            }

            // 1. Check NPC / Player incoming directional swing or thrust
            IncomingAttack ia = incomingAttacks.get(enemy);
            if (ia != null && ia.expiresAtSec >= elapsedTimeSec) {
                boolean match = (ia.direction.isThrust() && myDir.isThrust())
                        || (ia.direction.isSwing() && myDir.isSwing()
                        && (ia.direction == myDir || Math.abs(wrapRad(ia.angleRad - myAngleRad)) <= 1.30f));
                if (match) {
                    incomingAttacks.remove(enemy);
                    enemy.setHitReaction("BiteDefended");
                    stamina = Math.min(maxStamina, stamina + 8.0f);
                    lastTargetEnemy = enemy;
                    lastTargetTimer = 4.0f;
                    return true;
                }
            }

            // 2. Check Zombie incoming lunge / bite / scratch attack!
            if (enemy instanceof IsoZombie zomb && !zomb.isOnFloor() && !zomb.isStaggerBack()) {
                Float lastDefl = recentlyDeflectedZombies.get(zomb);
                if (lastDefl != null && (elapsedTimeSec - lastDefl) < 0.85f) {
                    continue;
                }
                boolean zombieAttackingUs = zomb.isZombieAttacking(player)
                        || zomb.getEatBodyTarget() == player
                        || (zomb.isAttacking() && zomb.getTarget() == player && dist <= 2.05f)
                        || (zomb.getTarget() == player && dist <= 1.85f && zomb.lungeTimer > 0.0f);
                if (zombieAttackingUs) {
                    recentlyDeflectedZombies.put(zomb, elapsedTimeSec);
                    zomb.setEatBodyTarget(null, false);
                    zomb.setAttackOutcome("fail");
                    zomb.setStaggerBack(true);
                    zomb.setHitReaction("BiteDefended");
                    stamina = Math.min(maxStamina, stamina + 8.0f);
                    lastTargetEnemy = zomb;
                    lastTargetTimer = 4.0f;
                    return true;
                }
            }
        }
        return false;
    }

    private void scanAndDeflectNearbyZombies(IsoPlayer player) {
        IsoCell cell = player.getCell();
        if (cell == null) {
            return;
        }
        for (IsoMovingObject obj : cell.getObjectList()) {
            if (!(obj instanceof IsoZombie zomb) || zomb.isDead() || zomb.isOnFloor()) {
                continue;
            }
            Float lastDefl = recentlyDeflectedZombies.get(zomb);
            if (lastDefl != null && (elapsedTimeSec - lastDefl) < 0.75f) {
                continue;
            }
            float dist = IsoUtils.DistanceTo(player.getX(), player.getY(), zomb.getX(), zomb.getY());
            if (dist <= 1.85f && isTargetInFrontArc(player, zomb, -0.10f)) {
                boolean isAttackingPlayer = zomb.isZombieAttacking(player)
                        || (zomb.getEatBodyTarget() == player)
                        || (zomb.isAttacking() && zomb.getTarget() == player)
                        || (zomb.getTarget() == player && dist <= 1.50f);
                if (isAttackingPlayer) {
                    deflectZombie(player, zomb);
                }
            }
        }
    }

    public boolean tryParryZombieAttack(IsoPlayer player, IsoZombie zombie) {
        if (player == null || !player.isLocalPlayer() || zombie == null) {
            return false;
        }
        if ((phase == CombatPhase.PARRY || (phase == CombatPhase.WINDUP && (chamberActive || riposteActive)))
                && isTargetInFrontArc(player, zombie, -0.20f)) {
            deflectZombie(player, zombie);
            return true;
        }
        return false;
    }

    /**
     * Valid PZ B42 Zombie Parry Deflection (fixes zombie freeze bug completely — NEVER uses "default"!).
     */
    private void deflectZombie(IsoPlayer player, IsoZombie zombie) {
        recentlyDeflectedZombies.put(zombie, elapsedTimeSec);
        zombie.setEatBodyTarget(null, false);
        zombie.setAttackOutcome("fail");
        zombie.setStaggerBack(true);
        if (!zombie.isOnFloor()) {
            zombie.setHitReaction("BiteDefended");
        }
        playDeflectClangSound(player);
        riposteWindowTimer = 0.85f;
        stamina = Math.min(maxStamina, stamina + 5.0f);
        lastTargetEnemy = zombie;
        lastTargetTimer = 4.0f;
        showFeedback("parry", 0.90f);
    }

    public boolean tryParryCharacterHit(IsoGameCharacter defender, IsoGameCharacter wielder, HandWeapon weapon) {
        if (!(defender instanceof IsoPlayer player) || !player.isLocalPlayer() || wielder == null) {
            return false;
        }
        if (weapon != null && weapon.isRanged()) {
            return false;
        }
        if ((phase == CombatPhase.PARRY || (phase == CombatPhase.WINDUP && (chamberActive || riposteActive)))
                && isTargetInFrontArc(player, wielder, -0.20f)) {
            if (wielder instanceof IsoZombie z) {
                deflectZombie(player, z);
                return true;
            }
            wielder.setHitReaction("BiteDefended");
            playDeflectClangSound(player);
            riposteWindowTimer = 0.85f;
            stamina = Math.min(maxStamina, stamina + 6.0f);
            lastTargetEnemy = wielder;
            lastTargetTimer = 4.0f;
            showFeedback("parry", 0.95f);
            return true;
        }
        return false;
    }

    private void playDeflectClangSound(IsoPlayer player) {
        if (player == null || player.getEmitter() == null) {
            return;
        }
        InventoryItem item = player.getPrimaryHandItem();
        if (item instanceof HandWeapon hw) {
            String snd = hw.getDoorHitSound();
            if (snd != null && !snd.isEmpty()) {
                player.getEmitter().playSoundImpl(snd, null);
                return;
            }
        }
        player.getEmitter().playSoundImpl("ShoveZombie", null);
    }

    private String getWeaponDescriptor(HandWeapon weapon) {
        if (weapon == null) {
            return "";
        }
        String ft = weapon.getFullType() != null ? weapon.getFullType() : "";
        String sub = weapon.getSubCategory() != null ? weapon.getSubCategory() : "";
        String anim = weapon.getSwingAnim() != null ? weapon.getSwingAnim() : "";
        return (ft + " " + sub + " " + anim).toLowerCase();
    }

    public boolean isSpearWeapon(HandWeapon weapon) {
        if (weapon == null) {
            return false;
        }
        WeaponType wt = WeaponType.getWeaponType(weapon);
        if (wt == WeaponType.SPEAR) {
            return true;
        }
        String desc = getWeaponDescriptor(weapon);
        return desc.contains("spear") || desc.contains("lance") || desc.contains("halberd")
                || desc.contains("pike") || desc.contains("trident") || desc.contains("pitchfork");
    }

    public boolean isBladeWeapon(HandWeapon weapon) {
        if (weapon == null || isSpearWeapon(weapon)) {
            return false;
        }
        WeaponType wt = WeaponType.getWeaponType(weapon);
        if (wt == WeaponType.KNIFE) {
            return true;
        }
        String desc = getWeaponDescriptor(weapon);
        return desc.contains("blade") || desc.contains("sword") || desc.contains("katana")
                || desc.contains("machete") || desc.contains("knife") || desc.contains("cleaver")
                || desc.contains("dagger") || desc.contains("scalpel") || desc.contains("sabre")
                || desc.contains("saber") || desc.contains("rapier") || desc.contains("scimitar")
                || desc.contains("claymore") || desc.contains("stab");
    }

    public boolean isBluntOrAxeWeapon(HandWeapon weapon) {
        if (weapon == null) {
            return false;
        }
        return !isSpearWeapon(weapon) && !isBladeWeapon(weapon);
    }

    /**
     * Computes exact Mordhau damage for our 3D BladeTracer:
     * - Blunt / Axe / Improvised THRUST deals VERY LOW damage (0.20x in normal grip, 0.45x in Spike [R] grip)
     *   and NEVER triggers instant-kill crits! Always lower than slashing/swinging!
     * - Blade / Spear THRUST deals strong piercing damage (+headshot bonus if hitting Head/Neck).
     * - Overhead / Diagonal-Top strikes reward head accuracy.
     */
    public float computeDirectMeleeHitDamage(
            IsoPlayer player,
            IsoGameCharacter victim,
            HandWeapon weapon,
            BodyPartType hitPart,
            int partIdx,
            float baseDamage
    ) {
        boolean bluntOrAxe = isBluntOrAxeWeapon(weapon);
        boolean spear = isSpearWeapon(weapon);
        boolean blade = isBladeWeapon(weapon);

        float mult;
        if (activeDirection == AttackDirection.THRUST) {
            if (bluntOrAxe) {
                // Strictly low poke damage for blunt/improvised weapons & tools (much lower than swings, 0% crit!)
                mult = altGripMode ? 0.45f : 0.20f;
            } else if (spear) {
                mult = 1.28f;
            } else {
                // Short/long blade piercing thrust: controlled damage ( slightly below heavy overhead slash, no instant JawStab!)
                mult = 0.92f;
            }
        } else {
            if (spear) {
                // Sideways shaft swing with a spear is weaker than its piercing thrust
                mult = (activeDirection == AttackDirection.OVERHEAD) ? 0.55f : 0.46f;
            } else {
                // Continuous angle verticality bonus: steeper overhead cuts hit harder!
                float overheadFactor = Math.max(0.0f, -(float) Math.sin(activeAngleRad));
                mult = 1.04f + overheadFactor * 0.28f;
            }
        }

        // 3D Skeleton BodyPart Localization & Clothing Armor Check
        float scratchDef = victim.getBodyPartClothingDefense(partIdx, false, false);
        float biteDef = victim.getBodyPartClothingDefense(partIdx, true, false);
        float totalArmor = scratchDef + biteDef;

        float armorPen = activeDirection.baseArmorPenetration;
        if (bluntOrAxe) {
            armorPen = (activeDirection == AttackDirection.THRUST && !altGripMode) ? 0.05f : 0.48f;
        } else if (spear) {
            armorPen = (activeDirection == AttackDirection.THRUST) ? 0.50f : 0.12f;
        } else if (blade) {
            armorPen = (activeDirection == AttackDirection.THRUST) ? 0.42f : 0.14f;
        }
        if (altGripMode) {
            armorPen += 0.22f;
        }
        float effectiveArmor = totalArmor * (1.0f - PZMath.clamp(armorPen, 0.0f, 0.85f));
        if (effectiveArmor > 15.0f) {
            mult *= PZMath.clamp(1.0f - (effectiveArmor - 15.0f) * 0.0045f, 0.45f, 1.0f);
        } else if (totalArmor < 8.0f && blade && activeDirection.isSwing()) {
            // Unarmored flesh slash bonus
            mult *= 1.18f;
        }

        // 3D Head / Neck hit bonus (except blunt/improvised normal thrust which stays weak!)
        boolean hitHeadOrNeck = (hitPart == BodyPartType.Head || hitPart == BodyPartType.Neck);
        if (hitHeadOrNeck && !(bluntOrAxe && activeDirection == AttackDirection.THRUST && !altGripMode)) {
            mult *= 1.22f;
        } else if (partIdx >= BodyPartType.ToIndex(BodyPartType.UpperLeg_L)) {
            mult *= 0.85f;
        }

        if (riposteActive) {
            mult *= 1.22f;
        } else if (chamberActive) {
            mult *= 1.18f;
        }
        if (accelDragFactor > 1.12f) {
            mult *= 1.08f;
        }

        // Controlled Crit Check (NEVER on blunt/improvised normal thrust, NEVER triggers KnifeDeath!)
        boolean isCrit = false;
        player.setCriticalHit(false);
        if (!(bluntOrAxe && activeDirection == AttackDirection.THRUST && !altGripMode)) {
            float critChance = activeDirection.bonusCritChance + (hitHeadOrNeck ? 8.0f : 0.0f);
            if (activeDirection == AttackDirection.THRUST && blade) {
                critChance = 10.0f;
            }
            if (ThreadLocalRandom.current().nextFloat() * 100.0f < critChance) {
                isCrit = true;
                mult *= 1.30f;
            }
        }

        float finalDamage = Math.max(0.05f, baseDamage * mult);

        // Update HUD Hitmarker & "+XX Damage" popup
        lastTargetEnemy = victim;
        lastTargetTimer = 4.5f;
        hitmarkerTimer = 0.28f;
        hitmarkerKill = isCrit || victim.getHealth() <= 0.15f;
        lastHitDamagePoints = Math.max(3, Math.min(100, Math.round(finalDamage * 36.0f)));
        lastHitWasKill = victim.getHealth() <= 0.12f;
        damagePopupTimer = 1.15f;

        return finalDamage;
    }
}
