package com.milky.hunt.mixin;

import com.milky.hunt.modules.BoostedBounce;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;

@Mixin(KeyMapping.class)
public abstract class KeyBindingMixin {

    @Final
    @Shadow
    private String name;

    @Unique
    BoostedBounce efly = null;

    @Inject(at = @At("RETURN"), method = "isDown", cancellable = true)
    public void isDown(CallbackInfoReturnable<Boolean> cir)
    {
        // setting it beforehand caused a crash because meteor wasnt loaded yet
        efly = efly == null ? Modules.get().get(BoostedBounce.class) : efly;
        if (efly != null && efly.isActive() && efly.enabled() && name.equals("key.forward"))
        {
            cir.setReturnValue(true);
        }
    }
}
