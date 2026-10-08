package org.gtlcore.localsubmit;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.*;
import appeng.api.networking.storage.IStorageService;
import appeng.api.parts.IPart;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;
import appeng.menu.me.crafting.*;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

@Mod("gtlsubmissionprobe")
public class SubmissionProbe {
    static final AEKey KEY=AEItemKey.of(Items.f_42597_);
    static final long AMOUNT=5_000_000_123L;
    static int tests;
    public SubmissionProbe(){MinecraftForge.EVENT_BUS.addListener(this::register);}
    void register(RegisterCommandsEvent e){
        for(boolean fixed:new boolean[]{false,true})e.getDispatcher().register(Commands.m_82127_("submitprobe"+fixed).requires(s->s.m_6761_(4)).executes(c->{
            try {run(c.getSource().m_81372_(),fixed);return 1;}
            catch(Throwable t){System.out.println("[Submission Probe] FAIL "+t);t.printStackTrace();return 0;}
        }));
    }
    static void run(ServerLevel level, boolean fixed)throws Exception {
        boolean lock=ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock;
        try {
            ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock=false;
            if(!fixed){
                var f=new Fixture(level,1);f.menu.startJob();
                check(f.starts==1&&f.calculations==1,"baseline starts refresh");
                check(f.menu.submitError.result()==null,"baseline swallowed error");
                f.future.complete(plan(false,AMOUNT));f.menu.m_38946_();
                check(f.starts==1&&f.returned==0&&f.menu.getPlan()!=null,"baseline needs another click");
                System.out.println("[Submission Probe] BASELINE REPRO: first click erased error, recalculated, did not submit without second click");
                return;
            }
            for(boolean locked:new boolean[]{false,true}){
                ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock=locked;
                var direct=new Fixture(level,0);direct.menu.startJob();
                check(direct.starts==1&&direct.calculations==0&&direct.returned==1,"direct success lock="+locked);
                var f=new Fixture(level,1);f.menu.startJob();
                check(f.starts==1&&f.calculations==1&&f.pending(),"first shortage keeps confirmation lock="+locked);
                var pending=f.future;f.menu.startJob();f.menu.startJob();
                check(f.starts==1&&f.future==pending&&f.calculations==1,"extra clicks ignored");
                check(f.lastAmount==AMOUNT&&f.lastStrategy==CalculationStrategy.REPORT_MISSING_ITEMS,"exact long request retained");
                check(f.lastRequester.getClass().getName().contains("SubmissionRefresh"),"refresh requester selects current extraction");
                f.future.complete(plan(false,AMOUNT));f.menu.m_38946_();
                check(f.starts==2&&f.returned==1&&!f.pending(),"one click resumes success lock="+locked);
                check(!org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock.hasReservations(f.inventory),"no reservation leak");
            }
            ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock=false;
            var twice=new Fixture(level,2);twice.menu.startJob();
            for(int i=0;i<2;i++){twice.future.complete(plan(false,AMOUNT));twice.menu.m_38946_();}
            check(twice.starts==3&&twice.calculations==2&&twice.returned==1,"two racing shortages, one confirmation");
            var forever=new Fixture(level,99);forever.menu.startJob();
            for(int i=0;i<2;i++){forever.future.complete(plan(false,AMOUNT));forever.menu.m_38946_();}
            check(forever.starts==3&&forever.calculations==2&&!forever.pending(),"bounded automatic retries");
            check(forever.menu.submitError.result().errorCode()==CraftingSubmitErrorCode.MISSING_INGREDIENT,"bounded failure visible");
            forever.menu.m_38946_();check(forever.starts==3,"no tick retry loop");
            var missing=new Fixture(level,1);missing.menu.startJob();missing.future.complete(plan(true,AMOUNT));missing.menu.m_38946_();
            check(missing.starts==1&&!missing.pending()&&missing.menu.getPlan().isSimulation(),"missing preview requires explicit approval");
            var cancelled=new Fixture(level,1);cancelled.menu.startJob();var old=cancelled.future;cancelled.menu.m_6877_(cancelled.player);
            check(old.isCancelled()&&!cancelled.pending(),"close cancels refresh");
            cancelled.menu.m_38946_();check(cancelled.starts==1,"closed confirmation cannot submit");
            var failed=new Fixture(level,1);failed.menu.startJob();failed.future.completeExceptionally(new IllegalStateException("probe calculation failure"));failed.menu.m_38946_();
            check(!failed.pending()&&failed.starts==1,"calculation exception releases submission state");
            for(var error:List.of(CraftingSubmitResult.CPU_BUSY,CraftingSubmitResult.INCOMPLETE_PLAN)){
                var rejected=new Fixture(level,0);rejected.forced=error;rejected.menu.startJob();
                check(rejected.calculations==0&&!rejected.pending()&&rejected.menu.submitError.result().errorCode()==error.errorCode(),"unrelated error not retried");
            }
            var smaller=new Fixture(level,1);set(smaller.menu,"result",plan(false,42));smaller.menu.startJob();
            check(smaller.lastAmount==42,"previous CRAFT_LESS confirmation retains reviewed amount");
            smaller.menu.m_6877_(smaller.player);
            currentInventory(level);
            System.out.println("[Submission Probe] ALL PASS assertions="+tests);
        } finally {ConfigHolder.INSTANCE.enableAe2ManualCraftingInventoryLock=lock;}
    }
    static AeGraphPlan plan(boolean missing,long amount){
        return new AeGraphPlan(new GraphPlan<>(KEY,amount,true,new PlanStep.Sequence(List.of()),Map.of(),Map.of(KEY,amount),Map.of(),missing?Map.of(KEY,amount):Map.of(),missing?GraphPlan.Result.MISSING_INPUT:GraphPlan.Result.FEASIBLE,0,0),Map.of(),Set.of(),missing?Map.of():Map.of(KEY,amount));
    }
    static class Fixture {
        int starts,calculations,returned,shortages;long lastAmount;
        CalculationStrategy lastStrategy;ICraftingSimulationRequester lastRequester;ICraftingSubmitResult forced;
        CompletableFuture<ICraftingPlan> future;
        CraftConfirmMenu menu;FakePlayer player;MEStorage inventory;IStorageService storage;IGrid grid;
        Fixture(ServerLevel level,int shortages)throws Exception {
            this.shortages=shortages;
            inventory=proxy(MEStorage.class,(p,m,a)->switch(m.getName()){
                case "getAvailableStacks"->{((KeyCounter)a[0]).add(KEY,AMOUNT*2);yield null;}
                case "extract"->a[1];case "getDescription"->Component.m_237113_("local probe");default->zero(m);
            });
            storage=proxy(IStorageService.class,(p,m,a)->switch(m.getName()){
                case "getInventory"->inventory;case "getCachedInventory"->{var k=new KeyCounter();k.add(KEY,AMOUNT*2);yield k;}default->zero(m);
            });
            var service=proxy(ICraftingService.class,(p,m,a)->switch(m.getName()){
                case "submitJob"->{starts++;yield forced!=null?forced:starts<=this.shortages?CraftingSubmitResult.missingIngredient(new GenericStack(KEY,1)):CraftingSubmitResult.successful(null);}
                case "beginCraftingCalculation"->{calculations++;lastRequester=(ICraftingSimulationRequester)a[1];lastAmount=(long)a[3];lastStrategy=(CalculationStrategy)a[4];yield future=new CompletableFuture<>();}
                case "getCpus"->com.google.common.collect.ImmutableSet.of();case "getCraftables"->Set.of();default->zero(m);
            });
            grid=proxy(IGrid.class,(p,m,a)->switch(m.getName()){
                case "getCraftingService"->service;case "getStorageService"->storage;default->zero(m);
            });
            var node=proxy(IGridNode.class,(p,m,a)->m.getName().equals("getGrid")?grid:zero(m));
            var host=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IPart.class,ISubMenuHost.class,IActionHost.class},(p,m,a)->switch(m.getName()){
                case "getActionableNode"->node;case "returnToMainMenu"->{returned++;yield null;}case "getMainMenuIcon"->ItemStack.f_41583_;default->zero(m);
            });
            player=FakePlayerFactory.get(level,new GameProfile(UUID.randomUUID(),"SubmitProbe"));
            menu=new CraftConfirmMenu(77,player.m_150109_(),(ISubMenuHost)host);player.f_36096_=menu;
            set(menu,"result",plan(false,AMOUNT));set(menu,"whatToCraft",KEY);set(menu,"amount",Integer.MAX_VALUE);
            menu.setPlan(new CraftingPlanSummary(1,false,List.of()));
        }
        boolean pending()throws Exception{return (boolean)get(menu,"gtlcore$submitting");}
    }
    static void currentInventory(ServerLevel level)throws Exception {
        long[] real={3};int[] queries={0},writes={0};var cached=new KeyCounter();cached.add(KEY,999);
        MEStorage inventory=proxy(MEStorage.class,(p,m,a)->switch(m.getName()){
            case "extract"->{queries[0]++;if(a[2]!=Actionable.SIMULATE)writes[0]++;yield Math.min((long)a[1],real[0]);}
            case "getAvailableStacks"->{((KeyCounter)a[0]).add(KEY,real[0]);yield null;}
            case "getDescription"->Component.m_237113_("local snapshot probe");default->zero(m);
        });
        IStorageService storage=proxy(IStorageService.class,(p,m,a)->switch(m.getName()){
            case "getInventory"->inventory;case "getCachedInventory"->cached;default->zero(m);
        });
        var energy=proxy(IEnergyService.class,(p,m,a)->zero(m));CraftingService[] holder={null};
        var grid=proxy(IGrid.class,(p,m,a)->switch(m.getName()){
            case "getCraftingService"->holder[0];case "getStorageService"->storage;case "getEnergyService"->energy;default->zero(m);
        });
        holder[0]=new CraftingService(grid,storage,energy);
        IActionSource source=proxy(IActionSource.class,(p,m,a)->Optional.empty());
        var catalog=new GtlPatternCatalog();
        for(long amount:new long[]{3,0,1234}){
            real[0]=amount;var c=catalog.begin(grid,holder[0],level,source,KEY,Set.of(),new PlanningBudget(0,10000,()->false),true);
            while(!c.step()){}
            check(c.result().stock().getOrDefault(KEY,0L)==amount,"refresh uses extractable amount "+amount+" despite cached 999");
        }
        check(queries[0]>=3&&writes[0]==0,"refresh simulation does not move inventory");
    }
    static Object get(Object o,String n)throws Exception{var f=field(o.getClass(),n);return f.get(o);}
    static void set(Object o,String n,Object v)throws Exception{field(o.getClass(),n).set(o,v);}
    static Field field(Class<?> type,String n)throws Exception{for(Class<?> c=type;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException e){}throw new NoSuchFieldException(n);}
    static <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class[]{t},(p,m,a)->switch(m.getName()){
        case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"local "+t.getSimpleName();default->h.invoke(p,m,a);
    }));}
    static Object zero(Method m){var t=m.getReturnType();if(t==boolean.class)return false;if(t==long.class)return 0L;if(t==int.class)return 0;if(t==double.class)return 0D;if(Set.class.isAssignableFrom(t))return Set.of();if(Collection.class.isAssignableFrom(t))return List.of();if(t==Optional.class)return Optional.empty();return null;}
    static void check(boolean v,String message){if(!v)throw new AssertionError(message);tests++;System.out.println("[Submission Probe] PASS "+message);}
}
