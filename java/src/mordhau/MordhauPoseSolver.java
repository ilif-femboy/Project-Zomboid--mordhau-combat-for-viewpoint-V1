package mordhau;

import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.TwistableBoneTransform;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningBone;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.Vector3;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

/**
 * Pure 3D Procedural Weapon, Arm & Leg Kinematics + Analytical Two-Bone IK Solver
 * for MordhauCombat v0.2.
 *
 * Runs strictly AFTER ViewpointAdvancedMovement (crouch / Q-E lean / jump) and Viewpoint (HeadLook),
 * keeping 100% compatibility with crouching and Q/E leaning while giving full 360/240-degree
 * free-angle directional strikes, smooth Morph slerps, 3D Half-Swording / Spike-Flip [R],
 * directional Parries, short 2H Spear-Poke Thrusts, and a 1st-Person Right-Leg Kick!
 */
public final class MordhauPoseSolver {

    private static final float METRES_PER_MODEL_UNIT = 1.5f;

    private static final IdentityHashMap<SkinningData, Rig> RIGS = new IdentityHashMap<>();
    private static final WeakHashMap<AnimationPlayer, Locals> LOCALS = new WeakHashMap<>();
    private static volatile boolean localsKept = false;

    // Reusable math scratch buffers (guarded by synchronized(RIGS))
    private static final Matrix4f TURN = new Matrix4f();
    private static final Matrix4f PARENT_INVERSE = new Matrix4f();
    private static final Matrix4f HAND_TURN = new Matrix4f();
    private static final Matrix4f HAND_KEEP = new Matrix4f();
    private static final Matrix4f WANT = new Matrix4f();
    private static final Vector3 TMP_VEC = new Vector3();
    private static final Vector3 WORLD_HILT = new Vector3();
    private static final Vector3 WORLD_DIR = new Vector3();

    private static final float[] FACING = new float[2];
    private static final float[] H0_R = new float[12];
    private static final float[] P0_R = new float[12];
    private static final float[] GR_R = new float[12];
    private static final float[] GR_R_INV = new float[12];
    private static final float[] P_WANT = new float[12];
    private static final float[] H_WANT = new float[12];
    private static final float[] H_ACT = new float[12];
    private static final float[] P_FINAL = new float[12];
    private static final float[] H0_L = new float[12];
    private static final float[] L_WANT = new float[12];
    private static final float[] ROT_BLEND = new float[9];
    private static final float[] COMPOSE_TMP = new float[12];
    private static final float[] QA = new float[4];
    private static final float[] QB = new float[4];
    private static final Matrix4f FOOT_R_ORIG = new Matrix4f();
    private static final Matrix4f FOOT_R_WANT = new Matrix4f();

    // Smooth blending state for local player
    private static long lastPoseNanos = 0L;
    private static float poseWeight = 0.0f;
    private static float spikeFlipWeight = 0.0f;
    private static float halfSwordWeight = 0.0f;
    private static float smoothedIdleAngle = 0.0f;
    private static float idleBreathPhase = 0.0f;

    // Morph / Phase smooth transition snapshot (in chest-local R, U, F coordinates)
    private static float lastLocalGripX = 0.20f;
    private static float lastLocalGripY = -0.10f;
    private static float lastLocalGripZ = 0.34f;
    private static float lastLocalBladeX = -0.25f;
    private static float lastLocalBladeY = 0.76f;
    private static float lastLocalBladeZ = 0.60f;
    private static float lastLocalEdgeX = -0.70f;
    private static float lastLocalEdgeY = -0.35f;
    private static float lastLocalEdgeZ = 0.62f;

    private static float morphStartGripX, morphStartGripY, morphStartGripZ;
    private static float morphStartBladeX, morphStartBladeY, morphStartBladeZ;
    private static float morphStartEdgeX, morphStartEdgeY, morphStartEdgeZ;
    private static float morphBlendTimer = 0.0f;
    private static final float MORPH_BLEND_DURATION = 0.115f;

    // Cached world-space blade Hilt & Tip direction from latest posed skeleton frame
    private static volatile boolean hasFreshWorldBlade = false;
    private static volatile float cachedHiltX, cachedHiltY, cachedHiltZ;
    private static volatile float cachedBladeDirX, cachedBladeDirY, cachedBladeDirZ;
    private static volatile long cachedBladeNanos = 0L;

    private MordhauPoseSolver() {
    }

    /**
     * Triggered by CombatEngine when a Morph or Feint occurs so the 3D weapon trajectory
     * cross-fades smoothly from its exact current 3D position & angle without snapping.
     */
    public static void notifySmoothTransition() {
        synchronized (RIGS) {
            morphStartGripX = lastLocalGripX;
            morphStartGripY = lastLocalGripY;
            morphStartGripZ = lastLocalGripZ;
            morphStartBladeX = lastLocalBladeX;
            morphStartBladeY = lastLocalBladeY;
            morphStartBladeZ = lastLocalBladeZ;
            morphStartEdgeX = lastLocalEdgeX;
            morphStartEdgeY = lastLocalEdgeY;
            morphStartEdgeZ = lastLocalEdgeZ;
            morphBlendTimer = MORPH_BLEND_DURATION;
        }
    }

    public static boolean getLatestWorldBlade(Vector3 outHilt, Vector3 outDir) {
        if (!hasFreshWorldBlade) {
            return false;
        }
        long ageNs = System.nanoTime() - cachedBladeNanos;
        if (ageNs > 100_000_000L) { // older than 100ms
            return false;
        }
        outHilt.set(cachedHiltX, cachedHiltY, cachedHiltZ);
        outDir.set(cachedBladeDirX, cachedBladeDirY, cachedBladeDirZ);
        return true;
    }

    public static void restoreLocals(AnimationPlayer player) {
        if (!localsKept || player == null) {
            return;
        }
        if (!(player.getIsoGameCharacter() instanceof IsoPlayer isoPlayer) || isoPlayer.isAnimal()) {
            return;
        }
        synchronized (RIGS) {
            Locals l = LOCALS.get(player);
            if (l != null && l.count != 0) {
                boolean same = player.boneTransforms != null && player.boneTransforms.length == l.has.length;
                for (int i = 0; i < l.count; i++) {
                    int bone = l.list[i];
                    if (same) {
                        player.boneTransforms[bone].set(l.saved[bone]);
                    }
                    l.has[bone] = false;
                }
                l.count = 0;
            }
        }
    }

    public static void onSkeletonPosed(AnimationPlayer player) {
        if (player == null || player.parentPlayer != null) {
            return;
        }
        if (!(player.getIsoGameCharacter() instanceof IsoPlayer isoPlayer) || isoPlayer.isAnimal()) {
            return;
        }
        if (!isoPlayer.isLocalPlayer() || isoPlayer != IsoPlayer.getInstance()) {
            return;
        }
        SkinningData data = player.getSkinningData();
        if (data == null || player.boneTransforms == null || player.boneTransforms.length != data.numBones()) {
            return;
        }

        synchronized (RIGS) {
            applyLocalPlayerPose(isoPlayer, player, data);
        }
    }

    private static boolean shouldYieldToEnvironmentOrActions(IsoPlayer player) {
        if (player.isDead() || player.getVehicle() != null) {
            return true;
        }
        if (player.isClimbing() || player.isOnFloor() || player.isSitOnGround() || player.isKnockedDown() || player.isbFalling()) {
            return true;
        }
        if (!player.getCharacterActions().isEmpty()) {
            return true;
        }
        // Respect ViewpointAdvancedMovement prone, rolls, and mantle/ledge-climb animations
        if (player.getVariableBoolean("VPAM_Prone")
                || player.getVariableBoolean("VPAM_ProneRise")
                || player.getVariableBoolean("VPAM_RollToBack")
                || player.getVariableBoolean("VPAM_RollToFront")
                || player.getVariableBoolean("VPAM_SideRollLeft")
                || player.getVariableBoolean("VPAM_SideRollRight")) {
            return true;
        }
        String climbClip = player.getVariableString("VPAM_Climb");
        return climbClip != null && !climbClip.isEmpty();
    }

    private static void applyLocalPlayerPose(IsoPlayer isoPlayer, AnimationPlayer player, SkinningData data) {
        long now = System.nanoTime();
        float dt = lastPoseNanos == 0L ? (1.0f / 60.0f) : PZMath.clamp((float) (now - lastPoseNanos) * 1.0e-9f, 0.0005f, 0.1f);
        lastPoseNanos = now;

        CombatEngine engine = CombatEngine.getInstance();
        CombatPhase phase = engine.getPhase();
        boolean kickActive = (phase == CombatPhase.KICK);
        boolean meleeActive = engine.isMeleeModeActive(isoPlayer);
        boolean yield = shouldYieldToEnvironmentOrActions(isoPlayer);

        boolean wantWeaponPose = meleeActive && !yield;
        boolean wantKickPose = kickActive && !yield;

        float blendRate = (phase == CombatPhase.IDLE) ? (dt / 0.14f) : (dt / 0.06f);
        poseWeight = approach(poseWeight, wantWeaponPose ? 1.0f : 0.0f, blendRate);

        if (morphBlendTimer > 0.0f) {
            morphBlendTimer = Math.max(0.0f, morphBlendTimer - dt);
        }

        if (poseWeight <= 0.001f && !wantKickPose) {
            hasFreshWorldBlade = false;
            return;
        }

        Rig rig = rigOf(data);
        if (rig.leftClavicle < 0 || rig.rightClavicle < 0) {
            return;
        }

        // Compute orthonormal Model-Space Basis: F_horiz (fx, 0, fz), R_horiz (rx, 0, rz), U_up (0, 1, 0)
        float rendered = player.getRenderedAngle();
        float bodyAngle = player.getAngle();
        float viewYaw = engine.getViewYawRadians(isoPlayer, bodyAngle);
        // Clamp view yaw relative to body so torso doesn't twist past anatomical limits
        float relYaw = wrapAngle(viewYaw - bodyAngle);
        viewYaw = bodyAngle + PZMath.clamp(relYaw, -0.95f, 0.95f);

        modelDirection((float) Math.cos(viewYaw), (float) Math.sin(viewYaw), rendered, FACING);
        float fx = FACING[0];
        float fz = FACING[1];

        // Verify exact Right vector sign directly from character's Left -> Right Clavicle!
        Matrix4f lc = player.getModelTransformAt(rig.leftClavicle);
        Matrix4f rc = player.getModelTransformAt(rig.rightClavicle);
        float clavDx = rc.m03 - lc.m03;
        float clavDz = rc.m23 - lc.m23;
        float candRx = -fz;
        float candRz = fx;
        if (candRx * clavDx + candRz * clavDz < 0.0f) {
            candRx = -candRx;
            candRz = -candRz;
        }
        float rx = candRx;
        float rz = candRz;

        // 1. Apply 3D First-Person Right-Leg Kick if active (works BOTH armed and unarmed!)
        if (wantKickPose) {
            applyRightLegKick(player, rig, fx, fz, rx, rz, engine.getPhaseProgress());
        }

        if (poseWeight <= 0.001f || rig.rightArm == null || rig.rightUpperArm == null
                || rig.rightForearm == null || rig.rightHand == null || rig.rightProp == null) {
            hasFreshWorldBlade = false;
            return;
        }

        InventoryItem item = isoPlayer.getPrimaryHandItem();
        HandWeapon weapon = (item instanceof HandWeapon hw) ? hw : null;
        if (weapon == null || weapon.isRanged() || weapon.isBareHands()) {
            hasFreshWorldBlade = false;
            return;
        }

        // Capture vanilla hand-to-prop grip BEFORE modifying arm bones
        read(player.getModelTransformAt(rig.rightArm[2]), H0_R);
        read(player.getModelTransformAt(rig.rightProp.bone), P0_R);
        relative(P0_R, H0_R, GR_R);
        relative(H0_R, P0_R, GR_R_INV);

        // Read leaned chest up vector (automatically inherits ViewpointAdvancedMovement Q/E lean!)
        float chestUpX = 0.0f;
        float chestUpY = 1.0f;
        float chestUpZ = 0.0f;
        if (rig.spine != null && rig.neck >= 0) {
            Matrix4f sp = player.getModelTransformAt(rig.spine.bone);
            Matrix4f nk = player.getModelTransformAt(rig.neck);
            float vx = nk.m03 - sp.m03;
            float vy = nk.m13 - sp.m13;
            float vz = nk.m23 - sp.m23;
            float vl = length(vx, vy, vz);
            if (vl > 1.0e-4f && vy > 0.2f * vl) {
                // Blend 65% of spine lean so weapon tilts naturally with Q/E lean
                chestUpX = (vx / vl) * 0.65f;
                chestUpY = 0.35f + (vy / vl) * 0.65f;
                chestUpZ = (vz / vl) * 0.65f;
                float cl = length(chestUpX, chestUpY, chestUpZ);
                chestUpX /= cl;
                chestUpY /= cl;
                chestUpZ /= cl;
            }
        }

        // Camera Pitch (Look.pitch: > 0 when looking UP, < 0 when looking DOWN)
        float camPitch = PZMath.clamp(engine.getViewPitchRadians(), -1.10f, 1.10f);
        float cosP = (float) Math.cos(camPitch);
        float sinP = (float) Math.sin(camPitch);

        // Build pitched + leaned Combat View Basis: R (right), U (up), F (forward)
        // Start from horizontal F0 = (fx, 0, fz), orthonormalize with chestUp
        float rVecX = chestUpY * fz - chestUpZ * 0.0f; // cross(chestUp, F0) or keep aligned with clavicles
        // Project rx, 0, rz perpendicular to chestUp and F0:
        float dotRU = rx * chestUpX + rz * chestUpZ;
        rVecX = rx - chestUpX * dotRU;
        float rVecY = -chestUpY * dotRU;
        float rVecZ = rz - chestUpZ * dotRU;
        float rLen = length(rVecX, rVecY, rVecZ);
        if (rLen < 1.0e-4f) {
            rVecX = rx;
            rVecY = 0.0f;
            rVecZ = rz;
        } else {
            rVecX /= rLen;
            rVecY /= rLen;
            rVecZ /= rLen;
        }

        // Base unpitched forward F0 perpendicular to chestUp and R
        float f0X = fx;
        float f0Y = -(fx * chestUpX + fz * chestUpZ) / Math.max(0.25f, chestUpY);
        float f0Z = fz;
        float f0L = length(f0X, f0Y, f0Z);
        f0X /= f0L;
        f0Y /= f0L;
        f0Z /= f0L;

        // Pitch F and U around R by camPitch
        float fwdX = f0X * cosP + chestUpX * sinP;
        float fwdY = f0Y * cosP + chestUpY * sinP;
        float fwdZ = f0Z * cosP + chestUpZ * sinP;

        float upX = chestUpX * cosP - f0X * sinP;
        float upY = chestUpY * cosP - f0Y * sinP;
        float upZ = chestUpZ * cosP - f0Z * sinP;

        // Update Alt-Grip visual weights ([R] Half-Swording vs Spike-Flip)
        boolean altGrip = engine.isAltGripMode();
        boolean twoHanded = weapon.isTwoHandWeapon();
        boolean isSpear = engine.isSpearWeapon(weapon);
        boolean isBlade = engine.isBladeWeapon(weapon);
        boolean wantHalfSword = altGrip && (isBlade || isSpear || twoHanded && !engine.isBluntOrAxeWeapon(weapon));
        boolean wantSpikeFlip = altGrip && !wantHalfSword;

        halfSwordWeight = approach(halfSwordWeight, wantHalfSword ? 1.0f : 0.0f, dt / 0.14f);
        spikeFlipWeight = approach(spikeFlipWeight, wantSpikeFlip ? 1.0f : 0.0f, dt / 0.14f);

        idleBreathPhase += dt * (isoPlayer.isPlayerMoving() ? 5.2f : 2.1f);
        if (idleBreathPhase > (float) (Math.PI * 200.0)) {
            idleBreathPhase -= (float) (Math.PI * 200.0);
        }

        // Smooth selected angle for subtle guard responsiveness in IDLE
        float selAng = engine.getSelectedAngleRad();
        float angDiff = wrapAngle(selAng - smoothedIdleAngle);
        smoothedIdleAngle = wrapAngle(smoothedIdleAngle + angDiff * Math.min(1.0f, dt * 16.0f));

        // Compute target weapon pose in local Combat View Basis (meters, relative to Shoulder Center C)
        // localGrip = (gx, gy, gz) in meters along (R, U, F)
        // localBlade = (bx, by, bz) unit vector in (R, U, F)
        // localEdge  = (ex, ey, ez) unit vector in (R, U, F)
        // shoulderTwistRad = horizontal shoulder girdle yaw around U
        float[] targetLocal = new float[10];
        computeWeaponTargetInViewBasis(
                engine, weapon, twoHanded, isSpear, phase, engine.getPhaseProgress(), dt, targetLocal
        );

        float gx = targetLocal[0];
        float gy = targetLocal[1];
        float gz = targetLocal[2];
        float bx = targetLocal[3];
        float by = targetLocal[4];
        float bz = targetLocal[5];
        float ex = targetLocal[6];
        float ey = targetLocal[7];
        float ez = targetLocal[8];
        float shoulderTwist = targetLocal[9];

        // Smooth Morph / Feint cross-fade if active
        if (morphBlendTimer > 0.0f) {
            float mAlpha = easeOutCubic(1.0f - (morphBlendTimer / MORPH_BLEND_DURATION));
            gx = morphStartGripX + (gx - morphStartGripX) * mAlpha;
            gy = morphStartGripY + (gy - morphStartGripY) * mAlpha;
            gz = morphStartGripZ + (gz - morphStartGripZ) * mAlpha;
            bx = morphStartBladeX + (bx - morphStartBladeX) * mAlpha;
            by = morphStartBladeY + (by - morphStartBladeY) * mAlpha;
            bz = morphStartBladeZ + (bz - morphStartBladeZ) * mAlpha;
            ex = morphStartEdgeX + (ex - morphStartEdgeX) * mAlpha;
            ey = morphStartEdgeY + (ey - morphStartEdgeY) * mAlpha;
            ez = morphStartEdgeZ + (ez - morphStartEdgeZ) * mAlpha;
        }

        // Normalize localBlade and orthogonalize localEdge
        float bLen = length(bx, by, bz);
        if (bLen < 1.0e-4f) {
            bx = 0.0f; by = 0.707f; bz = 0.707f;
        } else {
            bx /= bLen; by /= bLen; bz /= bLen;
        }
        float dotEB = ex * bx + ey * by + ez * bz;
        ex -= bx * dotEB;
        ey -= by * dotEB;
        ez -= bz * dotEB;
        float eLen = length(ex, ey, ez);
        if (eLen < 1.0e-4f) {
            // Fallback perpendicular to blade
            ex = 0.0f; ey = -bz; ez = by;
            eLen = length(ex, ey, ez);
            if (eLen < 1.0e-4f) {
                ex = 1.0f; ey = 0.0f; ez = 0.0f; eLen = 1.0f;
            }
        }
        ex /= eLen; ey /= eLen; ez /= eLen;

        // Save current local state for seamless Morph snapshots
        lastLocalGripX = gx; lastLocalGripY = gy; lastLocalGripZ = gz;
        lastLocalBladeX = bx; lastLocalBladeY = by; lastLocalBladeZ = bz;
        lastLocalEdgeX = ex; lastLocalEdgeY = ey; lastLocalEdgeZ = ez;

        // Apply athletic shoulder-girdle twist on rig.arms (keeps Bip01_Head 100% camera-stable in 1P!)
        if (Math.abs(shoulderTwist) > 0.001f && rig.arms != null) {
            lc = player.getModelTransformAt(rig.leftClavicle);
            rc = player.getModelTransformAt(rig.rightClavicle);
            float cx0 = (lc.m03 + rc.m03) * 0.5f;
            float cy0 = (lc.m13 + rc.m13) * 0.5f;
            float cz0 = (lc.m23 + rc.m23) * 0.5f;
            rotateAbout(player, rig.arms, upX, upY, upZ, shoulderTwist * poseWeight, cx0, cy0, cz0);
        }

        // Re-read Shoulder Center C after shoulder-girdle twist
        lc = player.getModelTransformAt(rig.leftClavicle);
        rc = player.getModelTransformAt(rig.rightClavicle);
        float cx = (lc.m03 + rc.m03) * 0.5f;
        float cy = (lc.m13 + rc.m13) * 0.5f;
        float cz = (lc.m23 + rc.m23) * 0.5f;

        // Convert local (R, U, F) meters -> Model-Space coordinates (1 model unit = 1.5m)
        float invScale = 1.0f / METRES_PER_MODEL_UNIT;
        float gripModelX = cx + (rVecX * gx + upX * gy + fwdX * gz) * invScale;
        float gripModelY = cy + (rVecY * gx + upY * gy + fwdY * gz) * invScale;
        float gripModelZ = cz + (rVecZ * gx + upZ * gy + fwdZ * gz) * invScale;

        float bladeModelX = rVecX * bx + upX * by + fwdX * bz;
        float bladeModelY = rVecY * bx + upY * by + fwdY * bz;
        float bladeModelZ = rVecZ * bz + upZ * by + fwdZ * bz;
        // Careful! Check bladeModelZ typo above: rVecZ * bx + upZ * by + fwdZ * bz!
        bladeModelZ = rVecZ * bx + upZ * by + fwdZ * bz;
        float bmLen = length(bladeModelX, bladeModelY, bladeModelZ);
        bladeModelX /= bmLen; bladeModelY /= bmLen; bladeModelZ /= bmLen;

        float edgeModelX = rVecX * ex + upX * ey + fwdX * ez;
        float edgeModelY = rVecY * ex + upY * ey + fwdY * ez;
        float edgeModelZ = rVecZ * ex + upZ * ey + fwdZ * ez;
        float emDot = edgeModelX * bladeModelX + edgeModelY * bladeModelY + edgeModelZ * bladeModelZ;
        edgeModelX -= bladeModelX * emDot;
        edgeModelY -= bladeModelY * emDot;
        edgeModelZ -= bladeModelZ * emDot;
        float emLen = length(edgeModelX, edgeModelY, edgeModelZ);
        edgeModelX /= emLen; edgeModelY /= emLen; edgeModelZ /= emLen;

        // Build P_WANT (12-float rigid transform of Bip01_Prop1):
        // Verified from PoseApplier (AngleTorch & standard Torch):
        //   col1 (+Y, [3..5]) = +D_blade (along weapon shaft toward tip)
        //   col2 (+Z, [6..8]) = -D_edge  (-Z points out the knuckles / leading cutting edge!)
        //   col0 (+X, [0..2]) = col1 x col2 = D_blade x (-D_edge) (ensures right-handed SO(3) det = +1!)
        float col1X = bladeModelX, col1Y = bladeModelY, col1Z = bladeModelZ;
        float col2X = -edgeModelX, col2Y = -edgeModelY, col2Z = -edgeModelZ;
        float col0X = col1Y * col2Z - col1Z * col2Y;
        float col0Y = col1Z * col2X - col1X * col2Z;
        float col0Z = col1X * col2Y - col1Y * col2X;

        P_WANT[0] = col0X; P_WANT[1] = col0Y; P_WANT[2] = col0Z;
        P_WANT[3] = col1X; P_WANT[4] = col1Y; P_WANT[5] = col1Z;
        P_WANT[6] = col2X; P_WANT[7] = col2Y; P_WANT[8] = col2Z;
        P_WANT[9] = gripModelX;
        P_WANT[10] = gripModelY;
        P_WANT[11] = gripModelZ;

        // Compute exact wanted Right Hand transform H_WANT = compose(GR_R_INV, P_WANT)
        // so fingers stay 100% locked around the weapon handle!
        compose(GR_R_INV, P_WANT, H_WANT);

        // Blend with vanilla H0_R when poseWeight < 1
        float targetHandX = H0_R[9] + (H_WANT[9] - H0_R[9]) * poseWeight;
        float targetHandY = H0_R[10] + (H_WANT[10] - H0_R[10]) * poseWeight;
        float targetHandZ = H0_R[11] + (H_WANT[11] - H0_R[11]) * poseWeight;
        slerp(H0_R, H_WANT, poseWeight, ROT_BLEND);

        // Solve analytical Two-Bone IK for Right Arm
        twoBoneIk(
                player, rig.rightUpperArm, rig.rightForearm, rig.rightArm,
                targetHandX, targetHandY, targetHandZ, rVecX, rVecZ
        );

        // Swivel Right Elbow comfortably down-and-right so it never blocks 1P camera
        float poleRX = rVecX * 0.62f - upX * 0.75f - fwdX * 0.22f;
        float poleRY = rVecY * 0.62f - upY * 0.75f - fwdY * 0.22f;
        float poleRZ = rVecZ * 0.62f - upZ * 0.75f - fwdZ * 0.22f;
        swivelElbowToPole(player, rig.rightUpperArm, rig.rightHand, rig.rightArm, poleRX, poleRY, poleRZ, poseWeight);

        // Apply wrist orientation to Right Hand while keeping its IK-solved position
        setRotationKeepPos(player, rig.rightHand, rig.rightArm[2], ROT_BLEND);

        // Place Bip01_Prop1 directly from actual posed Right Hand via GR_R
        read(player.getModelTransformAt(rig.rightArm[2]), H_ACT);
        compose(GR_R, H_ACT, P_FINAL);

        // If [R] Spike Alt-Grip is active on an axe/hammer, smoothly roll weapon 180 deg around its shaft (+Y)
        if (spikeFlipWeight > 0.001f) {
            float flipAngle = spikeFlipWeight * (float) Math.PI * poseWeight;
            float cf = (float) Math.cos(flipAngle);
            float sf = (float) Math.sin(flipAngle);
            float x0 = P_FINAL[0], x1 = P_FINAL[1], x2 = P_FINAL[2];
            float z0 = P_FINAL[6], z1 = P_FINAL[7], z2 = P_FINAL[8];
            P_FINAL[0] = x0 * cf + z0 * sf;
            P_FINAL[1] = x1 * cf + z1 * sf;
            P_FINAL[2] = x2 * cf + z2 * sf;
            P_FINAL[6] = z0 * cf - x0 * sf;
            P_FINAL[7] = z1 * cf - x1 * sf;
            P_FINAL[8] = z2 * cf - x2 * sf;
        }

        write(P_FINAL, WANT);
        keepHand(player, rig.rightProp, rig.rightProp.bone, WANT);

        // Solve Left Arm IK:
        // - For 2H weapons (or Half-Swording [R] on 1H/2H blades): Left hand grips pommel or forward blade/shaft!
        // - For 1H weapons in normal grip: subtle natural left-hand balance guard during swings/parry!
        if (rig.leftArm != null && rig.leftUpperArm != null && rig.leftForearm != null && rig.leftHand != null) {
            InventoryItem secItem = isoPlayer.getSecondaryHandItem();
            boolean holdsTorchOffhand = secItem != null && secItem != weapon && secItem.isTorchCone();
            if (!holdsTorchOffhand) {
                applyLeftArmPose(
                        player, rig, twoHanded, isSpear, phase, poseWeight,
                        cx, cy, cz, rVecX, rVecY, rVecZ, upX, upY, upZ, fwdX, fwdY, fwdZ,
                        P_FINAL
                );
            }
        }

        // Cache exact world-space Hilt & Blade Direction for 3D BladeTracer hit detection
        try {
            Model.boneToWorldCoords(isoPlayer, rig.rightProp.bone, WORLD_HILT);
            Model.boneYDirectionToWorldCoords(isoPlayer, rig.rightProp.bone, WORLD_DIR, 1.0f);
            float wdLen = length(WORLD_DIR.x, WORLD_DIR.y, WORLD_DIR.z * 2.44949f);
            if (wdLen > 1.0e-4f) {
                cachedHiltX = WORLD_HILT.x;
                cachedHiltY = WORLD_HILT.y;
                cachedHiltZ = WORLD_HILT.z;
                cachedBladeDirX = WORLD_DIR.x / wdLen;
                cachedBladeDirY = WORLD_DIR.y / wdLen;
                cachedBladeDirZ = WORLD_DIR.z / wdLen;
                cachedBladeNanos = now;
                hasFreshWorldBlade = true;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyLeftArmPose(
            AnimationPlayer player,
            Rig rig,
            boolean twoHanded,
            boolean isSpear,
            CombatPhase phase,
            float weight,
            float cx, float cy, float cz,
            float rx, float ry, float rz,
            float ux, float uy, float uz,
            float fx, float fy, float fz,
            float[] propFinal
    ) {
        read(player.getModelTransformAt(rig.leftArm[2]), H0_L);

        boolean gripShaft = twoHanded || halfSwordWeight > 0.05f || phase == CombatPhase.PARRY;
        float invScale = 1.0f / METRES_PER_MODEL_UNIT;

        float lx, ly, lz;
        float leftWeight = weight;

        if (gripShaft) {
            // Normal 2H sword/axe/bat: left hand on pommel (-0.11m below right hand)
            // Spear OR Half-Swording [R]: left hand forward on shaft/mid-blade (+0.23m above right hand)!
            float normalOffsetM = isSpear ? 0.22f : -0.11f;
            float halfSwordOffsetM = 0.25f;
            float shaftOffsetM = normalOffsetM + (halfSwordOffsetM - normalOffsetM) * halfSwordWeight;
            if (!twoHanded && halfSwordWeight <= 0.05f && phase == CombatPhase.PARRY) {
                // 1H Parry bracing near upper blade
                shaftOffsetM = 0.18f;
                leftWeight *= 0.85f;
            } else if (!twoHanded) {
                leftWeight *= halfSwordWeight;
            }

            float shiftModel = shaftOffsetM * invScale;
            lx = propFinal[9] + propFinal[3] * shiftModel - rx * (0.02f * invScale);
            ly = propFinal[10] + propFinal[4] * shiftModel - ry * (0.02f * invScale);
            lz = propFinal[11] + propFinal[5] * shiftModel - rz * (0.02f * invScale);
        } else {
            // 1H weapon: subtle counterbalance guard low-left out of screen clutter
            if (phase == CombatPhase.IDLE) {
                leftWeight *= 0.35f;
            } else {
                leftWeight *= 0.65f;
            }
            lx = cx + (-rx * 0.20f - ux * 0.22f + fx * 0.24f) * invScale;
            ly = cy + (-ry * 0.20f - uy * 0.22f + fy * 0.24f) * invScale;
            lz = cz + (-rz * 0.20f - uz * 0.22f + fz * 0.24f) * invScale;
        }

        if (leftWeight <= 0.005f) {
            return;
        }

        float targetLX = H0_L[9] + (lx - H0_L[9]) * leftWeight;
        float targetLY = H0_L[10] + (ly - H0_L[10]) * leftWeight;
        float targetLZ = H0_L[11] + (lz - H0_L[11]) * leftWeight;

        twoBoneIk(
                player, rig.leftUpperArm, rig.leftForearm, rig.leftArm,
                targetLX, targetLY, targetLZ, -rx, -rz
        );

        float poleLX = -rx * 0.62f - ux * 0.75f - fx * 0.22f;
        float poleLY = -ry * 0.62f - uy * 0.75f - fy * 0.22f;
        float poleLZ = -rz * 0.62f - uz * 0.75f - fz * 0.22f;
        swivelElbowToPole(player, rig.leftUpperArm, rig.leftHand, rig.leftArm, poleLX, poleLY, poleLZ, leftWeight);

        if (gripShaft) {
            // Orient Left Hand palm cleanly toward the weapon shaft
            // Left hand +Y along shaft (or -shaft), knuckles wrapping around shaft
            L_WANT[3] = propFinal[3];
            L_WANT[4] = propFinal[4];
            L_WANT[5] = propFinal[5];
            L_WANT[6] = -propFinal[6];
            L_WANT[7] = -propFinal[7];
            L_WANT[8] = -propFinal[8];
            L_WANT[0] = L_WANT[4] * L_WANT[8] - L_WANT[5] * L_WANT[7];
            L_WANT[1] = L_WANT[5] * L_WANT[6] - L_WANT[3] * L_WANT[8];
            L_WANT[2] = L_WANT[3] * L_WANT[7] - L_WANT[4] * L_WANT[6];
            slerp(H0_L, L_WANT, leftWeight * 0.80f, ROT_BLEND);
            setRotationKeepPos(player, rig.leftHand, rig.leftArm[2], ROT_BLEND);
        }
    }

    /**
     * Computes desired weapon pose in local Combat View Basis (R = Right, U = Up, F = Forward):
     * out[0..2] = grip position in meters relative to Shoulder Center C
     * out[3..5] = blade direction (+Y of Prop1, grip -> tip)
     * out[6..8] = cutting edge direction (-Z of Prop1, knuckles)
     * out[9]    = shoulder girdle yaw twist in radians
     */
    private static void computeWeaponTargetInViewBasis(
            CombatEngine engine,
            HandWeapon weapon,
            boolean twoHanded,
            boolean isSpear,
            CombatPhase phase,
            float progress,
            float dt,
            float[] out
    ) {
        // Compute Base Idle Guard first (used by IDLE, RECOVERY, KICK, etc.)
        float swayX = (float) Math.cos(idleBreathPhase * 0.5f) * 0.005f;
        float swayY = (float) Math.sin(idleBreathPhase) * 0.007f;

        // Subtle Mordhau guard shift toward selected mouse direction
        float guardBiasX = (float) Math.cos(smoothedIdleAngle) * 0.035f;
        float guardBiasY = -(float) Math.sin(smoothedIdleAngle) * 0.030f;

        float idleGX, idleGY, idleGZ;
        float idleBX, idleBY, idleBZ;
        float idleEX, idleEY, idleEZ;

        if (halfSwordWeight > 0.5f) {
            // Mordhau Half-Swording [R] stance: weapon held diagonally across lower chest, tip forward-left!
            idleGX = 0.22f + swayX + guardBiasX * 0.6f;
            idleGY = -0.14f + swayY + guardBiasY * 0.6f;
            idleGZ = 0.31f;
            idleBX = -0.58f;
            idleBY = 0.42f;
            idleBZ = 0.70f;
            idleEX = -0.45f;
            idleEY = -0.65f;
            idleEZ = 0.60f;
        } else if (isSpear) {
            // Spear / Polearm guard: couched low-right, tip angled forward toward crosshair
            idleGX = 0.22f + swayX + guardBiasX * 0.5f;
            idleGY = -0.15f + swayY + guardBiasY * 0.5f;
            idleGZ = 0.26f;
            idleBX = -0.16f + guardBiasX * 1.2f;
            idleBY = 0.34f + guardBiasY * 1.2f;
            idleBZ = 0.92f;
            idleEX = 0.0f;
            idleEY = -0.92f;
            idleEZ = 0.38f;
        } else if (twoHanded) {
            // 2H Sword / Axe / Bat Mordhau right-side guard: clearly visible in 1P without blocking center!
            idleGX = 0.21f + swayX + guardBiasX;
            idleGY = -0.13f + swayY + guardBiasY;
            idleGZ = 0.33f;
            idleBX = -0.22f + guardBiasX * 1.8f;
            idleBY = 0.76f + guardBiasY * 1.5f;
            idleBZ = 0.60f;
            idleEX = -0.55f;
            idleEY = -0.45f;
            idleEZ = 0.70f;
        } else {
            // 1H Weapon Mordhau guard
            idleGX = 0.22f + swayX + guardBiasX;
            idleGY = -0.12f + swayY + guardBiasY;
            idleGZ = 0.34f;
            idleBX = -0.18f + guardBiasX * 1.8f;
            idleBY = 0.78f + guardBiasY * 1.5f;
            idleBZ = 0.58f;
            idleEX = -0.50f;
            idleEY = -0.48f;
            idleEZ = 0.72f;
        }

        if (phase == CombatPhase.IDLE || phase == CombatPhase.KICK) {
            out[0] = idleGX; out[1] = idleGY; out[2] = idleGZ;
            out[3] = idleBX; out[4] = idleBY; out[5] = idleBZ;
            out[6] = idleEX; out[7] = idleEY; out[8] = idleEZ;
            out[9] = 0.0f;
            if (phase == CombatPhase.KICK) {
                // Pull weapon slightly back/sideways for balance while kicking forward
                float k = (float) Math.sin(PZMath.clamp(progress, 0.0f, 1.0f) * Math.PI);
                out[0] += 0.06f * k;
                out[1] -= 0.04f * k;
                out[2] -= 0.08f * k;
            }
            return;
        }

        // PARRY & PARRY_RECOVERY: Crisp Mordhau diagonal block across center view
        if (phase == CombatPhase.PARRY || phase == CombatPhase.PARRY_RECOVERY) {
            float pBiasX = (float) Math.cos(smoothedIdleAngle) * 0.06f;
            float pBiasY = -(float) Math.sin(smoothedIdleAngle) * 0.05f;
            float parryGX = 0.09f + pBiasX;
            float parryGY = -0.02f + pBiasY;
            float parryGZ = 0.38f;
            // Diagonal guard bar across upper-center view
            float parryBX = -0.62f;
            float parryBY = 0.72f;
            float parryBZ = 0.28f;
            // Edge turned outward toward incoming blow
            float parryEX = 0.20f;
            float parryEY = 0.15f;
            float parryEZ = 0.96f;

            float w = (phase == CombatPhase.PARRY)
                    ? easeOutCubic(Math.min(1.0f, progress * 4.5f))
                    : (1.0f - easeInOutSine(progress));

            out[0] = lerp(idleGX, parryGX, w);
            out[1] = lerp(idleGY, parryGY, w);
            out[2] = lerp(idleGZ, parryGZ, w);
            out[3] = lerp(idleBX, parryBX, w);
            out[4] = lerp(idleBY, parryBY, w);
            out[5] = lerp(idleBZ, parryBZ, w);
            out[6] = lerp(idleEX, parryEX, w);
            out[7] = lerp(idleEY, parryEY, w);
            out[8] = lerp(idleEZ, parryEZ, w);
            out[9] = -0.10f * w;
            return;
        }

        AttackDirection activeDir = engine.getActiveDirection();
        float activeAng = engine.getActiveAngleRad();

        // THRUST: Short, crisp, controlled piercing stab / 2H spear poke straight down the crosshair!
        if (activeDir == AttackDirection.THRUST) {
            // Chamber position: pulled back beside right ribcage, tip aimed dead at center crosshair!
            float chamGX = twoHanded ? 0.19f : 0.21f;
            float chamGY = -0.11f;
            float chamGZ = twoHanded ? 0.13f : 0.16f;
            float chamBX = -0.10f;
            float chamBY = 0.06f;
            float chamBZ = 0.99f;
            float chamEX = 0.0f;
            float chamEY = -1.0f;
            float chamEZ = 0.06f;

            // Peak extension: crisp controlled poke straight into crosshair (NOT an overextended lunge!)
            float extGX = 0.07f;
            float extGY = -0.05f;
            float extGZ = twoHanded ? 0.50f : 0.53f;
            float extBX = -0.03f;
            float extBY = 0.02f;
            float extBZ = 0.999f;

            if (phase == CombatPhase.WINDUP) {
                float w = easeOutCubic(progress);
                out[0] = lerp(idleGX, chamGX, w);
                out[1] = lerp(idleGY, chamGY, w);
                out[2] = lerp(idleGZ, chamGZ, w);
                out[3] = lerp(idleBX, chamBX, w);
                out[4] = lerp(idleBY, chamBY, w);
                out[5] = lerp(idleBZ, chamBZ, w);
                out[6] = lerp(idleEX, chamEX, w);
                out[7] = lerp(idleEY, chamEY, w);
                out[8] = lerp(idleEZ, chamEZ, w);
                out[9] = -0.22f * w;
            } else if (phase == CombatPhase.RELEASE) {
                // Fast forward thrust peaking at progress = 0.45, then starting controlled retraction
                float thrustCurve;
                if (progress <= 0.45f) {
                    thrustCurve = easeOutCubic(progress / 0.45f);
                } else {
                    thrustCurve = 1.0f - 0.35f * easeInOutSine((progress - 0.45f) / 0.55f);
                }
                out[0] = lerp(chamGX, extGX, thrustCurve);
                out[1] = lerp(chamGY, extGY, thrustCurve);
                out[2] = lerp(chamGZ, extGZ, thrustCurve);
                out[3] = lerp(chamBX, extBX, thrustCurve);
                out[4] = lerp(chamBY, extBY, thrustCurve);
                out[5] = lerp(chamBZ, extBZ, thrustCurve);
                out[6] = chamEX;
                out[7] = chamEY;
                out[8] = chamEZ;
                out[9] = lerp(-0.22f, 0.18f, thrustCurve);
            } else {
                // RECOVERY / BOUNCE from thrust
                float endGX = lerp(chamGX, extGX, 0.65f);
                float endGY = lerp(chamGY, extGY, 0.65f);
                float endGZ = lerp(chamGZ, extGZ, 0.65f);
                float w = easeInOutSine(progress);
                out[0] = lerp(endGX, idleGX, w);
                out[1] = lerp(endGY, idleGY, w);
                out[2] = lerp(endGZ, idleGZ, w);
                out[3] = lerp(extBX, idleBX, w);
                out[4] = lerp(extBY, idleBY, w);
                out[5] = lerp(extBZ, idleBZ, w);
                out[6] = lerp(chamEX, idleEX, w);
                out[7] = lerp(chamEY, idleEY, w);
                out[8] = lerp(chamEZ, idleEZ, w);
                out[9] = lerp(0.12f, 0.0f, w);
            }
            return;
        }

        // 240/360-DEGREE CONTINUOUS FREE-ANGLE SWING!
        // Screen-space start direction S = (sx, sy, 0) in (R, U, F):
        //   ang = 0      -> S = (+1,  0) (Starts Right, sweeps Left)
        //   ang = PI     -> S = (-1,  0) (Starts Left, sweeps Right)
        //   ang = -PI/2  -> S = ( 0, +1) (Starts High Overhead, sweeps Down)
        //   ang = +PI/2  -> S = ( 0, -1) (Starts Low Underhand, sweeps Up)
        float sx = (float) Math.cos(activeAng);
        float sy = -(float) Math.sin(activeAng);
        // Strike travel direction T = -S
        float tx = -sx;
        float ty = -sy;

        // Chamber pose (end of WINDUP): coiled back on +S side!
        float chamGX = sx * 0.28f + 0.04f;
        float chamGY = sy * 0.24f - 0.03f;
        float chamGZ = 0.23f;
        // Blade cocked back into windup arc so tip trails behind hand along +S and slightly back (-F)
        float chamBX = sx * 0.84f;
        float chamBY = sy * 0.80f + 0.22f;
        float chamBZ = -0.28f;
        // Cutting edge aimed forward-into-arc along T + F
        float chamEX = tx * 0.65f;
        float chamEY = ty * 0.65f;
        float chamEZ = 0.75f;
        float chamTwist = -sx * 0.28f;

        // Follow-through pose (end of RELEASE): swept across screen to T = -S!
        float endArcPhi = 1.22f; // ~70 deg past center
        float sinEnd = (float) Math.sin(endArcPhi);
        float cosEnd = (float) Math.cos(endArcPhi);
        float folGX = tx * 0.28f + 0.02f;
        float folGY = ty * 0.24f - 0.06f;
        float folGZ = 0.34f;
        float folBX = tx * sinEnd;
        float folBY = ty * sinEnd;
        float folBZ = cosEnd;
        float folEX = tx * cosEnd;
        float folEY = ty * cosEnd;
        float folEZ = -sinEnd;
        float folTwist = tx * 0.30f;

        if (phase == CombatPhase.WINDUP) {
            // Mordhau windup: coils smoothly back with a slight anticipation curve
            float w = easeOutCubic(progress);
            out[0] = lerp(idleGX, chamGX, w);
            out[1] = lerp(idleGY, chamGY, w);
            out[2] = lerp(idleGZ, chamGZ, w);
            out[3] = lerp(idleBX, chamBX, w);
            out[4] = lerp(idleBY, chamBY, w);
            out[5] = lerp(idleBZ, chamBZ, w);
            out[6] = lerp(idleEX, chamEX, w);
            out[7] = lerp(idleEY, chamEY, w);
            out[8] = lerp(idleEZ, chamEZ, w);
            out[9] = chamTwist * w;
        } else if (phase == CombatPhase.RELEASE) {
            // Sweeps cleanly through a ~148-degree arc right across the 1P crosshair!
            float s = easeInOutSweep(progress);
            float phi = lerp(-1.26f, endArcPhi, s);
            float handPhi = phi * 0.62f;

            float sinH = (float) Math.sin(handPhi);
            float cosH = (float) Math.cos(handPhi);
            float reachFwd = 0.14f + 0.36f * cosH; // peaks at 0.50m dead-center!

            out[0] = tx * (0.36f * sinH) + 0.03f * (1.0f - s);
            out[1] = ty * (0.32f * sinH) - 0.04f;
            out[2] = reachFwd;

            float sinB = (float) Math.sin(phi);
            float cosB = (float) Math.cos(phi);
            out[3] = tx * sinB;
            out[4] = ty * sinB + 0.08f * (1.0f - Math.abs(sinB));
            out[5] = cosB;

            // Cutting edge tangent to the swing arc: dV/dPhi = T * cos(phi) - F * sin(phi)
            out[6] = tx * cosB;
            out[7] = ty * cosB;
            out[8] = -sinB;
            out[9] = lerp(chamTwist, folTwist, s);
        } else if (phase == CombatPhase.BOUNCE) {
            // Wall Hit-Stop recoil: sharp bounce back toward chamber side then settle to idle
            float recoil = (float) Math.sin(progress * Math.PI) * (1.0f - progress * 0.5f);
            float settle = easeInOutSine(progress);
            float bGX = lerp(sx * 0.10f, idleGX, settle) + sx * 0.08f * recoil;
            float bGY = lerp(sy * 0.08f, idleGY, settle) + sy * 0.06f * recoil;
            float bGZ = lerp(0.38f, idleGZ, settle) - 0.10f * recoil;
            out[0] = bGX;
            out[1] = bGY;
            out[2] = bGZ;
            out[3] = lerp(sx * 0.35f, idleBX, settle);
            out[4] = lerp(sy * 0.35f + 0.45f, idleBY, settle);
            out[5] = lerp(0.75f, idleBZ, settle);
            out[6] = lerp(tx, idleEX, settle);
            out[7] = lerp(ty, idleEY, settle);
            out[8] = lerp(0.3f, idleEZ, settle);
            out[9] = lerp(-sx * 0.12f, 0.0f, settle);
        } else {
            // RECOVERY: smooth athletic return from follow-through to guard
            float w = easeInOutSine(progress);
            out[0] = lerp(folGX, idleGX, w);
            out[1] = lerp(folGY, idleGY, w);
            out[2] = lerp(folGZ, idleGZ, w);
            out[3] = lerp(folBX, idleBX, w);
            out[4] = lerp(folBY, idleBY, w);
            out[5] = lerp(folBZ, idleBZ, w);
            out[6] = lerp(folEX, idleEX, w);
            out[7] = lerp(folEY, idleEY, w);
            out[8] = lerp(folEZ, idleEZ, w);
            out[9] = lerp(folTwist, 0.0f, w);
        }
    }

    /**
     * Procedural 3D First-Person Right-Leg Mordhau Kick with authentic human body-weight inertia!
     * 3-Stage Biomechanical Curve:
     * 1. [0.00..0.30] Heavy Weight-Shift & Knee Chamber (knee coils high toward chest, foot still tucked back)
     * 2. [0.30..0.46] Explosive Body-Weight Drive + [0.46..0.56] Heavy Impact Hold (heel drives out, torso counter-leans)
     * 3. [0.56..1.00] Grounded Gravity Recovery (heavy leg settles back under the hips)
     */
    private static void applyRightLegKick(
            AnimationPlayer player,
            Rig rig,
            float fx, float fz,
            float rx, float rz,
            float progress
    ) {
        if (rig.rightLeg == null || rig.rightThigh == null || rig.rightCalf == null || rig.rightFoot == null) {
            return;
        }

        float ext;
        float lift;
        float footPitch;
        if (progress < 0.30f) {
            // Stage 1: Heavy weight transfer & knee coil (starts heavy, accelerates as knee rises)
            float t = progress / 0.30f;
            float coil = t * t * (3.0f - 2.0f * t);
            ext = 0.20f * coil;
            lift = 0.44f * easeOutCubic(t);
            footPitch = -0.45f * coil;
        } else if (progress < 0.46f) {
            // Stage 2A: Explosive body-weight heel drive forward!
            float t = (progress - 0.30f) / 0.16f;
            float drive = 1.0f - (1.0f - t) * (1.0f - t) * (1.0f - t);
            ext = lerp(0.20f, 0.78f, drive);
            lift = lerp(0.44f, 0.54f, drive);
            footPitch = lerp(-0.45f, -1.22f, drive);
        } else if (progress < 0.56f) {
            // Stage 2B: Heavy impact hold at full extension so the boot feels weighty & planted!
            float t = (progress - 0.46f) / 0.10f;
            ext = lerp(0.78f, 0.73f, t);
            lift = lerp(0.54f, 0.50f, t);
            footPitch = -1.20f;
        } else {
            // Stage 3: Grounded, heavy recovery back to standing stance
            float t = (progress - 0.56f) / 0.44f;
            float rec = t * t * (3.0f - 2.0f * t);
            ext = lerp(0.73f, 0.0f, rec);
            lift = lerp(0.50f, 0.0f, rec);
            footPitch = lerp(-1.20f, 0.0f, rec);
        }

        float w = (progress < 0.14f) ? (progress / 0.14f) : (progress > 0.84f ? (1.0f - progress) / 0.16f : 1.0f);
        w = PZMath.clamp(w, 0.0f, 1.0f);
        if (w <= 0.005f) {
            return;
        }

        // Authentic body-weight counterbalance: torso leans back & twists slightly as hip drives the right leg forward
        if (rig.spine != null) {
            float thrustRatio = ext / 0.78f;
            float backTilt = (0.06f + 0.17f * thrustRatio) * w;
            float hipTwist = -0.10f * thrustRatio * w;
            rotate(player, rig.spine, -fz, 0.0f, fx, backTilt);
            rotate(player, rig.spine, 0.0f, 1.0f, 0.0f, hipTwist);
            if (rig.head != null) {
                // Keep camera/head horizon steady while feeling the heavy torso shift
                rotate(player, rig.head, -fz, 0.0f, fx, -backTilt * 0.82f);
                rotate(player, rig.head, 0.0f, 1.0f, 0.0f, -hipTwist * 0.85f);
            }
        }

        Matrix4f hip = player.getModelTransformAt(rig.rightLeg[0]);
        FOOT_R_ORIG.load(player.getModelTransformAt(rig.rightLeg[2]));

        float invScale = 1.0f / METRES_PER_MODEL_UNIT;
        // Kick ankle target relative to right hip: centered toward crosshair so boot sole is clearly visible in 1P
        float kickAnkleX = hip.m03 + (fx * ext - rx * 0.03f) * invScale;
        float kickAnkleY = hip.m13 + (-0.34f + lift) * invScale;
        float kickAnkleZ = hip.m23 + (fz * ext - rz * 0.03f) * invScale;

        float tx = lerp(FOOT_R_ORIG.m03, kickAnkleX, w);
        float ty = lerp(FOOT_R_ORIG.m13, kickAnkleY, w);
        float tz = lerp(FOOT_R_ORIG.m23, kickAnkleZ, w);

        twoBoneIk(player, rig.rightThigh, rig.rightCalf, rig.rightLeg, tx, ty, tz, rx, rz);

        // Swivel right knee UP-and-forward so knee bends anatomically!
        swivelElbowToPole(player, rig.rightThigh, rig.rightFoot, rig.rightLeg, fx * 0.55f, 0.90f, fz * 0.55f, w);

        // Pitch right foot up so the heavy boot sole stamps straight forward into the enemy!
        FOOT_R_WANT.load(player.getModelTransformAt(rig.rightLeg[2]));
        keepHand(player, rig.rightFoot, rig.rightLeg[2], FOOT_R_ORIG);
        rotate(player, rig.rightFoot, rx, 0.0f, rz, footPitch * w);
    }

    // =========================================================================
    // Analytical Two-Bone IK, Elbow Pole Swivel & Rigid Bone Transform Helpers
    // =========================================================================

    private static void twoBoneIk(
            AnimationPlayer player,
            Part upper,
            Part lower,
            int[] limb,
            float tx, float ty, float tz,
            float fallbackX, float fallbackZ
    ) {
        Matrix4f s = player.getModelTransformAt(limb[0]);
        Matrix4f e = player.getModelTransformAt(limb[1]);
        Matrix4f h = player.getModelTransformAt(limb[2]);
        float sx = s.m03, sy = s.m13, sz = s.m23;
        float ex = e.m03, ey = e.m13, ez = e.m23;
        float ux = sx - ex, uy = sy - ey, uz = sz - ez;
        float vx = h.m03 - ex, vy = h.m13 - ey, vz = h.m23 - ez;
        float a = length(ux, uy, uz);
        float b = length(vx, vy, vz);
        if (a < 1.0e-5f || b < 1.0e-5f) {
            return;
        }
        // Keep slight 2% anatomical bend margin so arm never locks into singularity
        float maxReach = (a + b) * 0.985f;
        float minReach = Math.abs(a - b) + 0.015f;
        float d = PZMath.clamp(length(tx - sx, ty - sy, tz - sz), minReach, maxReach);
        float want = (float) Math.acos(PZMath.clamp((a * a + b * b - d * d) / (2.0f * a * b), -1.0f, 1.0f));
        float have = (float) Math.acos(PZMath.clamp((ux * vx + uy * vy + uz * vz) / (a * b), -1.0f, 1.0f));
        float nx = vy * uz - vz * uy;
        float ny = vz * ux - vx * uz;
        float nz = vx * uy - vy * ux;
        float nl = length(nx, ny, nz);
        if (nl > 1.0e-6f) {
            rotateAbout(player, lower, nx / nl, ny / nl, nz / nl, have - want, ex, ey, ez);
        } else if (!Float.isNaN(fallbackX)) {
            rotateAbout(player, lower, fallbackX, 0.0f, fallbackZ, have - want, ex, ey, ez);
        }

        h = player.getModelTransformAt(limb[2]);
        float cx = h.m03 - sx, cy = h.m13 - sy, cz = h.m23 - sz;
        float gx = tx - sx, gy = ty - sy, gz = tz - sz;
        float cl = length(cx, cy, cz);
        float gl = length(gx, gy, gz);
        float ax = cy * gz - cz * gy;
        float ay = cz * gx - cx * gz;
        float az = cx * gy - cy * gx;
        float al = length(ax, ay, az);
        if (cl > 1.0e-5f && gl > 1.0e-5f && al > 1.0e-6f) {
            float swing = (float) Math.acos(PZMath.clamp((cx * gx + cy * gy + cz * gz) / (cl * gl), -1.0f, 1.0f));
            rotateAbout(player, upper, ax / al, ay / al, az / al, swing, sx, sy, sz);
        }
    }

    /**
     * Swivels the middle joint (elbow / knee) around the shoulder-to-wrist axis toward a natural anatomical pole
     * without changing the wrist/ankle position.
     */
    private static void swivelElbowToPole(
            AnimationPlayer player,
            Part upper,
            Part endPart,
            int[] limb,
            float poleX, float poleY, float poleZ,
            float weight
    ) {
        Matrix4f s = player.getModelTransformAt(limb[0]);
        Matrix4f e = player.getModelTransformAt(limb[1]);
        Matrix4f h = player.getModelTransformAt(limb[2]);
        float sx = s.m03, sy = s.m13, sz = s.m23;
        float ax = h.m03 - sx, ay = h.m13 - sy, az = h.m23 - sz;
        float al = length(ax, ay, az);
        if (al < 1.0e-4f) {
            return;
        }
        ax /= al; ay /= al; az /= al;

        // Current elbow offset perpendicular to axis A
        float ex = e.m03 - sx, ey = e.m13 - sy, ez = e.m23 - sz;
        float eDot = ex * ax + ey * ay + ez * az;
        float curX = ex - ax * eDot;
        float curY = ey - ay * eDot;
        float curZ = ez - az * eDot;
        float curL = length(curX, curY, curZ);
        if (curL < 1.0e-4f) {
            return;
        }
        curX /= curL; curY /= curL; curZ /= curL;

        // Desired pole projected perpendicular to axis A
        float pDot = poleX * ax + poleY * ay + poleZ * az;
        float wantX = poleX - ax * pDot;
        float wantY = poleY - ay * pDot;
        float wantZ = poleZ - az * pDot;
        float wantL = length(wantX, wantY, wantZ);
        if (wantL < 1.0e-4f) {
            return;
        }
        wantX /= wantL; wantY /= wantL; wantZ /= wantL;

        float crossX = curY * wantZ - curZ * wantY;
        float crossY = curZ * wantX - curX * wantZ;
        float crossZ = curX * wantY - curY * wantX;
        float sinA = crossX * ax + crossY * ay + crossZ * az;
        float cosA = PZMath.clamp(curX * wantX + curY * wantY + curZ * wantZ, -1.0f, 1.0f);
        float angle = (float) Math.atan2(sinA, cosA) * weight;
        if (Math.abs(angle) > 1.0e-4f) {
            HAND_KEEP.load(h);
            rotateAbout(player, upper, ax, ay, az, angle, sx, sy, sz);
            keepHand(player, endPart, limb[2], HAND_KEEP);
        }
    }

    private static void setRotationKeepPos(AnimationPlayer player, Part part, int bone, float[] rot9) {
        Matrix4f m = player.getModelTransformAt(bone);
        System.arraycopy(rot9, 0, L_WANT, 0, 9);
        L_WANT[9] = m.m03;
        L_WANT[10] = m.m13;
        L_WANT[11] = m.m23;
        write(L_WANT, WANT);
        keepHand(player, part, bone, WANT);
    }

    private static void keepHand(AnimationPlayer player, Part part, int bone, Matrix4f wanted) {
        if (part != null && Matrix4f.invert(player.getModelTransformAt(bone), HAND_TURN) != null) {
            Matrix4f.mul(HAND_TURN, wanted, HAND_TURN);
            transform(player, part, HAND_TURN);
        }
    }

    private static void rotate(AnimationPlayer player, Part part, float ux, float uy, float uz, float angle) {
        if (angle != 0.0f && part != null) {
            Matrix4f pivot = player.getModelTransformAt(part.bone);
            rotateAbout(player, part, ux, uy, uz, angle, pivot.m03, pivot.m13, pivot.m23);
        }
    }

    private static void rotateAbout(
            AnimationPlayer player, Part part,
            float ux, float uy, float uz, float angle,
            float px, float py, float pz
    ) {
        if (angle != 0.0f && part != null) {
            rotation(ux, uy, uz, angle, px, py, pz, TURN);
            transform(player, part, TURN);
        }
    }

    private static void transform(AnimationPlayer player, Part part, Matrix4f turn) {
        for (int bone : part.moved) {
            Matrix4f model = player.getModelTransformAt(bone);
            Matrix4f.mul(model, turn, model);
        }
        for (int i = 0; i < part.fix.length; i++) {
            if (Matrix4f.invert(player.getModelTransformAt(part.fixParent[i]), PARENT_INVERSE) != null) {
                keepLocal(player, part.fix[i]);
                player.boneTransforms[part.fix[i]].mul(player.getModelTransformAt(part.fix[i]), PARENT_INVERSE);
            }
        }
    }

    private static void keepLocal(AnimationPlayer player, int bone) {
        Locals l = LOCALS.get(player);
        if (l == null || l.has.length != player.boneTransforms.length) {
            l = new Locals(player.boneTransforms.length);
            LOCALS.put(player, l);
        }
        if (!l.has[bone]) {
            if (l.saved[bone] == null) {
                l.saved[bone] = TwistableBoneTransform.alloc();
            }
            l.saved[bone].set(player.boneTransforms[bone]);
            l.has[bone] = true;
            l.list[l.count++] = bone;
            localsKept = true;
        }
    }

    private static void rotation(float ux, float uy, float uz, float angle, float px, float py, float pz, Matrix4f out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float t = 1.0f - c;
        out.m00 = c + t * ux * ux;
        out.m01 = t * ux * uy - s * uz;
        out.m02 = t * ux * uz + s * uy;
        out.m10 = t * ux * uy + s * uz;
        out.m11 = c + t * uy * uy;
        out.m12 = t * uy * uz - s * ux;
        out.m20 = t * ux * uz - s * uy;
        out.m21 = t * uy * uz + s * ux;
        out.m22 = c + t * uz * uz;
        out.m30 = 0.0f;
        out.m31 = 0.0f;
        out.m32 = 0.0f;
        out.m33 = 1.0f;
        out.m03 = px - (out.m00 * px + out.m01 * py + out.m02 * pz);
        out.m13 = py - (out.m10 * px + out.m11 * py + out.m12 * pz);
        out.m23 = pz - (out.m20 * px + out.m21 * py + out.m22 * pz);
    }

    private static void modelDirection(float worldX, float worldY, float renderedAngle, float[] out) {
        TMP_VEC.set(worldX / METRES_PER_MODEL_UNIT, 0.0f, worldY / METRES_PER_MODEL_UNIT);
        TMP_VEC.rotatey(-renderedAngle);
        float x = -TMP_VEC.x;
        float z = TMP_VEC.z;
        float len = (float) Math.sqrt(x * x + z * z);
        if (len < 1.0e-6f) {
            out[0] = 0.0f;
            out[1] = 1.0f;
        } else {
            out[0] = x / len;
            out[1] = z / len;
        }
    }

    private static void read(Matrix4f m, float[] f) {
        f[0] = m.m00; f[1] = m.m10; f[2] = m.m20;
        f[3] = m.m01; f[4] = m.m11; f[5] = m.m21;
        f[6] = m.m02; f[7] = m.m12; f[8] = m.m22;
        f[9] = m.m03; f[10] = m.m13; f[11] = m.m23;
    }

    private static void write(float[] f, Matrix4f m) {
        m.m00 = f[0]; m.m10 = f[1]; m.m20 = f[2]; m.m30 = 0.0f;
        m.m01 = f[3]; m.m11 = f[4]; m.m21 = f[5]; m.m31 = 0.0f;
        m.m02 = f[6]; m.m12 = f[7]; m.m22 = f[8]; m.m32 = 0.0f;
        m.m03 = f[9]; m.m13 = f[10]; m.m23 = f[11]; m.m33 = 1.0f;
    }

    private static void compose(float[] a, float[] b, float[] out) {
        float[] r = COMPOSE_TMP;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        for (int j = 0; j < 3; j++) {
            r[9 + j] = a[9] * b[j] + a[10] * b[3 + j] + a[11] * b[6 + j] + b[9 + j];
        }
        System.arraycopy(r, 0, out, 0, 12);
    }

    private static void relative(float[] a, float[] b, float[] out) {
        float[] r = COMPOSE_TMP;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j * 3] + a[i * 3 + 1] * b[j * 3 + 1] + a[i * 3 + 2] * b[j * 3 + 2];
            }
        }
        float px = a[9] - b[9];
        float py = a[10] - b[10];
        float pz = a[11] - b[11];
        for (int j = 0; j < 3; j++) {
            r[9 + j] = px * b[j * 3] + py * b[j * 3 + 1] + pz * b[j * 3 + 2];
        }
        System.arraycopy(r, 0, out, 0, 12);
    }

    private static void slerp(float[] a, float[] b, float t, float[] out) {
        if (t >= 1.0f) {
            System.arraycopy(b, 0, out, 0, 9);
            return;
        }
        if (t <= 0.0f) {
            System.arraycopy(a, 0, out, 0, 9);
            return;
        }
        quat(a, QA);
        quat(b, QB);
        float d = QA[0] * QB[0] + QA[1] * QB[1] + QA[2] * QB[2] + QA[3] * QB[3];
        if (d < 0.0f) {
            for (int i = 0; i < 4; i++) {
                QB[i] = -QB[i];
            }
            d = -d;
        }
        float wa, wb;
        if (d > 0.9995f) {
            wa = 1.0f - t;
            wb = t;
        } else {
            float th = (float) Math.acos(d);
            float st = (float) Math.sin(th);
            wa = (float) Math.sin((1.0f - t) * th) / st;
            wb = (float) Math.sin(t * th) / st;
        }
        float w = QA[0] * wa + QB[0] * wb;
        float x = QA[1] * wa + QB[1] * wb;
        float y = QA[2] * wa + QB[2] * wb;
        float z = QA[3] * wa + QB[3] * wb;
        float n = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        w /= n; x /= n; y /= n; z /= n;
        out[0] = 1.0f - 2.0f * (y * y + z * z);
        out[1] = 2.0f * (x * y - z * w);
        out[2] = 2.0f * (x * z + y * w);
        out[3] = 2.0f * (x * y + z * w);
        out[4] = 1.0f - 2.0f * (x * x + z * z);
        out[5] = 2.0f * (y * z - x * w);
        out[6] = 2.0f * (x * z - y * w);
        out[7] = 2.0f * (y * z + x * w);
        out[8] = 1.0f - 2.0f * (x * x + y * y);
    }

    private static void quat(float[] m, float[] q) {
        float tr = m[0] + m[4] + m[8];
        float w, x, y, z;
        if (tr > 0.0f) {
            float s = (float) Math.sqrt(tr + 1.0f) * 2.0f;
            w = 0.25f * s;
            x = (m[7] - m[5]) / s;
            y = (m[2] - m[6]) / s;
            z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            float s = (float) Math.sqrt(1.0f + m[0] - m[4] - m[8]) * 2.0f;
            w = (m[7] - m[5]) / s;
            x = 0.25f * s;
            y = (m[1] + m[3]) / s;
            z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            float s = (float) Math.sqrt(1.0f + m[4] - m[0] - m[8]) * 2.0f;
            w = (m[2] - m[6]) / s;
            x = (m[1] + m[3]) / s;
            y = 0.25f * s;
            z = (m[5] + m[7]) / s;
        } else {
            float s = (float) Math.sqrt(1.0f + m[8] - m[0] - m[4]) * 2.0f;
            w = (m[3] - m[1]) / s;
            x = (m[2] + m[6]) / s;
            y = (m[5] + m[7]) / s;
            z = 0.25f * s;
        }
        float n = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        float sign = w >= 0.0f ? 1.0f : -1.0f;
        q[0] = (w / n) * sign;
        q[1] = (x / n) * sign;
        q[2] = (y / n) * sign;
        q[3] = (z / n) * sign;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float approach(float value, float target, float step) {
        return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float easeOutCubic(float x) {
        x = PZMath.clamp(x, 0.0f, 1.0f);
        float inv = 1.0f - x;
        return 1.0f - inv * inv * inv;
    }

    private static float easeInOutSine(float x) {
        x = PZMath.clamp(x, 0.0f, 1.0f);
        return -((float) Math.cos(Math.PI * x) - 1.0f) * 0.5f;
    }

    private static float easeInOutSweep(float x) {
        x = PZMath.clamp(x, 0.0f, 1.0f);
        // Quick energetic acceleration into center cut, smooth follow-through
        return x < 0.45f
                ? 2.22f * x * x / 0.45f * 0.5f + 0.35f * x
                : 1.0f - (float) Math.pow(1.0f - x, 2.1) * 0.68f;
    }

    private static float wrapAngle(float a) {
        while (a > (float) Math.PI) a -= (float) (Math.PI * 2.0);
        while (a < -(float) Math.PI) a += (float) (Math.PI * 2.0);
        return a;
    }

    private static Rig rigOf(SkinningData data) {
        Rig rig = RIGS.get(data);
        if (rig == null) {
            rig = new Rig(data);
            RIGS.put(data, rig);
        }
        return rig;
    }

    private static final class Locals {
        final TwistableBoneTransform[] saved;
        final boolean[] has;
        final int[] list;
        int count;

        Locals(int bones) {
            this.saved = new TwistableBoneTransform[bones];
            this.has = new boolean[bones];
            this.list = new int[bones];
        }
    }

    private static final class Part {
        final int bone;
        final int[] moved;
        final int[] fix;
        final int[] fixParent;

        private Part(int bone, int[] moved, int[] fix, int[] fixParent) {
            this.bone = bone;
            this.moved = moved;
            this.fix = fix;
            this.fixParent = fixParent;
        }

        static Part body(SkinningData data, String name) {
            return build(data, name, false);
        }

        static Part limb(SkinningData data, String name) {
            return build(data, name, true);
        }

        static Part arms(SkinningData data) {
            int left = index(data, "Bip01_L_Clavicle");
            int right = index(data, "Bip01_R_Clavicle");
            if (left < 0 || right < 0) {
                return null;
            }
            int count = data.numBones();
            int prop1 = index(data, "Bip01_Prop1");
            int prop2 = index(data, "Bip01_Prop2");
            boolean[] moves = new boolean[count];
            for (int i = 0; i < count; i++) {
                moves[i] = below(data, i, left) || below(data, i, right) || below(data, i, prop1) || below(data, i, prop2);
            }
            return fromMask(data, left, moves);
        }

        private static Part build(SkinningData data, String name, boolean limbOnly) {
            int top = index(data, name);
            if (top < 0) {
                return null;
            }
            int count = data.numBones();
            int leftThigh = index(data, "Bip01_L_Thigh");
            int rightThigh = index(data, "Bip01_R_Thigh");
            boolean[] moves = new boolean[count];
            for (int i = 0; i < count; i++) {
                moves[i] = below(data, i, top) && (limbOnly || (!below(data, i, leftThigh) && !below(data, i, rightThigh)));
            }
            if (!limbOnly) {
                ride(data, moves, "Bip01_R_Hand", "Bip01_Prop1");
                ride(data, moves, "Bip01_L_Hand", "Bip01_Prop2");
            }
            return fromMask(data, top, moves);
        }

        private static Part fromMask(SkinningData data, int top, boolean[] moves) {
            int count = data.numBones();
            int[] moved = new int[count];
            int[] fix = new int[count];
            int[] fixParent = new int[count];
            int movedCount = 0;
            int fixCount = 0;
            for (int i = 0; i < count; i++) {
                if (moves[i]) {
                    moved[movedCount++] = i;
                }
                SkinningBone parent = data.getBoneAt(i).parent;
                if (parent != null && moves[i] != moves[parent.index]) {
                    fix[fixCount] = i;
                    fixParent[fixCount++] = parent.index;
                }
            }
            return new Part(
                    top,
                    Arrays.copyOf(moved, movedCount),
                    Arrays.copyOf(fix, fixCount),
                    Arrays.copyOf(fixParent, fixCount)
            );
        }

        private static void ride(SkinningData data, boolean[] moves, String hand, String prop) {
            int handIdx = index(data, hand);
            int propIdx = index(data, prop);
            if (handIdx >= 0 && propIdx >= 0 && moves[handIdx]) {
                for (int i = 0; i < moves.length; i++) {
                    if (below(data, i, propIdx)) {
                        moves[i] = true;
                    }
                }
            }
        }

        static int index(SkinningData data, String name) {
            Integer idx = data.boneIndices == null ? null : data.boneIndices.get(name);
            return idx != null && idx >= 0 && idx < data.numBones() ? idx : -1;
        }

        private static boolean below(SkinningData data, int i, int ancestor) {
            if (ancestor < 0) {
                return false;
            }
            for (SkinningBone b = data.getBoneAt(i); b != null; b = b.parent) {
                if (b.index == ancestor) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Rig {
        final Part spine;
        final Part head;
        final Part arms;
        final int leftClavicle;
        final int rightClavicle;
        final int neck;
        final Part leftUpperArm;
        final Part rightUpperArm;
        final Part leftForearm;
        final Part rightForearm;
        final Part leftHand;
        final Part rightHand;
        final Part rightProp;
        final Part rightThigh;
        final Part rightCalf;
        final Part rightFoot;
        final int[] leftArm;
        final int[] rightArm;
        final int[] rightLeg;

        Rig(SkinningData data) {
            this.spine = Part.body(data, "Bip01_Spine");
            this.head = Part.body(data, "Bip01_Head");
            this.arms = Part.arms(data);
            this.leftClavicle = Part.index(data, "Bip01_L_Clavicle");
            this.rightClavicle = Part.index(data, "Bip01_R_Clavicle");
            this.neck = Part.index(data, "Bip01_Neck");
            this.leftUpperArm = Part.limb(data, "Bip01_L_UpperArm");
            this.rightUpperArm = Part.limb(data, "Bip01_R_UpperArm");
            this.leftForearm = Part.limb(data, "Bip01_L_Forearm");
            this.rightForearm = Part.limb(data, "Bip01_R_Forearm");
            this.leftHand = Part.limb(data, "Bip01_L_Hand");
            this.rightHand = Part.limb(data, "Bip01_R_Hand");
            this.rightProp = Part.limb(data, "Bip01_Prop1");
            this.rightThigh = Part.limb(data, "Bip01_R_Thigh");
            this.rightCalf = Part.limb(data, "Bip01_R_Calf");
            this.rightFoot = Part.limb(data, "Bip01_R_Foot");
            this.leftArm = joints(data, "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand");
            this.rightArm = joints(data, "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand");
            this.rightLeg = joints(data, "Bip01_R_Thigh", "Bip01_R_Calf", "Bip01_R_Foot");
        }

        private static int[] joints(SkinningData data, String a, String b, String c) {
            int[] out = new int[]{Part.index(data, a), Part.index(data, b), Part.index(data, c)};
            return out[0] >= 0 && out[1] >= 0 && out[2] >= 0 ? out : null;
        }
    }
}
