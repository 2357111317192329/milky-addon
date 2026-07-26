package com.milky.hunt.mixin;

import com.milky.hunt.Addon;
import com.milky.hunt.modules.EventLog;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

@Mixin(Screen.class)
public abstract class ScreenInitMixin {
    @Shadow public int width;
    @Shadow public int height;

    @Shadow protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T drawable);

    @Inject(method = "init(II)V", at = @At("TAIL"))
    private void eventlog$addOpenFolderButton(int w, int h, CallbackInfo ci) {//MinecraftClient client, int w, int h, CallbackInfo ci
        if (!(((Object) this) instanceof DisconnectedScreen)) return;

        int bw = 200;
        int bh = 20;
        int x = (this.width - bw) / 2;
        int y = this.height / 2 + 92;

        this.addRenderableWidget(
            Button.builder(Component.literal("Open EventLog Folder"), btn -> {
                try {
                    File dir = EventLog.EventLogShots.getEventLogDir();
                    Util.getPlatform().openFile(dir);
                } catch (Throwable t) {
                    Addon.LOG.error("Failed to open EventLog folder.", t);
                }
            }).bounds(x, y, bw, bh).build()
        );
    }
}
