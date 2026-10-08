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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.*;
import java.util.*;

@Mod("gtlclientprobe")
@Mod.EventBusSubscriber(modid="gtlclientprobe",value=Dist.CLIENT)
public final class ClientProbe {
    static int stage, ticks, totalTicks, branches=16, depth=8;
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
    static void log(String x){System.out.println("[Graph Cache] "+x);}
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
        keys.clear();
        ring=new CraftingRingScreen(parent);mc.m_91152_(ring);
        var recipes=new LinkedHashMap<String,GraphRecipe<AEKey>>();
        var steps=new ArrayList<PlanStep>();
        var initial=new LinkedHashMap<AEKey,Long>();
        var missing=new LinkedHashMap<AEKey,Long>();
        var inputs=new ArrayList<GraphRecipe.Slot<AEKey>>();
        int seed=branches*(depth+2);
        for(int i=0;i<seed+20;i++)keys.add(key(i));
        keys.set(0,AEFluidKey.of(ForgeRegistries.FLUIDS.getValue(new ResourceLocation("minecraft","water"))));
        long amount=Integer.MAX_VALUE;
        for(int branch=0;branch<branches;branch++){
            int first=branch*(depth+2);
            initial.put(keys.get(first),amount);if(branch%3==0)missing.put(keys.get(first),amount-2);
            for(int d=0;d<depth;d++){
                String r="b"+branch+"d"+d;
                recipes.put(r,new GraphRecipe<>(r,r,List.of(new GraphRecipe.Slot<>(keys.get(first+d),1)),Map.of(keys.get(first+d+1),1L)));
                steps.add(new PlanStep.Batch(r,amount));
            }
            inputs.add(new GraphRecipe.Slot<>(keys.get(first+depth),1));
        }
        initial.put(keys.get(0),amount+1);
        initial.put(keys.get(seed+0),1L);
        recipes.put("seed_in",new GraphRecipe<>("seed_in","seed_in",List.of(new GraphRecipe.Slot<>(keys.get(seed+0),1)),Map.of(keys.get(seed+1),1L)));
        recipes.put("seed_out",new GraphRecipe<>("seed_out","seed_out",List.of(new GraphRecipe.Slot<>(keys.get(seed+1),1)),Map.of(keys.get(seed+0),1L,keys.get(seed+2),1L)));
        steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("seed_in",1),new PlanStep.Batch("seed_out",1))),amount));
        inputs.add(new GraphRecipe.Slot<>(keys.get(seed+2),1));
        // A consuming cycle must show initial input without inventing a retained seed.
        initial.put(keys.get(seed+3),amount+1);
        missing.put(keys.get(seed+3),amount-1);
        recipes.put("loss_in",new GraphRecipe<>("loss_in","loss_in",List.of(new GraphRecipe.Slot<>(keys.get(seed+3),2)),Map.of(keys.get(seed+4),1L)));
        recipes.put("loss_out",new GraphRecipe<>("loss_out","loss_out",List.of(new GraphRecipe.Slot<>(keys.get(seed+4),1)),Map.of(keys.get(seed+3),1L,keys.get(seed+5),1L)));
        steps.add(new PlanStep.Repeat(new PlanStep.Sequence(List.of(new PlanStep.Batch("loss_in",1),new PlanStep.Batch("loss_out",1))),amount));
        inputs.add(new GraphRecipe.Slot<>(keys.get(seed+5),1));
        recipes.put("finish",new GraphRecipe<>("finish","finish",inputs,Map.of(keys.get(seed+19),1L)));
        steps.add(new PlanStep.Batch("finish",amount));
        var plan=new GraphPlan<>(keys.get(seed+19),amount,true,new PlanStep.Sequence(steps),recipes,initial,Map.of(keys.get(0),1L,keys.get(seed+0),1L),missing,GraphPlan.Result.MISSING_INPUT,0,0);
        var view=new GraphRingView(id,plan,plan.patternTimes());
        for(int offset=0;offset<view.page(0).total();){var page=view.page(offset);CraftingRingScreen.receive(new GraphRingPackets.Response(998,page));offset+=page.rows().size();}
    }

    static Object stable;
    static int benchmark=-1, samples;
    static boolean benchmarkDone;
    static long screenStart;
    static final ArrayList<Double> timings=new ArrayList<>(), cold=new ArrayList<>();
    static String label;
    static String capture;
    static Object cache()throws Exception{return get(ring,"diagramCache");}
    static boolean complete()throws Exception{
        return (Integer)call(cache(),"progress",new Class<?>[]{})==100;
    }
    static Object call(Object obj,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=obj.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(obj,args);
    }
    static int width()throws Exception{return (Integer)call(ring,"viewWidth",new Class<?>[]{});}
    static int height()throws Exception{return (Integer)call(ring,"viewHeight",new Class<?>[]{});}
    static void shot(String name){var mc=Minecraft.m_91087_();Screenshot.m_92295_(mc.f_91069_,name+".png",mc.m_91385_(),message->log("SCREENSHOT "+name));}
    static void fail(Throwable e){e.printStackTrace();log("FAIL "+e);Minecraft.m_91087_().m_91395_();stage=999;}
    static void startBench(String name){label=name;benchmark=0;samples=-5;timings.clear();benchmarkDone=false;}
    static double median(List<Double> list){var copy=new ArrayList<>(list);Collections.sort(copy);return copy.get(copy.size()/2);}
    static void draw(GuiGraphics g,boolean cached)throws Exception{
        g.m_280262_();
        int draw=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] vp=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,vp);
        double x=(Double)get(ring,"panX"),y=(Double)get(ring,"panY"),z=(Double)get(ring,"zoom");
        int w=width(),h=height(),left=ring.getGuiLeft(),top=ring.getGuiTop();
        g.m_280168_().m_85836_();g.m_280168_().m_85837_(left,top,0);
        g.m_280509_(10,48,10+w,48+h,0xFFD4D4D7);g.m_280262_();
        if(cached){
            ((List<?>)get(ring,"hits")).clear();
            call(ring,"drawGraph",new Class<?>[]{GuiGraphics.class,int.class,int.class},g,-100,-100);
        }else{
            g.m_280588_(left+10,top+48,left+10+w,top+48+h);
            g.m_280168_().m_85836_();g.m_280168_().m_85837_(10+x,48+y,0);g.m_280168_().m_85841_((float)z,(float)z,1);
            var box=new PlanGraphLayout.Box(-x/z,-y/z,w/z,h/z);
            set(ring,"hoveredNode",-1);
            var type=Class.forName("org.gtlcore.gtlcore.client.ae2.graph.CraftingRingScreen$DiagramPainter");
            var constructor=type.getDeclaredConstructor(CraftingRingScreen.class,PlanGraphLayout.Box.class,double.class,double.class);constructor.setAccessible(true);
            var painter=constructor.newInstance(ring,box,z,z*(Double)get(cache(),"guiScale"));
            while(!(Boolean)call(painter,"advance",new Class<?>[]{GuiGraphics.class,long.class},g,Long.MAX_VALUE)){}
            g.m_280262_();g.m_280168_().m_85849_();g.m_280618_();
        }
        g.m_280262_();g.m_280168_().m_85849_();
        int[] after=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,after);
        check(draw==GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)&&read==GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)&&Arrays.equals(vp,after),"framebuffer and viewport preserved");
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"GL state");
    }
    @SubscribeEvent public static void before(ScreenEvent.Render.Pre event){if(event.getScreen()==ring)screenStart=System.nanoTime();}
    @SubscribeEvent public static void after(ScreenEvent.Render.Post event){
        if(event.getScreen()!=ring)return;
        if(stage==2||stage==3||stage==6||stage==8||stage==9||stage==12||stage==13)cold.add((System.nanoTime()-screenStart)/1e6);
        try{
            if(capture!=null){
                draw(event.getGuiGraphics(),true);GL11.glFinish();shot(capture+"-cached");
                draw(event.getGuiGraphics(),false);GL11.glFinish();shot(capture+"-direct");
                capture=null;
            }
            if(benchmark<0)return;
            GL11.glFinish();long start=System.nanoTime();
            draw(event.getGuiGraphics(),benchmark==1);GL11.glFinish();
            double time=(System.nanoTime()-start)/1e6;
            if(samples++>=0)timings.add(time);
            if(samples>=30){
                var sorted=new ArrayList<>(timings);Collections.sort(sorted);
                log("BENCH "+label+" "+(benchmark==0?"direct":"cached")+" median_ms="+median(timings)+" p95_ms="+sorted.get((int)(sorted.size()*.95))+" max_ms="+sorted.get(sorted.size()-1)+" samples="+timings.size());
                timings.clear();samples=-5;benchmark++;
                if(benchmark==2){benchmark=-1;benchmarkDone=true;}
            }
        }catch(Throwable e){fail(e);}
    }

    static int frames;
    static double originX,originY;
    static Map<Object,Object> retained=new HashMap<>();
    static Map<Object,Object> tiles()throws Exception{return (Map<Object,Object>)get(cache(),"tiles");}
    static void remember()throws Exception{retained.clear();retained.putAll(tiles());}
    static void retained()throws Exception{
        check(tiles().size()<=32,"tile memory bounded");
        for(var entry:tiles().entrySet())if(retained.containsKey(entry.getKey()))check(retained.get(entry.getKey())==entry.getValue(),"retained tile not restarted");
        remember();
    }
    static void dragStart()throws Exception{
        originX=(Double)get(ring,"panX");originY=(Double)get(ring,"panY");
        set(ring,"dragging",true);set(ring,"dragDistance",10.);remember();frames=0;cold.clear();
    }
    static void drag()throws Exception{
        retained();set(ring,"panX",originX+Math.sin(frames*.075)*width()*1.8);
        set(ring,"panY",originY+Math.sin(frames*.09)*height()*.8);
    }
    static void endDrag()throws Exception{
        set(ring,"panX",originX);set(ring,"panY",originY);set(ring,"dragging",false);
        log("DRAG frames="+cold.size()+" median_ms="+median(cold)+" max_ms="+Collections.max(cold)+" tiles="+tiles().size());
    }
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent event){
        if(event.phase!=TickEvent.Phase.END||stage==999)return;
        var mc=Minecraft.m_91087_();GLFW.glfwHideWindow(mc.m_91268_().m_85439_());mc.m_91300_().m_94919_();
        try{
            if(mc.f_91074_==null){
                if(++totalTicks%120==0)log("WAIT screen="+(mc.f_91080_==null?"null":mc.f_91080_.getClass().getName()));
                if(!connecting&&mc.m_91265_()==null&&mc.f_91080_!=null){connecting=true;ConnectScreen.m_278792_(mc.f_91080_,mc,new ServerAddress("127.0.0.1",25576),new ServerData("Local UI fixture","127.0.0.1:25576",false),false);}
                return;
            }
            if(capture!=null)return;
            if(++totalTicks>24000)throw new AssertionError("fixture timeout stage="+stage);
            if(totalTicks%180==0)log("STATE stage="+stage+" screen="+(mc.f_91080_==null?"null":mc.f_91080_.getClass().getSimpleName())+" progress="+(ring==null?-1:call(cache(),"progress",new Class<?>[]{})));
            frames++;
            switch(stage){
                case 0 -> {install(mc);branches=200;depth=20;openRing(mc);log("START 8212-node fixture");stage++;}
                case 1 -> {if(get(ring,"tree")==null)return;set(ring,"zoom",.01);var tree=(PlanDependencyLayout.View)get(ring,"tree");var box=tree.bounds();set(ring,"panX",width()/2.-(box.x()+box.width()/2)*.01);set(ring,"panY",height()/2.-(box.y()+box.height()/2)*.01);dragStart();stage++;}
                case 2 -> {drag();if(frames<180)return;endDrag();frames=0;stage++;}
                case 3 -> {retained();if(!complete()){check(frames<1500,"min zoom eventually completes");return;}log("PASS min zoom completes after continuous drag");capture="01-min-tree";stage++;}
                case 4 -> {startBench("min-tree");stage++;}
                case 5 -> {if(!benchmarkDone)return;remember();dragStart();stage++;}
                case 6 -> {drag();if(frames<120)return;endDrag();frames=0;stage++;}
                case 7 -> {if(!complete())return;set(ring,"page",1);invoke(ring,"resetView");frames=0;stage=70;}
                case 70 -> {var full=(PlanGraphLayout<?>)get(ring,"fullLayout");if(full==null)return;set(ring,"zoom",.01);var box=full.bounds();set(ring,"panX",width()/2.-(box.x()+box.width()/2)*.01);set(ring,"panY",height()/2.-(box.y()+box.height()/2)*.01);dragStart();stage=8;}
                case 8 -> {drag();if(frames<180)return;endDrag();frames=0;stage++;}
                case 9 -> {retained();if(!complete()){check(frames<1500,"full eventually completes");return;}log("PASS minimum full graph drag, all tiles complete");capture="02-min-full";stage++;}
                case 10 -> {startBench("min-full");stage++;}
                case 11 -> {if(!benchmarkDone)return;set(ring,"zoom",1.);set(ring,"panX",30.);set(ring,"panY",30.);dragStart();stage++;}
                case 12 -> {drag();if(frames<140)return;endDrag();frames=0;stage++;}
                case 13 -> {retained();if(!complete())return;log("PASS normal zoom uses tile cache while dragging");capture="03-full-detail";stage++;}
                case 14 -> {startBench("full-detail");stage++;}
                case 15 -> {if(!benchmarkDone)return;set(ring,"page",3);invoke(ring,"resetView");set(ring,"zoom",.65);stage++;}
                case 16 -> {if(!complete())return;capture="04-seed";remember();stage++;}
                case 17 -> {Field f=cache().getClass().getDeclaredField("resourceVersion");f.setAccessible(true);f.setInt(null,f.getInt(null)+1);stage++;}
                case 18 -> {if(!complete())return;for(var tile:tiles().values())check(!retained.containsValue(tile),"reload releases old tiles");mc.m_91268_().m_166447_(900,600);stage++;}
                case 19 -> {if(!complete()||frames<10)return;capture="05-resize";stage++;}
                case 20 -> {var saved=cache();mc.m_91152_(parent);check(((Map<?,?>)get(saved,"tiles")).isEmpty(),"screen close releases all tiles");log("ALL PASS continuous pan before/after completion, minimum/normal zoom, tile progress retention, clip/framebuffer, seeds, resource reload, resize, cleanup");mc.m_91395_();stage=999;}
            }
        }catch(Throwable e){fail(e);}
    }
}
