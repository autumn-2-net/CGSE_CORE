package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.config.ConfigHolder;
import org.cgse.core.*;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import net.minecraft.nbt.*;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import java.math.BigInteger;
import java.util.*;

public final class ByteCostIntegrationReview {
    static void equal(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    public static void main(String[] args)throws Exception {
        CaptureReview.boot();ConfigHolder previous=ConfigHolder.INSTANCE;
        try {
            ConfigHolder.INSTANCE=new ConfigHolder();equal(ConfigHolder.INSTANCE.ae2GraphByteCostMode,CraftingCostModel.Mode.LEGACY);equal(ConfigHolder.INSTANCE.ae2GraphPlannerMaxSteps,20_000_000);
            AEKey iron=AEItemKey.of(Items.IRON_INGOT),copper=AEItemKey.of(Items.COPPER_INGOT),water=AEFluidKey.of(Fluids.WATER),target=AEItemKey.of(Items.BOOK);
            var patternData=new CompoundTag();var ins=new ListTag();var outs=new ListTag();ins.add(GenericStack.writeTag(new GenericStack(iron,1)));outs.add(GenericStack.writeTag(new GenericStack(target,1)));patternData.put("in",ins);patternData.put("out",outs);
            IPatternDetails pattern=new AEProcessingPattern(AEItemKey.of(Items.PAPER,patternData));
            var recipe=new GraphRecipe<AEKey>("task","task",List.of(new GraphRecipe.Slot<>(iron,7),new GraphRecipe.Slot<>(copper,7),new GraphRecipe.Slot<>(water,4000)),Map.of(target,1L));
            var graph=new GraphPlan<>(target,1,true,new PlanStep.Batch("task",1),Map.of("task",recipe),Map.of(iron,7L,copper,7L,water,4000L),Map.of(),Map.of(copper,7L),GraphPlan.Result.MISSING_INPUT,0,0);
            var stock=Map.of(iron,7L,water,1000L);
            var legacy=new AeGraphPlan(graph,Map.of("task",pattern),Set.of(water),stock);
            long oldCost=legacy.bytes();ConfigHolder.INSTANCE.ae2GraphByteCostMode=CraftingCostModel.Mode.COMPACT;
            var compact=new AeGraphPlan(graph,Map.of("task",pattern),Set.of(water),stock);
            equal(legacy.bytes(),oldCost);equal(legacy.costMode(),CraftingCostModel.Mode.LEGACY);
            BigInteger itemUnits=BigInteger.valueOf(iron.getType().getAmountPerByte()),fluidUnits=BigInteger.valueOf(water.getType().getAmountPerByte());
            BigInteger expected=CheckedAmounts.ceilDiv(BigInteger.valueOf(14).multiply(fluidUnits).add(BigInteger.valueOf(4000).multiply(itemUnits)),itemUnits.multiply(fluidUnits)).add(BigInteger.valueOf(8));
            equal(compact.exactBytes(),expected);equal(compact.summaryView().bytes(),expected.longValueExact());
            equal(compact.usedItems().get(iron),7L);equal(compact.usedItems().get(water),1000L);equal(compact.emittedItems().get(water),3000L);equal(compact.missingItems().get(copper),7L);
            if(!compact.fitsStorage(compact.bytes(),false)||compact.fitsStorage(compact.bytes()-1,false))throw new AssertionError("Admission boundary");
            var funded=new GraphPlan<>(target,1,true,graph.steps(),graph.recipes(),graph.initialExact(),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
            var full=new AeGraphPlan(funded,Map.of("task",pattern),Set.of(),funded.initial());equal(full.exactBytes(),compact.exactBytes());
            var enormous=new GraphPlan<>(target,Long.MAX_VALUE,true,graph.steps(),graph.recipes(),Map.of(iron,BigInteger.ONE.shiftLeft(100)),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
            var wide=new AeGraphPlan(enormous,Map.of("task",pattern),Set.of(),Map.of(iron,Long.MAX_VALUE));
            equal(wide.bytes(),Long.MAX_VALUE);if(wide.fitsStorage(Long.MAX_VALUE,false)||!wide.fitsStorage(Long.MAX_VALUE,true))throw new AssertionError("Exact CPU admission");
            ConfigHolder.INSTANCE.ae2GraphByteCostMode=CraftingCostModel.Mode.LEGACY;equal(compact.costMode(),CraftingCostModel.Mode.COMPACT);equal(compact.exactBytes(),expected);
            equal(new AeGraphPlan(graph,Map.of("task",pattern),Set.of(water),stock).bytes(),oldCost);
            System.out.println("PASS AE plan costs: default LEGACY, COMPACT="+expected+", item/fluid rounding, missing/emission partition, immutable preview configuration, exact CPU storage admission and capped UI");
        } finally {ConfigHolder.INSTANCE=previous;}
    }
}
