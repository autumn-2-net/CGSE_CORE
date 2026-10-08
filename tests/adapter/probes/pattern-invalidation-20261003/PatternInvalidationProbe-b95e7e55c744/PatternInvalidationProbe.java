package local.patternchange;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.*;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.api.storage.MEStorage;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

@Mod("localpatterninvalidationprobe")
public class PatternInvalidationProbe {
    static Run active;
    static NetworkPatternProbe network;
    public PatternInvalidationProbe() {
        MinecraftForge.EVENT_BUS.addListener(this::register);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.m_82127_("pattern_circuit_probe").requires(s->s.m_6761_(4)).executes(c->{
            var oldEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
            try {circuitEdits(new Fixture(c.getSource().m_81372_()));System.out.println("[Pattern Invalidation] DONE circuitOnly=true");}
            catch(Throwable ex){ex.printStackTrace();System.out.println("[Pattern Invalidation] ABORT "+ex);}
            finally {ConfigHolder.INSTANCE.ae2CraftingEngine=oldEngine;}
            return 1;
        }));
        e.getDispatcher().register(Commands.m_82127_("pattern_network_probe").requires(s->s.m_6761_(4)).executes(c->{
            try {network=new NetworkPatternProbe(c.getSource().m_81372_());}
            catch(Throwable ex){ex.printStackTrace();System.out.println("[Pattern Invalidation] ABORT "+ex);}
            return 1;
        }));
        e.getDispatcher().register(Commands.m_82127_("pattern_invalidation_probe").requires(s->s.m_6761_(4)).executes(c->{
            if(active!=null)throw new IllegalStateException("already active");
            try { active=new Run(c.getSource().m_81372_()); }
            catch(Throwable ex) {ex.printStackTrace();System.out.println("[Pattern Invalidation] ABORT "+ex);}
            return 1;
        }));
    }
    void tick(TickEvent.ServerTickEvent e) {
        if(e.phase==TickEvent.Phase.END&&network!=null) {
            try {if(network.tick()){network.close();network=null;}}
            catch(Throwable ex){ex.printStackTrace();System.out.println("[Pattern Invalidation] ABORT "+ex);network.close();network=null;}
        }
        if(e.phase!=TickEvent.Phase.END||active==null)return;
        try { if(active.tick()) {active.close();active=null;} }
        catch(Throwable ex) {ex.printStackTrace();System.out.println("[Pattern Invalidation] ABORT "+ex);active.close();active=null;}
    }
    record Case(String name, List<IPatternDetails> patterns, boolean missing, Map<AEKey,Long> used) {}
    static class Run {
        final AECraftingEngine oldEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
        final AE2CalculationMode oldMode=ConfigHolder.INSTANCE.ae2CalculationMode;
        final boolean oldByproducts=ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts;
        final boolean oldFallback=ConfigHolder.INSTANCE.ae2GraphFallback;
        final Fixture f;
        final List<Case> cases=new ArrayList<>();
        int caseId, engine, passes, failures;
        Future<ICraftingPlan> future;
        appeng.crafting.CraftingCalculation legacy;
        long started;
        Run(ServerLevel level)throws Exception {
            ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;
            ConfigHolder.INSTANCE.ae2GraphFallback=false;
            f=new Fixture(level);
            sliceEdits(f);
            var editedThroughGui=slotEdits(f);
            circuitEdits(f);
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=false;
            var root=f.pattern(f.mid,f.target,1,1);
            var original=f.pattern(f.raw,f.mid,2,1);
            cases.add(new Case("GUI change notification after intermediate edit",List.of(root,editedThroughGui),false,Map.of(f.alt,30L)));
            cases.add(new Case("cold",List.of(root,original),false,Map.of(f.raw,20L)));
            cases.add(new Case("warm",null,false,Map.of(f.raw,20L)));
            cases.add(new Case("intermediate amount changed",List.of(root,f.pattern(f.raw,f.mid,3,1)),false,Map.of(f.raw,30L)));
            cases.add(new Case("intermediate material changed",List.of(root,f.pattern(f.alt,f.mid,4,1)),false,Map.of(f.alt,40L)));
            cases.add(new Case("intermediate longer path",List.of(root,f.pattern(f.branch,f.mid,2,1),f.pattern(f.alt,f.branch,3,1)),false,Map.of(f.alt,60L)));
            cases.add(new Case("intermediate removed",List.of(root),true,Map.of()));
            cases.add(new Case("warm missing",null,true,Map.of()));
            cases.add(new Case("intermediate restored",List.of(root,original),false,Map.of(f.raw,20L)));
            cases.add(new Case("equivalent handles replaced",List.of(f.pattern(f.mid,f.target,1,1),f.pattern(f.raw,f.mid,2,1)),false,Map.of(f.raw,20L)));
            Random random=new Random(42811);
            for(int i=0;i<40;i++) {
                int amount=1+random.nextInt(8);var raw=random.nextBoolean()?f.raw:f.alt;
                var patterns=new ArrayList<>(List.of(root,f.pattern(raw,f.mid,amount,1),f.pattern(f.alt,f.branch,1,1)));
                Collections.shuffle(patterns,random);
                cases.add(new Case("random edit "+i,patterns,false,Map.of(raw,10L*amount)));
            }
            
            // The requested output stays identical across every edit and graph cache hit.
            var rawBranch=f.pattern(f.raw,f.branch,5,1);
            var splitRoot=f.pattern(new GenericStack[]{new GenericStack(f.mid,2),new GenericStack(f.branch,3)},f.target,1);
            cases.add(new Case("shared input cold",List.of(splitRoot,original,rawBranch),false,Map.of(f.raw,190L)));
            cases.add(new Case("shared input edit one branch",List.of(splitRoot,f.pattern(f.alt,f.mid,7,1),rawBranch),false,Map.of(f.raw,150L,f.alt,140L)));
            cases.add(new Case("shared input edit second branch",List.of(splitRoot,f.pattern(f.alt,f.mid,7,1),f.pattern(f.alt,f.branch,11,1)),false,Map.of(f.alt,470L)));
            cases.add(new Case("shared dependency removed",List.of(splitRoot,f.pattern(f.alt,f.mid,7,1)),true,Map.of()));
            cases.add(new Case("shared dependency reencoded and reinserted",List.of(splitRoot,f.pattern(f.raw,f.mid,2,1),f.pattern(f.raw,f.branch,5,1)),false,Map.of(f.raw,190L)));
            cases.add(new Case("equivalent duplicate insert",List.of(root,original,f.pattern(f.raw,f.mid,2,1)),false,Map.of(f.raw,20L)));
            cases.add(new Case("equivalent duplicate remove one",List.of(root,f.pattern(f.raw,f.mid,2,1)),false,Map.of(f.raw,20L)));
            cases.add(new Case("equivalent duplicate remove last",List.of(root),true,Map.of()));
            cases.add(new Case("reencode same output new input",List.of(root,f.pattern(f.alt,f.mid,8,1)),false,Map.of(f.alt,80L)));
            cases.add(new Case("reencode same output warm",null,false,Map.of(f.alt,80L)));
            cases.add(new Case("edit produced amount same output",List.of(root,f.pattern(f.alt,f.mid,8,2)),false,Map.of(f.alt,40L)));
            cases.add(new Case("shared ingredient output swaps cold",List.of(splitRoot,original,rawBranch),false,Map.of(f.raw,190L)));
            cases.add(new Case("shared ingredient output swaps edited",List.of(splitRoot,f.pattern(f.raw,f.branch,2,1),f.pattern(f.raw,f.mid,5,1)),false,Map.of(f.raw,160L)));
            System.out.println("[Pattern Invalidation] START cases="+cases.size()+" engines=CGSE,MAX_FAST byproducts=false,true");
        }
        boolean tick()throws Exception {
            if(future!=null) {
                if(legacy!=null&&!future.isDone())legacy.simulateFor(10000);
                if(!future.isDone()) {
                    if(System.nanoTime()-started>30_000_000_000L)throw new AssertionError("timeout case="+caseId+" engine="+engine);
                    return false;
                }
                var c=cases.get(caseId);String label=(engine==0?"CGSE":"MAX_FAST")+" byproducts="+ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts+" "+c.name();
                try {
                    ICraftingPlan p=future.get();
                    check((p instanceof AeGraphPlan)==(engine==0),label+" correct engine implementation "+p.getClass());
                    check(p.simulation()==c.missing(),label+" simulation="+p.simulation()+" expected="+c.missing());
                    if(!c.missing()) {
                        for(var x:c.used().entrySet())check(p.usedItems().get(x.getKey())==x.getValue(),label+" wrong input "+p.usedItems()+" expected="+c.used());
                        check(p.missingItems().isEmpty(),label+" missing="+p.missingItems());
                    }
                    passes++;System.out.println("[Pattern Invalidation] PASS "+label+" patterns="+p.patternTimes().size());
                } catch(Throwable failure) {failures++;failure.printStackTrace();System.out.println("[Pattern Invalidation] FAIL "+label+" "+failure);}
                future=null;
                legacy=null;
                if(++engine==2) {engine=0;caseId++;}
                if(caseId==cases.size()) {
                    if(ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts) {
                        System.out.println("[Pattern Invalidation] DONE passes="+passes+" failures="+failures);return true;
                    }
                    ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=true;caseId=0;
                }
            }
            var c=cases.get(caseId);
            if(engine==0&&c.patterns()!=null) {
                f.current=c.patterns();f.service.refreshNodeCraftingProvider(f.node);
                check(f.service.getCraftingFor(f.target).size()==1,"AE target remains registered");
                if(!c.missing())check(!f.service.getCraftingFor(f.mid).isEmpty(),"AE dependency index updated");
            }
            ConfigHolder.INSTANCE.ae2CraftingEngine=engine==0?AECraftingEngine.GRAPH:AECraftingEngine.LEGACY;
            started=System.nanoTime();
            if(engine==0)future=f.service.beginCraftingCalculation(f.level,f.requester,f.target,10,CalculationStrategy.REPORT_MISSING_ITEMS);
            else {
                legacy=new appeng.crafting.CraftingCalculation(f.level,f.grid,f.requester,new GenericStack(f.target,10),CalculationStrategy.REPORT_MISSING_ITEMS);
                future=CompletableFuture.supplyAsync(legacy::run);
            }
            return false;
        }
        void close() {
            ConfigHolder.INSTANCE.ae2CraftingEngine=oldEngine;ConfigHolder.INSTANCE.ae2CalculationMode=oldMode;
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=oldByproducts;ConfigHolder.INSTANCE.ae2GraphFallback=oldFallback;
        }
    }
    static void sliceEdits(Fixture f)throws Exception {
        int accepted=0,rejected=0;
        for(boolean byproducts:new boolean[]{false,true})for(boolean warm:new boolean[]{false,true})for(int offset=0;offset<72;offset++) {
            ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=byproducts;
            var root=f.pattern(f.mid,f.target,1,1);var old=f.pattern(f.raw,f.mid,2,1);var next=f.pattern(f.alt,f.mid,3,1);
            f.current=List.of(root,old);f.service.refreshNodeCraftingProvider(f.node);
            var catalog=new GtlPatternCatalog();
            if(warm)catalog.capture(f.grid,f.service,f.level,f.source,f.target,budget());
            f.service.refreshNodeCraftingProvider(f.node);
            var capture=catalog.begin(f.grid,f.service,f.level,f.source,f.target,budget());
            boolean alreadyDone=false;
            for(int i=0;i<offset;i++)if(capture.step()){alreadyDone=true;break;}
            f.current=List.of(root,next);f.service.refreshNodeCraftingProvider(f.node);
            if(!alreadyDone) {
                try {
                    while(!capture.step()){}
                    check(capture.result().structure().dependencies().get(f.mid).get(0).values().equals(PatternFingerprint.capture(next)),"stale accepted capture byproducts="+byproducts+" warm="+warm+" offset="+offset);
                    accepted++;
                } catch(IllegalStateException changed) {
                    check(changed.getMessage().equals("GRAPH_PATTERN_CHANGED_DURING_SNAPSHOT"),"unexpected capture failure "+changed);rejected++;
                }
            }
            var retry=catalog.capture(f.grid,f.service,f.level,f.source,f.target,budget());
            check(retry.structure().dependencies().get(f.mid).get(0).values().equals(PatternFingerprint.capture(next)),"retry reuses stale intermediate at offset="+offset);
        }
        System.out.println("[Pattern Invalidation] SLICE PASS scenarios=288 acceptedFresh="+accepted+" safelyRejected="+rejected+" retriesFresh=288");
    }
    static PlanningBudget budget(){return new PlanningBudget(0,1_000_000,()->false);}
    static IPatternDetails slotEdits(Fixture f)throws Exception {
        var pos=new net.minecraft.core.BlockPos(29,180,29);
        check(f.level.m_46859_(pos),"fixture block position must be air");
        var block=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("gtceu","me_final_pattern_buffer"));
        check(block!=null,"GTL final pattern buffer registered");
        f.level.m_7731_(pos,block.m_49966_(),3);
        try {
            var machine=(org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine)com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(f.level,pos);
            check(machine!=null,"real GTL pattern buffer instantiated");
            var inventory=machine.getTerminalPatternInventory();
            var p1=f.pattern(f.raw,f.mid,2,1);var p2=f.pattern(f.alt,f.mid,3,1);
            for(var p:List.of(p1,p2,p1)) {
                field(machine.getClass(),"needPatternSync").set(machine,false);
                inventory.setItemDirect(0,p.getDefinition().toStack());
                check(machine.getAvailablePatterns().size()==1,"real terminal slot exposes one pattern");
                check(machine.getAvailablePatterns().get(0).getInputs()[0].getPossibleInputs()[0].what().equals(p.getInputs()[0].getPossibleInputs()[0].what()),"real terminal slot uses edited input");
                check((boolean)field(machine.getClass(),"needPatternSync").get(machine),"real slot change schedules network sync");
            }
            inventory.setItemDirect(0,net.minecraft.world.item.ItemStack.f_41583_);
            check(machine.getAvailablePatterns().isEmpty(),"real terminal slot removal clears provider");
            var widget=new org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget(machine.getPatternInventory(),0,0,0);
            Method onChange=machine.getClass().getDeclaredMethod("onPatternChange",int.class);onChange.setAccessible(true);
            int[] callbacks={0};
            widget.setOnPatternSlotChanged(()->{try{callbacks[0]++;onChange.invoke(machine,0);}catch(Exception e){throw new RuntimeException(e);}});
            var slot=(net.minecraft.world.inventory.Slot)field(widget.getClass(),"slotReference").get(widget);
            slot.m_5852_(p1.getDefinition().toStack());
            check(machine.getAvailablePatterns().size()==1,"GUI insert registers pattern");
            check(callbacks[0]==1,"ordinary GUI set invokes callback once");
            field(machine.getClass(),"needPatternSync").set(machine,false);
            slot.m_6201_(1);
            slot.m_6654_();
            boolean removed=machine.getAvailablePatterns().isEmpty();
            System.out.println("[Pattern Invalidation] GUI removal providerEmpty="+removed+" slotEmpty="+inventory.getStackInSlot(0).m_41619_());
            if(Boolean.getBoolean("local.pattern-change.fixed"))check(removed&&(boolean)field(machine.getClass(),"needPatternSync").get(machine),"GUI removal notifies provider and network");
            slot.m_5852_(p2.getDefinition().toStack());
            check(machine.getAvailablePatterns().get(0).getInputs()[0].getPossibleInputs()[0].what().equals(f.alt),"GUI replacement uses edited input");
            System.out.println("[Pattern Invalidation] SLOT PASS real GTL terminal replace/remove/restore and GUI replace");
            slot.m_5852_(f.pattern(f.branch,f.mid,2,1).getDefinition().toStack());
            field(machine.getClass(),"needPatternSync").set(machine,false);
            machine.getPatternInventory().setStackInSlot(0,p2.getDefinition().toStack());
            slot.m_6654_();
            if(Boolean.getBoolean("local.pattern-change.fixed"))check((boolean)field(machine.getClass(),"needPatternSync").get(machine),"GUI contents edit schedules network sync");
            inventory.setItemDirect(1,p2.getDefinition().toStack());
            var beforeNbtEdit=machine.getAvailablePatterns().get(0);
            machine.getPatternInventory().getStackInSlot(0).m_41751_(p1.getDefinition().toStack().m_41783_().m_6426_());
            field(machine.getClass(),"needPatternSync").set(machine,false);
            slot.m_6654_();
            check((boolean)field(machine.getClass(),"needPatternSync").get(machine),"in place NBT mutation schedules network sync");
            check(machine.getAvailablePatterns().size()==2,"duplicate split exposes both old and changed pattern");
            check(beforeNbtEdit.getInputs()[0].getPossibleInputs()[0].what().equals(f.alt),"old pattern handle remains stable after stack NBT mutation");
            inventory.setItemDirect(1,net.minecraft.world.item.ItemStack.f_41583_);
            check(machine.getAvailablePatterns().size()==1,"removing unchanged duplicate preserves edited pattern");
            check(machine.getAvailablePatterns().get(0).getInputs()[0].getPossibleInputs()[0].what().equals(f.raw),"remaining edited duplicate uses new raw input");
            slot.m_6201_(1);slot.m_6654_();
            check(machine.getAvailablePatterns().isEmpty(),"remove reencoded last duplicate clears provider");
            slot.m_5852_(p2.getDefinition().toStack());
            System.out.println("[Pattern Invalidation] NBT PASS same output in-place edit, duplicate split/remove/reinsert, old handle immutable");
            return machine.getAvailablePatterns().get(0);
        } finally {f.level.m_7471_(pos,false);}
    }
    static Field field(Class<?> type,String name)throws Exception {for(Class<?> c=type;c!=null;c=c.getSuperclass())try{var field=c.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException e){}throw new NoSuchFieldException(name);}
    static void circuitEdits(Fixture f)throws Exception {
        var pos=new net.minecraft.core.BlockPos(29,180,29);
        var block=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("gtceu","me_final_pattern_buffer"));
        f.level.m_7731_(pos,block.m_49966_(),3);
        try {
            var machine=(org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine)com.gregtechceu.gtceu.api.machine.MetaMachine.getMachine(f.level,pos);
            var inventory=machine.getTerminalPatternInventory();
            var internal=((Object[])field(machine.getClass(),"internalInventory").get(machine))[0];
            var cache=(org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager)field(internal.getClass(),"cacheManager").get(internal);
            var match=internal.getClass().getMethod("handleItemInternal",it.unimi.dsi.fastutil.objects.Object2LongMap.class,int.class,boolean.class);match.setAccessible(true);
            var widget=new org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget(machine.getPatternInventory(),0,0,0);
            Method onChange=machine.getClass().getDeclaredMethod("onPatternChange",int.class);onChange.setAccessible(true);
            widget.setOnPatternSlotChanged(()->{try{onChange.invoke(machine,0);}catch(Exception e){throw new RuntimeException(e);}});
            var slot=(net.minecraft.world.inventory.Slot)field(widget.getClass(),"slotReference").get(widget);
            var first=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{GenericStack.fromItemStack(com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(1)),new GenericStack(f.raw,1)},new GenericStack[]{new GenericStack(f.mid,1)});
            var next=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{GenericStack.fromItemStack(com.gregtechceu.gtceu.common.item.IntCircuitBehaviour.stack(2)),new GenericStack(f.raw,2)},new GenericStack[]{new GenericStack(f.mid,1)});
            for(var engine:AECraftingEngine.values())for(boolean gui:new boolean[]{false,true}) {
                ConfigHolder.INSTANCE.ae2CraftingEngine=engine;
                inventory.setItemDirect(0,net.minecraft.world.item.ItemStack.f_41583_);
                if(gui)slot.m_5852_(first.m_41777_());else inventory.setItemDirect(0,first.m_41777_());
                int inserted=cache.getCircuitCache();
                if(gui)slot.m_5852_(next.m_41777_());else inventory.setItemDirect(0,next.m_41777_());
                int replaced=cache.getCircuitCache();
                boolean matchBefore=(boolean)match.invoke(internal,new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(),2,true);
                slot.m_6654_();
                int notifiedAgain=cache.getCircuitCache();
                boolean matchAfter=(boolean)match.invoke(internal,new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>(),2,true);
                if(gui)slot.m_5852_(f.pattern(f.raw,f.mid,2,1).getDefinition().toStack());else inventory.setItemDirect(0,f.pattern(f.raw,f.mid,2,1).getDefinition().toStack());
                int removedCircuit=cache.getCircuitCache();
                inventory.setItemDirect(0,net.minecraft.world.item.ItemStack.f_41583_);
                inventory.setItemDirect(0,next.m_41777_());
                int reinserted=cache.getCircuitCache();
                System.out.println("[Pattern Invalidation] CIRCUIT engine="+engine+" route="+(gui?"GUI":"TERMINAL")+" inserted="+inserted+" replaced="+replaced+" expected=2 repeatNotification="+notifiedAgain+" removedCircuit="+removedCircuit+" expectedRemoved=-1 removeReinsert="+reinserted+" circuit2RecipeMatchBeforeRepeat="+matchBefore+" circuit2RecipeMatchAfterRepeat="+matchAfter);
                check(inserted==1&&notifiedAgain==2&&reinserted==2,"circuit controls");
                if(Boolean.getBoolean("local.circuit.expectedFixed"))check(replaced==2&&removedCircuit==-1,"replacement circuit lost or removed circuit retained");
            }
        } finally {f.level.m_7471_(pos,false);}
    }
    static class Fixture {
        final ServerLevel level;
        final AEKey raw=key("raw"),alt=key("alt"),mid=key("mid"),target=key("target"),branch=key("branch");
        final KeyCounter stock=new KeyCounter();
        final IGrid grid;final CraftingService service;final IGridNode node;
        final IActionSource source;final ICraftingSimulationRequester requester;
        List<IPatternDetails> current=List.of();
        Fixture(ServerLevel level)throws Exception {
            this.level=level;stock.add(raw,1_000_000);stock.add(alt,1_000_000);
            var inventory=proxy(MEStorage.class,(p,m,a)->switch(m.getName()) {
                case "getAvailableStacks"->{((KeyCounter)a[0]).addAll(stock);yield null;}
                case "extract"->{check(a[2]==Actionable.SIMULATE,"preview never extracts");yield Math.min((long)a[1],stock.get((AEKey)a[0]));}
                case "getDescription"->Component.m_237113_("local pattern-change fixture");default->zero(m);
            });
            var storage=proxy(IStorageService.class,(p,m,a)->switch(m.getName()) {case "getInventory"->inventory;case "getCachedInventory"->stock;default->zero(m);});
            var energy=proxy(IEnergyService.class,(p,m,a)->zero(m));CraftingService[] ref={null};
            grid=proxy(IGrid.class,(p,m,a)->switch(m.getName()) {case "getCraftingService"->ref[0];case "getStorageService"->storage;case "getEnergyService"->energy;default->zero(m);});
            service=ref[0]=new CraftingService(grid,storage,energy);
            var provider=proxy(ICraftingProvider.class,(p,m,a)->switch(m.getName()) {case "getAvailablePatterns"->current;case "getEmitableItems"->Set.of();default->zero(m);});
            node=proxy(IGridNode.class,(p,m,a)->switch(m.getName()) {case "getGrid"->grid;case "getService"->a[0]==ICraftingProvider.class?provider:null;default->zero(m);});
            var host=proxy(IActionHost.class,(p,m,a)->m.getName().equals("getActionableNode")?node:zero(m));
            source=proxy(IActionSource.class,(p,m,a)->m.getName().equals("machine")?Optional.of(host):Optional.empty());
            requester=new ICraftingSimulationRequester(){public IActionSource getActionSource(){return source;}public IGridNode getGridNode(){return node;}};
        }
        IPatternDetails pattern(GenericStack[] ins,AEKey out,long produced) {
            return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(ins,new GenericStack[]{new GenericStack(out,produced)}),level);
        }
        IPatternDetails pattern(AEKey in,AEKey out,long amount,long produced) {
            return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(in,amount)},new GenericStack[]{new GenericStack(out,produced)}),level);
        }
    }
    static AEKey key(String id) {var tag=new CompoundTag();tag.m_128359_("local_pattern_change",id);return AEItemKey.of(Items.f_42597_,tag);}
    static <T>T proxy(Class<T> type,InvocationHandler h) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()) {case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"local "+type.getSimpleName();default->h.invoke(p,m,a);}));}
    static Object zero(Method m) {var t=m.getReturnType();if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;if(t==double.class)return 0D;if(t==Optional.class)return Optional.empty();if(Set.class.isAssignableFrom(t))return Set.of();if(Collection.class.isAssignableFrom(t))return List.of();return null;}
    static void check(boolean b,String reason) {if(!b)throw new AssertionError(reason);}
}
