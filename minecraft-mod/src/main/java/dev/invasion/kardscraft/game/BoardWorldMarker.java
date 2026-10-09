package dev.invasion.kardscraft.game;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit world consent is separate from whether the previously prepared board is currently enabled. */
public final class BoardWorldMarker {
    private final Path enabled;
    private final Path prepared;
    private final Path layout;
    private final String expected;
    private final String expectedLayout;

    public BoardWorldMarker(Path worldRoot, int floorY) {
        enabled = worldRoot.resolve("kardscraft-board.enabled");
        prepared = worldRoot.resolve("kardscraft-board.prepared");
        expected = "kardscraft-board-v1\n" + floorY + "\n";
        layout = worldRoot.resolve("kardscraft-board.layout");
        expectedLayout = "board-layer-v2\n" + floorY + "\n";
    }

    /**
     * True once the visible board layer was laid at this floor height. After that the board layer is never
     * refilled, so craters from TNT or Steve stay across restarts.
     */
    public boolean isLayoutDone() throws IOException { return matches(layout, expectedLayout); }
    public void markLayoutDone() throws IOException { Files.writeString(layout, expectedLayout, StandardCharsets.UTF_8); }

    public boolean isEnabled() throws IOException { return matches(enabled); }
    public boolean isPrepared() throws IOException { return matches(prepared) || matches(enabled); }

    private boolean matches(Path file) throws IOException { return matches(file, expected); }
    private static boolean matches(Path file, String content) throws IOException {
        return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content);
    }

    public void enable() throws IOException {
        Files.writeString(prepared, expected, StandardCharsets.UTF_8);
        Files.writeString(enabled, expected, StandardCharsets.UTF_8);
    }

    public void disable() throws IOException {
        // Upgrade older worlds which only have an enabled marker before removing it.
        if (isEnabled()) Files.writeString(prepared, expected, StandardCharsets.UTF_8);
        Files.deleteIfExists(enabled);
    }
}
