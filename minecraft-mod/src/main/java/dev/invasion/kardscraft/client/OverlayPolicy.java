package dev.invasion.kardscraft.client;

/** Pure overlay decisions, kept free of Win32 so they can be unit-tested. */
public final class OverlayPolicy {
    /** How long the foreground may be something else before the overlay is hidden. */
    public static final long FOREGROUND_GRACE_NANOS = 750_000_000L;

    private OverlayPolicy() {}

    /**
     * Tracks how long the foreground has been unrelated to KARDS / Minecraft / the overlay.
     * Windows reports a NULL foreground for a moment during every focus switch; that must not hide
     * the overlay (it used to, and re-showing requested focus again, which looped into flicker).
     */
    public static final class ForegroundGrace {
        private long irrelevantSince = -1;

        /** @param relevant foreground is KARDS, Minecraft or the overlay; callers pass true for a NULL foreground. */
        public boolean shouldHide(boolean relevant, long nowNanos) {
            if (relevant) { irrelevantSince = -1; return false; }
            if (irrelevantSince < 0) irrelevantSince = nowNanos;
            return nowNanos - irrelevantSince >= FOREGROUND_GRACE_NANOS;
        }

        public void reset() { irrelevantSince = -1; }
    }

    /** Minecraft client size used as the overlay source: {@code percent} of the KARDS area, at least 320x180. */
    public static int[] sourceSize(int kardsWidth, int kardsHeight, int percent) {
        int p = Math.max(25, Math.min(100, percent));
        int w = Math.max(320, (int) Math.round(kardsWidth * p / 100.0));
        int h = Math.max(180, (int) Math.round(kardsHeight * p / 100.0));
        return new int[]{Math.min(w, kardsWidth), Math.min(h, kardsHeight)};
    }

    /**
     * Outer window rectangle {x, y, width, height} that puts a window's client area exactly on a target client area.
     * outer = GetWindowRect (left, top, right, bottom), inner = GetClientRect, origin = ClientToScreen(0, 0).
     */
    public static int[] coverClient(int targetX, int targetY, int targetWidth, int targetHeight,
                                    int[] outer, int[] inner, int[] origin) {
        int left = origin[0] - outer[0], top = origin[1] - outer[1];
        int borderWidth = (outer[2] - outer[0]) - (inner[2] - inner[0]);
        int borderHeight = (outer[3] - outer[1]) - (inner[3] - inner[1]);
        return new int[]{targetX - left, targetY - top, targetWidth + borderWidth, targetHeight + borderHeight};
    }
}
