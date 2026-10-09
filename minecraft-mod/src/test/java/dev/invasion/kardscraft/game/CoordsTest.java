package dev.invasion.kardscraft.game;

import dev.invasion.kardscraft.Config;
import dev.invasion.kardscraft.bridge.BridgeState;
import net.minecraft.world.phys.Vec3;
import dev.invasion.kardscraft.client.TableProjection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordsTest {
    private BridgeState state(String right) {
        return BridgeState.parse("""
            {"protocol":2,"sessionId":"session","matchId":"match","inMatch":true,"ai":true,
            "mySide":1,"turn":1,"hqLeft":{"x":100,"y":200,"z":30},"hqRight":%s,"end":1}
            """.formatted(right));
    }

    @Test void headquartersMidpointAndDistanceMatchBoardCoordinates() {
        BridgeState state = state("{\"x\":100,\"y\":1400,\"z\":30}");
        assertNotNull(state);
        Coords co = Coords.from(state);
        assertNotNull(co);
        Vec3 left = co.toMcOnFloor(100, 200, 30);
        Vec3 right = co.toMcOnFloor(100, 1400, 30);
        assertEquals(Config.boardDepthBlocks, left.distanceTo(right), 1e-9);
        assertEquals(Coords.ORIGIN_X, (left.x + right.x) / 2, 1e-9);
        assertEquals(Coords.ORIGIN_Z, (left.z + right.z) / 2, 1e-9);
        assertEquals(Coords.floorTop(), left.y);
        assertTrue(right.z > left.z, "Unreal Y maps to Minecraft Z");
    }

    @Test void zeroHeadquartersSeparationCannotProduceInvalidScale() {
        assertNull(Coords.from(state("{\"x\":100,\"y\":200,\"z\":30}")));
    }

    @Test void unrealForwardAndUpMapToMinecraftCameraAxes() {
        float[] forward = Coords.toMcRotation(0, 0);
        assertEquals(-90, forward[0], 1e-6);
        assertEquals(0, forward[1], 1e-6);
        float[] up = Coords.toMcRotation(90, 0);
        assertEquals(-90, up[1], 1e-6);
    }

    @Test void fieldOfViewHandlesAspectAndRejectsNonfiniteInputs() {
        assertEquals(90, Coords.toVerticalFov(90, 1), 1e-9);
        assertTrue(Coords.toVerticalFov(90, 16.0 / 9) < 90);
        assertEquals(70, Coords.toVerticalFov(Double.NaN, 1));
        assertEquals(70, Coords.toVerticalFov(180, 1));
        assertEquals(70, Coords.toVerticalFov(90, Double.POSITIVE_INFINITY));
    }
    @Test void unrolledUnrealUpIsTheMinecraftCameraUp() {
        for (double[] c:new double[][]{{-55,-90},{-89.5,10},{-20,135},{0,0}}) {
            float[] r=Coords.toMcRotation(c[0],c[1]);
            double[] up=Coords.toMcUp(c[0],c[1],0);
            assertArrayEquals(TableProjection.basis(r[0],r[1])[2],up,1e-6);
            assertEquals(0,TableProjection.rollAngle(r[0],r[1],up),1e-6);
        }
    }
    @Test void unrealRollBecomesAnEqualSizedMinecraftRoll() {
        float[] r=Coords.toMcRotation(-55,-90);
        for (double roll:new double[]{0.3,-0.3,2.0}) {
            double mc=TableProjection.rollAngle(r[0],r[1],Coords.toMcUp(-55,-90,roll));
            assertEquals(Math.toRadians(Math.abs(roll)),Math.abs(mc),1e-6);
        }
        // Opposite Unreal rolls give opposite Minecraft rolls.
        assertEquals(-TableProjection.rollAngle(r[0],r[1],Coords.toMcUp(-55,-90,0.3)),
                TableProjection.rollAngle(r[0],r[1],Coords.toMcUp(-55,-90,-0.3)),1e-9);
    }

    @Test void minecraftPoseConvertsBackToTheSameUnrealCamera() {
        Coords co = Coords.from(state("{\"x\":37.4,\"y\":-104.0,\"z\":3.1}"));
        assertNotNull(co);
        double[] ue = {12.5, -40.25, 43.0};
        var mc = co.toMc(ue[0], ue[1], ue[2]);
        assertArrayEquals(ue, co.toUe(mc.x, mc.y, mc.z), 1e-9);
        for (double[] r : new double[][]{{0, 0}, {35, -20}, {-170, 60}, {90, 10}, {200, -80}}) {
            double[] rot = Coords.toUeRotation(r[0], r[1]);
            float[] back = Coords.toMcRotation(rot[0], rot[1]);
            double dy = ((back[0] - r[0]) % 360 + 540) % 360 - 180;
            assertEquals(0, dy, 1e-3, "yaw " + r[0]);
            assertEquals(r[1], back[1], 1e-3, "pitch " + r[1]);
        }
        assertEquals(90, Coords.toHorizontalFov(Coords.toVerticalFov(90, 16.0 / 9), 16.0 / 9), 1e-9);
    }
}
