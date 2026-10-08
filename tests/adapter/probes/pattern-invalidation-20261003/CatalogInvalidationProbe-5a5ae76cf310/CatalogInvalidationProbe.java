package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.RegistryBuilder;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.me.service.CraftingService;
import java.lang.reflect.*;
import java.util.*;

public final class CatalogInvalidationProbe {
    static sun.misc.Unsafe unsafe;
    static ServerLevel level;
    static IGrid grid;
    static Service service;
    static AEKey rawA, rawB, mid, target;
    static class LevelDouble extends ServerLevel {
        static MinecraftServer fakeServer;
        static RecipeManager recipes;
        private LevelDouble() {super(null,null,null,null,null,null,null,false,0L,List.of(),false,null);}
        @Override public MinecraftServer getServer(){return fakeServer;}
        @Override public RecipeManager getRecipeManager(){return recipes;}
    }
    static class Service extends CraftingService implements GraphRequestTracker {
        long generation=1;
        List<IPatternDetails> current=new ArrayList<>();
        Service(IStorageService storage){super(null,storage,null);}
        public void gtlcore$expectGraphOutput(AEKey key){}
        public long gtlcore$graphProviderGeneration(){return generation;}
        public Iterable<IPatternDetails> gtlcore$registeredGraphPatterns(){return current;}
        @Override public Collection<IPatternDetails> getCraftingFor(AEKey key){return current.stream().filter(p->p.getPrimaryOutput().what().equals(key)).toList();}
        @Override public boolean canEmitFor(AEKey key){return false;}
        @Override public Iterable<ICraftingProvider> getProviders(IPatternDetails pattern){return List.of();}
    }
    static IPatternDetails pattern(AEKey in, AEKey out, long count){
        var data=new CompoundTag();var inputs=new ListTag();var outputs=new ListTag();
        inputs.add(GenericStack.writeTag(new GenericStack(in,count))); outputs.add(GenericStack.writeTag(new GenericStack(out,1)));
        data.put("in",inputs);data.put("out",outputs);return new AEProcessingPattern(AEItemKey.of(Items.PAPER,data));
    }
    static void check(boolean test,String label){if(!test)throw new AssertionError(label);System.out.println("PASS "+label);}
    static GtlPatternCatalog.Snapshot capture(GtlPatternCatalog c,AEKey key){return c.capture(grid,service,level,null,key,new PlanningBudget(0,1_000_000,()->false));}
    static void boot()throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var builder=new RegistryBuilder<AEKeyType>().setName(new ResourceLocation("gtlcore","invalidation_probe_keys"));
        var create=RegistryBuilder.class.getDeclaredMethod("create");create.setAccessible(true);
        var registry=(net.minecraftforge.registries.IForgeRegistry<AEKeyType>)create.invoke(builder);
        AEKeyTypesInternal.setRegistry(()->registry);AEKeyTypesInternal.register(AEKeyType.items());AEKeyTypesInternal.register(AEKeyType.fluids());
        var uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);unsafe=(sun.misc.Unsafe)uf.get(null);
        LevelDouble.fakeServer=(MinecraftServer)unsafe.allocateInstance(DedicatedServer.class);
        var thread=MinecraftServer.class.getDeclaredField("serverThread");thread.setAccessible(true);thread.set(LevelDouble.fakeServer,Thread.currentThread());
        LevelDouble.recipes=new RecipeManager();level=(ServerLevel)unsafe.allocateInstance(LevelDouble.class);
        ConfigHolder.INSTANCE=new ConfigHolder();ConfigHolder.INSTANCE.ae2GraphDiscoverByproducts=false;
        IStorageService storage=(IStorageService)Proxy.newProxyInstance(CatalogInvalidationProbe.class.getClassLoader(),new Class[]{IStorageService.class},(p,m,a)->m.getName().equals("getCachedInventory")?new KeyCounter():null);
        service=new Service(storage);
        grid=(IGrid)Proxy.newProxyInstance(CatalogInvalidationProbe.class.getClassLoader(),new Class[]{IGrid.class},(p,m,a)->switch(m.getName()){case "getStorageService"->storage;case "getCraftingService"->service;default->null;});
        rawA=AEItemKey.of(Items.IRON_INGOT);rawB=AEItemKey.of(Items.GOLD_INGOT);mid=AEItemKey.of(Items.PAPER);target=AEItemKey.of(Items.BOOK);
    }
    public static void main(String[]args)throws Exception{
        boot();
        var c=new GtlPatternCatalog();var first=pattern(rawA,mid,1);var finalP=pattern(mid,target,1);
        service.current=new ArrayList<>(List.of(first,finalP));var cold=capture(c,target);var warm=capture(c,target);
        check(warm.cacheHit()&&warm.structure().catalog()==cold.structure().catalog(),"unchanged catalog reused");
        service.current.set(0,pattern(rawB,mid,2));service.generation++;
        var changed=capture(c,target);check(!changed.cacheHit()&&changed.structure().resources().contains(rawB)&&!changed.structure().resources().contains(rawA),"same-output ingredient/count edit rebuilds transitive target");
        service.current.remove(0);service.generation++;var missing=capture(c,target);
        check(!missing.cacheHit()&&missing.structure().catalog().size()==1,"provider removal removes transitive producer");
        service.current.add(0,first);service.generation++;var restored=capture(c,target);
        check(!restored.cacheHit()&&restored.structure().catalog().size()==2,"provider re-add repairs cached missing dependency");
        service.current.set(0,pattern(rawB,mid,3));var stale=capture(c,target);
        check(stale.cacheHit()&&stale.structure().resources().contains(rawA)&&!stale.structure().resources().contains(rawB),"unannounced edit retains stale graph (controlled provider-event failure)");
        c.invalidateBinding("not-built-yet");var invalidated=capture(c,target);
        check(!invalidated.cacheHit()&&invalidated.structure().resources().contains(rawB),"execution invalidation forces refresh even without event");
        var sliced=c.begin(grid,service,level,null,target,new PlanningBudget(0,1_000_000,()->false));
        sliced.step();service.current.set(0,first);service.generation++;
        boolean rejected=false;try{while(!sliced.step()){};}catch(IllegalStateException e){rejected=e.getMessage().equals("GRAPH_PATTERN_CHANGED_DURING_SNAPSHOT");}
        check(rejected,"edit between snapshot slices rejects mixed generation");
        var after=capture(c,target);check(!after.cacheHit()&&after.structure().resources().contains(rawA),"request after sliced rejection rebuilds normally");
        System.out.println("DONE production catalog state machine; controlled service and unstarted level/server doubles");
    }
}
