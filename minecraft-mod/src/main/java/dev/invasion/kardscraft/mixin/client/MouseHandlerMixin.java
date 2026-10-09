package dev.invasion.kardscraft.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow @Final private Minecraft minecraft;
    @Inject(method="grabMouse",at=@At("HEAD"),cancellable=true)
    private void kardscraft$keepCursor(CallbackInfo ci) { if (ViewController.keepCursorFree()) ci.cancel(); }
    @Inject(method="onPress",at=@At("HEAD"),cancellable=true)
    private void kardscraft$tableClick(long window,int button,int action,int modifiers,CallbackInfo ci) {
        if (minecraft.screen!=null || window!=minecraft.getWindow().getWindow()) return;
        if (ViewController.interactionBlocked()) { ci.cancel(); return; }
        if (!ViewController.steveFromTable()) return;
        ViewController.refreshPick(minecraft);
        InputConstants.Key key=InputConstants.Type.MOUSE.getOrCreate(button);
        KeyMapping.set(key,action==1);
        if (action==1) KeyMapping.click(key);
        ci.cancel();
    }
}
