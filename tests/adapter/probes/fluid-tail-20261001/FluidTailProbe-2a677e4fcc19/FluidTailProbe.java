package org.gtlcore.gtlcore.common.machine.multiblock.part.ae;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import net.minecraftforge.fml.common.Mod;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import java.math.BigInteger;

@Mod("localfluidprobe")
public final class FluidTailProbe {
    public static String math() {
        var result = new StringBuilder();
        for (long p : new long[]{192153673490149L, 192153584011663L, 2147483647L}) {
            long expected = Math.multiplyExact(750L, p);
            long actual = ContentModifier.multiplier((double) p).apply(750L).longValue();
            result.append("p=").append(p).append(" expected=").append(expected)
                .append(" actual=").append(actual).append(" lost=").append(expected - actual).append(';');
        }
        return result.toString();
    }

    public static String recipe(GTRecipe recipe) {
        var result = new StringBuilder("recipe=" + recipe.id + ';');
        for (long p : new long[]{192153673490149L, 192153584011663L}) {
            GTRecipe scaled = RecipeCalculationHelper.INSTANCE.multipleRecipe(recipe, p);
            long in = FluidRecipeCapability.CAP.of(scaled.getInputContents(FluidRecipeCapability.CAP).get(0).content).getAmount();
            long out = FluidRecipeCapability.CAP.of(scaled.getOutputContents(FluidRecipeCapability.CAP).get(0).content).getAmount();
            long expected = Math.multiplyExact(750L, p);
            result.append("p=").append(p).append(" in=").append(in).append(" out=").append(out)
                .append(" input_tail=").append(expected-in).append(" output_missing=").append(expected-out).append(';');
        }
        return result.toString();
    }

    public static String prepare(MEPatternBufferPartMachine buffer, GTRecipe recipe, String parallel) {
        long p = Long.parseLong(parallel);
        var slot = buffer.getInternalSlot(1);
        slot.getItemInventory().clear();
        slot.getFluidInventory().clear();
        var item = ItemRecipeCapability.CAP.of(recipe.getInputContents(ItemRecipeCapability.CAP).get(0).content).m_43908_()[0];
        var fluid = FluidRecipeCapability.CAP.of(recipe.getInputContents(FluidRecipeCapability.CAP).get(0).content).getStacks()[0];
        slot.add(AEItemKey.of(item), p);
        slot.add(AEFluidKey.of(fluid.getFluid()), Math.multiplyExact(750L, p));
        slot.getOnContentsChanged().run();
        return "loaded p=" + p + " fluid=" + Math.multiplyExact(750L,p);
    }

    public static String slot(MEPatternBufferPartMachine buffer) {
        var slot = buffer.getInternalSlot(1);
        return "items=" + slot.getItemInventory() + " fluids=" + slot.getFluidInventory();
    }
}
