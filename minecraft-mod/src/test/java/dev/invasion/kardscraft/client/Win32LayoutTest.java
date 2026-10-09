package dev.invasion.kardscraft.client;

import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Checks native ABI layout only. Does not load user32 or create a window. */
class Win32LayoutTest {
    @Test void dibHeaderUses32BitPixelsAndTopDownRows() {
        Win32.BitmapInfo info=new Win32.BitmapInfo(1920,1080);
        assertEquals(44,info.size());
        info.write();
        assertEquals(40,info.getPointer().getInt(0));
        assertEquals(1920,info.getPointer().getInt(4));
        assertEquals(-1080,info.getPointer().getInt(8));
        assertEquals(1,info.getPointer().getShort(12));
        assertEquals(32,info.getPointer().getShort(14));
    }
    @Test void blendFunctionUsesPerPixelAlpha() {
        Win32.Blend blend=new Win32.Blend();
        assertEquals(4,blend.size());
        blend.write();
        assertEquals(255,blend.getPointer().getByte(2)&255);
        assertEquals(1,blend.getPointer().getByte(3));
    }
    @Test void pointContainsTwo32BitCoordinates() {
        Win32.Point point=new Win32.Point(-1200,300);
        assertEquals(8,point.size());
        point.write();
        assertEquals(-1200,point.getPointer().getInt(0));
        assertEquals(300,point.getPointer().getInt(4));
    }
    @Test void topmostHandleIsMinusOneAtFullPointerWidth() {
        // (HWND)-1 must be 0xffffffffffffffff on 64-bit Windows, not 0x00000000ffffffff (error 1400).
        assertEquals(-1L,Pointer.nativeValue(Win32.HWND_TOPMOST));
    }
}
