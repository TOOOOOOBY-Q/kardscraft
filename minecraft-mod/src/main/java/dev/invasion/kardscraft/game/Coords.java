package dev.invasion.kardscraft.game;

import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.bridge.BridgeState;
import net.minecraft.world.phys.Vec3;

/**
 * Maps Unreal world coordinates (KARDS) to Minecraft world coordinates.
 *
 * Unreal: X forward, Y right, Z up (left-handed, centimetres).
 * Minecraft: X east, Y up, Z south (right-handed, blocks).
 * Swapping Unreal Y and Z flips handedness, so mc = origin + s * (ue.x, ue.z, ue.y).
 * The board centre (midpoint of the two HQs) lands on (0.5, boardY, 0.5), and the
 * scale is chosen so the two HQs are Config.boardDepthBlocks apart.
 */
public final class Coords {
    public static final double ORIGIN_X = 0.5, ORIGIN_Z = 0.5;

    public final double scale;
    private final double cx, cy, cz;

    private Coords(double scale, double cx, double cy, double cz) {
        this.scale = scale;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    public static Coords from(BridgeState st) {
        if (st == null || st.hqLeft == null || st.hqRight == null
                || st.hqLeft.length < 3 || st.hqRight.length < 3
                || !Double.isFinite(Config.boardDepthBlocks) || Config.boardDepthBlocks <= 0
                || Config.boardDepthBlocks > BoardManager.FLOOR_HALF * 1.5 || !Double.isFinite(Config.boardY)) return null;
        double[] a = st.hqLeft, b = st.hqRight;
        for (int i = 0; i < 3; i++) if (!Double.isFinite(a[i]) || !Double.isFinite(b[i])) return null;
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(dist) || dist < 1.0) return null;
        return new Coords(Config.boardDepthBlocks / dist, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2);
    }

    public static double floorTop() {
        return Math.floor(Config.boardY);
    }

    public Vec3 toMc(double x, double y, double z) {
        return new Vec3(ORIGIN_X + scale * (x - cx),
                floorTop() + scale * (z - cz) - Config.floorOffsetBlocks,
                ORIGIN_Z + scale * (y - cy));
    }

    /** Inverse of toMc: Minecraft position to Unreal {x, y, z}. */
    public double[] toUe(double mcX, double mcY, double mcZ) {
        return new double[]{cx + (mcX - ORIGIN_X) / scale, cy + (mcZ - ORIGIN_Z) / scale,
                cz + (mcY - floorTop() + Config.floorOffsetBlocks) / scale};
    }

    /** Inverse of toMcRotation: Minecraft {yRot, xRot} (degrees) to an Unreal {pitch, yaw} (degrees, roll 0). */
    public static double[] toUeRotation(double yRotDeg, double xRotDeg) {
        double y = Math.toRadians(yRotDeg), x = Math.toRadians(xRotDeg);
        double mx = -Math.sin(y) * Math.cos(x), my = -Math.sin(x), mz = Math.cos(y) * Math.cos(x);
        double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, my))));
        double yaw = Math.toDegrees(Math.atan2(mz, mx));
        return new double[]{pitch, yaw};
    }

    /** Minecraft vertical FOV to the horizontal FOV Unreal expects, for the given aspect (width / height). */
    public static double toHorizontalFov(double verticalDeg, double aspect) {
        double v = Math.toRadians(verticalDeg);
        return Math.toDegrees(2.0 * Math.atan(Math.tan(v / 2.0) * aspect));
    }

    /** A point on the board surface (Y snapped to the floor). */
    public Vec3 toMcOnFloor(double x, double y, double z) {
        Vec3 v = toMc(x, y, z);
        return new Vec3(v.x, floorTop(), v.z);
    }

    /**
     * The camera's up direction for an Unreal rotator (degrees), in Minecraft axes.
     * Unreal FRotationMatrix Z axis: (-(cr*sp*cy + sr*sy), cy*sr - cr*sp*sy, cr*cp); then (x, z, y) as for positions.
     */
    public static double[] toMcUp(double pitchDeg, double yawDeg, double rollDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg), r = Math.toRadians(rollDeg);
        double sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(y), cy = Math.cos(y), sr = Math.sin(r), cr = Math.cos(r);
        double ux = -(cr * sp * cy + sr * sy), uy = cy * sr - cr * sp * sy, uz = cr * cp;
        return new double[]{ux, uz, uy};
    }

    /** Converts an Unreal rotator (degrees) to Minecraft {yRot, xRot}. */
    public static float[] toMcRotation(double pitchDeg, double yawDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg);
        // Unreal forward vector
        double fx = Math.cos(p) * Math.cos(y), fy = Math.cos(p) * Math.sin(y), fz = Math.sin(p);
        // Same direction in Minecraft axes
        double mx = fx, my = fz, mz = fy;
        float yRot = (float) Math.toDegrees(Math.atan2(-mx, mz));
        float xRot = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, my))));
        return new float[]{yRot, xRot};
    }

    /** Unreal reports horizontal FOV; Minecraft wants vertical FOV for the given aspect (width / height). */
    public static double toVerticalFov(double horizontalDeg, double aspect) {
        if (!Double.isFinite(horizontalDeg) || !Double.isFinite(aspect)
                || horizontalDeg <= 1 || horizontalDeg >= 179 || aspect <= 0) return 70.0;
        double h = Math.toRadians(horizontalDeg);
        return Math.toDegrees(2.0 * Math.atan(Math.tan(h / 2.0) / aspect));
    }
}
