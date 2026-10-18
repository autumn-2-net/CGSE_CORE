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
import appeng.api.networking.crafting.ICraftingPlan;
import java.util.concurrent.Future;
import org.cgse.core.GraphJobRuntime;
import org.cgse.core.RequestPlanningWork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;

@Mixin(targets = "org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter", remap = false)
public abstract class RouterMixin {
    // Hosts before/after reuse handoff expose different private descriptors.
    // Exactly one wrapper must bind; incompatible hosts must fail visibly.
    @Group(name = "cgseBegin", min = 1, max = 1)
    @WrapMethod(method = "begin(Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlPatternCatalog;Lappeng/api/networking/IGrid;Lappeng/me/service/CraftingService;Lnet/minecraft/world/level/Level;Lappeng/api/networking/security/IActionSource;Lappeng/api/stacks/AEKey;JLappeng/api/networking/crafting/CalculationStrategy;Lorg/cgse/core/GraphJobRuntime$ReplanCheckpoint;ZZ)Lorg/gtlcore/gtlcore/integration/ae2/graph/GraphPlanningRequest;", remap = false, require = 0)
    @Coerce
    private static Future<ICraftingPlan> aedump$begin(@Coerce Object catalog, IGrid grid, CraftingService service, Level level,
            IActionSource source, AEKey target, long amount, CalculationStrategy strategy,
            GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve, boolean refresh, Operation<Future<ICraftingPlan>> original) {
        CaptureRecord record = null;
        try { record = DumpManager.begin(grid, level, source, target, amount, strategy, "CGSE", checkpoint != null); }
        catch (RuntimeException | LinkageError e) { DumpManager.LOG.error("Could not start CGSE diagnostic capture", e); }
        return DumpManager.observe(record, () -> original.call(catalog, grid, service, level, source, target, amount, strategy, checkpoint, preserve, refresh));
    }

    @Group(name = "cgseBegin", min = 1, max = 1)
    @WrapMethod(method = "begin(Lorg/gtlcore/gtlcore/integration/ae2/graph/GtlPatternCatalog;Lappeng/api/networking/IGrid;Lappeng/me/service/CraftingService;Lnet/minecraft/world/level/Level;Lappeng/api/networking/security/IActionSource;Lappeng/api/stacks/AEKey;JLappeng/api/networking/crafting/CalculationStrategy;Lorg/cgse/core/GraphJobRuntime$ReplanCheckpoint;ZZLorg/cgse/core/RequestPlanningWork$ReuseCandidate;)Lorg/gtlcore/gtlcore/integration/ae2/graph/GraphPlanningRequest;", remap = false, require = 0)
    @Coerce
    private static Future<ICraftingPlan> aedump$beginWithReuse(@Coerce Object catalog, IGrid grid, CraftingService service, Level level,
            IActionSource source, AEKey target, long amount, CalculationStrategy strategy,
            GraphJobRuntime.ReplanCheckpoint<AEKey> checkpoint, boolean preserve, boolean refresh,
            RequestPlanningWork.ReuseCandidate<AEKey> candidate, Operation<Future<ICraftingPlan>> original) {
        CaptureRecord record = null;
        try { record = DumpManager.begin(grid, level, source, target, amount, strategy, "CGSE", checkpoint != null); }
        catch (RuntimeException | LinkageError e) { DumpManager.LOG.error("Could not start CGSE diagnostic capture", e); }
        return DumpManager.observe(record, () -> original.call(catalog, grid, service, level, source, target, amount, strategy, checkpoint, preserve, refresh, candidate));
    }
}
