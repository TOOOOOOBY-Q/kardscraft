package dev.invasion.kardscraft.game;

import dev.invasion.kardscraft.KardsCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only explosion bookkeeping for verification. It does not damage anything and does not submit requests:
 * vanilla's explosion already calls CardEntity.hurt for every card it reaches, and that goes through the existing
 * damage entry (BoardManager.onCardHit, one request per explosion per card). This only logs, separately,
 * (1) how many board blocks the blast destroys and (2) which card proxies were inside the blast and visible to it,
 * so "board destroyed", "damage request submitted" and "KARDS HP changed" can be checked independently.
 */
public final class BoardExplosions {
    private BoardExplosions() {}

    /** Called after Explosion.explode() computed the blocks to blow and hurt the entities. Server only. */
    public static void afterExplode(Level level, Explosion explosion) {
        if (!(level instanceof ServerLevel server) || !BoardManager.isManagedLevel(level)) return;
        Vec3 center = explosion.center();
        float radius = explosion.radius();
        int floorY = BoardManager.floorY(), boardBlocks = 0;
        for (BlockPos pos : explosion.getToBlow())
            if (pos.getY() == floorY && server.getBlockState(pos).is(KardsCraft.BOARD)) boardBlocks++;
        double reach = radius * 2.0;
        List<String> hits = new ArrayList<>();
        for (CardEntity card : server.getEntitiesOfClass(CardEntity.class, new AABB(center, center).inflate(reach + 1))) {
            double distance = Math.sqrt(card.distanceToSqr(center)) / reach;
            if (distance > 1.0) continue;
            float seen = Explosion.getSeenPercent(center, card);
            hits.add("#" + card.getCardId() + (seen > 0 ? "" : "(遮挡)") + String.format("@%.2f", (1.0 - distance) * seen));
        }
        KardsCraft.LOG.info("KardsCraft explosion at ({}, {}, {}) r={}: board blocks destroyed {}, cards in blast {}{}",
                round(center.x), round(center.y), round(center.z), radius, boardBlocks, hits.size(),
                hits.isEmpty() ? "" : " " + hits);
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }
}
