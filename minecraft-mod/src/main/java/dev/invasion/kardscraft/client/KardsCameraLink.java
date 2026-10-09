package dev.invasion.kardscraft.client;

import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.KardsCraft;
import dev.invasion.kardscraft.bridge.BridgeState;
import dev.invasion.kardscraft.game.Coords;
import net.minecraft.world.phys.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks KARDS to render its own table from Steve's eyes (first person on the real board, real card models).
 * Writes bridge/KardsCraft_camera.json (a separate file; the damage bridge and its protocol are untouched) and
 * confirms only from the camera KARDS itself reports. Applying the camera inside KARDS is the KARDS-side backend's
 * job; until it does, the reported camera never matches and the caller falls back.
 */
public final class KardsCameraLink {
    private static final CameraFollow follow = new CameraFollow();
    private static final AtomicReference<String> pending = new AtomicReference<>();
    private static Thread writer;
    private static boolean requesting, loggedFollow;
    private static long lastSentMs, lastWarnMs = Long.MIN_VALUE / 2;
    private KardsCameraLink() {}

    private static Path file() { return Config.bridgePath().resolve("KardsCraft_camera.json"); }

    /** Per frame while first person is wanted: request Steve's eye pose (throttled to ~60 Hz). */
    public static void request(BridgeState st, Coords co, Vec3 eye, float yRot, float xRot, double horizontalFov) {
        long now = System.currentTimeMillis();
        if (st == null || co == null || now - lastSentMs < 16) return;
        double[] p = co.toUe(eye.x, eye.y, eye.z), r = Coords.toUeRotation(yRot, xRot);
        CameraFollow.Pose pose = new CameraFollow.Pose(p[0], p[1], p[2], r[0], r[1], horizontalFov);
        for (double v : new double[]{p[0], p[1], p[2], r[0], r[1], horizontalFov}) if (!Double.isFinite(v)) return;
        lastSentMs = now;
        if (!requesting) { requesting = true; loggedFollow = false; KardsCraft.LOG.info("KARDS camera follow requested ({})", file()); }
        follow.sent(pose, now);
        submit(CameraFollow.followJson(follow.nextSeq(), st.sessionId, st.matchId, pose, now));
    }

    /** Give the camera back to KARDS (idempotent). */
    public static void release(String reason) {
        if (!requesting) return;
        requesting = false;
        follow.reset();
        submit(CameraFollow.releaseJson(follow.nextSeq(), System.currentTimeMillis()));
        KardsCraft.LOG.info("KARDS camera released ({})", reason);
    }

    /** Feed every bridge snapshot; true while KARDS reports a camera we asked for. */
    public static boolean observe(BridgeState st) {
        long now = System.currentTimeMillis();
        if (requesting && st != null && st.cam != null) {
            var c = st.cam;
            if (follow.observe(new CameraFollow.Pose(c.x, c.y, c.z, c.pitch, c.yaw, c.fov), now) && !loggedFollow) {
                loggedFollow = true;
                KardsCraft.LOG.info("KARDS camera follow confirmed: KARDS reports the requested pose");
            }
        }
        return requesting && follow.following(now);
    }

    public static boolean requesting() { return requesting; }

    private static synchronized void submit(String json) {
        pending.set(json);
        if (writer != null) { java.util.concurrent.locks.LockSupport.unpark(writer); return; }
        writer = new Thread(KardsCameraLink::writeLoop, "KardsCraft-camera");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String json = pending.getAndSet(null);
            if (json == null) { java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L); continue; }
            Path target = file(), tmp = target.resolveSibling("KardsCraft_camera.tmp");
            try {
                Files.createDirectories(target.getParent());
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                // KARDS may be reading the file; the next pose replaces this one anyway. A lost release is retried.
                if (json.contains("\"release\"")) pending.compareAndSet(null, json);
                long now = System.currentTimeMillis();
                if (now - lastWarnMs > 5000) { lastWarnMs = now; KardsCraft.LOG.warn("KARDS camera request write: {}", e.toString()); }
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
            }
        }
    }
}
