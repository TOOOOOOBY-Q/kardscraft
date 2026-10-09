package dev.invasion.kardscraft.client;

import java.nio.ByteBuffer;

/** Pure pixel conversion, kept independent of Minecraft/Win32 for headless tests. */
public final class OverlayPixels {
    private OverlayPixels() {}
    /** OpenGL bottom-up RGBA -> top-down premultiplied BGRA DIB, with nearest-neighbour resize. */
    public static void convert(ByteBuffer rgba, int sourceWidth, int sourceHeight, int[] output,
                               int width, int height, boolean captureInput) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0
                || (long)sourceWidth * sourceHeight * 4 > rgba.limit() || (long)width * height > output.length)
            throw new IllegalArgumentException("Invalid frame dimensions/buffer");
        for (int y = 0; y < height; y++) {
            int sy = sourceHeight - 1 - (int)((long)y * sourceHeight / height);
            for (int x = 0; x < width; x++) {
                int sx = (int)((long)x * sourceWidth / width);
                int i = (sy * sourceWidth + sx) * 4;
                int r = rgba.get(i) & 255, g = rgba.get(i + 1) & 255, b = rgba.get(i + 2) & 255;
                // A zero-alpha layered pixel always passes mouse clicks through on Windows.
                // Alpha 1 black keeps the board clickable in Steve mode without an opaque backdrop.
                boolean background = r >= 250 && g <= 5 && b >= 250;
                output[y * width + x] = background ? (captureInput ? 0x01000000 : 0)
                        : 0xff000000 | r << 16 | g << 8 | b;
            }
        }
    }
}
