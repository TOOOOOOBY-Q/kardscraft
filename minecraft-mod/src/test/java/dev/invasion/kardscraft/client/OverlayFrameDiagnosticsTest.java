package dev.invasion.kardscraft.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Headless evidence tests: no native window, OpenGL context or external pixels. */
class OverlayFrameDiagnosticsTest {
    private static ByteBuffer source() {
        return ByteBuffer.wrap(new byte[]{(byte)255,0,0,0, (byte)255,0,(byte)255,0,
                0,0,(byte)255,(byte)255, (byte)255,0,(byte)255,0});
    }
    @Test void distinguishesVisibleContentFromTransparentAndHitTestOnlyPixels() {
        int[] output={0xff0000ff,0x01000000,0xffff0000,0};
        var frame=OverlayFrameDiagnostics.snapshot(source(),2,2,output,2,2);
        assertEquals(2,frame.sourceMagenta());
        assertEquals(3,frame.sourceZeroAlpha());
        assertEquals(1,frame.outputZeroAlpha());
        assertEquals(1,frame.outputInputOnly());
        assertEquals(2,frame.outputContent());
        assertEquals(0,frame.left()); assertEquals(0,frame.top());
        assertEquals(0,frame.right()); assertEquals(1,frame.bottom());
    }
    @Test void diagnosticSourceFlipsRowsAndPreservesRgbEvenWhenOriginalAlphaIsZero() {
        var frame=OverlayFrameDiagnostics.snapshot(source(),2,2,new int[4],2,2);
        assertArrayEquals(new int[]{0xff0000ff,0xffff00ff,0xffff0000,0xffff00ff},frame.sourceRgb());
        assertEquals(-1,frame.left()); assertEquals(-1,frame.bottom());
    }
    @Test void asynchronousSnapshotDoesNotReferenceMutableRenderBuffers() {
        ByteBuffer source=source(); int[] output={0xff123456,0,0,0};
        var frame=OverlayFrameDiagnostics.snapshot(source,2,2,output,2,2);
        source.put(8,(byte)12); output[0]=0;
        assertEquals(0xff0000ff,frame.sourceRgb()[0]);
        assertEquals(0xff123456,frame.compositeArgb()[0]);
    }
    @Test void savedPngsRetainOrientationAndCompositeTransparency(@TempDir Path directory) throws Exception {
        int[] output={0xff0000ff,0x01000000,0xffff0000,0};
        var frame=OverlayFrameDiagnostics.snapshot(source(),2,2,output,2,2);
        OverlayFrameDiagnostics.write(directory,"test",frame,"visible=true");
        assertEquals(0xffff0000,ImageIO.read(directory.resolve("test-mc-rgb.png").toFile()).getRGB(0,1));
        var composite=ImageIO.read(directory.resolve("test-composite-alpha.png").toFile());
        assertEquals(0x01000000,composite.getRGB(1,0));
        assertEquals(0,composite.getRGB(1,1));
        assertTrue(Files.readString(directory.resolve("test.txt")).contains("outputContent=2"));
    }
}
