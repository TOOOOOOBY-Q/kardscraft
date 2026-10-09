package dev.invasion.kardscraft.mixin;

import dev.invasion.kardscraft.game.BoardManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Vanilla target goals may otherwise replace the assigned card with Steve on the next AI tick. */
@Mixin(Mob.class)
public abstract class MobMixin {
    @ModifyVariable(method = "setTarget", at = @At("HEAD"), argsOnly = true)
    private LivingEntity kardscraft$targetCards(LivingEntity proposed) {
        return BoardManager.boardTarget((Mob) (Object) this, proposed);
    }
}
