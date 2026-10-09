package dev.invasion.kardscraft.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BoardWorldMarkerTest {
    @TempDir Path world;

    @Test void ordinaryWorldNeverBecomesPreparedWithoutExplicitEnable() throws Exception {
        BoardWorldMarker marker = new BoardWorldMarker(world, 99);
        assertFalse(marker.isEnabled());
        assertFalse(marker.isPrepared());
    }

    @Test void disablePreservesConsentAndBuildingsForLaterEnable() throws Exception {
        BoardWorldMarker marker = new BoardWorldMarker(world, 99);
        marker.enable();
        Path building = world.resolve("player-building.dat");
        Files.writeString(building, "saved blocks");
        marker.disable();
        assertFalse(marker.isEnabled());
        assertTrue(marker.isPrepared(), "Re-enable must skip the first-use empty-region check");
        assertEquals("saved blocks", Files.readString(building));
        marker.enable();
        assertTrue(marker.isEnabled());
        assertEquals("saved blocks", Files.readString(building));
    }

    @Test void movedBoardHeightRequiresFreshPreparationConsent() throws Exception {
        new BoardWorldMarker(world, 99).enable();
        BoardWorldMarker moved = new BoardWorldMarker(world, 199);
        assertFalse(moved.isEnabled());
        assertFalse(moved.isPrepared());
    }

    @Test void disablingLegacyWorldMigratesItsPreparedStatus() throws Exception {
        Files.writeString(world.resolve("kardscraft-board.enabled"), "kardscraft-board-v1\n99\n");
        BoardWorldMarker marker = new BoardWorldMarker(world, 99);
        assertTrue(marker.isPrepared());
        marker.disable();
        assertFalse(marker.isEnabled());
        assertTrue(marker.isPrepared());
    }

    @Test void truncatedOrForeignMarkersCannotAuthorizeChanges() throws Exception {
        Files.writeString(world.resolve("kardscraft-board.prepared"), "kardscraft-board-v1\n");
        Files.writeString(world.resolve("kardscraft-board.enabled"), "true");
        BoardWorldMarker marker = new BoardWorldMarker(world, 99);
        assertFalse(marker.isEnabled());
        assertFalse(marker.isPrepared());
    }

    @Test void boardLayerIsLaidOnceSoCratersPersistAcrossRestarts() throws Exception {
        BoardWorldMarker marker = new BoardWorldMarker(world, 99);
        assertFalse(marker.isLayoutDone(), "old barrier-floor worlds must be migrated once");
        marker.markLayoutDone();
        assertTrue(new BoardWorldMarker(world, 99).isLayoutDone(), "a restart must not refill the board layer");
        marker.disable();
        assertTrue(marker.isLayoutDone(), "disable keeps the layout record (and the craters)");
        assertFalse(new BoardWorldMarker(world, 120).isLayoutDone(), "a moved board needs its own layer");
    }
}
