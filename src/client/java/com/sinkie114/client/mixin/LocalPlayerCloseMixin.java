package com.sinkie114.client.mixin;

import com.sinkie114.client.EntityDebugScreen;
import com.sinkie114.client.EntityNbtLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A container close must not throw the user out to the game while the entity editor is open:
 * the inventory tab returns to the editor, and the editor tabs keep their unsynced copy on screen.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerCloseMixin {
    @Inject(method = "clientSideCloseContainer", at = @At("HEAD"), cancellable = true)
    private void see$keepEntityEditor(CallbackInfo ci) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof EntityNbtLayer) {
            // Same as vanilla, minus the setScreen(null).
            LocalPlayer player = (LocalPlayer) (Object) this;
            player.containerMenu = player.inventoryMenu;
            ci.cancel();
            if (screen instanceof EntityDebugScreen debug) debug.showEditor();
        }
    }
}
