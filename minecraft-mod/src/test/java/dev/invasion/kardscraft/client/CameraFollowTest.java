package dev.invasion.kardscraft.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraFollowTest {
    private static CameraFollow.Pose pose(double x, double yaw) { return new CameraFollow.Pose(x, 0, 40, -10, yaw, 90); }

    @Test void onlyARequestedPoseConfirmsTheFollow() {
        CameraFollow f = new CameraFollow();
        f.sent(pose(0, 0), 0);
        f.sent(pose(5, 10), 50);
        assertFalse(f.observe(new CameraFollow.Pose(0, 0, 415, -89, -90, 78), 100), "the table camera is not a follow");
        assertFalse(f.following(100));
        assertTrue(f.observe(pose(0.3, 0.4), 120), "a slightly older pose still counts (KARDS lags)");
        assertTrue(f.following(120));
        assertFalse(f.following(120 + CameraFollow.CONFIRM_WINDOW_MS), "confirmation expires");
    }

    @Test void yawWrapsAroundAndOldPosesExpire() {
        CameraFollow f = new CameraFollow();
        f.sent(pose(0, 179.6), 0);
        assertTrue(f.observe(pose(0, -179.8), 10));
        f.sent(pose(100, 0), 5000);
        assertFalse(f.observe(pose(0, 179.6), 5000), "poses older than 3 s are forgotten");
        f.reset();
        assertFalse(f.following(5000));
    }

    @Test void requestFileIsPlainJsonWithoutInjectableIds() {
        String json = CameraFollow.followJson(7, "kb-1\"x", "m 1", pose(1.5, 2), 123);
        assertTrue(json.startsWith("{\"schema\":1,\"mode\":\"follow\",\"seq\":7,\"sessionId\":\"kb-1x\",\"matchId\":\"m1\""), json);
        assertTrue(json.contains("\"x\":1.5000") && json.contains("\"fov\":90.0000"), json);
        assertEquals("{\"schema\":1,\"mode\":\"release\",\"seq\":8,\"sentAtMs\":9}\n", CameraFollow.releaseJson(8, 9));
    }
}
