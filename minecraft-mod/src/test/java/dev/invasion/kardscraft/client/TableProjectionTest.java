package dev.invasion.kardscraft.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TableProjectionTest {
    @Test void centreFollowsMinecraftYawAndPitch() {
        assertArrayEquals(new double[]{0,0,1},TableProjection.direction(0,0,60,16.0/9,.5,.5),1e-9);
        assertArrayEquals(new double[]{-1,0,0},TableProjection.direction(90,0,60,16.0/9,.5,.5),1e-9);
        assertArrayEquals(new double[]{0,-1,0},TableProjection.direction(0,90,60,16.0/9,.5,.5),1e-9);
    }
    @Test void overheadCameraRetainsItsYawAtTheRightEdge() {
        double[] a=TableProjection.direction(0,90,90,1,1,.5);
        double[] b=TableProjection.direction(90,90,90,1,1,.5);
        assertTrue(a[0]<-.7); assertEquals(0,a[2],1e-9);
        assertTrue(b[2]<-.7); assertEquals(0,b[0],1e-9);
    }
    @Test void cornersAreSymmetricAndRespectViewportAspect() {
        double[] left=TableProjection.direction(0,0,90,2,0,.5);
        double[] right=TableProjection.direction(0,0,90,2,1,.5);
        assertEquals(-left[0],right[0],1e-9);
        assertEquals(left[2],right[2],1e-9);
        assertEquals(2,Math.abs(right[0]/right[2]),1e-9);
    }
    @Test void rejectsInvalidCameraData() {
        assertThrows(IllegalArgumentException.class,() -> TableProjection.direction(Double.NaN,0,60,1,.5,.5));
        assertThrows(IllegalArgumentException.class,() -> TableProjection.direction(0,0,180,1,.5,.5));
        assertThrows(IllegalArgumentException.class,() -> TableProjection.direction(0,0,60,0,.5,.5));
    }
    @Test void zeroRollMatchesTheUnrolledProjection() {
        for (double[] c:new double[][]{{0,0},{37,58},{-120,89},{200,-30}})
            for (double[] uv:new double[][]{{.5,.5},{0,0},{1,.3},{.2,1}})
                assertArrayEquals(TableProjection.direction(c[0],c[1],60,16.0/9,uv[0],uv[1]),
                        TableProjection.direction(c[0],c[1],0,60,16.0/9,uv[0],uv[1]),1e-12);
    }
    @Test void rollRotatesTheScreenAroundItsCentre() {
        double q=Math.PI/2;
        // Centre ray is the view axis whatever the roll.
        assertArrayEquals(TableProjection.direction(30,50,60,1,.5,.5),TableProjection.direction(30,50,q,60,1,.5,.5),1e-12);
        // With a square viewport, a quarter roll moves the right edge onto the bottom edge.
        assertArrayEquals(TableProjection.direction(30,50,0,60,1,.5,1),TableProjection.direction(30,50,q,60,1,1,.5),1e-12);
    }
    @Test void rollAngleRecoversTheRollThatProducedAnUpVector() {
        double[][] b=TableProjection.basis(-75,62);
        for (double roll:new double[]{0,.004,-.02,.3,-1.2}) {
            double[] up={b[2][0]*Math.cos(roll)+b[1][0]*Math.sin(roll),b[2][1]*Math.cos(roll)+b[1][1]*Math.sin(roll),
                    b[2][2]*Math.cos(roll)+b[1][2]*Math.sin(roll)};
            assertEquals(roll,TableProjection.rollAngle(-75,62,up),1e-12);
        }
    }
}
