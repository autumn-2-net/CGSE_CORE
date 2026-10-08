package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.RegistryBuilder;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import java.util.*;

public final class CaptureReview {
    static void boot() throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var builder=new RegistryBuilder<AEKeyType>().setName(new ResourceLocation("gtlcore","snapshot_review_keys"));
        var create=RegistryBuilder.class.getDeclaredMethod("create");create.setAccessible(true);
        var registry=(net.minecraftforge.registries.IForgeRegistry<AEKeyType>)create.invoke(builder);
        AEKeyTypesInternal.setRegistry(()->registry);AEKeyTypesInternal.register(AEKeyType.items());AEKeyTypesInternal.register(AEKeyType.fluids());
    }
    static class Wrapped implements IPatternDetails {
        final IPatternDetails delegate;final IInput[] inputs;
        Wrapped(IPatternDetails delegate,int genericFrom) {
            this.delegate=delegate;inputs=Arrays.copyOf(delegate.getInputs(),delegate.getInputs().length,IInput[].class);
            for(int i=genericFrom;i<inputs.length;i++) {
                var nativeInput=inputs[i];final int slot=i;
                inputs[i]=new IInput() {
                    public GenericStack[] getPossibleInputs(){return nativeInput.getPossibleInputs();}
                    public long getMultiplier(){return nativeInput.getMultiplier();}
                    public boolean isValid(AEKey key,Level level){return slot==0?nativeInput.isValid(key,level):key.getPrimaryKey().equals(nativeInput.getPossibleInputs()[0].what().getPrimaryKey());}
                    public AEKey getRemainingKey(AEKey key){return slot==0?null:AEItemKey.of(Items.BUCKET);}
                };
            }
        }
        public AEItemKey getDefinition(){return delegate.getDefinition();}
        public IInput[] getInputs(){return inputs;}
        public GenericStack[] getOutputs(){return delegate.getOutputs();}
        public boolean supportsPushInputsToExternalInventory(){return true;}
    }
    record Result(CapturedPattern pattern,long work){}
    static Result capture(IPatternDetails pattern,KeyCounter stock) {
        var budget=new PlanningBudget(0,1_000_000,()->false);
        var work=new GtlPatternCatalog.CandidateCapture(pattern,PatternFingerprint.capture(pattern),stock,null,budget);
        while(!work.step()){}
        return new Result(work.result(),budget.nodes());
    }
    static List<GraphRecipe<AEKey>> recipes(CapturedPattern pattern) {
        List<GraphRecipe<AEKey>> result=new ArrayList<>();var context=new PatternFingerprint.Context();
        var expansion=pattern.expand(new PlanningBudget(0,1_000_000,()->false));
        while(expansion.hasNext())result.add(expansion.next().encode("same-binding",context));return result;
    }
    public static void main(String[] args)throws Exception {
        boot();KeyCounter stock=new KeyCounter();AEKey paper=null,iron=null;
        for(int i=0;i<8192;i++){var tag=new CompoundTag();tag.putInt("variant",i);var key=AEItemKey.of(Items.PAPER,tag);stock.add(key,3_000_000_000L);if(i==4096)paper=key;}
        for(int i=0;i<16;i++){var tag=new CompoundTag();tag.putInt("variant",i);var key=AEItemKey.of(Items.IRON_INGOT,tag);stock.add(key,100);if(i==0)iron=key;}
        for(boolean mixed:new boolean[]{false,true}) {
            var data=new CompoundTag();var ins=new ListTag();var outs=new ListTag();
            ins.add(GenericStack.writeTag(new GenericStack(paper,3_000_000_000L)));
            if(mixed)ins.add(GenericStack.writeTag(new GenericStack(iron,2)));
            outs.add(GenericStack.writeTag(new GenericStack(AEItemKey.of(Items.BOOK),7)));data.put("in",ins);data.put("out",outs);
            var original=new AEProcessingPattern(AEItemKey.of(Items.PAPER,data));
            var wrapped=new Wrapped(original,mixed?1:original.getInputs().length);var full=new Wrapped(original,0);
            Result fast=capture(wrapped,stock),generic=capture(full,stock);
            if(!recipes(fast.pattern).equals(recipes(generic.pattern)))throw new AssertionError("Lost alternatives/amounts/remainders");
            if(mixed && fast.pattern.size()!=16)throw new AssertionError("Fuzzy input collapsed");
            for(int i=0;i<32;i++)capture(wrapped,stock);
            List<Long> nanos=new ArrayList<>();for(int i=0;i<101;i++){long began=System.nanoTime();capture(wrapped,stock);nanos.add(System.nanoTime()-began);}Collections.sort(nanos);
            System.out.println("CAPTURE\t"+mixed+"\t"+fast.work+"\t"+generic.work+"\t"+nanos.get(50)/1e6);
        }
        System.out.println("PASS: native wrapped and mixed custom inputs preserve exact variants, long quantities, fuzzy remainder and recipe IDs");
    }
}
