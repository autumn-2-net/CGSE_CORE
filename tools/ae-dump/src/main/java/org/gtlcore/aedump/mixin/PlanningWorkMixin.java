package org.gtlcore.aedump.mixin;

import org.gtlcore.aedump.*;
import org.cgse.core.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = GraphPlanningWork.class, remap = false)
public abstract class PlanningWorkMixin {
    @Shadow @Final private PlanningBudget budget;
    @Inject(method = "close", at = @At("HEAD"))
    private void aedump$state(CallbackInfo ci) {
        var t = Trace.of(budget); if (t != null) DumpManager.guard(() -> t.record.state(this));
    }
    @Inject(method = "limited", at = @At("HEAD"))
    private void aedump$limited(CallbackInfoReturnable<?> cir) {
        var t = Trace.of(budget); if (t != null) { t.limited = true; DumpManager.guard(() -> t.record.state(this)); }
    }
}
