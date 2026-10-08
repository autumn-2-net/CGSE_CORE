// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump.mixin;

import org.gtlcore.aedump.*;
import org.cgse.core.PlanningBudget;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(value = PlanningBudget.class, remap = false)
public abstract class BudgetMixin implements Trace.Access {
    @Unique private volatile Trace aedump$trace;
    public Trace aedump$get() { return aedump$trace; }
    public void aedump$set(Trace value) { aedump$trace = value; }
    @Inject(method = "note", at = @At("HEAD"))
    private void aedump$note(String stage, String detail, CallbackInfo ci) {
        var t = aedump$trace; if (t != null) DumpManager.guard(() -> t.event((PlanningBudget) (Object) this, stage, detail));
    }
    @Inject(method = "exhausted", at = @At("RETURN"))
    private void aedump$limit(CallbackInfoReturnable<PlanningBudget.Exhausted> cir) {
        var t = aedump$trace;
        if (t != null) { t.limited = true; DumpManager.guard(() -> t.event((PlanningBudget) (Object) this, "exhausted", Trace.stack(cir.getReturnValue()))); }
    }
}
