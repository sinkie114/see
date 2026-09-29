package com.sinkie114.client.mixin;

import com.sinkie114.client.EntityNbtLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A server-side container close must not throw the user out of an entity NBT editor that holds unsynced edits. */
@Mixin(LocalPlayer.class)
public class LocalPlayerCloseMixin {
    @Inject(method = "clientSideCloseContainer", at = @At("HEAD"), cancellable = true)
    private void see$keepNbtEditor(CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof EntityNbtLayer) {
            // Same as vanilla, minus the setScreen(null): the editor's own copy stays on screen.
            LocalPlayer player = (LocalPlayer) (Object) this;
            player.containerMenu = player.inventoryMenu;
            ci.cancel();
        }
    }
}
