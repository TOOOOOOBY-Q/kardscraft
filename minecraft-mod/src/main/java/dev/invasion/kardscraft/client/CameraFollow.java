package dev.invasion.kardscraft.client;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Pure bookkeeping for the first-person camera request (no Minecraft or file access, unit-tested).
 * Remembers the poses recently sent to KARDS and decides whether the camera KARDS reports is one of them,
 * i.e. whether KARDS really renders from Steve's eyes.
 */
public final class CameraFollow {
    public static final long CONFIRM_WINDOW_MS = 1500;
    public static final double POSITION_TOLERANCE = 1.0, ANGLE_TOLERANCE = 1.0, FOV_TOLERANCE = 1.0;
    private static final int HISTORY = 120;

    public record Pose(double x, double y, double z, double pitch, double yaw, double fov) {}
    private record Sent(long atMs, Pose pose) {}

    private final ArrayDeque<Sent> sent = new ArrayDeque<>();
    private long lastMatchMs = Long.MIN_VALUE / 2;
    private long seq;

    public long nextSeq() { return ++seq; }

    public void sent(Pose pose, long nowMs) {
        sent.addLast(new Sent(nowMs, pose));
        while (sent.size() > HISTORY || (!sent.isEmpty() && nowMs - sent.peekFirst().atMs() > 3000)) sent.removeFirst();
    }

    /** Called with every camera KARDS reports. @return true if it matches a recently requested pose. */
    public boolean observe(Pose reported, long nowMs) {
        if (reported == null) return false;
        for (Sent s : sent) {
            if (matches(s.pose(), reported)) { lastMatchMs = nowMs; return true; }
        }
        return false;
    }

    public boolean following(long nowMs) { return nowMs - lastMatchMs < CONFIRM_WINDOW_MS; }

    public void reset() { sent.clear(); lastMatchMs = Long.MIN_VALUE / 2; }

    static boolean matches(Pose a, Pose b) {
        double d = Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z));
        return d <= POSITION_TOLERANCE && Math.abs(a.pitch - b.pitch) <= ANGLE_TOLERANCE
                && Math.abs(angle(a.yaw - b.yaw)) <= ANGLE_TOLERANCE && Math.abs(a.fov - b.fov) <= FOV_TOLERANCE;
    }

    static double angle(double degrees) { return ((degrees % 360) + 540) % 360 - 180; }

    /** Request file content (see reports/claude/REQUEST_FOR_CODEX_*.md). Plain JSON, one object. */
    public static String followJson(long seq, String sessionId, String matchId, Pose p, long epochMs) {
        return String.format(Locale.ROOT,
                "{\"schema\":1,\"mode\":\"follow\",\"seq\":%d,\"sessionId\":\"%s\",\"matchId\":\"%s\",\"sentAtMs\":%d,"
                        + "\"x\":%.4f,\"y\":%.4f,\"z\":%.4f,\"pitch\":%.4f,\"yaw\":%.4f,\"roll\":0,\"fov\":%.4f}\n",
                seq, clean(sessionId), clean(matchId), epochMs, p.x, p.y, p.z, p.pitch, p.yaw, p.fov);
    }

    public static String releaseJson(long seq, long epochMs) {
        return String.format(Locale.ROOT, "{\"schema\":1,\"mode\":\"release\",\"seq\":%d,\"sentAtMs\":%d}\n", seq, epochMs);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[^A-Za-z0-9._:-]", ""); }
}
