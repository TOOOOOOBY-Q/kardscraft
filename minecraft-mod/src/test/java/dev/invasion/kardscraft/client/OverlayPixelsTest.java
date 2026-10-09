package dev.invasion.kardscraft.client;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/** Headless contracts: no Minecraft, OpenGL, JNA or window API is invoked. */
class OverlayPixelsTest {
    private static ByteBuffer frame() {
        return ByteBuffer.wrap(new byte[]{
            (byte)255,0,0,(byte)255, 0,(byte)255,0,(byte)255,
            (byte)255,0,(byte)255,0, 0,0,(byte)255,(byte)255});
    }
    @Test void mapsRgbaToWindowsBgraWithCorrectVerticalOrientation() {
        int[] result=new int[4];
        OverlayPixels.convert(frame(),2,2,result,2,2,false);
        assertArrayEquals(new int[]{0,0xff0000ff,0xffff0000,0xff00ff00},result);
    }
    @Test void zeroAlphaBackgroundPassesThroughInKardsMode() {
        int[] result=new int[4];
        OverlayPixels.convert(frame(),2,2,result,2,2,false);
        assertEquals(0,result[0]);
    }
    @Test void steveModeKeepsBackgroundHitTestableWithoutOpaqueColor() {
        int[] result=new int[4];
        OverlayPixels.convert(frame(),2,2,result,2,2,true);
        assertEquals(0x01000000,result[0]);
        assertEquals(0xff0000ff,result[1]);
    }
    @Test void scalesThePresentationWithoutChangingScreenCorners() {
        int[] result=new int[16];
        OverlayPixels.convert(frame(),2,2,result,4,4,false);
        assertEquals(0,result[0]); assertEquals(0,result[1]);
        assertEquals(0xff0000ff,result[2]); assertEquals(0xff00ff00,result[15]);
    }
    @Test void rejectsZeroDimensions() {
        assertThrows(IllegalArgumentException.class,() -> OverlayPixels.convert(frame(),0,2,new int[4],2,2,false));
    }
    @Test void rejectsAnUndersizedReadbackBuffer() {
        assertThrows(IllegalArgumentException.class,() -> OverlayPixels.convert(frame(),3,2,new int[4],2,2,false));
    }
    @Test void rejectsAnUndersizedDestination() {
        assertThrows(IllegalArgumentException.class,() -> OverlayPixels.convert(frame(),2,2,new int[3],2,2,false));
    }
}
