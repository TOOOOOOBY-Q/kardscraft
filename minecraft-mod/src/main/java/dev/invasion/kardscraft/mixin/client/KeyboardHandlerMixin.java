package dev.invasion.kardscraft.mixin.client;

import dev.invasion.kardscraft.client.ViewController;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In first person, Esc goes back to the table instead of pausing the game. Menus still close with Esc. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void kardscraft$escapeFirstPerson(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS && mc.screen == null
                && window == mc.getWindow().getWindow() && ViewController.escapeFirstPerson()) ci.cancel();
    }
}
