package dev.invasion.kardscraft.mixin;

import dev.invasion.kardscraft.game.BoardExplosions;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logging only (see BoardExplosions); never changes the explosion. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;

    @Inject(method = "explode", at = @At("TAIL"))
    private void kardscraft$logBoardExplosion(CallbackInfo ci) {
        try { BoardExplosions.afterExplode(level, (Explosion) (Object) this); }
        catch (RuntimeException ignored) { /* diagnostics must never break an explosion */ }
    }
}
