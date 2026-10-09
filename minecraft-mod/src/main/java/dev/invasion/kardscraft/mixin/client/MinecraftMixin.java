package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Holding attack continues mining with the table cursor released. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Redirect(method="handleKeybinds",at=@At(value="INVOKE",target="Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean kardscraft$grabbedForMining(MouseHandler handler) {
        return ViewController.tableInput() || handler.isMouseGrabbed();
    }
}
