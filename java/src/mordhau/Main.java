package mordhau;

public class Main {
    public static void init() {
        CombatEngine.getInstance();
        System.out.println("[MordhauCombat] v0.2.0 initialized (Procedural 3D Kinematics + Two-Bone IK + BladeTracer)");
    }
}
