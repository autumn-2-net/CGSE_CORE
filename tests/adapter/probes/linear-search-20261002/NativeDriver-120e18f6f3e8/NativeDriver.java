package local.linearaudit;
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


@Mod("locallinearaudit")
public class NativeDriver {
    static boolean started;
    public NativeDriver(){
        MinecraftForge.EVENT_BUS.addListener(this::commands);
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }
    void tick(TickEvent.ServerTickEvent e){if(e.phase==TickEvent.Phase.END)RandomOrderAudit.tickLegacy();}
    void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.m_82127_("linear_search_audit").requires(s->s.m_6761_(4)).executes(c->{
            if(started)throw new IllegalStateException("Already running");started=true;
            try{
                ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;
                ConfigHolder.INSTANCE.ae2GraphFallback=false;
                var f=new Fixture(c.getSource().m_81372_());
                String spec=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("local.linear.spec")));
                RandomOrderAudit.load(f.level,f.grid,f.source,java.nio.file.Path.of(System.getProperty("local.linear.spec")).resolveSibling("target-closure.zip").toString())
                    .thenCompose(loaded->{System.out.println("[Linear Search] LOADED "+loaded);return RandomOrderAudit.batch(spec,"paired-stable.jsonl");})
                    .whenComplete((result,error)->{if(error!=null){error.printStackTrace();System.out.println("[Linear Search] ABORT "+error);}else System.out.println("[Linear Search] DONE "+result);});
            }catch(Throwable ex){ex.printStackTrace();System.out.println("[Linear Search] ABORT "+ex);}
            return 1;
        }));
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
        IPatternDetails pattern(AEKey in,AEKey out,long amount,long produced) {
            return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(in,amount)},new GenericStack[]{new GenericStack(out,produced)}),level);
        }
    }
    static AEKey key(String id) {var tag=new CompoundTag();tag.m_128359_("local_pattern_change",id);return AEItemKey.of(Items.f_42597_,tag);}
    static <T>T proxy(Class<T> type,InvocationHandler h) {return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->switch(m.getName()) {case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];case "toString"->"local "+type.getSimpleName();default->h.invoke(p,m,a);}));}
    static Object zero(Method m) {var t=m.getReturnType();if(t==boolean.class)return false;if(t==int.class)return 0;if(t==long.class)return 0L;if(t==double.class)return 0D;if(t==Optional.class)return Optional.empty();if(Set.class.isAssignableFrom(t))return Set.of();if(Collection.class.isAssignableFrom(t))return List.of();return null;}
    static void check(boolean b,String reason) {if(!b)throw new AssertionError(reason);}
}
