// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.Level;
import org.gtlcore.aedump.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.GraphJobRuntime;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = CraftingEngineRouter.class, remap = false)
public abstract class RouterMixin {
    @WrapMethod(method = "begin(Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlPatternCatalog;Lappeng/api/networking/IGrid;Lappeng/me/service/CraftingService;Lnet/minecraft/world/level/Level;Lappeng/api/networking/security/IActionSource;Lappeng/api/stacks/AEKey;JLappeng/api/networking/crafting/CalculationStrategy;Lorg/cgse/core/GraphJobRuntime$ReplanCheckpoint;ZZ)Lorg/gtlcore/gtlcore/integration/ae2/graph/GraphPlanningRequest;", remap = false)
    private static GraphPlanningRequest aedump$begin(GtlPatternCatalog catalog, IGrid grid, CraftingService service, Level level,
            IActionSource source, AEKey target, long amount, CalculationStrategy strategy,
            GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve, boolean refresh, Operation<GraphPlanningRequest> original) {
        CaptureRecord record = null;
        try { record = DumpManager.begin(grid, level, source, target, amount, strategy, "CGSE", checkpoint != null); }
        catch (RuntimeException | LinkageError e) { DumpManager.LOG.error("Could not start CGSE diagnostic capture", e); }
        return DumpManager.observe(record, () -> original.call(catalog, grid, service, level, source, target, amount, strategy, checkpoint, preserve, refresh));
    }
}
