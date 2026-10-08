package local.circuitregression;

import org.gtlcore.gtlcore.config.*;
import org.gtlcore.gtlcore.common.item.VirtualIngredientBehavior;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;
import org.gtlcore.gtlcore.integration.ae2.handler.SlotCacheManager;
import org.gtlcore.gtlcore.integration.ae2.widget.AEPatternViewExtendSlotWidget;
import org.gtlcore.gtlcore.api.machine.trait.MEPart.IMEPatternTrait;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.lang.reflect.*;
import java.util.*;

@Mod("localcircuitregression")
public final class CircuitRegressionProbe {
    static int passed, failed;
    public CircuitRegressionProbe(){MinecraftForge.EVENT_BUS.addListener(this::register);}
    void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.m_82127_("circuit_regression").requires(s->s.m_6761_(4)).executes(c->{
            var oldEngine=ConfigHolder.INSTANCE.ae2CraftingEngine;
            var oldMode=ConfigHolder.INSTANCE.ae2CalculationMode;
            passed=failed=0;
            try {
                System.out.println("[Circuit Regression] ENV java="+System.getProperty("java.version")+" dist="+net.minecraftforge.fml.loading.FMLLoader.getDist()+" coreSource="+MEPatternBufferPartMachine.class.getProtectionDomain().getCodeSource().getLocation());
                for(var mod:ModList.get().getMods())if(Set.of("minecraft","forge","gtlcore","gtceu","ae2","gtladditions","expatternprovider","extendedae_plus","kotlinforforge").contains(mod.getModId()))System.out.println("[Circuit Regression] MOD "+mod.getModId()+"="+mod.getVersion());
                ConfigHolder.INSTANCE.ae2CalculationMode=AE2CalculationMode.MAX_FAST;
                for(var engine:AECraftingEngine.values())for(boolean gui:new boolean[]{false,true})for(boolean byproducts:new boolean[]{false,true}) {
                    ConfigHolder.INSTANCE.ae2CraftingEngine=engine;
                    var f=new Fixture(c.getSource().m_81372_(),gui,byproducts);
                    try {matrix(f,engine+" route="+(gui?"GUI":"TERMINAL")+" keepByproducts="+byproducts);}
                    finally {f.close();}
                }
                System.out.println("[Circuit Regression] DONE passed="+passed+" failed="+failed);
            }catch(Throwable ex){ex.printStackTrace();System.out.println("[Circuit Regression] ABORT "+ex);}
            finally {ConfigHolder.INSTANCE.ae2CraftingEngine=oldEngine;ConfigHolder.INSTANCE.ae2CalculationMode=oldMode;}
            return 1;
        }));
    }
    interface Scenario{void run()throws Exception;}
    static void test(Fixture f,String label,String name,Scenario action)throws Exception{
        f.reset();
        try{action.run();passed++;System.out.println("[Circuit Regression] PASS "+label+" case="+name);}
        catch(Throwable ex){failed++;System.out.println("[Circuit Regression] FAIL "+label+" case="+name+" "+ex);}
    }
    static void matrix(Fixture f,String label)throws Exception {
        test(f,label,"new slot circuit extraction",()->{f.set(f.pattern(1,1));f.circuit(1);check(f.match(1),"real InternalSlot rejects initial circuit1");f.outputs();});
        test(f,label,"changed input direct replacement keeps new circuit",()->{f.set(f.pattern(1,1));f.seedRecipe();f.set(f.pattern(2,2));f.circuit(2);check(f.match(2)&&!f.match(1),"real InternalSlot failed replacement circuit match");f.cache(false);f.outputs();});
        test(f,label,"circuit-only edit invalidates GT recipe",()->{f.set(f.pattern(1,1));f.seedRecipe();f.set(f.pattern(2,1));f.circuit(2);check(f.match(2)&&!f.match(1),"real InternalSlot failed circuit-only match");f.cache(false);});
        test(f,label,"circuit removal same effective IO clears state",()->{f.set(f.pattern(2,1));f.seedRecipe();f.set(f.pattern(-1,1));f.circuit(-1);check(!f.match(2),"removed circuit2 still matches");f.cache(false);});
        test(f,label,"circuit removal changed IO clears state",()->{f.set(f.pattern(2,1));f.set(f.pattern(-1,2));f.circuit(-1);check(!f.match(2),"removed circuit2 still matches");});
        test(f,label,"repeat unchanged notification preserves GT cache",()->{f.set(f.pattern(2,1));f.seedRecipe();f.notifySlot();f.circuit(2);f.cache(true);check(f.match(2),"repeated notification changed match");});
        test(f,label,"equivalent reencoded stack preserves GT cache",()->{f.set(f.pattern(2,1));f.seedRecipe();f.set(f.pattern(2,1));f.circuit(2);f.cache(true);});
        test(f,label,"remove and reinsert clears then restores",()->{f.set(f.pattern(1,1));f.seedRecipe();f.set(ItemStack.f_41583_);f.circuit(-1);f.cache(false);check(f.machine.getAvailablePatterns().isEmpty(),"removed pattern advertised");f.set(f.pattern(2,2));f.circuit(2);check(f.match(2),"reinserted circuit does not match");});
        test(f,label,"circuit-only in-place NBT edit invalidates GT cache",()->{f.set(f.pattern(1,1));f.seedRecipe();f.machine.getPatternInventory().getStackInSlot(0).m_41751_(f.pattern(2,1).m_41783_().m_6426_());f.notifySlot();f.circuit(2);f.cache(false);});
        test(f,label,"shared fallback returns after independent circuit removal",()->{f.shared(4);f.set(f.pattern(2,1));check(IntCircuitBehaviour.getCircuitConfiguration(f.machine.getCircuitForRecipe(0))==2,"independent circuit did not take precedence");f.set(f.pattern(-1,1));f.circuit(-1);check(IntCircuitBehaviour.getCircuitConfiguration(f.machine.getCircuitForRecipe(0))==4,"shared circuit fallback hidden by stale individual circuit");});
        test(f,label,"virtual circuit repeat notification preserved",()->{f.set(f.virtualPattern(7));f.pushVirtual(7);f.circuit(7);f.seedRecipe();f.notifySlot();f.circuit(7);f.cache(true);check(f.match(7),"virtual circuit lost on repeated notification");});
        test(f,label,"virtual circuit byproduct refresh preserved",()->{f.set(f.virtualPattern(7));f.pushVirtual(7);f.refreshByproducts();f.circuit(7);check(f.match(7),"virtual circuit lost on byproduct refresh");});
        test(f,label,"virtual to real replacement keeps new real circuit",()->{f.set(f.virtualPattern(7));f.pushVirtual(7);f.seedRecipe();f.set(f.pattern(2,2));f.circuit(2);check(f.virtualCircuit()==-1,"obsolete virtual circuit survives replacement");check(f.match(2)&&!f.match(7),"new real circuit displaced by virtual circuit");f.cache(false);});
        test(f,label,"real to virtual clears real before dispatch",()->{f.set(f.pattern(2,1));f.set(f.virtualPattern(7));f.circuit(-1);f.pushVirtual(7);f.circuit(7);check(f.match(7)&&!f.match(2),"virtual dispatch kept stale real circuit");});
        test(f,label,"removing virtual pattern clears dispatched circuit",()->{f.set(f.virtualPattern(7));f.pushVirtual(7);f.set(ItemStack.f_41583_);f.circuit(-1);check(f.virtualCircuit()==-1&&!f.match(7),"removed virtual circuit still matches");});
    }
    static final class Fixture {
        final ServerLevel level;final BlockPos pos=new BlockPos(29,180,29);final boolean gui,byproducts;
        final MEPatternBufferPartMachine machine;final Object internal;final SlotCacheManager cache;final Slot slot;
        final IMEPatternTrait recipes;final GTRecipe recipe;final Method match,add,refresh;
        final AEItemKey raw=key("raw"),target=key("target"),byproduct=key("byproduct");
        Fixture(ServerLevel level,boolean gui,boolean byproducts)throws Exception {
            this.level=level;this.gui=gui;this.byproducts=byproducts;
            check(level.m_46859_(pos),"fixture position must be air");
            var block=net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new ResourceLocation("gtceu","me_final_pattern_buffer"));
            level.m_7731_(pos,block.m_49966_(),3);machine=(MEPatternBufferPartMachine)MetaMachine.getMachine(level,pos);
            internal=((Object[])field(machine.getClass(),"internalInventory").get(machine))[0];
            cache=(SlotCacheManager)field(internal.getClass(),"cacheManager").get(internal);
            match=internal.getClass().getMethod("handleItemInternal",Object2LongMap.class,int.class,boolean.class);match.setAccessible(true);
            add=internal.getClass().getMethod("add",AEKey.class,long.class);add.setAccessible(true);
            refresh=machine.getClass().getDeclaredMethod("refreshAllByProduct");refresh.setAccessible(true);
            recipes=machine.getMETrait();
            recipe=GTRecipeTypes.ASSEMBLER_RECIPES.recipeBuilder("local_circuit_regression").inputItems(raw.toStack()).outputItems(target.toStack()).circuitMeta(1).duration(20).EUt(1).buildRawRecipe();
            field(machine.getClass(),"keepByProduct").set(machine,byproducts);
            var widget=new AEPatternViewExtendSlotWidget(machine.getPatternInventory(),0,0,0);
            var changed=machine.getClass().getDeclaredMethod("onPatternChange",int.class);changed.setAccessible(true);
            widget.setOnPatternSlotChanged(()->{try{changed.invoke(machine,0);}catch(Exception ex){throw new RuntimeException(ex);}});
            slot=(Slot)field(widget.getClass(),"slotReference").get(widget);
        }
        ItemStack pattern(int circuit,long amount){
            var inputs=new ArrayList<GenericStack>();if(circuit>=0)inputs.add(GenericStack.fromItemStack(IntCircuitBehaviour.stack(circuit)));inputs.add(new GenericStack(raw,amount));
            return encoded(inputs.toArray(GenericStack[]::new));
        }
        ItemStack virtualPattern(int circuit){return encoded(new GenericStack[]{new GenericStack(AEItemKey.of(VirtualIngredientBehavior.wrap(IntCircuitBehaviour.stack(circuit))),1),new GenericStack(raw,1)});}
        ItemStack encoded(GenericStack[] inputs){return PatternDetailsHelper.encodeProcessingPattern(inputs,new GenericStack[]{new GenericStack(target,1),new GenericStack(byproduct,1)});}
        void reset()throws Exception {machine.getTerminalPatternInventory().setItemDirect(0,ItemStack.f_41583_);shared(-1);cache.clearAllCaches();recipes.getSlot2RecipesCache().clear();((boolean[])field(machine.getClass(),"cacheRecipe").get(machine))[0]=false;}
        void set(ItemStack value){if(gui)slot.m_5852_(value);else machine.getTerminalPatternInventory().setItemDirect(0,value);}
        void notifySlot(){slot.m_6654_();}
        void circuit(int expected){check(cache.getCircuitCache()==expected,"cached circuit="+cache.getCircuitCache()+" expected="+expected);}
        boolean match(int circuit)throws Exception{return (boolean)match.invoke(internal,new Object2LongOpenHashMap<>(),circuit,true);}
        void seedRecipe(){recipes.setSlotCacheRecipe(0,recipe);check(recipes.hasCacheInSlot(0)&&recipes.getSlot2RecipesCache().get(0).contains(recipe),"GT recipe cache fixture seed failed");}
        void cache(boolean expected){check(recipes.hasCacheInSlot(0)==expected&&recipes.getSlot2RecipesCache().containsKey(0)==expected,"GT recipe cache present="+recipes.hasCacheInSlot(0)+" map="+recipes.getSlot2RecipesCache().containsKey(0)+" expected="+expected);}
        void outputs(){check(machine.getAvailablePatterns().size()==1,"expected one available pattern");check(machine.getAvailablePatterns().get(0).getOutputs().length==(byproducts?2:1),"byproduct mode changed");}
        void shared(int circuit)throws Exception{var inventory=(NotifiableItemStackHandler)field(machine.getClass(),"sharedCircuitInventory").get(machine);inventory.setStackInSlot(0,circuit<0?ItemStack.f_41583_:IntCircuitBehaviour.stack(circuit));}
        void pushVirtual(int circuit)throws Exception{add.invoke(internal,AEItemKey.of(VirtualIngredientBehavior.wrap(IntCircuitBehaviour.stack(circuit))),1L);}
        int virtualCircuit()throws Exception{return (int)field(internal.getClass(),"virtualCircuitConfig").get(internal);}
        void refreshByproducts()throws Exception{refresh.invoke(machine);}
        void close(){level.m_7471_(pos,false);}
    }
    static AEItemKey key(String id){var tag=new CompoundTag();tag.m_128359_("local_circuit_regression",id);return AEItemKey.of(Items.f_42597_,tag);}
    static Field field(Class<?> type,String name)throws Exception {for(var c=type;c!=null;c=c.getSuperclass())try{var field=c.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static void check(boolean good,String reason){if(!good)throw new AssertionError(reason);}
}
