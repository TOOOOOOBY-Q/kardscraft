package dev.invasion.kardscraft;

import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings in config/kardscraft.properties (created with defaults on first run). */
public final class Config {
    /** Distance between the two HQs, in Minecraft blocks. Bigger = larger board in Minecraft. */
    // 8 (the lower bound) keeps Steve visible under KARDS' overhead camera: a card is ~2.6 blocks wide.
    public static double boardDepthBlocks = 8.0;
    /** Height (Y) of the board surface in the Minecraft world. */
    public static double boardY = 100.0;
    /** Raise/lower the invisible floor relative to the KARDS board, in blocks. */
    public static double floorOffsetBlocks = 0.0;
    /** Make the Minecraft window a transparent, always-on-top layer over KARDS. */
    public static boolean overlay = true;
    /** Damage dealt to every card caught in a creeper / TNT explosion. */
    public static int explosionDamage = 4;
    /** Hearts of counter damage per point of card attack (1 heart = 2 health). */
    public static double counterHeartsPerAttack = 1.0;
    /** Ticks a card ignores further hits from the same attacker. */
    public static int hitCooldownTicks = 10;
    /** Folder shared with the KARDS mod. Empty = %TEMP%. */
    public static String bridgeDir = "";

    private Config() {}

    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("kardscraft.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (Exception e) {
                KardsCraft.LOG.warn("Could not read {}: {}", file, e.toString());
            }
        }
        boardDepthBlocks = Math.clamp(d(p, "boardDepthBlocks", boardDepthBlocks), 8.0, 64.0);
        boardY = Math.clamp(d(p, "boardY", boardY), 8.0, 300.0);
        floorOffsetBlocks = Math.clamp(d(p, "floorOffsetBlocks", floorOffsetBlocks), -10.0, 10.0);
        overlay = Boolean.parseBoolean(p.getProperty("overlay", Boolean.toString(overlay)).trim());
        explosionDamage = (int) Math.clamp(d(p, "explosionDamage", explosionDamage), 1, 100);
        counterHeartsPerAttack = Math.clamp(d(p, "counterHeartsPerAttack", counterHeartsPerAttack), 0, 20);
        hitCooldownTicks = (int) Math.clamp(d(p, "hitCooldownTicks", hitCooldownTicks), 1, 100);
        bridgeDir = p.getProperty("bridgeDir", bridgeDir).trim();

        p.setProperty("boardDepthBlocks", Double.toString(boardDepthBlocks));
        p.setProperty("boardY", Double.toString(boardY));
        p.setProperty("floorOffsetBlocks", Double.toString(floorOffsetBlocks));
        p.setProperty("overlay", Boolean.toString(overlay));
        p.setProperty("explosionDamage", Integer.toString(explosionDamage));
        p.setProperty("counterHeartsPerAttack", Double.toString(counterHeartsPerAttack));
        p.setProperty("hitCooldownTicks", Integer.toString(hitCooldownTicks));
        p.setProperty("bridgeDir", bridgeDir);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                p.store(w, "KardsCraft settings (Minecraft x KARDS)");
            }
        } catch (Exception e) {
            KardsCraft.LOG.warn("Could not write {}: {}", file, e.toString());
        }
    }

    private static double d(Properties p, String key, double def) {
        try {
            double value = Double.parseDouble(p.getProperty(key, Double.toString(def)).trim());
            return Double.isFinite(value) ? value : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static Path bridgePath() {
        String dir = bridgeDir;
        if (dir == null || dir.isEmpty()) {
            dir = System.getenv("TEMP");
            if (dir == null || dir.isEmpty()) dir = System.getProperty("java.io.tmpdir");
        }
        return Path.of(dir);
    }
}
