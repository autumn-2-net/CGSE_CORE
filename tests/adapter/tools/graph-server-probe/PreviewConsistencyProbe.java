package org.gtlcore.test;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.menu.me.crafting.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

public final class PreviewConsistencyProbe {
    static int problems, queries, mutations, submissions, calculations;
    static ICraftingSubmitResult forcedSubmitError;
    static long requestedAmount;
    static CalculationStrategy requestedStrategy;
    static long stock;
    static boolean strict;
    static IGridNode actualNode;
    static AEKey raw=AEItemKey.of(Items.f_42416_), output=AEItemKey.of(Items.f_42597_);
    static void check(boolean ok,String name){ if(!ok)problems++; System.out.println("[Preview Consistency] "+(ok?"PASS ":"BUG ")+name); }
    static Object proxy(Class<?> type, InvocationHandler h){return Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},h);}
    public static void run(ServerLevel level, boolean fixed, IGridNode fixtureNode) {
        actualNode=fixtureNode;
        strict=fixed; problems=queries=mutations=submissions=calculations=0;
        com.neuvillette.ae2ct.Config.CONFIG.setConfig(com.electronwill.nightconfig.core.CommentedConfig.inMemory());
        var storage=new MEStorage(){
            public net.minecraft.network.chat.Component getDescription(){return raw.getDisplayName();}
            public void getAvailableStacks(KeyCounter counter){counter.add(raw,stock);}
            public long extract(AEKey key,long amount,Actionable mode,IActionSource source){queries++;if(mode==Actionable.MODULATE)mutations++; return key.equals(raw)?Math.max(0,Math.min(stock,amount)):0;}
        };
        var service=(IStorageService)proxy(IStorageService.class,(p,m,x)->{
            if(m.getName().equals("getInventory"))return storage;
            if(m.getName().equals("getCachedInventory")){var counter=new KeyCounter();storage.getAvailableStacks(counter);return counter;}
            return null;
        });
        var crafting=(ICraftingService)proxy(ICraftingService.class,(p,m,x)->switch(m.getName()) {
            case "canEmitFor" -> false;
            case "getCpus" -> com.google.common.collect.ImmutableSet.of();
            case "beginCraftingCalculation" -> {calculations++;requestedAmount=(Long)x[3];requestedStrategy=(CalculationStrategy)x[4];yield new java.util.concurrent.CompletableFuture<ICraftingPlan>();}
            case "submitJob" -> {
                submissions++;
                if(forcedSubmitError!=null) yield forcedSubmitError;
                var p1=(ICraftingPlan)x[0];var src=(IActionSource)x[4];
                long need=p1.usedItems().get(raw);
                long available=org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.limitExtraction(storage,raw,Math.min(stock,need),src);
                if(available<need)yield appeng.crafting.execution.CraftingSubmitResult.missingIngredient(new GenericStack(raw,need-available));
                stock-=need;mutations++;
                yield appeng.crafting.execution.CraftingSubmitResult.successful(null);
            }
            default -> null;
        });
        var grid=(IGrid)proxy(IGrid.class,(p,m,x)->m.getName().equals("getStorageService")?service:m.getName().equals("getCraftingService")?crafting:m.invoke(actualNode.getGrid(),x));
        var source=(IActionSource)proxy(IActionSource.class,(p,m,x)->Optional.empty());
        var max=BigInteger.valueOf(Long.MAX_VALUE);
        for(int kind=0;kind<3;kind++) {
            long in=kind==0?2:1, out=kind==0?3:1;
            var count=kind==0?max:BigInteger.valueOf(100);
            var initial=count.multiply(BigInteger.valueOf(in));
            var used=kind==0?max:BigInteger.TEN;
            var missing=initial.subtract(used);
            boolean emitted=kind==2;
            var pattern=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(raw,in)},new GenericStack[]{new GenericStack(output,out)}),level);
            var recipe=new GraphRecipe<AEKey>("r","r",List.of(new GraphRecipe.Slot<>(raw,in)),Map.of(output,out));
            var graph=new GraphPlan<AEKey>(output,count.longValueExact(),true,PlanStep.batch("r",count),Map.of("r",recipe),Map.of(raw,initial),Map.of(),emitted?Map.of():Map.of(raw,missing),emitted?GraphPlan.Result.FEASIBLE:GraphPlan.Result.MISSING_INPUT,0,0);
            var plan=new AeGraphPlan(graph,Map.of("r",pattern),emitted?Set.of(raw):Set.of(),Map.of(raw,used.longValueExact()));
            stock=kind==0?Long.MAX_VALUE:0;
            int before=queries;
            var summary=CraftingPlanSummary.fromJob(grid,source,plan);
            var rawEntry=summary.getEntries().stream().filter(e->e.getWhat().equals(raw)).findFirst().orElseThrow();
            var outputEntry=summary.getEntries().stream().filter(e->e.getWhat().equals(output)).findFirst().orElseThrow();
            check(rawEntry.getStoredAmount()==used.longValueExact(),"snapshot stored kind="+kind+" got="+rawEntry.getStoredAmount()+" expected="+used);
            check(rawEntry.getMissingAmount()==(emitted?0:ExactAmounts.capped(missing)),"snapshot missing kind="+kind+" got="+rawEntry.getMissingAmount());
            check(outputEntry.getCraftAmount()==ExactAmounts.capped(count.multiply(BigInteger.valueOf(out))),"exact output multiplication kind="+kind+" got="+outputEntry.getCraftAmount());
            check(queries==before,"summary does not resample inventory kind="+kind+" queries="+(queries-before));
            GraphPacketProbe.roundTrip(summary);
        }
        if(strict)try { menu(level,grid,storage,source); } catch(Throwable e){e.printStackTrace();throw new RuntimeException(e);}
        check(mutations==1,"only the accepted submission transfers inventory");
        System.out.println("[Preview Consistency] COMPLETE problems="+problems+" strict="+strict);
        if(strict&&problems>0)throw new AssertionError("preview inconsistencies="+problems);
    }
    static Field field(String name)throws Exception {var f=CraftConfirmMenu.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static final class Host extends appeng.api.implementations.menuobjects.ItemMenuHost implements appeng.api.storage.ISubMenuHost,appeng.api.networking.security.IActionHost {
        final IGridNode node;
        Host(net.minecraft.world.entity.player.Player player,IGrid grid){super(player,null,((AEItemKey)raw).toStack());node=(IGridNode)proxy(IGridNode.class,(p,m,x)->m.getName().equals("getGrid")?grid:m.invoke(actualNode,x));}
        public IGridNode getActionableNode(){return node;}
        public void returnToMainMenu(net.minecraft.world.entity.player.Player p,appeng.menu.ISubMenu m){}
        public net.minecraft.world.item.ItemStack getMainMenuIcon(){return getItemStack();}
        public boolean onBroadcastChanges(net.minecraft.world.inventory.AbstractContainerMenu m){return true;}
    }
    static AeGraphPlan plan(ServerLevel level,long used,long missing) {
        var pattern=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(raw,1)},new GenericStack[]{new GenericStack(output,1)}),level);
        var recipe=new GraphRecipe<AEKey>("r","r",List.of(new GraphRecipe.Slot<>(raw,1)),Map.of(output,1L));
        var graph=new GraphPlan<AEKey>(output,used+missing,true,new PlanStep.Batch("r",used+missing),Map.of("r",recipe),Map.of(raw,used+missing),Map.of(),missing==0?Map.of():Map.of(raw,missing),missing==0?GraphPlan.Result.FEASIBLE:GraphPlan.Result.MISSING_INPUT,0,0);
        return new AeGraphPlan(graph,Map.of("r",pattern),Set.of(),Map.of(raw,used));
    }
    static void menu(ServerLevel level,IGrid grid,MEStorage storage,IActionSource source)throws Exception {
        var cfg=org.gtlcore.gtlcore.config.ConfigHolder.INSTANCE;
        boolean previousLock=cfg.enableAe2ManualCraftingInventoryLock;
        boolean previousMissing=cfg.enableAe2MissingCrafting;
        var player=net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(level);
        var previousMenu=player.f_36096_;
        var menu=new CraftConfirmMenu(991,player.m_150109_(),new Host(player,grid));
        player.f_36096_=menu;
        stock=10;
        var request=new KeyCounter();request.add(raw,7);
        var lock=org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.tryAcquire(storage,request,source);
        try {
            cfg.enableAe2ManualCraftingInventoryLock=true;
            cfg.enableAe2MissingCrafting=false;
            field("whatToCraft").set(menu,output);field("amount").setInt(menu,10);
            var job=field("job");var graph=plan(level,10,0);
            job.set(menu,java.util.concurrent.CompletableFuture.completedFuture(graph));menu.m_38946_();
            var originalSummary=menu.getPlan();
            check(originalSummary!=null&&((GraphPlanMenu)menu).gtlcore$graphPlan()==graph,"conflicting reservation does not block graph preview");
            for(long available:new long[]{0,3,10,Long.MAX_VALUE,10}) { stock=available; for(int i=0;i<25;i++)menu.m_38946_(); }
            check(menu.getPlan()==originalSummary&&job.get(menu)==null&&calculations==0&&submissions==0&&mutations==0,"preview remains stable and never recalculates, submits, or extracts");
            check(((GraphPlanMenu)menu).gtlcore$planningFailure().isEmpty(),"no reservation planning error");
            check(org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.limitExtraction(storage,raw,10,source)==3,"preview does not acquire a lock");
            menu.startJob();
            check(submissions==1&&calculations==1&&mutations==0,"only clicking Start checks stock and requests one refresh");
            check(job.get(menu)!=null&&menu.getPlan()==null&&!menu.isAutoStart()&&((GraphPlanMenu)menu).gtlcore$planningFailure().isEmpty(),"short submit refreshes instead of showing a planning error or auto-starting");
            var pending=job.get(menu);menu.startJob();menu.m_38946_();
            check(job.get(menu)==pending&&calculations==1&&submissions==1,"duplicate Start does not cancel the active refresh");
            graph=plan(level,3,7);
            job.set(menu,java.util.concurrent.CompletableFuture.completedFuture(graph));menu.m_38946_();
            check(((GraphPlanMenu)menu).gtlcore$graphPlan()==graph&&menu.getPlan()!=null,"refreshed missing preview publishes normally");
            check(((GraphPlanSummaryView)menu.getPlan()).gtlcore$graphPlanId().equals(graph.id()),"menu result and summary share one identity");
            var entry=menu.getPlan().getEntries().stream().filter(e->e.getWhat().equals(raw)).findFirst().orElseThrow();
            check(entry.getStoredAmount()==3&&entry.getMissingAmount()==7,"locked preview preserves captured available/missing split");
            check(field("gtlcore$lastSentStored").get(menu)==null,"graph preview does not stream changing live inventory");
            check(org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.limitExtraction(storage,raw,10,source)==3,"missing preview does not hold inventory");
            stock=20;graph=plan(level,10,0);job.set(menu,java.util.concurrent.CompletableFuture.completedFuture(graph));menu.m_38946_();
            menu.startJob();
            check(stock==10&&submissions==2&&mutations==1&&calculations==1,"successful Start consumes exactly once without recalculation");
            check(org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.limitExtraction(storage,raw,10,source)==3,"submission lock is released while foreign reservation remains");
            forcedSubmitError=appeng.crafting.execution.CraftingSubmitResult.CPU_BUSY;
            menu.startJob();forcedSubmitError=null;
            check(calculations==1,"CPU errors are not treated as inventory changes");
            field("gtlcore$longAmount").setLong(menu,Long.MAX_VALUE);
            stock=0;menu.setAutoStart(true);job.set(menu,java.util.concurrent.CompletableFuture.completedFuture(graph));menu.m_38946_();
            check(calculations==2&&job.get(menu)!=null&&!menu.isAutoStart(),"auto-start shortage retains one refresh and disables further auto-start");
            check(requestedAmount==Long.MAX_VALUE&&requestedStrategy==CalculationStrategy.REPORT_MISSING_ITEMS,"refresh preserves long order and strategy");
            graph=plan(level,0,10);job.set(menu,java.util.concurrent.CompletableFuture.completedFuture(graph));menu.m_38946_();
            for(int i=0;i<30;i++)menu.m_38946_();
            check(calculations==2&&job.get(menu)==null&&menu.getPlan().isSimulation(),"refreshed missing preview requires confirmation and remains stable");
            stock=10;
            menu.m_6877_(player);
            check(org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.limitExtraction(storage,raw,10,source)==3,"close releases own reservation and keeps foreign reservation");
        } finally {menu.m_6877_(player);lock.close();player.f_36096_=previousMenu;cfg.enableAe2ManualCraftingInventoryLock=previousLock;cfg.enableAe2MissingCrafting=previousMissing;forcedSubmitError=null;}
    }

}
