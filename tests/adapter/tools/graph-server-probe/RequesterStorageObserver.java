package org.gtlcore.test.mixin;

import appeng.api.networking.IStackWatcher;
import appeng.api.stacks.AEKey;
import com.almostreliable.merequester.requester.StorageManager;
import org.gtlcore.test.RequesterStallProbe;
import org.gtlcore.test.RequesterRepairProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StorageManager.class, remap = false)
public class RequesterStorageObserver {
    @Inject(method = "onStackChange", at = @At("RETURN"), remap = false)
    private void observe(AEKey key, long amount, CallbackInfo ci) {
        RequesterStallProbe.storageChanged((StorageManager)(Object)this, key);
        RequesterRepairProbe.storageChanged((StorageManager)(Object)this, key);
    }
    @Inject(method = "updateWatcher", at = @At("RETURN"), remap = false)
    private void bound(IStackWatcher watcher, CallbackInfo ci) {
        RequesterStallProbe.watcherBound((StorageManager)(Object)this);
        RequesterRepairProbe.watcherBound((StorageManager)(Object)this);
    }
}
