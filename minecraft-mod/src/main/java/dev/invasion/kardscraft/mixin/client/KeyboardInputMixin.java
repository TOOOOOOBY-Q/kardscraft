package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void kardscraft$screenRelative(CallbackInfo ci) {
        Input input=(Input)(Object)this;
        if (ViewController.interactionBlocked()) {
            input.leftImpulse=0; input.forwardImpulse=0; input.jumping=false; input.shiftKeyDown=false;
            return;
        }
        if (!ViewController.steveFromTable()) return;
        Minecraft mc=Minecraft.getInstance();
        if (mc.player==null) return;
        double d=Math.toRadians(ViewController.camYaw()-mc.player.getYRot());
        double left=input.leftImpulse,forward=input.forwardImpulse;
        input.leftImpulse=(float)(left*Math.cos(d)-forward*Math.sin(d));
        input.forwardImpulse=(float)(forward*Math.cos(d)+left*Math.sin(d));
    }
}
