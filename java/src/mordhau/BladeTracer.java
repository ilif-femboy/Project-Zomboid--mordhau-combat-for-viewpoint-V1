package mordhau;

import zombie.CombatManager;
import zombie.Lua.LuaEventManager;
import zombie.audio.parameters.ParameterMeleeHitSurface.Material;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.CharacterStat;
import zombie.characters.HitReactionNetworkAI;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.skills.PerkFactory.Perks;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.scripting.objects.ItemTag;
import zombie.scripting.objects.MoodleType;
import zombie.ui.MoodlesUI;

import java.util.HashSet;
import java.util.Set;

/**
 * 3D Swept-Blade Collision & Hit Tracer for MordhauCombat v0.2.
 *
 * Tracks the real 3D Hilt -> Tip segment of the weapon across frames during RELEASE:
 * - Hits enemies accurately against their 3D skeleton bones (Head, Neck, Torso, Arms, Legs)
 * - Enforces realistic weapon reach (no inflated range or erratic knockback spikes)
 * - Enforces Mordhau wall hit-stop on wide swings in tight corridors while letting Thrust & Overhead pass cleanly
 * - Executes authentic PZ damage, sounds, blood splash, XP, endurance, and durability without SwipeStatePlayer!
 */
public final class BladeTracer {

    private static final float METRES_PER_LEVEL = 2.44949f;

    private final Set<IsoMovingObject> hitTargetsThisSwing = new HashSet<>();
    private int hitCountThisSwing = 0;
    private boolean damagedObjectThisSwing = false;

    private boolean hasPrevBlade = false;
    private final Vector3 prevHilt = new Vector3();
    private final Vector3 prevTip = new Vector3();
    private final Vector3 curHilt = new Vector3();
    private final Vector3 curDir = new Vector3();
    private final Vector3 curTip = new Vector3();
    private final Vector3 subHilt = new Vector3();
    private final Vector3 subTip = new Vector3();
    private final Vector3 bonePos = new Vector3();
    private final Vector2 tmpV2A = new Vector2();
    private final Vector2 tmpV2B = new Vector2();

    private static final String[] TARGET_BONES = {
            "Bip01_Head",
            "Bip01_Neck",
            "Bip01_Spine1",
            "Bip01_L_UpperArm",
            "Bip01_R_UpperArm",
            "Bip01_Pelvis",
            "Bip01_L_Thigh",
            "Bip01_R_Thigh"
    };

    private static final BodyPartType[] TARGET_PARTS = {
            BodyPartType.Head,
            BodyPartType.Neck,
            BodyPartType.Torso_Upper,
            BodyPartType.UpperArm_L,
            BodyPartType.UpperArm_R,
            BodyPartType.Torso_Lower,
            BodyPartType.UpperLeg_L,
            BodyPartType.UpperLeg_R
    };

    private static final float[] TARGET_RADII = {
            0.25f, // Head
            0.22f, // Neck
            0.31f, // Spine1
            0.24f, // L_UpperArm
            0.24f, // R_UpperArm
            0.29f, // Pelvis
            0.26f, // L_Thigh
            0.26f  // R_Thigh
    };

    public void beginRelease(IsoPlayer player, HandWeapon weapon) {
        hitTargetsThisSwing.clear();
        hitCountThisSwing = 0;
        damagedObjectThisSwing = false;
        hasPrevBlade = false;

        if (player != null && weapon != null) {
            // Ensure no leftover vanilla instant-kill flags (JawStab / KnifeDeath) ever carry into our strike!
            player.setCriticalHit(false);
            player.setVariable("ZombieHitReaction", "");
            if (player.getAttackVars() != null) {
                player.getAttackVars().closeKill = false;
            }

            String swingSound = weapon.getSwingSound();
            if (swingSound != null && !swingSound.isEmpty() && player.getEmitter() != null) {
                player.getEmitter().playSoundImpl(swingSound, null);
            }
            player.addWorldSoundUnlessInvisible(4, 4, false);
        }
    }

    public void reset() {
        hitTargetsThisSwing.clear();
        hitCountThisSwing = 0;
        damagedObjectThisSwing = false;
        hasPrevBlade = false;
    }

    /**
     * Computes real physical blade length in meters from the grip (Hilt) to the Tip.
     */
    public float computePhysicalBladeLength(CombatEngine engine, HandWeapon weapon) {
        float baseLen = weapon.weaponLength;
        if (baseLen <= 0.06f) {
            baseLen = Math.max(0.26f, weapon.getMaxRange() - 0.52f);
        } else {
            baseLen = baseLen * 1.16f;
        }
        baseLen = PZMath.clamp(baseLen, 0.25f, 1.08f);
        if (engine.isAltGripMode() && (engine.isBladeWeapon(weapon) || engine.isSpearWeapon(weapon))) {
            // Half-swording grips forward on the blade, shortening protruding reach slightly
            baseLen *= 0.88f;
        }
        return baseLen;
    }

    public void traceReleaseFrame(IsoPlayer player, HandWeapon weapon, CombatEngine engine, float progress) {
        if (player == null || weapon == null || player.isDead()) {
            return;
        }

        AttackDirection activeDir = engine.getActiveDirection();
        float minProg = activeDir.isThrust() ? 0.10f : 0.10f;
        float maxProg = activeDir.isThrust() ? 0.80f : 0.88f;
        if (progress < minProg || progress > maxProg) {
            return;
        }

        float bladeLenM = computePhysicalBladeLength(engine, weapon);
        computeCurrentWorldBlade(player, engine, bladeLenM, progress);

        int maxHits = activeDir.isThrust() ? 1 : Math.max(1, weapon.getMaxHitCount());
        if (hitCountThisSwing < maxHits) {
            int subSteps = hasPrevBlade ? 3 : 1;
            for (int step = 1; step <= subSteps; step++) {
                float alpha = (float) step / (float) subSteps;
                if (hasPrevBlade) {
                    subHilt.set(
                            lerp(prevHilt.x, curHilt.x, alpha),
                            lerp(prevHilt.y, curHilt.y, alpha),
                            lerp(prevHilt.z, curHilt.z, alpha)
                    );
                    subTip.set(
                            lerp(prevTip.x, curTip.x, alpha),
                            lerp(prevTip.y, curTip.y, alpha),
                            lerp(prevTip.z, curTip.z, alpha)
                    );
                } else {
                    subHilt.set(curHilt.x, curHilt.y, curHilt.z);
                    subTip.set(curTip.x, curTip.y, curTip.z);
                }

                // 1. Check enemy character hits along the swept 3D blade
                checkEnemyHitsOnSegment(player, weapon, engine, subHilt, subTip, maxHits);
                if (hitCountThisSwing >= maxHits) {
                    break;
                }

                // 2. Check wall / doorframe / window collision
                if (hitCountThisSwing == 0 && checkWallOrDoorCollision(player, weapon, engine, subTip, progress)) {
                    // Wall bounce stopped the swing!
                    return;
                }
            }
        }

        prevHilt.set(curHilt.x, curHilt.y, curHilt.z);
        prevTip.set(curTip.x, curTip.y, curTip.z);
        hasPrevBlade = true;
    }

    private void computeCurrentWorldBlade(IsoPlayer player, CombatEngine engine, float bladeLenM, float progress) {
        if (MordhauPoseSolver.getLatestWorldBlade(curHilt, curDir)) {
            curTip.set(
                    curHilt.x + curDir.x * bladeLenM,
                    curHilt.y + curDir.y * bladeLenM,
                    curHilt.z + curDir.z * bladeLenM
            );
            return;
        }

        // Clean fallback if skeleton pose wasn't sampled yet this frame
        float yaw = engine.getViewYawRadians(player, player.getDirectionAngleRadians());
        float pitch = PZMath.clamp(engine.getViewPitchRadians(), -1.0f, 1.0f);
        float cosY = (float) Math.cos(yaw);
        float sinY = (float) Math.sin(yaw);
        float cosP = (float) Math.cos(pitch);
        float sinP = (float) Math.sin(pitch);

        // Right vector in PZ isometric world (+X SE, +Y SW): (-sinY, cosY)
        float rightX = -sinY;
        float rightY = cosY;

        float chestZ = player.getZ() + (1.32f / METRES_PER_LEVEL);
        curHilt.set(
                player.getX() + cosY * 0.36f,
                player.getY() + sinY * 0.36f,
                chestZ + (sinP * 0.25f) / METRES_PER_LEVEL
        );

        if (engine.getActiveDirection().isThrust()) {
            curTip.set(
                    curHilt.x + cosY * cosP * bladeLenM,
                    curHilt.y + sinY * cosP * bladeLenM,
                    curHilt.z + (sinP * bladeLenM) / METRES_PER_LEVEL
            );
        } else {
            float ang = engine.getActiveAngleRad();
            float tx = -(float) Math.cos(ang);
            float ty = (float) Math.sin(ang);
            float phi = lerp(-1.15f, 1.15f, progress);
            float cPhi = (float) Math.cos(phi);
            float sPhi = (float) Math.sin(phi);

            float dirWorldX = cosY * cosP * cPhi + rightX * (tx * sPhi);
            float dirWorldY = sinY * cosP * cPhi + rightY * (tx * sPhi);
            float dirWorldZM = sinP * cPhi + ty * sPhi;
            float len = (float) Math.sqrt(dirWorldX * dirWorldX + dirWorldY * dirWorldY + dirWorldZM * dirWorldZM);
            if (len < 1.0e-4f) len = 1.0f;
            dirWorldX /= len;
            dirWorldY /= len;
            dirWorldZM /= len;

            curTip.set(
                    curHilt.x + dirWorldX * bladeLenM,
                    curHilt.y + dirWorldY * bladeLenM,
                    curHilt.z + (dirWorldZM * bladeLenM) / METRES_PER_LEVEL
            );
        }
    }

    private boolean checkWallOrDoorCollision(
            IsoPlayer player,
            HandWeapon weapon,
            CombatEngine engine,
            Vector3 tip,
            float progress
    ) {
        IsoCell cell = player.getCell();
        if (cell == null) {
            return false;
        }
        int z = PZMath.fastfloor(player.getZ());
        int px = PZMath.fastfloor(player.getX());
        int py = PZMath.fastfloor(player.getY());
        int tx = PZMath.fastfloor(tip.x);
        int ty = PZMath.fastfloor(tip.y);
        if (px == tx && py == ty) {
            return false;
        }

        IsoGridSquare sq0 = cell.getGridSquare(px, py, z);
        IsoGridSquare sq1 = cell.getGridSquare(tx, ty, z);
        if (sq0 == null || sq1 == null) {
            return false;
        }

        // Check closed unbroken Window or Door first so melee smashing windows / bashing doors works naturally!
        if (!damagedObjectThisSwing && progress >= 0.25f && progress <= 0.68f) {
            IsoWindow win = sq0.getWindowTo(sq1);
            if (win != null && !win.IsOpen() && !win.isDestroyed()) {
                damagedObjectThisSwing = true;
                win.WeaponHit(player, weapon);
                return false;
            }
            IsoObject doorObj = sq0.getDoorTo(sq1);
            if (doorObj instanceof IsoDoor door && !door.IsOpen() && !door.isDestroyed()) {
                damagedObjectThisSwing = true;
                door.WeaponHit(player, weapon);
                return false;
            }
            if (doorObj instanceof IsoThumpable thump && !thump.IsOpen() && !thump.isDestroyed()) {
                damagedObjectThisSwing = true;
                thump.WeaponHit(player, weapon);
                return false;
            }
        }

        // Mordhau Wall Hit-Stop:
        // Wide horizontal / diagonal swings clang off solid walls in tight corridors,
        // while Thrust and Overhead (|cos(angle)| < 0.42) pass cleanly alongside corridor walls!
        if (!engine.isWallHitStopEnabled()) {
            return false;
        }
        AttackDirection activeDir = engine.getActiveDirection();
        if (activeDir == AttackDirection.THRUST) {
            return false;
        }
        float horizComponent = Math.abs((float) Math.cos(engine.getActiveAngleRad()));
        if (horizComponent < 0.42f) {
            // Overhead / steep vertical strike passes cleanly along walls!
            return false;
        }
        if (progress < 0.16f || progress > 0.72f) {
            return false;
        }

        if (sq0.isWallTo(sq1)) {
            if (player.getEmitter() != null) {
                player.getEmitter().stopSoundByName(weapon.getSwingSound());
                String hitSound = weapon.getDoorHitSound();
                if (hitSound != null && !hitSound.isEmpty()) {
                    player.getEmitter().playSoundImpl(hitSound, null);
                }
            }
            player.addWorldSoundUnlessInvisible(7, 7, false);
            applyWeaponMaintenance(player, weapon, null, 0.5f);
            engine.onWallBounce(player, weapon);
            return true;
        }
        return false;
    }

    private void checkEnemyHitsOnSegment(
            IsoPlayer player,
            HandWeapon weapon,
            CombatEngine engine,
            Vector3 segA,
            Vector3 segB,
            int maxHits
    ) {
        IsoCell cell = player.getCell();
        if (cell == null) {
            return;
        }

        AttackDirection activeDir = engine.getActiveDirection();
        boolean halfSwordShort = engine.isAltGripMode() && (engine.isBladeWeapon(weapon) || engine.isSpearWeapon(weapon));
        // Strict realistic reach cap based on weapon's actual maxRange (fixes inflated distance bug!)
        float maxReach = weapon.getMaxRange(player)
                * (activeDir.isThrust() ? 1.05f : 1.00f)
                * (halfSwordShort ? 0.90f : 1.00f);
        maxReach = PZMath.clamp(maxReach, 0.78f, 1.88f);

        float yaw = engine.getViewYawRadians(player, player.getDirectionAngleRadians());
        float lookX = (float) Math.cos(yaw);
        float lookY = (float) Math.sin(yaw);

        IsoGridSquare pSq = player.getSquare();

        for (IsoMovingObject obj : cell.getObjectList()) {
            if (hitCountThisSwing >= maxHits) {
                return;
            }
            if (obj == player || !(obj instanceof IsoGameCharacter target) || target.isDead()) {
                continue;
            }
            if (hitTargetsThisSwing.contains(target)) {
                continue;
            }
            if (target instanceof IsoPlayer && !engine.shouldAllowMeleePvpOrNpc(player, target)) {
                continue;
            }
            if (Math.abs(player.getZ() - target.getZ()) > 0.65f) {
                continue;
            }

            float dx = target.getX() - player.getX();
            float dy = target.getY() - player.getY();
            float centerDist = (float) Math.sqrt(dx * dx + dy * dy);
            if (centerDist > maxReach + 0.18f) {
                continue;
            }

            // Must be within front 190-degree hemisphere (narrower for Thrust!)
            if (centerDist > 0.15f) {
                float dot = (dx * lookX + dy * lookY) / centerDist;
                float minDot = activeDir.isThrust() ? 0.55f : -0.05f;
                if (dot < minDot) {
                    continue;
                }
            }

            // Never hit through a solid wall!
            IsoGridSquare tSq = target.getSquare();
            if (pSq != null && tSq != null && pSq != tSq && pSq.isWallTo(tSq)) {
                continue;
            }

            BodyPartType hitPart = testSegmentAgainstSkeleton(target, segA, segB, activeDir);
            if (hitPart != null) {
                // Enforce exact realistic reach cap (allow slight +0.08m if hitting leaning head/limbs)
                if (centerDist <= maxReach + 0.08f) {
                    hitTargetsThisSwing.add(target);
                    hitCountThisSwing++;
                    applyDirectMeleeHit(player, target, weapon, engine, hitPart, hitCountThisSwing, centerDist, maxReach);
                }
            }
        }
    }

    private BodyPartType testSegmentAgainstSkeleton(
            IsoGameCharacter target,
            Vector3 segA,
            Vector3 segB,
            AttackDirection activeDir
    ) {
        BodyPartType bestPart = null;
        float bestMargin = -1.0f;

        AnimationPlayer animPlayer = target.hasAnimationPlayer() ? target.getAnimationPlayer() : null;
        SkinningData skin = (animPlayer != null && animPlayer.isReady()) ? animPlayer.getSkinningData() : null;

        if (skin != null && skin.boneIndices != null) {
            for (int i = 0; i < TARGET_BONES.length; i++) {
                Integer boneIdx = skin.boneIndices.get(TARGET_BONES[i]);
                if (boneIdx == null || boneIdx < 0 || boneIdx >= skin.numBones()) {
                    continue;
                }
                try {
                    Model.boneToWorldCoords(target, boneIdx, bonePos);
                    float distM = distancePointToSegmentMeters(bonePos, segA, segB);
                    float radius = TARGET_RADII[i] + (activeDir.isThrust() ? 0.04f : 0.06f);
                    if (distM <= radius) {
                        float margin = radius - distM;
                        if (margin > bestMargin) {
                            bestMargin = margin;
                            bestPart = TARGET_PARTS[i];
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (bestPart != null) {
            return bestPart;
        }

        // Capsule fallback (e.g. if character skeleton wasn't posed yet or target is lying flat on floor)
        boolean onFloor = target.isOnFloor();
        float zLow = target.getZ() + (onFloor ? 0.05f : 0.35f) / METRES_PER_LEVEL;
        float zMid = target.getZ() + (onFloor ? 0.12f : 0.95f) / METRES_PER_LEVEL;
        float zHigh = target.getZ() + (onFloor ? 0.18f : 1.48f) / METRES_PER_LEVEL;
        float capRadius = onFloor ? 0.42f : 0.32f;

        bonePos.set(target.getX(), target.getY(), zHigh);
        if (distancePointToSegmentMeters(bonePos, segA, segB) <= capRadius) {
            return onFloor ? BodyPartType.Torso_Upper : BodyPartType.Head;
        }
        bonePos.set(target.getX(), target.getY(), zMid);
        if (distancePointToSegmentMeters(bonePos, segA, segB) <= capRadius + 0.04f) {
            return BodyPartType.Torso_Upper;
        }
        bonePos.set(target.getX(), target.getY(), zLow);
        if (distancePointToSegmentMeters(bonePos, segA, segB) <= capRadius) {
            return BodyPartType.UpperLeg_R;
        }

        return null;
    }

    private static float distancePointToSegmentMeters(Vector3 p, Vector3 a, Vector3 b) {
        float abx = b.x - a.x;
        float aby = b.y - a.y;
        float abz = (b.z - a.z) * METRES_PER_LEVEL;

        float apx = p.x - a.x;
        float apy = p.y - a.y;
        float apz = (p.z - a.z) * METRES_PER_LEVEL;

        float abLenSq = abx * abx + aby * aby + abz * abz;
        float t = 0.0f;
        if (abLenSq > 1.0e-6f) {
            t = PZMath.clamp((apx * abx + apy * aby + apz * abz) / abLenSq, 0.0f, 1.0f);
        }
        float cx = apx - abx * t;
        float cy = apy - aby * t;
        float cz = apz - abz * t;
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    private void applyDirectMeleeHit(
            IsoPlayer player,
            IsoGameCharacter target,
            HandWeapon weapon,
            CombatEngine engine,
            BodyPartType hitPart,
            int hitIndex,
            float dist,
            float maxReach
    ) {
        // 1. Stop swing whoosh & play zombie/character flesh impact sound
        long hitSoundHandle = 0L;
        if (player.getEmitter() != null) {
            player.getEmitter().stopSoundByName(weapon.getSwingSound());
            player.setMeleeHitSurface(Material.Body);
            String hitSound = weapon.getZombieHitSound();
            if (hitSound != null && !hitSound.isEmpty()) {
                hitSoundHandle = player.getEmitter().playSoundImpl(hitSound, null);
            }
        }
        player.addWorldSoundUnlessInvisible(8, 8, false);

        // 2. Clear any instant-kill closeKill / JawStab state
        player.setCriticalHit(false);
        player.setVariable("ZombieHitReaction", "");
        if (player.getAttackVars() != null) {
            player.getAttackVars().closeKill = false;
        }
        target.setCloseKilled(false);

        // 3. Compute base PZ melee damage with skills, two-hand bonus, moodles & arm pain
        float minDmg = weapon.getMinDamage();
        float maxDmg = Math.max(minDmg, weapon.getMaxDamage());
        float baseDamage = Rand.Next(minDmg, maxDmg);
        baseDamage *= weapon.getDamageMod(player) * player.getHittingMod();

        boolean heldTwoHands = !weapon.isTwoHandWeapon() || player.isItemInBothHands(weapon);
        if (!heldTwoHands && maxDmg > minDmg) {
            baseDamage -= minDmg * 0.5f;
        }
        if (player.getCharacterTraits() != null) {
            baseDamage *= player.getCharacterTraits().getTraitDamageDealtReductionModifier();
        }

        // Arm pain reduction (vanilla PZ realism)
        if (player.getBodyDamage() != null) {
            float armsPain = 0.0f;
            for (int x = BodyPartType.ToIndex(BodyPartType.Hand_L); x <= BodyPartType.ToIndex(BodyPartType.UpperArm_R); x++) {
                armsPain += player.getBodyDamage().getBodyParts().get(x).getPain();
            }
            if (armsPain > 15.0f) {
                baseDamage /= PZMath.clamp(armsPain / 15.0f, 1.0f, 10.0f);
            }
        }

        // Endurance & Tiredness moodle scaling
        if (player.getMoodles() != null) {
            int endLvl = player.getMoodles().getMoodleLevel(MoodleType.ENDURANCE);
            if (endLvl == 1) baseDamage *= 0.6f;
            else if (endLvl == 2) baseDamage *= 0.3f;
            else if (endLvl == 3) baseDamage *= 0.15f;
            else if (endLvl >= 4) baseDamage *= 0.08f;

            int tiredLvl = player.getMoodles().getMoodleLevel(MoodleType.TIRED);
            if (tiredLvl == 1) baseDamage *= 0.65f;
            else if (tiredLvl == 2) baseDamage *= 0.35f;
            else if (tiredLvl >= 3) baseDamage *= 0.15f;

            if (endLvl > 0 && MoodlesUI.getInstance() != null) {
                MoodlesUI.getInstance().wiggle(MoodleType.ENDURANCE);
            }
        }

        // Cleave falloff for 2nd / 3rd target in one swing
        if (hitIndex > 1) {
            baseDamage /= (1.0f + (hitIndex - 1) * 0.55f);
        }
        baseDamage = Math.max(0.06f, baseDamage);

        // 4. Apply Mordhau directional, weapon-type, headshot, armor & crit rules!
        int partIdx = BodyPartType.ToIndex(hitPart);
        float finalDamage = engine.computeDirectMeleeHitDamage(player, target, weapon, hitPart, partIdx, baseDamage);

        // 5. Orient hit direction & zombie hit flags cleanly
        tmpV2A.set(target.getX() - player.getX(), target.getY() - player.getY());
        if (tmpV2A.getLength() > 0.001f) {
            tmpV2A.normalize();
        } else {
            player.getLookVector(tmpV2A);
        }
        tmpV2B.set(target.getForwardDirectionX(), target.getForwardDirectionY());
        float hitDot = tmpV2A.dot(tmpV2B);
        target.setHitFromAngle(hitDot);

        boolean hitHead = (hitPart == BodyPartType.Head || hitPart == BodyPartType.Neck);
        boolean hitLegs = (partIdx >= BodyPartType.ToIndex(BodyPartType.Groin) && partIdx <= BodyPartType.ToIndex(BodyPartType.Foot_R));

        if (target instanceof IsoZombie zomb) {
            zomb.setHitFromBehind(player.isBehind(zomb));
            zomb.setPlayerAttackPosition(zomb.testDotSide(player));
            zomb.setHitHeadWhileOnFloor(hitHead ? 1 : 0);
            zomb.setHitLegsWhileOnFloor(hitLegs);
        }

        target.helmetFall(hitHead);

        // 6. Controlled, physically grounded knockback (NO erratic catapulting!)
        AttackDirection activeDir = engine.getActiveDirection();
        boolean bluntThrust = activeDir.isThrust() && engine.isBluntOrAxeWeapon(weapon) && !engine.isAltGripMode();
        player.knockbackAttackMod = bluntThrust ? 0.65f : (activeDir.isThrust() ? 0.35f : 0.52f);
        float controlledModDelta = PZMath.clamp((dist / Math.max(0.8f, maxReach)) * 0.55f, 0.28f, 0.62f);

        // Mark internal guard so Patches.Patch_IsoGameCharacter_Hit doesn't double-multiply damage!
        engine.setApplyingTracerHit(true);
        float dealtHitDamage;
        try {
            dealtHitDamage = target.Hit(weapon, player, finalDamage, false, controlledModDelta);
        } finally {
            engine.setApplyingTracerHit(false);
        }

        // 7. Endurance, Stamina refund on hit, XP, Blood Splash & Durability
        CombatManager.getInstance().applyMeleeEnduranceLoss(player, target, weapon, dealtHitDamage);
        engine.onSuccessfulMeleeHit(player, target, weapon, finalDamage);

        if (!GameClient.client && !GameServer.server) {
            LuaEventManager.triggerEvent("OnWeaponHitXp", player, weapon, target, finalDamage, 1);
        }

        if (dist < 1.55f) {
            player.addBlood(null, false, false, false);
        }
        if (target.isDead()) {
            if (player.getStats() != null) {
                player.getStats().remove(CharacterStat.STRESS, 0.02f);
            }
        } else if (!(target instanceof IsoPlayer)) {
            CombatManager.getInstance().splash(target, weapon, player);
        }

        applyWeaponMaintenance(player, weapon, target, 1.0f);

        // 8. Valid PZ B42 HitReaction (NEVER "default"!)
        if (!target.isDead()) {
            if (bluntThrust && target instanceof IsoZombie zomb && !zomb.isOnFloor()) {
                zomb.setEatBodyTarget(null, false);
                zomb.setAttackOutcome("fail");
                zomb.setStaggerBack(true);
                zomb.setHitReaction("BiteDefended");
            } else {
                HitReactionNetworkAI.calculateHitReactionFromWeapon(player, target, weapon);
                String hr = target.getHitReaction();
                if ("default".equalsIgnoreCase(hr)) {
                    target.setHitReaction("BiteDefended");
                }
            }
        }
    }

    private void applyWeaponMaintenance(IsoPlayer player, HandWeapon weapon, IsoObject hitObj, float breakMult) {
        if (GameClient.client || weapon == null || player == null) {
            return;
        }
        try {
            weapon.checkSyncItemFields(weapon.damageCheck(0, breakMult));
            if (Rand.NextBool(3) && !weapon.hasTag(ItemTag.NO_MAINTENANCE_XP)) {
                float amount = 1.5f;
                if (weapon.getConditionLowerChance() > 10) {
                    amount = amount * 10.0f / weapon.getConditionLowerChance();
                }
                if (player.getXp() != null) {
                    player.getXp().AddXP(Perks.Maintenance, amount);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
