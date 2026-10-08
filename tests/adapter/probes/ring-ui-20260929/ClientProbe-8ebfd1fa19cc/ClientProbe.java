package org.gtlcore.localui;

import org.gtlcore.gtlcore.client.ae2.graph.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.stacks.*;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.storage.ISubMenuHost;
import appeng.client.gui.me.crafting.CraftConfirmScreen;
import appeng.client.gui.style.StyleManager;
import appeng.menu.me.crafting.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.*;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import java.lang.reflect.*;
import java.util.*;

@Mod("gtlclientprobe")
@Mod.EventBusSubscriber(modid="gtlclientprobe",value=Dist.CLIENT)
public final class ClientProbe {
    static int stage, ticks, totalTicks;
    static CraftConfirmMenu menu;
    static CraftConfirmScreen parent;
    static CraftingRingScreen ring;
    static UUID id;
    static boolean connecting;
    static final List<AEKey> keys=new ArrayList<>();
    static Object get(Object object,String name) throws Exception {
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static void set(Object object,String name,Object value)throws Exception{
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(object,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    static void invoke(Object object,String name)throws Exception{Method m=object.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(object);}
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void log(String x){System.out.println("[Graph UI] "+x);}
    static ItemStack item(String id){return new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft",id)));}
    static AEKey key(int n){
        var stack=item(new String[]{"diamond","iron_ingot","copper_ingot","gold_ingot","redstone","emerald","lapis_lazuli","quartz","amethyst_shard","ender_pearl","blaze_powder","nether_star"}[n%12]);
        var tag=new CompoundTag();tag.m_128405_("LocalGraphNode",n);stack.m_41751_(tag);return AEItemKey.of(stack);
    }
    static final class Host extends ItemMenuHost implements ISubMenuHost{
        Host(Player p){super(p,null,item("crafting_table"));}
        public void returnToMainMenu(Player p,appeng.menu.ISubMenu menu){}
        public ItemStack getMainMenuIcon(){return getItemStack();}
    }
    static void summary(boolean graph)throws Exception{
        var summary=new CraftingPlanSummary(100,true,List.of());
        ((GraphPlanSummaryView)summary).gtlcore$graphPlanId(graph?id:null);
        set(menu,"plan",summary);
    }
    static void install(Minecraft mc)throws Exception{
        id=UUID.randomUUID();
        menu=new CraftConfirmMenu(998,mc.f_91074_.m_150109_(),new Host(mc.f_91074_));
        summary(true);mc.f_91074_.f_36096_=menu;
        parent=new CraftConfirmScreen(menu,mc.f_91074_.m_150109_(),Component.m_237113_("UI fixture"),StyleManager.loadStyleDoc("/screens/craft_confirm.json"));
        mc.m_91152_(parent);
    }
    static void openRing(Minecraft mc){
        ring=new CraftingRingScreen(parent);mc.m_91152_(ring);
        var recipes=new LinkedHashMap<String,GraphRecipe<AEKey>>();
        var steps=new ArrayList<PlanStep>();
        var initial=new LinkedHashMap<AEKey,Long>();
        var missing=new LinkedHashMap<AEKey,Long>();
        var inputs=new ArrayList<GraphRecipe.Slot<AEKey>>();
        for(int i=0;i<320;i++)keys.add(key(i));
        keys.set(0,AEFluidKey.of(ForgeRegistries.FLUIDS.getValue(new ResourceLocation("minecraft","water"))));
        long amount=Integer.MAX_VALUE;
        for(int branch=0;branch<20;branch++){
            int first=branch*12;
            initial.put(keys.get(first),amount);if(branch%3==0)missing.put(keys.get(first),amount-2);
            for(int d=0;d<10;d++){
                String r="b"+branch+"d"+d;
                recipes.put(r,new GraphRecipe<>(r,r,List.of(new GraphRecipe.Slot<>(keys.get(first+d),1)),Map.of(keys.get(first+d+1),1L)));
                steps.add(new PlanStep.Batch(r,amount));
            }
            inputs.add(new GraphRecipe.Slot<>(keys.get(first+10),1));
        }
        initial.put(keys.get(0),amount+1);
        initial.put(keys.get(300),1L);
        recipes.put("seed_in",new GraphRecipe<>("seed_in","seed_in",List.of(new GraphRecipe.Slot<>(keys.get(300),1)),Map.of(keys.get(301),1L)));
        recipes.put("seed_out",new GraphRecipe<>("seed_out","seed_out",List.of(new GraphRecipe.Slot<>(keys.get(301),1)),Map.of(keys.get(300),1L,keys.get(302),1L)));
        steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("seed_in",1),new PlanStep.Batch("seed_out",1))),amount));
        inputs.add(new GraphRecipe.Slot<>(keys.get(302),1));
        // A consuming cycle must show initial input without inventing a retained seed.
        initial.put(keys.get(303),amount+1);
        missing.put(keys.get(303),amount-1);
        recipes.put("loss_in",new GraphRecipe<>("loss_in","loss_in",List.of(new GraphRecipe.Slot<>(keys.get(303),2)),Map.of(keys.get(304),1L)));
        recipes.put("loss_out",new GraphRecipe<>("loss_out","loss_out",List.of(new GraphRecipe.Slot<>(keys.get(304),1)),Map.of(keys.get(303),1L,keys.get(305),1L)));
        steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("loss_in",1),new PlanStep.Batch("loss_out",1))),amount));
        inputs.add(new GraphRecipe.Slot<>(keys.get(305),1));
        recipes.put("finish",new GraphRecipe<>("finish","finish",inputs,Map.of(keys.get(319),1L)));
        steps.add(new PlanStep.Batch("finish",amount));
        var plan=new GraphPlan<>(keys.get(319),amount,true,new PlanStep.Sequence(steps),recipes,initial,Map.of(keys.get(0),1L,keys.get(300),1L),missing,GraphPlan.Result.MISSING_INPUT,0,0);
        var view=new GraphRingView(id,plan,plan.patternTimes());
        for(int offset=0;offset<view.page(0).total();){var page=view.page(offset);CraftingRingScreen.receive(new GraphRingPackets.Response(998,page));offset+=page.rows().size();}
    }
    static void verifyStockSnapshot()throws Exception {
        AEKey used=key(500),missing=key(501);
        var captured=new CraftingPlanSummary(10,true,List.of(new CraftingPlanSummaryEntry(used,0,3,0),new CraftingPlanSummaryEntry(missing,7,10,0)));
        ((GraphPlanSummaryView)captured).gtlcore$graphPlanId(id);
        menu.setPlan(captured);
        var confirm=(org.gtlcore.gtlcore.integration.ae2.common.IConfirmStartMenu)menu;
        var before=List.copyOf(confirm.gtlcore$getMissingNow());
        check(before.equals(List.of(missing)),"captured missing set");
        var live=confirm.gtlcore$getClientRepo();
        for(long amount:new long[]{0,10,Long.MAX_VALUE,0}){
            live.handleUpdate(true,List.of(new appeng.menu.me.common.GridInventoryEntry(1,used,amount,0,false),new appeng.menu.me.common.GridInventoryEntry(2,missing,amount,0,false)));
            check(confirm.gtlcore$getLiveStored()==null&&confirm.gtlcore$getMissingNow().equals(before),"graph missing list ignores live inventory="+amount);
        }
        var retransmitted=new CraftingPlanSummary(10,true,captured.getEntries());
        ((GraphPlanSummaryView)retransmitted).gtlcore$graphPlanId(id);
        menu.setPlan(retransmitted);
        check(confirm.gtlcore$getClientRepo()==live,"duplicate same-plan summary retains serial mapping");
        var legacy=new CraftingPlanSummary(10,false,List.of(new CraftingPlanSummaryEntry(used,0,3,0)));
        menu.setPlan(legacy);
        check(confirm.gtlcore$getLiveStored()==null,"new plan waits for initial inventory packet");
        confirm.gtlcore$getClientRepo().handleUpdate(true,List.of(new appeng.menu.me.common.GridInventoryEntry(1,used,0,0,false)));
        check(confirm.gtlcore$getLiveStored()!=null&&confirm.gtlcore$getMissingNow().contains(used),"legacy preview retains live shortage checks");
        menu.setPlan(captured);
        check(confirm.gtlcore$getLiveStored()==null&&confirm.gtlcore$getMissingNow().equals(before),"return to graph restores snapshot shortage list");
        summary(true);
        log("STOCK PASS graph snapshot stable across 0/10/Long.MAX_VALUE; legacy live inventory retained; new plan clears old inventory; repeated same-plan summary is stable");
    }
    static void clickRoot(int button)throws Exception{
        var tree=(PlanDependencyLayout.View)get(ring,"tree");
        var point=tree.entries().get(0).point();
        double x=ring.getGuiLeft()+10+(Double)get(ring,"panX")+point.x()*(Double)get(ring,"zoom");
        double y=ring.getGuiTop()+48+(Double)get(ring,"panY")+point.y()*(Double)get(ring,"zoom");
        check(ring.getStackUnderMouse(x,y)!=null,"JEI hover ingredient");
        ring.m_6375_(x,y,button);ring.m_6348_(x,y,button);
    }
    static void shot(String name){var mc=Minecraft.m_91087_();Screenshot.m_92295_(mc.f_91069_,name+".png",mc.m_91385_(),message->log("SCREENSHOT "+name));}
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent event){
        if(event.phase!=TickEvent.Phase.END)return;
        var mc=Minecraft.m_91087_();
        GLFW.glfwHideWindow(mc.m_91268_().m_85439_());
        mc.m_91300_().m_94919_();
        try{
            if(mc.f_91074_==null){
                if(++totalTicks%120==0)log("WAIT screen="+(mc.f_91080_==null?"null":mc.f_91080_.getClass().getName())+" overlay="+mc.m_91265_());
                if(!connecting&&mc.m_91265_()==null&&mc.f_91080_!=null){connecting=true;log("CONNECT isolated loopback");ConnectScreen.m_278792_(mc.f_91080_,mc,new ServerAddress("127.0.0.1",25575),new ServerData("Local UI fixture","127.0.0.1:25575",false),false);}
                return;
            }
            if(++ticks<15)return;
            ticks=0;
            switch(stage){
                case 0 -> {install(mc);verifyStockSnapshot();stage++;}
                case 1 -> {
                    var tree=(net.minecraft.client.gui.components.Button)get(parent,"gtlcore$treeButton");
                    var circle=(net.minecraft.client.gui.components.Button)get(parent,"gtlcore$craftingRing");
                    check(!tree.f_93624_&&circle.f_93624_,"GRAPH toolbar identity");shot("01-graph-entry");summary(false);stage++;
                }
                case 2 -> {
                    var tree=(net.minecraft.client.gui.components.Button)get(parent,"gtlcore$treeButton");
                    var circle=(net.minecraft.client.gui.components.Button)get(parent,"gtlcore$craftingRing");
                    check(tree.f_93624_&&!circle.f_93624_,"LEGACY toolbar identity");shot("02-legacy-entry");summary(true);openRing(mc);stage++;
                }
                case 3 -> {
                    if(get(ring,"tree")==null)return;
                    check(get(ring,"error")==null,"Graph build error");
                    var materialInputs=(Set<?>)get(get(ring,"model"),"initialInputs");
                    check(materialInputs.equals(Set.of(keys.get(303))),"only unreserved cyclic initial input is orange: "+materialInputs);
                    shot("03-tree-normal");log("BOUNDS "+ring.getGuiLeft()+" "+ring.getGuiTop()+" "+mc.m_91268_().m_85449_());
                    set(ring,"zoom",0.2);set(ring,"panX",20.0);set(ring,"panY",5.0);stage++;
                }
                case 4 -> {shot("04-tree-zoom02");set(ring,"page",1);set(ring,"lastViewport",null);stage++;}
                case 5 -> {if(get(ring,"fullLayout")==null)return;shot("05-full-graph");set(ring,"zoom",0.2);set(ring,"panX",-50.0);set(ring,"panY",-40.0);stage++;}
                case 6 -> {shot("06-full-pan-clip");set(ring,"page",0);set(ring,"missingOnly",true);invoke(ring,"refreshTree");stage++;}
                case 7 -> {if(get(ring,"tree")==null)return;shot("07-missing");mc.m_91152_(new GraphViewSettingsScreen(ring));stage++;}
                case 8 -> {shot("08-settings");mc.m_91152_(ring);invoke(ring,"exportDiagram");stage++;}
                case 9 -> {
                    if(get(ring,"export")!=null)return;
                    mc.m_91268_().m_166447_(900,600);stage++;
                }
                case 10 -> {shot("09-small-window");stage++;}
                case 11 -> {set(ring,"missingOnly",false);set(ring,"page",0);invoke(ring,"home");stage++;}
                case 12 -> {
                    if(get(ring,"tree")==null)return;
                    ring.m_7933_(264,0,0);check((Integer)get(ring,"selectedEntry")>0,"down navigation");
                    ring.m_7933_(265,0,0);check((Integer)get(ring,"selectedEntry")==0,"up navigation");
                    clickRoot(0);stage++;
                }
                case 13 -> {check(mc.f_91080_.getClass().getName().contains("RecipesGui"),"left-click JEI recipes: "+mc.f_91080_.getClass());shot("10-jei-recipes");mc.m_91152_(ring);stage++;}
                case 14 -> {clickRoot(1);stage++;}
                case 15 -> {check(mc.f_91080_.getClass().getName().contains("RecipesGui"),"right-click JEI uses");shot("11-jei-uses");mc.m_91152_(ring);stage++;}
                case 16 -> {set(ring,"page",2);invoke(ring,"resetView");stage++;}
                case 17 -> {shot("12-seeded-cycle");set(ring,"mode",0);set(ring,"page",0);stage++;}
                case 18 -> {shot("13-materials");set(ring,"mode",2);stage++;}
                case 19 -> {shot("14-recipe-io");set(ring,"mode",3);stage++;}
                case 20 -> {shot("15-steps");set(ring,"mode",1);set(ring,"page",0);invoke(ring,"home");stage++;}
                case 21 -> {if(get(ring,"tree")==null)return;invoke(ring,"exportDiagram");stage++;}
                case 22 -> {
                    if(get(ring,"export")!=null)return;
                    CraftingRingScreen.fail(new GraphRingPackets.Failure(998,id,"PLAN_CHANGED"));
                    check(get(ring,"error")==null&&(Boolean)get(ring,"waitingForPlan"),"changed plan is recoverable, not a terminal error");
                    check(get(ring,"model")==null&&((List<?>)get(ring,"rows")).isEmpty(),"old graph discarded on plan change");
                    UUID previous=id;id=UUID.randomUUID();summary(true);
                    CraftingRingScreen.fail(new GraphRingPackets.Failure(998,previous,"VIEW_LIMIT"));
                    check(get(ring,"error")==null&&id.equals(get(ring,"planId")),"late failure from old plan is ignored");
                    var gp=new GraphPlan<AEKey>(keys.get(1),1,true,new PlanStep.Sequence(List.of()),Map.of(),Map.of(keys.get(1),1L),Map.of(),Map.of(keys.get(1),1L),GraphPlan.Result.MISSING_INPUT,0,0);
                    var stale=new GraphRingView(previous,gp,Map.of()).page(0);
                    CraftingRingScreen.receive(new GraphRingPackets.Response(998,stale));
                    check(((List<?>)get(ring,"rows")).isEmpty(),"late page cannot mix into new graph");
                    var data=new GraphRingView(id,gp,Map.of()).page(0);
                    CraftingRingScreen.receive(new GraphRingPackets.Response(998,data));
                    stage++;
                }
                case 23 -> {
                    if(get(ring,"tree")==null)return;
                    check(((List<?>)get(ring,"rows")).size()==2,"replacement plan contains its resource and empty sequence only");
                    var replacement=(PlanTopology<?>)get(get(ring,"model"),"topology");
                    check(replacement.nodes().size()==1&&keys.get(1).equals(replacement.nodes().get(0).resource()),"replacement topology excludes every old dependency");
                    check(get(ring,"error")==null&&!(Boolean)get(ring,"waitingForPlan"),"replacement plan recovered automatically");
                    shot("16-plan-replacement");
                    log("PASS preview identity replacement, waiting state, late page/failure rejection; graph/legacy toolbar, tree, zoom, clip, missing, settings, exports, navigation and JEI");
                    mc.m_91395_();stage++;
                }
            }
        }catch(Throwable e){e.printStackTrace();log("FAIL "+e);mc.m_91395_();stage=999;}
    }
}
