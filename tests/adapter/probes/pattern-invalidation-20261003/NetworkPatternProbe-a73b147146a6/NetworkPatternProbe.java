package local.patternchange;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.graph.AeGraphPlan;
import org.gtlcore.gtlcore.integration.ae2.graph.GraphRequestTracker;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.*;
import appeng.api.storage.IStorageProvider;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import static local.patternchange.PatternInvalidationProbe.*;

final class NetworkPatternProbe {
    final ServerLevel level;
    final BlockPos bufferPos=new BlockPos(34,180,29), powerPos=new BlockPos(33,180,29);
    final MEPatternBufferPartMachine machine;
    final Slot slot;
    final AEKey raw=key("network-raw"),alt=key("network-alt"),mid=key("network-mid"),target=key("network-target");
    final AECraftingEngine previousEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
    final AE2CalculationMode previousMode=ConfigHolder.INSTANCE.ae2CalculationMode;
    final boolean previousFallback=ConfigHolder.INSTANCE.ae2GraphFallback, previousByproducts=ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts;
    IGrid grid; IStorageProvider store; ICraftingSimulationRequester requester;
    Future<ICraftingPlan> future; appeng.crafting.CraftingCalculation legacy;
    int ticks, phase=-1, waitUntil=40, engine, passes;
    long generation;
    final String[] names={"initial insert","same-output GUI replacement","GUI remove dependency","terminal reencode reinsert","NBT in-place same-output edit","same-output amount edit","duplicate insert","remove one duplicate","remove final duplicate","restore same encoded definition"};
    NetworkPatternProbe(ServerLevel level)throws Exception {
        this.level=level;
        check(level.m_46859_(bufferPos)&&level.m_46859_(powerPos),"native fixture positions air");
        place(powerPos,"ae2:creative_energy_cell");place(bufferPos,"gtceu:me_final_pattern_buffer");
        machine=(MEPatternBufferPartMachine)com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(level,bufferPos);
        machine.setFrontFacing(net.minecraft.core.Direction.valueOf("WEST"));
        var widget=new org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget(machine.getPatternInventory(),1,0,0);
        Method onChange=machine.getClass().getDeclaredMethod("onPatternChange",int.class);onChange.setAccessible(true);
        widget.setOnPatternSlotChanged(()->{try{onChange.invoke(machine,1);}catch(Exception e){throw new RuntimeException(e);}});
        slot=(Slot)field(widget.getClass(),"slotReference").get(widget);
        ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;ConfigHolder.INSTANCE.ae2GraphFallback=false;ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=false;
        System.out.println("[Pattern Invalidation] NETWORK START actual powered buffer, natural subscriptions, no manual registry refresh");
    }
    void place(BlockPos pos,String id){var block=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation(id));check(block!=null,"registered block "+id);level.m_7731_(pos,block.m_49966_(),3);}
    ItemStack pattern(AEKey in,AEKey out,long amount,long produced){return PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(in,amount)},new GenericStack[]{new GenericStack(out,produced)});}
    boolean tick()throws Exception {
        ticks++;
        if(ticks>2000)throw new AssertionError("native network timeout phase="+phase);
        if(ticks<waitUntil)return false;
        if(phase==-1) {
            if(!machine.getMainNode().isOnline()) {
                if(ticks<120)return false;
                throw new AssertionError("actual buffer node offline grid="+machine.getMainNode().getGrid());
            }
            grid=machine.getMainNode().getGrid();
            store=mounts->mounts.mount(new MEStorage(){
                public net.minecraft.network.chat.Component getDescription(){return net.minecraft.network.chat.Component.m_237113_("local pattern network stock");}
                public void getAvailableStacks(KeyCounter out){out.add(raw,1_000_000);out.add(alt,1_000_000);}
                public long extract(AEKey k,long n,Actionable mode,IActionSource source){check(mode==Actionable.SIMULATE,"native preview read only");return k.equals(raw)||k.equals(alt)?Math.min(n,1_000_000):0;}
            });
            grid.getStorageService().addGlobalStorageProvider(store);
            final var action=(IActionSource)field(machine.getClass(),"actionSource").get(machine);
            requester=new ICraftingSimulationRequester(){public IActionSource getActionSource(){return action;}public IGridNode getGridNode(){return machine.getMainNode().getNode();}};
            phase=0; edit(); return false;
        }
        if(future!=null) {
            if(legacy!=null&&!future.isDone())legacy.simulateFor(10000);
            if(!future.isDone())return false;
            var plan=future.get();boolean missing=phase==2||phase==8;
            check((plan instanceof AeGraphPlan)==(engine==0),"native correct engine implementation");
            check(plan.simulation()==missing,"native wrong feasibility "+names[phase]+" "+plan.missingItems());
            if(!missing) {
                AEKey expected=(phase==1||phase==4)?alt:raw;
                long amount=switch(phase){case 0->20;case 1->30;case 3->40;case 4->50;default->30;};
                check(plan.usedItems().get(expected)==amount,"native wrong material "+names[phase]+" "+plan.usedItems());
            }
            passes++;System.out.println("[Pattern Invalidation] NETWORK PASS "+(engine==0?"CGSE":"MAX_FAST")+" phase="+names[phase]+" generation="+((GraphRequestTracker)grid.getCraftingService()).gtlcore$graphProviderGeneration()+" ticks="+ticks);
            future=null;legacy=null;
            if(++engine==2){engine=0;if(++phase==names.length){System.out.println("[Pattern Invalidation] DONE nativeNetworkPasses="+passes+" manualRefreshes=0");return true;} edit();return false;}
        }
        check(!((boolean)field(machine.getClass(),"needPatternSync").get(machine)),"native pending sync not consumed after ticks for "+names[phase]);
        check(((GraphRequestTracker)grid.getCraftingService()).gtlcore$graphProviderGeneration()>generation,"native notification did not increment generation for "+names[phase]);
        check(grid.getCraftingService().getCraftingFor(target).size()==1,"native root still registered");
        check(grid.getCraftingService().getCraftingFor(mid).size()==((phase==2||phase==8)?0:1),"native dependency registry stale for "+names[phase]);
        ConfigHolder.INSTANCE.ae2CraftingEngine=engine==0?AECraftingEngine.GRAPH:AECraftingEngine.LEGACY;
        if(engine==0)future=grid.getCraftingService().beginCraftingCalculation(level,requester,target,10,CalculationStrategy.REPORT_MISSING_ITEMS);
        else {legacy=new appeng.crafting.CraftingCalculation(level,grid,requester,new GenericStack(target,10),CalculationStrategy.REPORT_MISSING_ITEMS);future=CompletableFuture.supplyAsync(legacy::run);}
        return false;
    }
    void edit(){
        generation=((GraphRequestTracker)grid.getCraftingService()).gtlcore$graphProviderGeneration();
        var inv=machine.getTerminalPatternInventory();
        switch(phase){
            case 0->{inv.setItemDirect(0,pattern(mid,target,1,1));inv.setItemDirect(1,pattern(raw,mid,2,1));}
            case 1->slot.m_5852_(pattern(alt,mid,3,1));
            case 2->{slot.m_6201_(1);slot.m_6654_();}
            case 3->inv.setItemDirect(1,pattern(raw,mid,4,1));
            case 4->{machine.getPatternInventory().getStackInSlot(1).m_41751_(pattern(alt,mid,5,1).m_41783_().m_6426_());slot.m_6654_();}
            case 5->slot.m_5852_(pattern(raw,mid,6,2));
            case 6->inv.setItemDirect(2,pattern(raw,mid,6,2));
            case 7->{slot.m_6201_(1);slot.m_6654_();}
            case 8->inv.setItemDirect(2,ItemStack.f_41583_);
            case 9->inv.setItemDirect(1,pattern(raw,mid,6,2));
        }
        waitUntil=ticks+5;
    }
    void close(){
        if(grid!=null&&store!=null)grid.getStorageService().removeGlobalStorageProvider(store);
        level.m_7471_(bufferPos,false);level.m_7471_(powerPos,false);
        ConfigHolder.INSTANCE.ae2CraftingEngine=previousEngine;ConfigHolder.INSTANCE.ae2CalculationMode=previousMode;ConfigHolder.INSTANCE.ae2GraphFallback=previousFallback;ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=previousByproducts;
    }
}
