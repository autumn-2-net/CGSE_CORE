// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.Level;
import org.gtlcore.aedump.*;
import org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter;
import org.spongepowered.asm.mixin.*;
import java.util.concurrent.Future;

@Mixin(value = CraftingService.class, priority = 500, remap = false)
public abstract class LegacyMixin {
    @Shadow @Final private IGrid grid;
    @WrapMethod(method = "beginCraftingCalculation", remap = false)
    private Future<ICraftingPlan> aedump$legacy(Level level, ICraftingSimulationRequester requester, AEKey target, long amount,
                                              CalculationStrategy strategy, Operation<Future<ICraftingPlan>> original) {
        // CGSE and execution replans are recorded by RouterMixin, exactly once.
        if (CraftingEngineRouter.useGraph()) return original.call(level, requester, target, amount, strategy);
        CaptureRecord record = null;
        try { record = DumpManager.begin(grid, level, requester.getActionSource(), target, amount, strategy,
                org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE.ae2CalculationMode.name(), false); }
        catch (RuntimeException | LinkageError e) { DumpManager.LOG.error("Could not start legacy diagnostic capture", e); }
        return DumpManager.observe(record, () -> original.call(level, requester, target, amount, strategy));
    }
}
