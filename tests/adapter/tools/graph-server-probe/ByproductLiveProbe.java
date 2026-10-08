package org.gtlcore.test;

import org.gtlcore.gtlcore.common.machine.multiblock.electric.TransfiniteComputationArrayMachine;
import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;

import java.util.*;

/** Local Forge fixture: real registration, snapshot invalidation, CPU/NBT/return routing. */
public final class ByproductLiveProbe {

    static ReturnRoutingProbe fixture;
    static boolean original, turnedOff;

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void start(Level level) throws Exception {
        check(fixture == null, "already active");
        TransfiniteComputationArrayMachine array = null;
        for (int x = 100; x < 122; x++) for (int y = 65; y < 88; y++) for (int z = 0; z < 22; z++)
            if (MetaMachine.getMachine(level, new BlockPos(x, y, z)) instanceof TransfiniteComputationArrayMachine a) array = a;
        check(array != null && array.isOperational(), "formed array required");
        original = ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts;
        fixture = new ReturnRoutingProbe(array);
        fixture.patterns.clear();
        Map<AEKey, Long> outputs = new LinkedHashMap<>();
        outputs.put(fixture.plate, 1L);
        outputs.put(fixture.target, 1L);
        fixture.pattern(Map.of(fixture.raw, 1L), outputs);
        fixture.install();
        try {
            var service = (CraftingService) fixture.grid.getCraftingService();
            check(service.getCraftingFor(fixture.target).isEmpty(), "fixture side output was primary registered");
            var catalog = new GtlPatternCatalog();
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = false;
            check(capture(catalog, level).structure().catalog().size() == 0, "default path unexpectedly discovered side output");
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = true;
            check(capture(catalog, level).structure().catalog().size() == 1, "enabled discovery lost whole recipe");
            check(capture(catalog, level).cacheHit(), "enabled catalog not cached");
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = false;
            check(capture(catalog, level).structure().catalog().size() == 0, "enabled cache leaked across toggle");
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = true;
            fixture.registry.removeProvider(fixture.mounted);
            check(capture(catalog, level).structure().catalog().size() == 0, "removed side source cached");
            fixture.registry.addProvider(fixture.mounted);
            check(capture(catalog, level).structure().catalog().size() == 1, "readded side source absent");
            var pending = new GtlPatternCatalog().begin(fixture.grid, service, level, array.getActionSource(), fixture.target, budget());
            pending.step();
            fixture.registry.removeProvider(fixture.mounted);
            fixture.registry.addProvider(fixture.mounted);
            while (!pending.step()) {}
            check(pending.result().structure().catalog().size() == 1, "sliced index failed across provider revision");
            check(service.getCraftingFor(fixture.target).isEmpty(), "legacy index was changed");
            PlanningUiProbe.run((net.minecraft.server.level.ServerLevel) level, fixture);
            turnedOff = false;
            ReturnRoutingProbe.log("[Byproduct Live] snapshots PASS off/on/cache/removal/readd/mid-index revision; starting 16 joint-output orders");
        } catch (Throwable error) {
            fixture.remove();
            fixture = null;
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = original;
            throw error;
        }
    }

    static PlanningBudget budget() {
        return new PlanningBudget(0, 2_000_000, () -> false);
    }

    static GtlPatternCatalog.Snapshot capture(GtlPatternCatalog c, Level level) {
        return c.capture(fixture.grid, (CraftingService) fixture.grid.getCraftingService(), level, fixture.array.getActionSource(), fixture.target, budget());
    }

    public static void tick() {
        if (fixture == null) return;
        try {
            if (!turnedOff && fixture.orders.size() == 16 && fixture.orders.stream().allMatch(o -> o.link != null)) {
                ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = false;
                turnedOff = true;
            }
            if (fixture.advance()) {
                check(turnedOff, "live toggle coverage absent");
                fixture.remove();
                fixture = null;
                ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = original;
                ReturnRoutingProbe.log("[Byproduct Live] ALL PASS: 16 orders, primary+side returns, NBT, retained tasks after disabling discovery");
            }
        } catch (Throwable error) {
            ReturnRoutingProbe.log("[Byproduct Live] FAIL " + error);
            error.printStackTrace();
            try {
                fixture.orders.forEach(o -> { if (o.link != null) o.link.cancel(); });
                fixture.remove();
            } catch (Exception ignored) {}
            fixture = null;
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts = original;
        }
    }
}
