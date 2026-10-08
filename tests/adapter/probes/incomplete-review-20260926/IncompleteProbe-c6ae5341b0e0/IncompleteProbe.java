package org.gtlcore.test;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import org.gtlcore.gtlcore.integration.ae2.crafting.transfinite.MissingCraftingPlan;
import org.gtlcore.gtlcore.integration.ae2.crafting.IPatternProviderAutoExpand;
import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.gtlcore.gtlcore.config.*;
import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.*;
import appeng.api.stacks.*;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import java.util.*;
import java.util.concurrent.Future;
import java.math.BigInteger;
import java.lang.reflect.Proxy;

public final class IncompleteProbe implements ICraftingProvider,IPatternProviderAutoExpand,IAutoExpandSettings,GraphCpuHost {
    static IncompleteProbe active;
    static final BigInteger MAX=BigInteger.valueOf(Long.MAX_VALUE), TOTAL=MAX.multiply(BigInteger.TWO);
    final IGrid network;final Level world;final IActionSource action;final AEKey raw,product;
    final IPatternDetails pattern;final ICraftingCPU cpu;final ListCraftingInventory orphan=new ListCraftingInventory(k->{});
    NetworkCraftingProviders providers;IGridNode node;IStorageProvider store;
    GraphCpuController controller;Future<ICraftingPlan> future;
    long available=37,productStock;int phase,ticks,reloads;
    BigInteger supplied=BigInteger.ZERO,consumed=BigInteger.ZERO,produced=BigInteger.ZERO;
    final Map<AEKey,Long> extraStock=new LinkedHashMap<>();final List<IPatternDetails> extraPatterns=new ArrayList<>();AEKey limitGoal;
    final AECraftingEngine oldEngine;final int oldSteps;
    IncompleteProbe(IGrid g,Level l,IActionSource s,AEKey paper)throws Exception {
        network=g;world=l;action=s;
        String tag=UUID.randomUUID().toString();
        var a=new CompoundTag();a.m_128359_("incomplete_probe",tag+"raw");raw=AEItemKey.of((net.minecraft.world.item.Item)paper.getPrimaryKey(),a);
        var b=new CompoundTag();b.m_128359_("incomplete_probe",tag+"product");product=AEItemKey.of((net.minecraft.world.item.Item)paper.getPrimaryKey(),b);
        pattern=PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(raw,2)},new GenericStack[]{new GenericStack(product,1)}),l);
        cpu=(ICraftingCPU)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ICraftingCPU.class},(p,m,x)->switch(m.getName()){
            case "getAvailableStorage" -> Long.MAX_VALUE;
            case "isActive" -> true;
            case "isBusy" -> controller!=null&&controller.ownsTask();
            case "getName" -> product.getDisplayName();
            case "getCoProcessors" -> 256;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==x[0];
            default -> null;
        });
        oldEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;oldSteps=ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps;
        ConfigHolder.INSTANCE.ae2CraftingEngine=AECraftingEngine.GRAPH;
        var field=CraftingService.class.getDeclaredField("craftingProviders");field.setAccessible(true);providers=(NetworkCraftingProviders)field.get(g.getCraftingService());
        node=(IGridNode)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IGridNode.class},(p,m,x)->switch(m.getName()){
            case "getService" -> x[0]==ICraftingProvider.class?this:null;
            case "getGrid" -> network;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==x[0];default -> null;
        });
        store=mounts->mounts.mount(new MEStorage(){
            public net.minecraft.network.chat.Component getDescription(){return raw.getDisplayName();}
            public void getAvailableStacks(KeyCounter out){if(available>0)out.add(raw,available);if(productStock>0)out.add(product,productStock);extraStock.forEach(out::add);}
            public long extract(AEKey k,long n,Actionable mode,IActionSource src){if(extraStock.containsKey(k)){check(mode==Actionable.SIMULATE,"read only limit fixture");return Math.min(n,extraStock.get(k));}long got=k.equals(raw)?Math.min(available,n):0;if(mode==Actionable.MODULATE){available-=got;supplied=supplied.add(BigInteger.valueOf(got));}return got;}
            public long insert(AEKey k,long n,Actionable mode,IActionSource src){if(!k.equals(product))return 0;long got=Math.min(n,Long.MAX_VALUE-productStock);if(mode==Actionable.MODULATE)productStock+=got;return got;}
        });
        network.getStorageService().addGlobalStorageProvider(store);providers.addProvider(node);
        future=calculate(CalculationStrategy.CRAFT_LESS);
    }
    Future<ICraftingPlan> calculate(CalculationStrategy strategy){return network.getCraftingService().beginCraftingCalculation(world,()->action,product,Long.MAX_VALUE,strategy);}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void start(IGrid grid,Level level,IActionSource source,AEKey paper)throws Exception {check(active==null,"already active");active=new IncompleteProbe(grid,level,source,paper);System.out.println("[Incomplete] START");}
    public static void tick(){if(active==null)return;try{if(active.advance()){active.close();active=null;System.out.println("[Incomplete] DONE");}}catch(Throwable e){System.out.println("[Incomplete] FAIL "+e);e.printStackTrace();active.close();active=null;}}
    boolean advance()throws Exception {
        check(++ticks<900,"timeout phase="+phase);
        if(phase==0){if(!future.isDone())return false;var p=future.get();check(!p.simulation()&&p.finalOutput().amount()==18,"CRAFT_LESS expected 18, got "+p.finalOutput()+" simulation="+p.simulation());System.out.println("[Incomplete] PASS craft_less_long amount=18");future=calculate(CalculationStrategy.REPORT_MISSING_ITEMS);phase=1;}
        else if(phase==1){
            if(!future.isDone())return false;var p=future.get();check(p.simulation(),"expected missing preview");
            var graph=((AeGraphPlan)p).graph();check(graph.initialExact().get(raw).equals(TOTAL),"wrong exact demand");
            controller=new GraphCpuController(this);var result=controller.submit(network,new MissingCraftingPlan(p),action,null);
            check(result.successful(),"missing long submit: "+result.errorCode());
            saveReload();System.out.println("[Incomplete] PASS submit exact_raw="+TOTAL+" initial_stock=37");phase=2;
        }else if(phase==2){
            long offered=ExactAmounts.capped(TOTAL.subtract(supplied));long got=controller.insert(raw,offered,Actionable.MODULATE);supplied=supplied.add(BigInteger.valueOf(got));
            controller.tick(network.getEnergyService(),(CraftingService)network.getCraftingService());
            if(controller.ownsTask()){saveReload();return false;}
            var stored=new KeyCounter();network.getStorageService().getInventory().getAvailableStacks(stored);
            check(supplied.equals(TOTAL)&&consumed.equals(TOTAL)&&produced.equals(MAX)&&stored.get(product)==Long.MAX_VALUE,"exact completion mismatch supplied="+supplied+" consumed="+consumed+" produced="+produced+" storage="+stored.get(product)+" localStore="+productStock);
            System.out.println("[Incomplete] PASS execute raw="+consumed+" product="+stored.get(product)+" reloads="+reloads+" real_controller=true");
            startEmpty();phase=3;
        }else if(phase==3){if(!future.isDone())return false;var p=future.get();check(p.simulation()&&p.finalOutput().amount()==Long.MAX_VALUE,"no-stock preview lost");System.out.println("[Incomplete] PASS craft_less_empty retains full missing preview");startLimit();phase=4;
        }else if(phase==4){if(!future.isDone())return false;var p=future.get();check(p.simulation()&&p.finalOutput().amount()==Long.MAX_VALUE,"budget lost original preview");System.out.println("[Incomplete] PASS craft_less_budget returns verified original preview, limit=300000, recipes="+extraPatterns.size());return true;}
        return false;
    }
    void startEmpty(){
        providers.removeProvider(node);
        var a=new CompoundTag();a.m_128359_("incomplete_empty",UUID.randomUUID().toString());var input=AEItemKey.of((net.minecraft.world.item.Item)raw.getPrimaryKey(),a);
        var b=new CompoundTag();b.m_128359_("incomplete_empty",UUID.randomUUID().toString());var output=AEItemKey.of((net.minecraft.world.item.Item)raw.getPrimaryKey(),b);
        extraPatterns.add(PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(input,2)},new GenericStack[]{new GenericStack(output,1)}),world));
        providers.addProvider(node);
        future=network.getCraftingService().beginCraftingCalculation(world,()->action,output,Long.MAX_VALUE,CalculationStrategy.CRAFT_LESS);
    }
    void startLimit()throws Exception {
        providers.removeProvider(node);
        extraPatterns.clear();
        var fixture=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("subset20-review.json"))).getAsJsonObject();
        Map<String,AEKey> keys=new HashMap<>();String nonce=UUID.randomUUID().toString();
        java.util.function.Function<String,AEKey> key=n->keys.computeIfAbsent(n,name->{var tag=new CompoundTag();tag.m_128359_("incomplete_limit",nonce+name);return AEItemKey.of((net.minecraft.world.item.Item)raw.getPrimaryKey(),tag);});
        fixture.getAsJsonObject("stock").entrySet().forEach(e->extraStock.put(key.apply(e.getKey()),e.getValue().getAsLong()));
        for(var v:fixture.getAsJsonArray("recipes")){
            var r=v.getAsJsonObject();var in=r.getAsJsonObject("inputs").entrySet().stream().map(e->new GenericStack(key.apply(e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);
            var out=r.getAsJsonObject("outputs").entrySet().stream().map(e->new GenericStack(key.apply(e.getKey()),e.getValue().getAsLong())).toArray(GenericStack[]::new);
            extraPatterns.add(PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(in,out),world));
        }
        limitGoal=key.apply("GOAL");providers.addProvider(node);
        ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps=300000;
        future=network.getCraftingService().beginCraftingCalculation(world,()->action,limitGoal,Long.MAX_VALUE,CalculationStrategy.CRAFT_LESS);
    }
    void saveReload(){var parent=new CompoundTag();controller.write(parent);var decoded=GraphJobCodec.read(parent.m_128469_(GraphJobCodec.NBT_KEY));check(decoded.plan().initialExact().get(raw).equals(TOTAL),"NBT truncated exact need");var again=new GraphCpuController(this);again.read(parent);check(again.ownsTask(),"reload lost job");controller=again;reloads++;}
    void close(){if(controller!=null&&controller.ownsTask())controller.cancel();providers.removeProvider(node);network.getStorageService().removeGlobalStorageProvider(store);ConfigHolder.INSTANCE.ae2CraftingEngine=oldEngine;ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps=oldSteps;}
    public List<IPatternDetails> getAvailablePatterns(){return extraPatterns.isEmpty()?List.of(pattern):extraPatterns;}
    public boolean isBusy(){return false;}
    public boolean pushPattern(IPatternDetails p,KeyCounter[] inputs){long total=0;for(var slot:inputs)for(var e:slot){check(e.getKey().equals(raw),"wrong raw");total=Math.addExact(total,e.getLongValue());}check(total>0&&total%2==0,"bad batch");long output=total/2;consumed=consumed.add(BigInteger.valueOf(total));produced=produced.add(BigInteger.valueOf(output));check(controller.insert(product,output,Actionable.MODULATE)==output,"return refused");return true;}
    public boolean isPatternAutoExpand(){return true;}public void setPatternAutoExpand(boolean b){}
    public long gtlcore$getMaxPatternOperations(IPatternDetails p,long n){return Math.min(n,Long.MAX_VALUE/17);}
    public long gtlcore$findMaxOperationsForTarget(PatternProviderTarget t,BlockEntity b,Direction d,KeyCounter k,long n){return n;}
    public ICraftingCPU cpu(){return cpu;}public IGrid grid(){return network;}public Level level(){return world;}public IActionSource source(){return action;}
    public boolean active(){return true;}public boolean unboundedJobStorage(){return true;}public long dispatchCapacity(){return 4096;}
    public ListCraftingInventory orphanInventory(){return orphan;}public void dirty(){}public void changed(AEKey k){}public void output(GenericStack s){}public void requesting(AEKey k,boolean yes){}
}
