package org.gtlcore.test;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Local-only planning inventory. Actual copied-save patterns remain untouched. */
public final class SaveCycleStockProbe {
    private static IStorageService service;
    private static IStorageProvider provider;
    public static void clear() {
        if (service != null && provider != null) service.removeGlobalStorageProvider(provider);
        service = null; provider = null;
    }
    public static void funded(IGrid grid) {
        clear();
        Map<AEKey,Long> stock = new LinkedHashMap<>();
        for (String id : List.of("gtceu:transcendentmetal_rod", "kubejs:quantum_anomaly", "gtceu:cosmicneutronium_plate", "kubejs:quantum_chromodynamic_charge"))
            stock.put(AEItemKey.of(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id)))), Long.MAX_VALUE);
        for (String id : List.of("gtceu:exciteddtec", "gtceu:exciteddtsc", "gtceu:spacetime"))
            stock.put(AEFluidKey.of(Objects.requireNonNull(ForgeRegistries.FLUIDS.getValue(new ResourceLocation(id)))), Long.MAX_VALUE);
        AEKey target = AEItemKey.of(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation("kubejs:hypercube"))));
        stock.put(target, 2L);
        stock.put(AEFluidKey.of(Objects.requireNonNull(ForgeRegistries.FLUIDS.getValue(new ResourceLocation("gtceu:dimensionallytranscendentresidue")))), 1100L);
        var frozen = Map.copyOf(stock);
        MEStorage storage = new MEStorage() {
            public Component getDescription() { return target.getDisplayName(); }
            public void getAvailableStacks(KeyCounter out) { frozen.forEach(out::add); }
            public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                if (mode == Actionable.MODULATE) throw new AssertionError("Planning-only fixture must not execute or extract");
                return Math.min(amount, frozen.getOrDefault(key,0L));
            }
        };
        service = grid.getStorageService();
        provider = mounts -> mounts.mount(storage);
        service.addGlobalStorageProvider(provider);
        System.out.println("[Save Cycle Stock] mounted finite hypercube=2 residue=1100; external ingredients=Long.MAX_VALUE");
    }
}
