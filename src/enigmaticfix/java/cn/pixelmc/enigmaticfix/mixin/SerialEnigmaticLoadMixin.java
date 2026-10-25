package cn.pixelmc.enigmaticfix.mixin;

import cn.pixelmc.enigmaticfix.SerializedLoadComplete;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {
        "com.aizistral.enigmaticlegacy.EnigmaticLegacy",
        "auviotre.enigmatic.addon.EnigmaticAddons",
        "auviotre.enigmatic.delicacy.EnigmaticDelicacy"
}, remap = false)
public abstract class SerialEnigmaticLoadMixin {
    @Inject(method = "onLoadComplete(Lnet/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 1)
    private void pixelmc$deferRegistration(FMLLoadCompleteEvent event, CallbackInfo callback) {
        if (SerializedLoadComplete.defer(this, event)) callback.cancel();
    }
}
