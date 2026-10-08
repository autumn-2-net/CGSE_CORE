package org.gtlcore.test.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.TickRateModulation;
import com.almostreliable.merequester.requester.RequesterBlockEntity;
import org.gtlcore.test.RequesterStallProbe;
import org.gtlcore.test.RequesterRepairProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RequesterBlockEntity.class, remap = false)
public class RequesterTickObserver {
    @Inject(method = "tickingRequest", at = @At("RETURN"), remap = false)
    private void observe(IGridNode node, int ticks, CallbackInfoReturnable<TickRateModulation> cir) {
        RequesterStallProbe.afterRequesterTick((RequesterBlockEntity)(Object)this);
        RequesterRepairProbe.afterRequesterTick((RequesterBlockEntity)(Object)this);
    }
}
