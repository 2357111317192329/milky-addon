package com.milky.hunt.mixin;

import com.milky.hunt.modules.BoostedBounce;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.player.ChatUtils.info;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin
{
    @Shadow
    private int noJumpDelay;

    @Shadow
    public abstract Brain<?> getBrain();

    BoostedBounce efly = Modules.get().get(BoostedBounce.class);

    @Inject(at = @At("HEAD"), method = "aiStep()V")
    private void tickMovement(CallbackInfo ci)
    {
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled())
        {
            this.noJumpDelay = 0;
        }
    }

    @Inject(at = @At("HEAD"), method = "isFallFlying()Z", cancellable = true)
    private void isFallFlying(CallbackInfoReturnable<Boolean> cir)
    {
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled())
        {
            cir.setReturnValue(true);
        }
    }
}
