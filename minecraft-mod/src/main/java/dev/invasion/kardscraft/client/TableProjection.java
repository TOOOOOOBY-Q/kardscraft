package dev.invasion.kardscraft.client;

/**
 * Pure camera basis math, including exactly vertical table cameras and camera roll.
 * Rendering (CameraMixin) and cursor picking both use {@link #rollAngle} and the same basis,
 * so a rolled KARDS camera is drawn and picked identically.
 */
public final class TableProjection {
    private TableProjection() {}

    /** Forward, zero-roll right and zero-roll up for a Minecraft yaw/pitch (degrees). Matches Camera.setRotation. */
    public static double[][] basis(double yaw, double pitch) {
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch),sy=Math.sin(y),cy=Math.cos(y),sp=Math.sin(p),cp=Math.cos(p);
        // Right comes from yaw, not forward x world-up (which degenerates at pitch +/-90).
        return new double[][]{{-sy*cp,-sp,cy*cp},{-cy,0,-sy},{-sy*sp,cp,cy*sp}};
    }

    /**
     * Roll (radians, right-hand rule about the forward axis) that turns the zero-roll up vector of
     * yaw/pitch into {@code targetUp}. 0 when targetUp is the zero-roll up.
     */
    public static double rollAngle(double yaw, double pitch, double[] targetUp) {
        double[][] b=basis(yaw,pitch);
        double onRight=dot(targetUp,b[1]), onUp=dot(targetUp,b[2]);
        if (!Double.isFinite(onRight) || !Double.isFinite(onUp) || (onRight==0 && onUp==0)) return 0;
        return Math.atan2(onRight,onUp);
    }

    public static double[] direction(double yaw,double pitch,double verticalFov,double aspect,double u,double v) {
        return direction(yaw,pitch,0,verticalFov,aspect,u,v);
    }

    /** Ray direction through viewport point (u, v) in [0,1], v downwards, for a camera rolled by {@code roll} radians. */
    public static double[] direction(double yaw,double pitch,double roll,double verticalFov,double aspect,double u,double v) {
        if (!Double.isFinite(yaw) || !Double.isFinite(pitch) || !Double.isFinite(roll) || !Double.isFinite(verticalFov)
                || !Double.isFinite(aspect) || !Double.isFinite(u) || !Double.isFinite(v)
                || verticalFov<=0 || verticalFov>=180 || aspect<=0) throw new IllegalArgumentException("Invalid projection");
        double[][] b=basis(yaw,pitch);
        double[] f=b[0],r0=b[1],u0=b[2];
        double c=Math.cos(roll),s=Math.sin(roll);
        // Rotation about f by roll (f x u0 = r0, f x r0 = -u0): r' = r0 c - u0 s, u' = u0 c + r0 s
        double[] r={r0[0]*c-u0[0]*s,r0[1]*c-u0[1]*s,r0[2]*c-u0[2]*s};
        double[] up={u0[0]*c+r0[0]*s,u0[1]*c+r0[1]*s,u0[2]*c+r0[2]*s};
        double t=Math.tan(Math.toRadians(verticalFov)/2);
        double horizontal=(2*u-1)*t*aspect, vertical=(1-2*v)*t;
        double x=f[0]+r[0]*horizontal+up[0]*vertical;
        double yy=f[1]+r[1]*horizontal+up[1]*vertical;
        double z=f[2]+r[2]*horizontal+up[2]*vertical;
        double length=Math.sqrt(x*x+yy*yy+z*z);
        return new double[]{x/length,yy/length,z/length};
    }

    private static double dot(double[] a,double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
}
