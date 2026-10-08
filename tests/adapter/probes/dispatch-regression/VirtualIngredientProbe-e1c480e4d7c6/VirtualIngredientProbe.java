package org.gtlcore.test;

import org.gtlcore.gtlcore.common.machine.VirtualIngredientSupplyMachine;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import appeng.api.config.Actionable;
import appeng.api.stacks.*;
import appeng.me.service.CraftingService;
import appeng.me.helpers.MachineSource;
import appeng.api.crafting.PatternDetailsHelper;
import java.util.*;

public final class VirtualIngredientProbe {
    static AEKey selectedKey;
    static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static VirtualIngredientSupplyMachine supply(MEPatternBufferPartMachine buffer, ItemStack value) throws Exception {
        var supply=new VirtualIngredientSupplyMachine(buffer.getHolder());
        var field=VirtualIngredientSupplyMachine.class.getDeclaredField("inventory");field.setAccessible(true);
        ((NotifiableItemStackHandler)field.get(supply)).setStackInSlot(0,value);
        var rebuild=VirtualIngredientSupplyMachine.class.getDeclaredMethod("rebuildPublishedKeys");rebuild.setAccessible(true);rebuild.invoke(supply);
        return supply;
    }
    public static void test(Level level) throws Exception {
        var buffer=(MEPatternBufferPartMachine) MetaMachine.getMachine(level,new BlockPos(66,65,0));
        var source=new MachineSource(buffer);var grid=buffer.getGrid();
        var item=stack("amethyst_shard");
        var wrapper=VirtualIngredientBehavior.wrap(item);var key=AEItemKey.of(wrapper);
        var supplier=supply(buffer,stack("amethyst_shard"));var duplicate=supply(buffer,stack("amethyst_shard"));
        check(supplier.extract(key,16,Actionable.SIMULATE,source)==16,"canonical wrapper unavailable");
        var edited=key.toStack(1);VirtualIngredientBehavior.saveFluidStorage(edited,VirtualIngredientBehavior.getFluidStorage(edited));
        check(supplier.extract(AEItemKey.of(edited),16,Actionable.SIMULATE,source)==16,"empty UI tag mismatch");
        check(supply(buffer,edited).extract(key,16,Actionable.SIMULATE,source)==16,"manually edited provider mismatch");
        check(supplier.extract(AEItemKey.of(VirtualIngredientBehavior.wrap(stack("diamond"))),1,Actionable.SIMULATE,source)==0,"wrong payload supplied");
        var unsealed=new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("gtlcore","virtual_ingredient")));
        VirtualIngredientBehavior.saveItemStorage(unsealed,VirtualIngredientBehavior.getItemStorage(wrapper));
        check(supplier.extract(AEItemKey.of(unsealed),1,Actionable.MODULATE,source)==0,"editable wrapper duplicated");
        var water=com.lowdragmc.lowdraglib.side.fluid.FluidStack.create(net.minecraftforge.registries.ForgeRegistries.FLUIDS.getValue(new net.minecraft.resources.ResourceLocation("minecraft","water")),1000);
        var fluid=VirtualIngredientBehavior.wrap(water);var fluidKey=AEItemKey.of(fluid);
        var fluidEdited=fluidKey.toStack(1);VirtualIngredientBehavior.saveItemStorage(fluidEdited,VirtualIngredientBehavior.getItemStorage(fluidEdited));
        check(supply(buffer,fluid).extract(AEItemKey.of(fluidEdited),Long.MAX_VALUE,Actionable.SIMULATE,source)==Long.MAX_VALUE,"virtual fluid UI tags mismatch");
        check(supply(buffer,fluidEdited).extract(fluidKey,1,Actionable.SIMULATE,source)==1,"edited fluid supply canonical key mismatch");
        selectedKey=AEItemKey.of(edited);
        var stacks=new KeyCounter();supplier.getAvailableStacks(stacks);duplicate.getAvailableStacks(stacks);
        System.out.println("[Virtual Probe] duplicate_count="+stacks.get(key));
        var product=AEItemKey.of(stack("paper"));
        var pattern=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(selectedKey,1)},new GenericStack[]{new GenericStack(product,1)});
        buffer.getTerminalPatternInventory().setItemDirect(0,pattern);
        // Run capture on the following command, after AE has advertised this pattern.
        grid.getStorageService().addGlobalStorageProvider(supplier);
        grid.getStorageService().addGlobalStorageProvider(duplicate);
        suppliers=List.of(supplier,duplicate);
        System.out.println("[Virtual Probe] fixture ready");
    }
    static List<VirtualIngredientSupplyMachine> suppliers=List.of();
    public static void capture(Level level) throws Exception {
        var buffer=(MEPatternBufferPartMachine) MetaMachine.getMachine(level,new BlockPos(66,65,0));
        var source=new MachineSource(buffer);var grid=buffer.getGrid();
        var key=selectedKey;
        var product=AEItemKey.of(stack("paper"));var budget=new PlanningBudget(0,10_000_000,128L<<20,()->false,System::nanoTime);
        grid.getStorageService().invalidateCache();
        System.out.println("[Virtual Probe] cached="+grid.getStorageService().getCachedInventory().get(key)+" simulated="+grid.getStorageService().getInventory().extract(key,Long.MAX_VALUE,Actionable.SIMULATE,source));
        var snapshot=new GtlPatternCatalog().capture(grid,(CraftingService)grid.getCraftingService(),level,source,product,budget);
        System.out.println("[Virtual Probe] captured="+snapshot.stock().get(key)+" catalog="+snapshot.structure().catalog().size());
        check(snapshot.stock().getOrDefault(key,0L)==Long.MAX_VALUE,"alias snapshot unavailable");
        var pattern=buffer.getAvailablePatterns().get(0);
        var method=GtlPatternCatalog.class.getDeclaredMethod("normalize",appeng.api.crafting.IPatternDetails.class,String.class,KeyCounter.class,Level.class);method.setAccessible(true);
        var recipe=((List<GraphRecipe<AEKey>>)method.invoke(null,pattern,PatternFingerprint.of(pattern),new KeyCounter(),level)).get(0);
        for(long count:new long[]{16,96,3_000_000_000L,Long.MAX_VALUE}) {
            var work=new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe)),product,count,snapshot.stock(),true,true,new PlanningBudget(0,1_000_000,()->false));while(!work.step()){}
            var plan=work.result();check(plan.feasible()&&plan.initial().get(key)==1,"virtual scaled with order "+count+" "+plan.result());
            var missing=new GraphPlanningWork<>(new GraphCompiler<>(List.of(recipe)),product,count,Map.<AEKey,Long>of(),true,true,new PlanningBudget(0,1_000_000,()->false));while(!missing.step()){}
            check(missing.result().missing().equals(Map.of(key,1L)),"virtual missing preview multiplied");
            GraphCpuHost host=(GraphCpuHost)java.lang.reflect.Proxy.newProxyInstance(GraphCpuHost.class.getClassLoader(),new Class<?>[]{GraphCpuHost.class},(p,m,a)->switch(m.getName()) {case "level"->level;case "grid"->grid;default->null;});
            var adapter=new GtlExecutionAdapter(host,null);adapter.services((CraftingService)grid.getCraftingService(),grid.getEnergyService());
            GraphJobRuntime.Adapter<AEKey> execution=new GraphJobRuntime.Adapter<>() {
                public long capacity(GraphRecipe<AEKey> r,long n){return adapter.capacity(r,n);}
                public GraphJobRuntime.Outcome push(GraphRecipe<AEKey> r,long n,Map<AEKey,Long> inputs){return adapter.push(r,n,inputs);}
                public long deliver(AEKey k,long n){return n;}
                public long refund(AEKey k,long n){return grid.getStorageService().getInventory().insert(k,n,Actionable.MODULATE,source);}
            };
            var runtime=new GraphJobRuntime<>(plan,plan.initial(),Map.of());runtime.tick(execution,0,4);
            check(runtime.dispatches()==1&&runtime.pendingRuns().isEmpty()&&runtime.expected().equals(Map.of(product,count)),"virtual runtime output obligations "+adapter.reason()+" "+runtime.expected());
            check(runtime.owned().get(key)==1,"virtual token spent");
            runtime=new GraphJobRuntime<>(GraphJobCodec.read(GraphJobCodec.write(runtime.snapshot())));runtime.tick(execution,1,4);
            check(runtime.pendingRuns().isEmpty()&&runtime.waiting(key)==0&&runtime.expected().equals(Map.of(product,count)),"virtual reload replay/return wait");
            check(runtime.accept(product,count,false)==count,"product not accepted");runtime.tick(execution,2,16);
            check(runtime.finished(),"virtual task did not settle "+runtime.reason());
            check(buffer.getMergedInternalSlot().left().keySet().stream().noneMatch(k->AEItemKey.of(k).equals(VirtualIngredientBehavior.payloadItemKey(wrapperForKey(key)))),"virtual lens entered consumed inventory");
        }
        for(var supply:suppliers)grid.getStorageService().removeGlobalStorageProvider(supply);suppliers=List.of();
        grid.getStorageService().invalidateCache();
        var offline=new GtlPatternCatalog().capture(grid,(CraftingService)grid.getCraftingService(),level,source,product,new PlanningBudget(0,1_000_000,()->false));
        check(offline.stock().getOrDefault(key,0L)==0,"offline source retained phantom inventory");
        System.out.println("[Virtual Probe] PASS item identity, sealed guard, missing=1, borrowed=1, real batch, NBT restore, no virtual output waits: "+buffer.getClass().getName());
    }
    static ItemStack wrapperForKey(AEKey key){return ((AEItemKey)key).toStack(1);}
    static ItemStack stack(String id){return new ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation("minecraft",id)));}
}
