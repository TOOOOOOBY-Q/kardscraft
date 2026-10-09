package dev.invasion.kardscraft.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPolicyTest {
    private static final long MS = 1_000_000L;

    @Test void briefLossOfFocusDoesNotHide() {
        var g = new OverlayPolicy.ForegroundGrace();
        assertFalse(g.shouldHide(false, 0));
        assertFalse(g.shouldHide(false, 700 * MS));
        assertFalse(g.shouldHide(true, 710 * MS));   // focus came back: timer resets
        assertFalse(g.shouldHide(false, 1000 * MS));
        assertFalse(g.shouldHide(false, 1700 * MS));
    }

    @Test void sustainedLossOfFocusHides() {
        var g = new OverlayPolicy.ForegroundGrace();
        assertFalse(g.shouldHide(false, 0));
        assertTrue(g.shouldHide(false, 750 * MS));
        g.reset();
        assertFalse(g.shouldHide(false, 800 * MS));
    }

    @Test void sourceIsAPercentageOfKardsWithLimits() {
        assertArrayEquals(new int[]{1280, 720}, OverlayPolicy.sourceSize(2560, 1440, 50));
        assertArrayEquals(new int[]{2560, 1440}, OverlayPolicy.sourceSize(2560, 1440, 100));
        assertArrayEquals(new int[]{640, 360}, OverlayPolicy.sourceSize(2560, 1440, 5));    // clamped to 25%
        assertArrayEquals(new int[]{320, 180}, OverlayPolicy.sourceSize(800, 600, 25));     // minimum size
        assertArrayEquals(new int[]{300, 180}, OverlayPolicy.sourceSize(300, 200, 25));     // width capped at the KARDS width
    }

    @Test void firstPersonPutsMinecraftClientAreaExactlyOnKards() {
        // Minecraft: outer (100,50)-(970,569), client 854x480 starting at screen (108,81): 8 px borders, 31 px title.
        int[] outer = {100, 50, 970, 569}, inner = {0, 0, 854, 480}, origin = {108, 81};
        int[] r = OverlayPolicy.coverClient(0, 0, 2560, 1440, outer, inner, origin);
        assertArrayEquals(new int[]{-8, -31, 2560 + 16, 1440 + 39}, r);
        // The resulting client origin is the KARDS client origin.
        assertEquals(0, r[0] + (origin[0] - outer[0]));
        assertEquals(0, r[1] + (origin[1] - outer[1]));
        // Borderless window: identical rectangle.
        assertArrayEquals(new int[]{300, 200, 1280, 720},
                OverlayPolicy.coverClient(300, 200, 1280, 720, new int[]{5, 5, 805, 605}, new int[]{0, 0, 800, 600}, new int[]{5, 5}));
    }
}
