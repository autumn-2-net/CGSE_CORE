package org.gtlcore.aedump.mixin;

import org.gtlcore.aedump.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter$RequestWork", remap = false)
public abstract class RequestWorkMixin {
    @Unique private CaptureRecord aedump$record;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void aedump$attach(CallbackInfo ci) {
        aedump$record = DumpManager.CURRENT.get();
        if (aedump$record != null) DumpManager.guard(() -> aedump$record.attach(this));
    }
    @Inject(method = "finish", at = @At("RETURN"))
    private void aedump$finished(CallbackInfoReturnable<Boolean> cir) {
        if (aedump$record != null && cir.getReturnValue()) DumpManager.guard(() -> aedump$record.workState(this));
    }
    @Inject(method = "logFailure", at = @At("HEAD"))
    private void aedump$failed(Throwable error, CallbackInfo ci) {
        if (aedump$record != null) DumpManager.guard(() -> aedump$record.workState(this));
    }
    @Inject(method = "limited", at = @At("HEAD"))
    private void aedump$limited(CallbackInfoReturnable<?> cir) {
        if (aedump$record != null && aedump$record.trace != null) aedump$record.trace.limited = true;
    }
    @Inject(method = "close", at = @At("RETURN"))
    private void aedump$detach(CallbackInfo ci) {
        Object budget = Reflect.get(this, "budget");
        if (budget instanceof Trace.Access a) a.aedump$set(null);
        aedump$record = null;
    }
}
