package dev.invasion.kardscraft.client;

import dev.invasion.kardscraft.KardsCraft;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Native virtual-key codes, independent of the shared gameplay configuration. */
public final class ClientConfig {
    public static int controlKey = 0x78, viewKey = 0x79, overlayFps = 20;
    /** Minecraft render size while overlaid, as a percentage of the KARDS area (25-100). */
    public static int overlayRenderPercent = 50;
    /** Debug only: draw a thin outline where each KARDS card is, over the real board, to check alignment. Off by default. */
    public static boolean debugCardOutlines = false;
    private ClientConfig() {}
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("kardscraft-client.properties");
        Properties p = new Properties();
        try {
            if (Files.exists(path)) try (var r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(r); }
            controlKey = value(p, "controlKey", controlKey, 1, 254);
            viewKey = value(p, "viewKey", viewKey, 1, 254);
            if (viewKey == controlKey) { controlKey = 0x78; viewKey = 0x79; }
            overlayFps = value(p, "overlayFps", overlayFps, 5, 60);
            overlayRenderPercent = value(p, "overlayRenderPercent", overlayRenderPercent, 25, 100);
            // The old key (default true) is dropped: outlines must never show on the table unless explicitly debugged.
            p.remove("showCardOutlines");
            debugCardOutlines = Boolean.parseBoolean(p.getProperty("debugCardOutlines", Boolean.toString(debugCardOutlines)).trim());
            p.setProperty("controlKey", Integer.toString(controlKey));
            p.setProperty("viewKey", Integer.toString(viewKey));
            p.setProperty("overlayFps", Integer.toString(overlayFps));
            p.setProperty("overlayRenderPercent", Integer.toString(overlayRenderPercent));
            p.setProperty("debugCardOutlines", Boolean.toString(debugCardOutlines));
            Files.createDirectories(path.getParent());
            try (var w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                p.store(w, "Win32 VK codes: 120=F9, 121=F10. F10 opens the reconstructed Minecraft view.");
            }
        } catch (Exception e) { KardsCraft.LOG.warn("Cannot load client settings: {}", e.toString()); }
    }
    private static int value(Properties p, String key, int fallback, int min, int max) {
        try { int n = Integer.decode(p.getProperty(key, Integer.toString(fallback)).trim()); return Math.max(min, Math.min(max, n)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
