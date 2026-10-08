/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability
 *  com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability
 *  com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder
 *  com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability
 *  com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability
 *  com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine
 *  com.gregtechceu.gtceu.api.recipe.GTRecipe
 *  com.gregtechceu.gtceu.api.recipe.GTRecipeType
 *  com.gregtechceu.gtceu.api.recipe.chance.boost.ChanceBoostFunction
 *  com.gregtechceu.gtceu.api.recipe.content.Content
 *  com.gregtechceu.gtceu.api.recipe.content.ContentModifier
 *  com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient
 *  com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient$FluidValue
 *  com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient$TagValue
 *  com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient$Value
 *  com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient
 *  com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder
 *  com.gtladd.gtladditions.api.machine.IEnergyMachine
 *  com.gtladd.gtladditions.api.recipe.FastRecipeModify$ReduceResult
 *  com.gtladd.gtladditions.api.recipe.ParallelCalculate
 *  com.gtladd.gtladditions.api.recipe.content.ContentList
 *  com.gtladd.gtladditions.api.recipe.content.ContentList$MaxChanceContent
 *  com.gtladd.gtladditions.api.recipe.ledger.ConsumePlan
 *  com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext
 *  com.gtladd.gtladditions.mixin.gtceu.api.FluidValueAccessor
 *  com.gtladd.gtladditions.utils.GTRecipeUtils$RecipeData
 *  com.gtladd.gtladditions.utils.MachineUtil
 *  com.gtladd.gtladditions.utils.MathUtil
 *  com.gtladd.gtladditions.utils.SingleStream
 *  com.lowdragmc.lowdraglib.side.fluid.FluidStack
 *  it.unimi.dsi.fastutil.objects.Object2IntMap
 *  it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
 *  it.unimi.dsi.fastutil.objects.ObjectArrayList
 *  it.unimi.dsi.fastutil.objects.ObjectListIterator
 *  it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
 *  kotlin.Metadata
 *  kotlin.jvm.functions.Function1
 *  kotlin.jvm.functions.Function2
 *  kotlin.jvm.internal.Intrinsics
 *  kotlin.jvm.internal.SourceDebugExtension
 *  kotlin.ranges.RangesKt
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.tags.TagKey
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.crafting.Ingredient
 *  net.minecraft.world.item.crafting.Ingredient$ItemValue
 *  net.minecraft.world.level.material.Fluid
 *  org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine
 *  org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine
 *  org.gtlcore.gtlcore.api.recipe.IGTRecipe
 *  org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper
 *  org.gtlcore.gtlcore.api.recipe.chance.LongChanceLogic
 *  org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient
 *  org.jetbrains.annotations.NotNull
 *  org.jetbrains.annotations.Nullable
 */
package com.gtladd.gtladditions.utils;

import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.chance.boost.ChanceBoostFunction;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import com.gtladd.gtladditions.api.machine.IEnergyMachine;
import com.gtladd.gtladditions.api.machine.IRecipeSearchProvider;
import com.gtladd.gtladditions.api.recipe.FastRecipeModify;
import com.gtladd.gtladditions.api.recipe.ParallelCalculate;
import com.gtladd.gtladditions.api.recipe.content.ContentList;
import com.gtladd.gtladditions.api.recipe.ledger.ConsumePlan;
import com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext;
import com.gtladd.gtladditions.mixin.gtceu.api.FluidValueAccessor;
import com.gtladd.gtladditions.utils.GTRecipeUtils;
import com.gtladd.gtladditions.utils.MachineUtil;
import com.gtladd.gtladditions.utils.MathUtil;
import com.gtladd.gtladditions.utils.SingleStream;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import kotlin.Metadata;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import kotlin.ranges.RangesKt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import org.gtlcore.gtlcore.api.machine.multiblock.ParallelMachine;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.api.recipe.chance.LongChanceLogic;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(mv={2, 0, 0}, k=1, xi=48, d1={"\u0000\u00c4\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\t\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\b\n\u0002\b\u0007\n\u0002\u0010#\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u0006\n\u0002\b\u0006\n\u0002\u0010%\n\u0002\u0018\u0002\n\u0002\u0010!\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0002\b\f\n\u0002\u0010 \n\u0002\b\u0003\n\u0002\u0010\u0004\n\u0002\b\u000b\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\n\n\u0002\u0018\u0002\n\u0002\b\u0007\b\u00c6\u0002\u0018\u00002\u00020\u0001:\u0001zB\t\b\u0002\u00a2\u0006\u0004\b\u0002\u0010\u0003J-\u0010\t\u001a\u00028\u0000\"\u0004\b\u0000\u0010\u0004*\u00020\u00052\u0014\u0010\b\u001a\u0010\u0012\u0006\u0012\u0004\u0018\u00010\u0007\u0012\u0004\u0012\u00028\u00000\u0006\u00a2\u0006\u0004\b\t\u0010\nJ[\u0010\u0014\u001a\u0004\u0018\u00010\f*\u00020\u00052\u0014\u0010\r\u001a\u0010\u0012\u0004\u0012\u00020\u000b\u0012\u0006\u0012\u0004\u0018\u00010\f0\u00062\u0014\b\u0002\u0010\u000f\u001a\u000e\u0012\u0004\u0012\u00020\u0001\u0012\u0004\u0012\u00020\u000e0\u00062\u0006\u0010\u0011\u001a\u00020\u00102\u0006\u0010\u0012\u001a\u00020\u00102\n\b\u0002\u0010\u0013\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\u0004\b\u0014\u0010\u0015JE\u0010\u0016\u001a\u0004\u0018\u00010\f*\u00020\u00052\u0014\u0010\r\u001a\u0010\u0012\u0004\u0012\u00020\u000b\u0012\u0006\u0012\u0004\u0018\u00010\f0\u00062\u0006\u0010\u0011\u001a\u00020\u00102\u0006\u0010\u0012\u001a\u00020\u00102\n\b\u0002\u0010\u0013\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\u0004\b\u0016\u0010\u0017Je\u0010\u001c\u001a\u0004\u0018\u00010\f*\u00020\u00052\f\u0010\u0019\u001a\b\u0012\u0004\u0012\u00020\f0\u00182\u0012\u0010\u000f\u001a\u000e\u0012\u0004\u0012\u00020\u0001\u0012\u0004\u0012\u00020\u000e0\u00062\u0012\u0010\u001b\u001a\u000e\u0012\u0004\u0012\u00020\f\u0012\u0004\u0012\u00020\u001a0\u00062\u0006\u0010\u0011\u001a\u00020\u00102\u0006\u0010\u0012\u001a\u00020\u00102\n\b\u0002\u0010\u0013\u001a\u0004\u0018\u00010\u0007\u00a2\u0006\u0004\b\u001c\u0010\u001dJ\u0019\u0010 \u001a\u00020\u000e*\u00020\f2\u0006\u0010\u001f\u001a\u00020\u001e\u00a2\u0006\u0004\b \u0010!J\u0019\u0010 \u001a\u00020\u000e*\u00020\u000b2\u0006\u0010\u001f\u001a\u00020\u001e\u00a2\u0006\u0004\b \u0010\"J\u0019\u0010$\u001a\u00020#*\u00020\u000b2\u0006\u0010\u001f\u001a\u00020\u001e\u00a2\u0006\u0004\b$\u0010%J!\u0010)\u001a\u00020\f*\u00020\f2\u0006\u0010'\u001a\u00020&2\u0006\u0010(\u001a\u00020\u000b\u00a2\u0006\u0004\b)\u0010*J!\u0010+\u001a\u00020\f*\u00020\f2\u0006\u0010'\u001a\u00020&2\u0006\u0010(\u001a\u00020\u000b\u00a2\u0006\u0004\b+\u0010*J1\u0010/\u001a\u00020\f*\u00020\f2\u0006\u0010'\u001a\u00020&2\u0006\u0010(\u001a\u00020\u000b2\u0006\u0010-\u001a\u00020,2\u0006\u0010.\u001a\u00020,\u00a2\u0006\u0004\b/\u00100J)\u0010/\u001a\u00020\f*\u00020\f2\u0006\u0010'\u001a\u00020&2\u0006\u0010(\u001a\u00020\u000b2\u0006\u00101\u001a\u00020\u0010\u00a2\u0006\u0004\b/\u00102JM\u0010:\u001a\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u000304\u0012\n\u0012\b\u0012\u0004\u0012\u00020605032\u001c\u00107\u001a\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u000304\u0012\n\u0012\b\u0012\u0004\u0012\u00020605032\b\u00109\u001a\u0004\u0018\u000108H\u0002\u00a2\u0006\u0004\b:\u0010;J\u0013\u0010<\u001a\u00020\f*\u00020\fH\u0002\u00a2\u0006\u0004\b<\u0010=J=\u0010?\u001a\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u000304\u0012\n\u0012\b\u0012\u0004\u0012\u00020605032\u0006\u0010'\u001a\u00020&2\u0006\u0010>\u001a\u00020\f2\u0006\u0010(\u001a\u00020\u000bH\u0002\u00a2\u0006\u0004\b?\u0010@JK\u0010H\u001a\u0004\u0018\u0001062\f\u0010B\u001a\b\u0012\u0002\b\u0003\u0018\u00010A2\n\u0010C\u001a\u0006\u0012\u0002\b\u0003042\u0006\u0010D\u001a\u00020\u000b2\u0006\u0010E\u001a\u0002062\u0006\u0010F\u001a\u00020\u00102\u0006\u0010G\u001a\u00020\u0010H\u0002\u00a2\u0006\u0004\bH\u0010IJ1\u0010J\u001a\u00020#*\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u000304\u0012\n\u0012\b\u0012\u0004\u0012\u00020605032\u0006\u0010(\u001a\u00020\u000bH\u0002\u00a2\u0006\u0004\bJ\u0010KJ?\u0010O\u001a(\u0012\f\u0012\n M*\u0004\u0018\u00010606\u0018\u0001 M*\u0012\u0012\f\u0012\n M*\u0004\u0018\u00010606\u0018\u00010N05*\u00020\f2\u0006\u0010L\u001a\u00020\u000b\u00a2\u0006\u0004\bO\u0010PJ%\u0010)\u001a\u00020#*\u0002062\n\u0010C\u001a\u0006\u0012\u0002\b\u0003042\u0006\u00109\u001a\u000208\u00a2\u0006\u0004\b)\u0010QJ%\u0010)\u001a\u00020#*\u0002062\n\u0010C\u001a\u0006\u0012\u0002\b\u0003042\u0006\u00109\u001a\u00020R\u00a2\u0006\u0004\b)\u0010SJ\u0019\u0010U\u001a\u000206*\u0002062\u0006\u0010T\u001a\u00020R\u00a2\u0006\u0004\bU\u0010VJ\u0019\u0010W\u001a\u000206*\u0002062\u0006\u0010T\u001a\u00020R\u00a2\u0006\u0004\bW\u0010VJ\u001d\u0010X\u001a\u00020\u000b*\u0002062\n\u0010C\u001a\u0006\u0012\u0002\b\u000304\u00a2\u0006\u0004\bX\u0010YJ\u001f\u0010\\\u001a\u00020\u000e\"\u0004\b\u0000\u0010Z*\u0002062\u0006\u0010[\u001a\u00028\u0000\u00a2\u0006\u0004\b\\\u0010]J\u0019\u0010\\\u001a\u00020\u000e*\u00020^2\u0006\u0010`\u001a\u00020_\u00a2\u0006\u0004\b\\\u0010aJ\u001f\u0010\\\u001a\u00020\u000e*\u00020^2\f\u0010c\u001a\b\u0012\u0004\u0012\u00020_0b\u00a2\u0006\u0004\b\\\u0010dJ\u0011\u0010f\u001a\u00020^*\u00020e\u00a2\u0006\u0004\bf\u0010gJ\u001b\u0010f\u001a\u00020i*\u00020h2\b\b\u0002\u0010X\u001a\u00020\u000b\u00a2\u0006\u0004\bf\u0010jR\u0015\u0010/\u001a\u00020\f*\u00020\f8F\u00a2\u0006\u0006\u001a\u0004\bk\u0010=R\u0015\u0010n\u001a\u00020\u000b*\u00020\f8F\u00a2\u0006\u0006\u001a\u0004\bl\u0010mR\u0015\u0010q\u001a\u00020\u0010*\u00020\f8F\u00a2\u0006\u0006\u001a\u0004\bo\u0010pR\u0015\u0010s\u001a\u00020\u000b*\u00020\f8F\u00a2\u0006\u0006\u001a\u0004\br\u0010mR\u0015\u0010X\u001a\u00020\u000b*\u00020t8F\u00a2\u0006\u0006\u001a\u0004\bu\u0010vR\u0015\u0010y\u001a\u00020e*\u00020^8F\u00a2\u0006\u0006\u001a\u0004\bw\u0010x\u00a8\u0006{"}, d2={"Lcom/gtladd/gtladditions/utils/GTRecipeUtils;", "", "<init>", "()V", "T", "Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;", "Lkotlin/Function1;", "Lcom/gtladd/gtladditions/api/recipe/ledger/RecipeSearchContext;", "block", "withSearchContext", "(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;Lkotlin/jvm/functions/Function1;)Ljava/lang/Object;", "", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "getRecipe", "", "testBefore", "", "maxThread", "minDuration", "ctx", "getOverclockRecipe", "(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;IILcom/gtladd/gtladditions/api/recipe/ledger/RecipeSearchContext;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "getFastMultipleRecipe", "(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;Lkotlin/jvm/functions/Function1;IILcom/gtladd/gtladditions/api/recipe/ledger/RecipeSearchContext;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "", "getRecipeSet", "Lcom/gtladd/gtladditions/api/recipe/FastRecipeModify$ReduceResult;", "modifyRecipe", "getMultipleRecipe", "(Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;Ljava/util/Set;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;IILcom/gtladd/gtladditions/api/recipe/ledger/RecipeSearchContext;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "Lcom/gtladd/gtladditions/api/machine/IEnergyMachine;", "machine", "matchEUt", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gtladd/gtladditions/api/machine/IEnergyMachine;)Z", "(JLcom/gtladd/gtladditions/api/machine/IEnergyMachine;)Z", "", "handleEUt", "(JLcom/gtladd/gtladditions/api/machine/IEnergyMachine;)V", "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;", "holder", "parallel", "modify", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;J)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "modifyNotTick", "", "reductionDuration", "reductionEUt", "copy", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;JDD)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "duration", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;JI)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "", "Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;", "", "Lcom/gregtechceu/gtceu/api/recipe/content/Content;", "contents", "Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;", "modifier", "copyMapContents", "(Ljava/util/Map;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;)Ljava/util/Map;", "markInternallyAggregated", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "recipe", "copyContentChances", "(Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;J)Ljava/util/Map;", "Lit/unimi/dsi/fastutil/objects/Object2IntMap;", "cache", "cap", "times", "content", "max", "chance", "rollChange", "(Lit/unimi/dsi/fastutil/objects/Object2IntMap;Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;JLcom/gregtechceu/gtceu/api/recipe/content/Content;II)Lcom/gregtechceu/gtceu/api/recipe/content/Content;", "contentModify", "(Ljava/util/Map;J)V", "eu", "kotlin.jvm.PlatformType", "", "setEU", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;J)Ljava/util/List;", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;)V", "", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;Ljava/lang/Number;)V", "x", "plus", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Ljava/lang/Number;)Lcom/gregtechceu/gtceu/api/recipe/content/Content;", "setAmount", "amount", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;)J", "K", "k", "test", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Ljava/lang/Object;)Z", "Lcom/gregtechceu/gtceu/api/recipe/ingredient/FluidIngredient;", "Lnet/minecraft/world/level/material/Fluid;", "fluid", "(Lcom/gregtechceu/gtceu/api/recipe/ingredient/FluidIngredient;Lnet/minecraft/world/level/material/Fluid;)Z", "Lnet/minecraft/tags/TagKey;", "key", "(Lcom/gregtechceu/gtceu/api/recipe/ingredient/FluidIngredient;Lnet/minecraft/tags/TagKey;)Z", "Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;", "create", "(Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;)Lcom/gregtechceu/gtceu/api/recipe/ingredient/FluidIngredient;", "Lnet/minecraft/world/item/ItemStack;", "Lorg/gtlcore/gtlcore/api/recipe/ingredient/LongIngredient;", "(Lnet/minecraft/world/item/ItemStack;J)Lorg/gtlcore/gtlcore/api/recipe/ingredient/LongIngredient;", "getCopy", "getGetEU", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)J", "getEU", "getEuTier", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)I", "euTier", "getLongParallel", "longParallel", "Lnet/minecraft/world/item/crafting/Ingredient;", "getAmount", "(Lnet/minecraft/world/item/crafting/Ingredient;)J", "getStack", "(Lcom/gregtechceu/gtceu/api/recipe/ingredient/FluidIngredient;)Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;", "stack", "RecipeData", "gtladditions"})
@SourceDebugExtension(value={"SMAP\nGTRecipeUtils.kt\nKotlin\n*S Kotlin\n*F\n+ 1 GTRecipeUtils.kt\ncom/gtladd/gtladditions/utils/GTRecipeUtils\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n+ 4 _Arrays.kt\nkotlin/collections/ArraysKt___ArraysKt\n*L\n1#1,425:1\n1#2:426\n1863#3:427\n1863#3,2:428\n1864#3:430\n1863#3:431\n1863#3,2:432\n1864#3:434\n13409#4,2:435\n13409#4,2:437\n13409#4,2:439\n*S KotlinDebug\n*F\n+ 1 GTRecipeUtils.kt\ncom/gtladd/gtladditions/utils/GTRecipeUtils\n*L\n278#1:427\n280#1:428,2\n278#1:430\n329#1:431\n330#1:432,2\n329#1:434\n404#1:435,2\n409#1:437,2\n414#1:439,2\n*E\n"})
public final class GTRecipeUtils {
    @NotNull
    public static final GTRecipeUtils INSTANCE = new GTRecipeUtils();

    private GTRecipeUtils() {
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public final <T> T withSearchContext(@NotNull WorkableElectricMultiblockMachine $this$withSearchContext, @NotNull Function1<? super RecipeSearchContext, ? extends T> block) {
        Object object;
        IRecipeSearchProvider provider;
        Intrinsics.checkNotNullParameter((Object)$this$withSearchContext, (String)"<this>");
        Intrinsics.checkNotNullParameter(block, (String)"block");
        IRecipeSearchProvider iRecipeSearchProvider = provider = $this$withSearchContext instanceof IRecipeSearchProvider ? (IRecipeSearchProvider)$this$withSearchContext : null;
        if (provider == null || !($this$withSearchContext instanceof IRecipeCapabilityMachine)) {
            return (T)block.invoke(null);
        }
        RecipeSearchContext recipeSearchContext = provider.getActiveSearchContext();
        if (recipeSearchContext != null) {
            RecipeSearchContext it = recipeSearchContext;
            boolean bl = false;
            return (T)block.invoke((Object)it);
        }
        RecipeSearchContext recipeSearchContext2 = provider.beginSearchCycle($this$withSearchContext);
        Intrinsics.checkNotNullExpressionValue((Object)recipeSearchContext2, (String)"beginSearchCycle(...)");
        RecipeSearchContext ctx = recipeSearchContext2;
        try {
            object = block.invoke((Object)ctx);
        }
        finally {
            provider.endSearchCycle();
        }
        return (T)object;
    }

    @Nullable
    public final GTRecipe getOverclockRecipe(@NotNull WorkableElectricMultiblockMachine $this$getOverclockRecipe, @NotNull Function1<? super Long, ? extends GTRecipe> getRecipe, @NotNull Function1<Object, Boolean> testBefore, int maxThread, int minDuration, @Nullable RecipeSearchContext ctx) {
        Intrinsics.checkNotNullParameter((Object)$this$getOverclockRecipe, (String)"<this>");
        Intrinsics.checkNotNullParameter(getRecipe, (String)"getRecipe");
        Intrinsics.checkNotNullParameter(testBefore, (String)"testBefore");
        if (!$this$getOverclockRecipe.hasProxies()) {
            return null;
        }
        long maxEUt = $this$getOverclockRecipe.getOverclockVoltage();
        if (maxEUt <= 0L) {
            return null;
        }
        ContentList il = new ContentList();
        ContentList fl = new ContentList();
        long p = ((ParallelMachine)$this$getOverclockRecipe).getMaxParallel();
        double totalEu = 0.0;
        boolean hasSubTickParallelized = false;
        int batchSize = 1;
        int index = 1;
        if (index <= maxThread) {
            GTRecipe recipe;
            while ((GTRecipe)getRecipe.invoke((Object)p) != null && ((Boolean)testBefore.invoke((Object)recipe)).booleanValue()) {
                List it;
                if (!RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)((IRecipeLogicMachine)$this$getOverclockRecipe), (GTRecipe)recipe)) {
                    RecipeSearchContext recipeSearchContext = ctx;
                    if (recipeSearchContext != null) {
                        recipeSearchContext.markStale();
                    }
                    break;
                }
                RecipeSearchContext recipeSearchContext = ctx;
                if (recipeSearchContext != null) {
                    recipeSearchContext.deductRecipe(recipe);
                }
                hasSubTickParallelized = hasSubTickParallelized || IGTRecipe.of((GTRecipe)recipe).isSubTickParallelized();
                totalEu += (double)recipe.duration * (double)this.getGetEU(recipe);
                if (recipe.getOutputContents((RecipeCapability)ItemRecipeCapability.CAP) != null) {
                    boolean bl = false;
                    il.addAll((Collection)it);
                }
                if (recipe.getOutputContents((RecipeCapability)FluidRecipeCapability.CAP) != null) {
                    boolean bl = false;
                    fl.addAll((Collection)it);
                }
                batchSize = RangesKt.coerceAtLeast((int)batchSize, (int)IGTRecipe.of((GTRecipe)recipe).getBatchSize());
                if (totalEu > (double)maxEUt || index == maxThread) break;
                ++index;
            }
        }
        if (il.isEmpty() && fl.isEmpty()) {
            return null;
        }
        double d = totalEu / (double)maxEUt;
        GTRecipeBuilder o = GTRecipeBuilder.ofRaw().duration(MathUtil.INSTANCE.maxToInt((Number)minDuration, (Number)d));
        Map map = o.tickInput;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"tickInput");
        Map map2 = map;
        EURecipeCapability eURecipeCapability = EURecipeCapability.CAP;
        ContentList contentList = ContentList.Companion.getEUtList(d > (double)minDuration ? (Number)maxEUt : (Number)((double)maxEUt * d / (double)minDuration));
        map2.put(eURecipeCapability, contentList);
        if (!il.isEmpty()) {
            Map map3 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"output");
            map2 = map3;
            map2.put(ItemRecipeCapability.CAP, il);
        }
        if (!fl.isEmpty()) {
            Map map4 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"output");
            map2 = map4;
            map2.put(FluidRecipeCapability.CAP, fl);
        }
        GTRecipe gTRecipe = o.buildRawRecipe();
        Intrinsics.checkNotNullExpressionValue((Object)gTRecipe, (String)"buildRawRecipe(...)");
        GTRecipe result = this.markInternallyAggregated(gTRecipe);
        IGTRecipe.of((GTRecipe)result).setSubTickParallelized(hasSubTickParallelized);
        if (batchSize > 1) {
            IGTRecipe.of((GTRecipe)result).setBatchSize(batchSize);
        }
        return result;
    }

    public static /* synthetic */ GTRecipe getOverclockRecipe$default(GTRecipeUtils gTRecipeUtils, WorkableElectricMultiblockMachine workableElectricMultiblockMachine, Function1 function1, Function1 function12, int n, int n2, RecipeSearchContext recipeSearchContext, int n3, Object object) {
        if ((n3 & 2) != 0) {
            function12 = GTRecipeUtils::getOverclockRecipe$lambda$1;
        }
        if ((n3 & 0x10) != 0) {
            recipeSearchContext = null;
        }
        return gTRecipeUtils.getOverclockRecipe(workableElectricMultiblockMachine, (Function1<? super Long, ? extends GTRecipe>)function1, (Function1<Object, Boolean>)function12, n, n2, recipeSearchContext);
    }

    @Nullable
    public final GTRecipe getFastMultipleRecipe(@NotNull WorkableElectricMultiblockMachine $this$getFastMultipleRecipe, @NotNull Function1<? super Long, ? extends GTRecipe> getRecipe, int maxThread, int minDuration, @Nullable RecipeSearchContext ctx) {
        Intrinsics.checkNotNullParameter((Object)$this$getFastMultipleRecipe, (String)"<this>");
        Intrinsics.checkNotNullParameter(getRecipe, (String)"getRecipe");
        if (!$this$getFastMultipleRecipe.hasProxies()) {
            return null;
        }
        long maxEUt = $this$getFastMultipleRecipe.getOverclockVoltage();
        if (maxEUt <= 0L) {
            return null;
        }
        ContentList il = new ContentList();
        ContentList fl = new ContentList();
        long rp = (long)((ParallelMachine)$this$getFastMultipleRecipe).getMaxParallel() * (long)maxThread;
        double totalEu = 0.0;
        while (rp > 0L && (GTRecipe)getRecipe.invoke((Object)rp) != null) {
            GTRecipe recipe;
            if (!RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)((IRecipeLogicMachine)$this$getFastMultipleRecipe), (GTRecipe)recipe)) {
                RecipeSearchContext recipeSearchContext = ctx;
                if (recipeSearchContext != null) {
                    recipeSearchContext.markStale();
                }
                break;
            }
            RecipeSearchContext recipeSearchContext = ctx;
            if (recipeSearchContext != null) {
                recipeSearchContext.deductRecipe(recipe);
            }
            rp -= this.getLongParallel(recipe);
            totalEu += (double)recipe.duration * (double)this.getGetEU(recipe);
            il.addAll((Collection)recipe.getOutputContents((RecipeCapability)ItemRecipeCapability.CAP));
            fl.addAll((Collection)recipe.getOutputContents((RecipeCapability)FluidRecipeCapability.CAP));
            if (!(totalEu > (double)maxEUt * (double)20 * (double)500)) continue;
        }
        if (il.isEmpty() && fl.isEmpty()) {
            return null;
        }
        double d = totalEu / (double)maxEUt;
        GTRecipeBuilder o = GTRecipeBuilder.ofRaw().duration(MathUtil.INSTANCE.maxToInt((Number)minDuration, (Number)d));
        Map map = o.tickInput;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"tickInput");
        Map map2 = map;
        EURecipeCapability eURecipeCapability = EURecipeCapability.CAP;
        ContentList contentList = ContentList.Companion.getEUtList(d > (double)minDuration ? (Number)maxEUt : (Number)((double)maxEUt * d / (double)minDuration));
        map2.put(eURecipeCapability, contentList);
        if (!il.isEmpty()) {
            Map map3 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"output");
            map2 = map3;
            map2.put(ItemRecipeCapability.CAP, il);
        }
        if (!fl.isEmpty()) {
            Map map4 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"output");
            map2 = map4;
            map2.put(FluidRecipeCapability.CAP, fl);
        }
        GTRecipe gTRecipe = o.buildRawRecipe();
        Intrinsics.checkNotNullExpressionValue((Object)gTRecipe, (String)"buildRawRecipe(...)");
        return this.markInternallyAggregated(gTRecipe);
    }

    public static /* synthetic */ GTRecipe getFastMultipleRecipe$default(GTRecipeUtils gTRecipeUtils, WorkableElectricMultiblockMachine workableElectricMultiblockMachine, Function1 function1, int n, int n2, RecipeSearchContext recipeSearchContext, int n3, Object object) {
        if ((n3 & 8) != 0) {
            recipeSearchContext = null;
        }
        return gTRecipeUtils.getFastMultipleRecipe(workableElectricMultiblockMachine, (Function1<? super Long, ? extends GTRecipe>)function1, n, n2, recipeSearchContext);
    }

    @Nullable
    public final GTRecipe getMultipleRecipe(@NotNull WorkableElectricMultiblockMachine $this$getMultipleRecipe, @NotNull Set<GTRecipe> getRecipeSet, @NotNull Function1<Object, Boolean> testBefore, @NotNull Function1<? super GTRecipe, FastRecipeModify.ReduceResult> modifyRecipe, int maxThread, int minDuration, @Nullable RecipeSearchContext ctx) {
        Intrinsics.checkNotNullParameter((Object)$this$getMultipleRecipe, (String)"<this>");
        Intrinsics.checkNotNullParameter(getRecipeSet, (String)"getRecipeSet");
        Intrinsics.checkNotNullParameter(testBefore, (String)"testBefore");
        Intrinsics.checkNotNullParameter(modifyRecipe, (String)"modifyRecipe");
        if (!$this$getMultipleRecipe.hasProxies()) {
            return null;
        }
        long maxEUt = $this$getMultipleRecipe.getOverclockVoltage();
        if (maxEUt <= 0L) {
            return null;
        }
        int length = getRecipeSet.size();
        if (length == 0) {
            return null;
        }
        long mp = ((ParallelMachine)$this$getMultipleRecipe).getMaxParallel();
        long[] pa = new long[length];
        int i = 0;
        long rp = mp * (long)maxThread;
        ObjectArrayFIFOQueue q = new ObjectArrayFIFOQueue(length);
        ObjectArrayList recipeList = new ObjectArrayList(length);
        for (GTRecipe r : getRecipeSet) {
            RecipeSearchContext recipeSearchContext = ctx;
            long p = recipeSearchContext != null ? recipeSearchContext.getPoolParallel(r, mp * (long)maxThread) : ParallelCalculate.INSTANCE.getMaxParallel($this$getMultipleRecipe, r, mp * (long)maxThread);
            if (p <= 0L) continue;
            recipeList.add((Object)r);
            pa[i] = MathUtil.INSTANCE.minToLong((Number)p, (Number)(mp * (long)maxThread / (long)length));
            if (p > pa[i]) {
                q.enqueue((Object)new RecipeData(i, p - pa[i]));
            }
            rp -= pa[i++];
        }
        if (recipeList.isEmpty()) {
            return null;
        }
        while (rp > 0L && !q.isEmpty()) {
            RecipeData d = (RecipeData)q.dequeue();
            long g = rp / (long)(q.size() + 1);
            if (g <= 0L) break;
            long give = MathUtil.INSTANCE.minToLong((Number)d.remainingWant(), (Number)g);
            int n = d.index();
            pa[n] = pa[n] + give;
            rp -= give;
            long nr = d.remainingWant() - give;
            if (nr <= 0L) continue;
            q.enqueue((Object)new RecipeData(d.index(), nr));
        }
        i = 0;
        if (!((Boolean)testBefore.invoke((Object)(mp * (long)maxThread - rp))).booleanValue()) {
            return null;
        }
        ContentList il = new ContentList();
        ContentList fl = new ContentList();
        double totalEu = 0.0;
        ObjectListIterator objectListIterator = recipeList.iterator();
        Intrinsics.checkNotNullExpressionValue((Object)objectListIterator, (String)"iterator(...)");
        ObjectListIterator objectListIterator2 = objectListIterator;
        while (objectListIterator2.hasNext()) {
            GTRecipe recipe = (GTRecipe)objectListIterator2.next();
            long par = pa[i] > 1L ? pa[i] : 1L;
            boolean ok = false;
            GTRecipe c = null;
            if (ctx != null) {
                ConsumePlan plan = ctx.allocate(recipe, par);
                if (plan != null) {
                    Intrinsics.checkNotNull((Object)recipe);
                    c = this.copy(recipe, (IRecipeLogicMachine)$this$getMultipleRecipe, plan.parallel, recipe.duration);
                    ok = RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)((IRecipeLogicMachine)$this$getMultipleRecipe), (GTRecipe)c);
                    if (ok) {
                        ctx.deduct(recipe, c, plan);
                    } else {
                        ctx.markStale();
                    }
                }
            } else {
                Intrinsics.checkNotNull((Object)recipe);
                c = this.copy(recipe, (IRecipeLogicMachine)$this$getMultipleRecipe, par, recipe.duration);
                ok = RecipeRunnerHelper.matchRecipeInput((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)$this$getMultipleRecipe), (GTRecipe)c) && RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)((IRecipeLogicMachine)$this$getMultipleRecipe), (GTRecipe)c);
            }
            ++i;
            if (ok && c != null) {
                FastRecipeModify.ReduceResult red = (FastRecipeModify.ReduceResult)modifyRecipe.invoke((Object)c);
                totalEu += (double)this.getGetEU(c) * (double)c.duration * MachineUtil.INSTANCE.maintenance((WorkableMultiblockMachine)$this$getMultipleRecipe) * red.reduceEUt() * red.reduceDuration();
                il.addAll((Collection)c.getOutputContents((RecipeCapability)ItemRecipeCapability.CAP));
                fl.addAll((Collection)c.getOutputContents((RecipeCapability)FluidRecipeCapability.CAP));
            }
            if (!(totalEu / (double)maxEUt > 10000.0)) continue;
        }
        if (il.isEmpty() && fl.isEmpty()) {
            return null;
        }
        double d = totalEu / (double)maxEUt;
        GTRecipeBuilder o = GTRecipeBuilder.ofRaw().duration(MathUtil.INSTANCE.maxToInt((Number)minDuration, (Number)d));
        Map map = o.tickInput;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"tickInput");
        Map map2 = map;
        EURecipeCapability eURecipeCapability = EURecipeCapability.CAP;
        ContentList contentList = ContentList.Companion.getEUtList(d > (double)minDuration ? (Number)maxEUt : (Number)((double)maxEUt * d / (double)minDuration));
        map2.put(eURecipeCapability, contentList);
        if (!il.isEmpty()) {
            Map map3 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"output");
            map2 = map3;
            map2.put(ItemRecipeCapability.CAP, il);
        }
        if (!fl.isEmpty()) {
            Map map4 = o.output;
            Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"output");
            map2 = map4;
            map2.put(FluidRecipeCapability.CAP, fl);
        }
        GTRecipe gTRecipe = o.buildRawRecipe();
        Intrinsics.checkNotNullExpressionValue((Object)gTRecipe, (String)"buildRawRecipe(...)");
        return this.markInternallyAggregated(gTRecipe);
    }

    public static /* synthetic */ GTRecipe getMultipleRecipe$default(GTRecipeUtils gTRecipeUtils, WorkableElectricMultiblockMachine workableElectricMultiblockMachine, Set set, Function1 function1, Function1 function12, int n, int n2, RecipeSearchContext recipeSearchContext, int n3, Object object) {
        if ((n3 & 0x20) != 0) {
            recipeSearchContext = null;
        }
        return gTRecipeUtils.getMultipleRecipe(workableElectricMultiblockMachine, set, (Function1<Object, Boolean>)function1, (Function1<? super GTRecipe, FastRecipeModify.ReduceResult>)function12, n, n2, recipeSearchContext);
    }

    public final boolean matchEUt(@NotNull GTRecipe $this$matchEUt, @NotNull IEnergyMachine machine) {
        Intrinsics.checkNotNullParameter((Object)$this$matchEUt, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        return this.getGetEU($this$matchEUt) <= machine.getEnergyContainerList().getEnergyStored();
    }

    public final boolean matchEUt(long $this$matchEUt, @NotNull IEnergyMachine machine) {
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        return $this$matchEUt > 0L && $this$matchEUt <= machine.getEnergyContainerList().getEnergyStored();
    }

    public final void handleEUt(long $this$handleEUt, @NotNull IEnergyMachine machine) {
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        machine.getEnergyContainerList().removeEnergy($this$handleEUt);
    }

    @NotNull
    public final GTRecipe getCopy(@NotNull GTRecipe $this$copy) {
        Intrinsics.checkNotNullParameter((Object)$this$copy, (String)"<this>");
        GTRecipeType gTRecipeType = $this$copy.recipeType;
        ResourceLocation resourceLocation = $this$copy.id;
        Map map = $this$copy.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        Map<RecipeCapability<?>, List<Content>> map2 = this.copyMapContents(map, null);
        Map map3 = $this$copy.outputs;
        Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"outputs");
        Map<RecipeCapability<?>, List<Content>> map4 = this.copyMapContents(map3, null);
        Map map5 = $this$copy.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map5, (String)"tickInputs");
        Map<RecipeCapability<?>, List<Content>> map6 = this.copyMapContents(map5, null);
        Map map7 = $this$copy.tickOutputs;
        Intrinsics.checkNotNullExpressionValue((Object)map7, (String)"tickOutputs");
        return new GTRecipe(gTRecipeType, resourceLocation, map2, map4, map6, this.copyMapContents(map7, null), $this$copy.inputChanceLogics, $this$copy.outputChanceLogics, $this$copy.tickInputChanceLogics, $this$copy.tickOutputChanceLogics, $this$copy.conditions, $this$copy.ingredientActions, $this$copy.data, $this$copy.duration, $this$copy.isFuel);
    }

    @NotNull
    public final GTRecipe modify(@NotNull GTRecipe $this$modify, @NotNull IRecipeLogicMachine holder, long parallel) {
        Intrinsics.checkNotNullParameter((Object)$this$modify, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        ((IGTRecipe)$this$modify).setRealParallels(parallel);
        Map map = $this$modify.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        this.contentModify(map, parallel);
        Map map2 = $this$modify.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map2, (String)"tickInputs");
        this.contentModify(map2, parallel);
        Map map3 = $this$modify.tickOutputs;
        Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"tickOutputs");
        this.contentModify(map3, parallel);
        Map<RecipeCapability<?>, List<Content>> o = this.copyContentChances(holder, $this$modify, parallel);
        $this$modify.outputs.replaceAll((arg_0, arg_1) -> GTRecipeUtils.modify$lambda$5((arg_0, arg_1) -> GTRecipeUtils.modify$lambda$4(o, arg_0, arg_1), arg_0, arg_1));
        return $this$modify;
    }

    @NotNull
    public final GTRecipe modifyNotTick(@NotNull GTRecipe $this$modifyNotTick, @NotNull IRecipeLogicMachine holder, long parallel) {
        Intrinsics.checkNotNullParameter((Object)$this$modifyNotTick, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        Map map = $this$modifyNotTick.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        this.contentModify(map, parallel);
        Map<RecipeCapability<?>, List<Content>> o = this.copyContentChances(holder, $this$modifyNotTick, parallel);
        $this$modifyNotTick.outputs.replaceAll((arg_0, arg_1) -> GTRecipeUtils.modifyNotTick$lambda$7((arg_0, arg_1) -> GTRecipeUtils.modifyNotTick$lambda$6(o, arg_0, arg_1), arg_0, arg_1));
        return $this$modifyNotTick;
    }

    @NotNull
    public final GTRecipe copy(@NotNull GTRecipe $this$copy, @NotNull IRecipeLogicMachine holder, long parallel, double reductionDuration, double reductionEUt) {
        Intrinsics.checkNotNullParameter((Object)$this$copy, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        ContentModifier modifier = ContentModifier.multiplier((double)parallel);
        ContentModifier modifierTick = ContentModifier.multiplier((double)((double)parallel * reductionEUt));
        GTRecipeType gTRecipeType = $this$copy.recipeType;
        ResourceLocation resourceLocation = $this$copy.id;
        Map map = $this$copy.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        Map<RecipeCapability<?>, List<Content>> map2 = this.copyMapContents(map, modifier);
        Map<RecipeCapability<?>, List<Content>> map3 = this.copyContentChances(holder, $this$copy, parallel);
        Map map4 = $this$copy.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"tickInputs");
        Map<RecipeCapability<?>, List<Content>> map5 = this.copyMapContents(map4, modifierTick);
        Map map6 = $this$copy.tickOutputs;
        Intrinsics.checkNotNullExpressionValue((Object)map6, (String)"tickOutputs");
        GTRecipe copy = new GTRecipe(gTRecipeType, resourceLocation, map2, map3, map5, this.copyMapContents(map6, modifierTick), $this$copy.inputChanceLogics, $this$copy.outputChanceLogics, $this$copy.tickInputChanceLogics, $this$copy.tickOutputChanceLogics, $this$copy.conditions, $this$copy.ingredientActions, $this$copy.data, RangesKt.coerceAtLeast((int)1, (int)((int)((double)$this$copy.duration * reductionDuration))), $this$copy.isFuel);
        ((IGTRecipe)copy).setRealParallels(parallel);
        return copy;
    }

    @NotNull
    public final GTRecipe copy(@NotNull GTRecipe $this$copy, @NotNull IRecipeLogicMachine holder, long parallel, int duration) {
        Intrinsics.checkNotNullParameter((Object)$this$copy, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        ContentModifier modifier = ContentModifier.multiplier((double)parallel);
        GTRecipeType gTRecipeType = $this$copy.recipeType;
        ResourceLocation resourceLocation = $this$copy.id;
        Map map = $this$copy.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        Map<RecipeCapability<?>, List<Content>> map2 = this.copyMapContents(map, modifier);
        Map<RecipeCapability<?>, List<Content>> map3 = this.copyContentChances(holder, $this$copy, parallel);
        Map map4 = $this$copy.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"tickInputs");
        Map<RecipeCapability<?>, List<Content>> map5 = this.copyMapContents(map4, modifier);
        Map map6 = $this$copy.tickOutputs;
        Intrinsics.checkNotNullExpressionValue((Object)map6, (String)"tickOutputs");
        GTRecipe copy = new GTRecipe(gTRecipeType, resourceLocation, map2, map3, map5, this.copyMapContents(map6, modifier), $this$copy.inputChanceLogics, $this$copy.outputChanceLogics, $this$copy.tickInputChanceLogics, $this$copy.tickOutputChanceLogics, $this$copy.conditions, $this$copy.ingredientActions, $this$copy.data, duration, $this$copy.isFuel);
        ((IGTRecipe)copy).setRealParallels(parallel);
        return copy;
    }

    private final Map<RecipeCapability<?>, List<Content>> copyMapContents(Map<RecipeCapability<?>, List<Content>> contents, ContentModifier modifier) {
        Reference2ObjectOpenHashMap map = new Reference2ObjectOpenHashMap(contents.size());
        Iterable $this$forEach$iv = contents.entrySet();
        boolean $i$f$forEach = false;
        for (Object element$iv : $this$forEach$iv) {
            Map.Entry entry = (Map.Entry)element$iv;
            boolean bl = false;
            RecipeCapability c = (RecipeCapability)entry.getKey();
            List l = (List)entry.getValue();
            ContentList cl = new ContentList();
            Iterable $this$forEach$iv2 = l;
            boolean $i$f$forEach2 = false;
            for (Object element$iv2 : $this$forEach$iv2) {
                Content it = (Content)element$iv2;
                boolean bl2 = false;
                cl.add((Object)it.copy(c, modifier));
            }
            if (cl.isEmpty()) continue;
            ((Map)map).put(c, cl);
        }
        return (Map)map;
    }

    private final GTRecipe markInternallyAggregated(GTRecipe $this$markInternallyAggregated) {
        GTRecipe gTRecipe;
        GTRecipe $this$markInternallyAggregated_u24lambda_u2410 = gTRecipe = $this$markInternallyAggregated;
        boolean bl = false;
        Intrinsics.checkNotNull((Object)$this$markInternallyAggregated_u24lambda_u2410, (String)"null cannot be cast to non-null type org.gtlcore.gtlcore.api.recipe.IGTRecipe");
        ((IGTRecipe)$this$markInternallyAggregated_u24lambda_u2410).setBatchProcessed(true);
        return gTRecipe;
    }

    private final Map<RecipeCapability<?>, List<Content>> copyContentChances(IRecipeLogicMachine holder, GTRecipe recipe, long parallel) {
        ContentModifier mdf = ContentModifier.multiplier((double)parallel);
        Reference2ObjectOpenHashMap rc = new Reference2ObjectOpenHashMap(recipe.outputs.size());
        Map map = recipe.outputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"outputs");
        for (Map.Entry entry : map.entrySet()) {
            RecipeCapability cap = (RecipeCapability)entry.getKey();
            List list = (List)entry.getValue();
            List cl = (List)rc.computeIfAbsent((Object)cap, GTRecipeUtils::copyContentChances$lambda$11);
            for (Content cont : list) {
                Content rolled;
                if (cont.chance >= cont.maxChance) {
                    Content content = cont.copy(cap, mdf);
                    Intrinsics.checkNotNullExpressionValue((Object)content, (String)"copy(...)");
                    cl.add(content);
                    continue;
                }
                int chance = LongChanceLogic.getChance((Content)cont, (ChanceBoostFunction)recipe.recipeType.getChanceFunction(), (int)this.getEuTier(recipe), (int)holder.getChanceTier());
                Object2IntMap object2IntMap = (Object2IntMap)holder.getRecipeLogic().getChanceCaches().get(cap);
                Intrinsics.checkNotNull((Object)cap);
                Intrinsics.checkNotNull((Object)cont);
                if (this.rollChange(object2IntMap, cap, parallel, cont, cont.maxChance, chance) == null) continue;
                boolean bl = false;
                Object object = rolled.content;
                Intrinsics.checkNotNullExpressionValue((Object)object, (String)"content");
                cl.add(new ContentList.MaxChanceContent(object));
            }
            if (!cl.isEmpty()) continue;
            rc.remove((Object)cap);
        }
        return (Map)rc;
    }

    private final Content rollChange(Object2IntMap<?> cache, RecipeCapability<?> cap, long times, Content content, int max, int chance) {
        long remainder = times % (long)max;
        long re = times / (long)max * (long)chance + remainder * (long)chance / (long)max;
        int n = (int)(remainder * (long)chance % (long)max);
        int cached = LongChanceLogic.getCachedChance((Content)content, cache);
        int sum = n + cached;
        if (sum >= max) {
            int bonus = sum / max;
            re += (long)bonus;
            n -= bonus * max;
        }
        LongChanceLogic.updateCachedChance((Object)content.content, cache, (int)(n / 2 + cached));
        if (re <= 0L) {
            return null;
        }
        return content.copy(cap, ContentModifier.multiplier((double)re));
    }

    private final void contentModify(Map<RecipeCapability<?>, List<Content>> $this$contentModify, long parallel) {
        if (parallel == 1L) {
            return;
        }
        Iterable $this$forEach$iv = $this$contentModify.entrySet();
        boolean $i$f$forEach = false;
        for (Object element$iv : $this$forEach$iv) {
            Map.Entry entry = (Map.Entry)element$iv;
            boolean bl = false;
            RecipeCapability cap = (RecipeCapability)entry.getKey();
            List list = (List)entry.getValue();
            if (list.isEmpty()) continue;
            Iterable $this$forEach$iv2 = list;
            boolean $i$f$forEach2 = false;
            for (Object element$iv2 : $this$forEach$iv2) {
                Content it = (Content)element$iv2;
                boolean bl2 = false;
                if (it.chance <= 0) continue;
                INSTANCE.modify(it, cap, (Number)parallel);
            }
        }
    }

    @Nullable
    public final List<Content> setEU(@NotNull GTRecipe $this$setEU, long eu) {
        Intrinsics.checkNotNullParameter((Object)$this$setEU, (String)"<this>");
        return (List)$this$setEU.tickInputs.put(EURecipeCapability.CAP, ContentList.Companion.getEUtList((Number)eu));
    }

    public final long getGetEU(@NotNull GTRecipe $this$getEU) {
        Object object;
        Intrinsics.checkNotNullParameter((Object)$this$getEU, (String)"<this>");
        if (!$this$getEU.tickInputs.isEmpty()) {
            Object v = $this$getEU.tickInputs.get(EURecipeCapability.CAP);
            Intrinsics.checkNotNull(v);
            object = ((Content)((List)v).get((int)0)).content;
        } else {
            Object v = $this$getEU.tickOutputs.get(EURecipeCapability.CAP);
            Intrinsics.checkNotNull(v);
            object = ((Content)((List)v).get((int)0)).content;
            if (object == null) {
                object = 0;
            }
        }
        Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type kotlin.Long");
        return (Long)object;
    }

    public final int getEuTier(@NotNull GTRecipe $this$euTier) {
        Intrinsics.checkNotNullParameter((Object)$this$euTier, (String)"<this>");
        return ((IGTRecipe)$this$euTier).getEuTier();
    }

    public final long getLongParallel(@NotNull GTRecipe $this$longParallel) {
        Intrinsics.checkNotNullParameter((Object)$this$longParallel, (String)"<this>");
        return ((IGTRecipe)$this$longParallel).getRealParallels();
    }

    public final void modify(@NotNull Content $this$modify, @NotNull RecipeCapability<?> cap, @NotNull ContentModifier modifier) {
        Intrinsics.checkNotNullParameter((Object)$this$modify, (String)"<this>");
        Intrinsics.checkNotNullParameter(cap, (String)"cap");
        Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
        RecipeCapability<?> recipeCapability = cap;
        if (Intrinsics.areEqual(recipeCapability, (Object)ItemRecipeCapability.CAP)) {
            Object object = $this$modify.content;
            LongIngredient longIngredient = object instanceof LongIngredient ? (LongIngredient)object : null;
            if (longIngredient != null) {
                LongIngredient it = longIngredient;
                boolean bl = false;
                it.setActualAmount(modifier.apply((Number)it.getActualAmount()).longValue());
            }
        } else if (Intrinsics.areEqual(recipeCapability, (Object)FluidRecipeCapability.CAP)) {
            Object object = $this$modify.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient");
            FluidIngredient it = (FluidIngredient)object;
            boolean bl = false;
            it.setAmount(modifier.apply((Number)it.getAmount()).longValue());
        } else if (Intrinsics.areEqual(recipeCapability, (Object)EURecipeCapability.CAP)) {
            Object object = $this$modify.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type kotlin.Long");
            long it = ((Number)((Long)object)).longValue();
            boolean bl = false;
            $this$modify.content = modifier.apply((Number)it).longValue();
        }
    }

    public final void modify(@NotNull Content $this$modify, @NotNull RecipeCapability<?> cap, @NotNull Number modifier) {
        Intrinsics.checkNotNullParameter((Object)$this$modify, (String)"<this>");
        Intrinsics.checkNotNullParameter(cap, (String)"cap");
        Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
        RecipeCapability<?> recipeCapability = cap;
        if (Intrinsics.areEqual(recipeCapability, (Object)ItemRecipeCapability.CAP)) {
            Object object = $this$modify.content;
            LongIngredient longIngredient = object instanceof LongIngredient ? (LongIngredient)object : null;
            if (longIngredient != null) {
                LongIngredient it = longIngredient;
                boolean bl = false;
                it.setActualAmount(it.getActualAmount() * modifier.longValue());
            }
        } else if (Intrinsics.areEqual(recipeCapability, (Object)FluidRecipeCapability.CAP)) {
            Object object = $this$modify.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient");
            FluidIngredient it = (FluidIngredient)object;
            boolean bl = false;
            it.setAmount(it.getAmount() * modifier.longValue());
        } else if (Intrinsics.areEqual(recipeCapability, (Object)EURecipeCapability.CAP)) {
            Object object = $this$modify.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type kotlin.Long");
            long it = ((Number)((Long)object)).longValue();
            boolean bl = false;
            $this$modify.content = it * modifier.longValue();
        }
    }

    @NotNull
    public final Content plus(@NotNull Content $this$plus, @NotNull Number x) {
        Intrinsics.checkNotNullParameter((Object)$this$plus, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)x, (String)"x");
        Object o = $this$plus.content;
        if (o instanceof LongIngredient) {
            ((LongIngredient)o).setActualAmount(MathUtil.INSTANCE.safePlus(((LongIngredient)o).getActualAmount(), x.longValue()));
        } else if (o instanceof FluidIngredient) {
            ((FluidIngredient)o).setAmount(MathUtil.INSTANCE.safePlus(((FluidIngredient)o).getAmount(), x.longValue()));
        } else if (o instanceof Long) {
            $this$plus.content = MathUtil.INSTANCE.safePlus(((Number)o).longValue(), x.longValue());
        }
        return $this$plus;
    }

    @NotNull
    public final Content setAmount(@NotNull Content $this$setAmount, @NotNull Number x) {
        Content content;
        Intrinsics.checkNotNullParameter((Object)$this$setAmount, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)x, (String)"x");
        Object o = $this$setAmount.content;
        if (o instanceof LongIngredient) {
            ((LongIngredient)o).setActualAmount(x.longValue());
            content = $this$setAmount;
        } else if (o instanceof FluidIngredient) {
            ((FluidIngredient)o).setAmount(x.longValue());
            content = $this$setAmount;
        } else if (o instanceof Long) {
            $this$setAmount.content = x.longValue();
            content = $this$setAmount;
        } else {
            content = $this$setAmount;
        }
        return content;
    }

    public final long amount(@NotNull Content $this$amount, @NotNull RecipeCapability<?> cap) {
        long l;
        Intrinsics.checkNotNullParameter((Object)$this$amount, (String)"<this>");
        Intrinsics.checkNotNullParameter(cap, (String)"cap");
        RecipeCapability<?> recipeCapability = cap;
        if (Intrinsics.areEqual(recipeCapability, (Object)ItemRecipeCapability.CAP)) {
            Object object = $this$amount.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type net.minecraft.world.item.crafting.Ingredient");
            l = this.getAmount((Ingredient)object);
        } else if (Intrinsics.areEqual(recipeCapability, (Object)FluidRecipeCapability.CAP)) {
            Object object = $this$amount.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient");
            l = ((FluidIngredient)object).getAmount();
        } else if (Intrinsics.areEqual(recipeCapability, (Object)EURecipeCapability.CAP)) {
            Object object = $this$amount.content;
            Intrinsics.checkNotNull((Object)object, (String)"null cannot be cast to non-null type kotlin.Long");
            l = (Long)object;
        } else {
            l = 1L;
        }
        return l;
    }

    public final <K> boolean test(@NotNull Content $this$test, K k) {
        Intrinsics.checkNotNullParameter((Object)$this$test, (String)"<this>");
        Object object = $this$test.content;
        Predicate predicate = object instanceof Predicate ? (Predicate)object : null;
        return predicate != null ? predicate.test(k) : false;
    }

    public final long getAmount(@NotNull Ingredient $this$amount) {
        Intrinsics.checkNotNullParameter((Object)$this$amount, (String)"<this>");
        Ingredient ingredient = $this$amount;
        return ingredient instanceof LongIngredient ? ((LongIngredient)$this$amount).getActualAmount() : (ingredient instanceof SizedIngredient ? (long)((SizedIngredient)$this$amount).getAmount() : 1L);
    }

    @NotNull
    public final FluidStack getStack(@NotNull FluidIngredient $this$stack) {
        Intrinsics.checkNotNullParameter((Object)$this$stack, (String)"<this>");
        FluidStack[] fluidStackArray = $this$stack.getStacks();
        Intrinsics.checkNotNullExpressionValue((Object)fluidStackArray, (String)"getStacks(...)");
        Object[] $this$forEach$iv = fluidStackArray;
        boolean $i$f$forEach = false;
        for (Object element$iv : $this$forEach$iv) {
            FluidStack it = (FluidStack)element$iv;
            boolean bl = false;
            if (it == null || it.isEmpty()) continue;
            return it;
        }
        FluidStack fluidStack = FluidStack.empty();
        Intrinsics.checkNotNullExpressionValue((Object)fluidStack, (String)"empty(...)");
        return fluidStack;
    }

    public final boolean test(@NotNull FluidIngredient $this$test, @NotNull Fluid fluid) {
        Intrinsics.checkNotNullParameter((Object)$this$test, (String)"<this>");
        Intrinsics.checkNotNullParameter((Object)fluid, (String)"fluid");
        Intrinsics.checkNotNullExpressionValue((Object)$this$test.values, (String)"values");
        Object[] $this$forEach$iv = $this$test.values;
        boolean $i$f$forEach = false;
        for (Object element$iv : $this$forEach$iv) {
            FluidIngredient.Value it = (FluidIngredient.Value)element$iv;
            boolean bl = false;
            FluidIngredient.FluidValue fluidValue = it instanceof FluidIngredient.FluidValue ? (FluidIngredient.FluidValue)it : null;
            if (fluidValue == null) continue;
            FluidIngredient.FluidValue value = fluidValue;
            boolean bl2 = false;
            Intrinsics.checkNotNull((Object)value, (String)"null cannot be cast to non-null type com.gtladd.gtladditions.mixin.gtceu.api.FluidValueAccessor");
            return Intrinsics.areEqual((Object)((FluidValueAccessor)value).getFluid(), (Object)fluid);
        }
        return false;
    }

    public final boolean test(@NotNull FluidIngredient $this$test, @NotNull TagKey<Fluid> key) {
        Intrinsics.checkNotNullParameter((Object)$this$test, (String)"<this>");
        Intrinsics.checkNotNullParameter(key, (String)"key");
        Intrinsics.checkNotNullExpressionValue((Object)$this$test.values, (String)"values");
        Object[] $this$forEach$iv = $this$test.values;
        boolean $i$f$forEach = false;
        for (Object element$iv : $this$forEach$iv) {
            FluidIngredient.Value it = (FluidIngredient.Value)element$iv;
            boolean bl = false;
            FluidIngredient.TagValue tagValue = it instanceof FluidIngredient.TagValue ? (FluidIngredient.TagValue)it : null;
            if (tagValue == null) continue;
            FluidIngredient.TagValue value = tagValue;
            boolean bl2 = false;
            return Intrinsics.areEqual((Object)value.getTag(), key);
        }
        return false;
    }

    @NotNull
    public final FluidIngredient create(@NotNull FluidStack $this$create) {
        Intrinsics.checkNotNullParameter((Object)$this$create, (String)"<this>");
        return new FluidIngredient((Stream)SingleStream.Companion.createSingle((Object)new FluidIngredient.FluidValue($this$create.getFluid())), $this$create.getAmount(), $this$create.getTag());
    }

    @NotNull
    public final LongIngredient create(@NotNull ItemStack $this$create, long amount) {
        Intrinsics.checkNotNullParameter((Object)$this$create, (String)"<this>");
        LongIngredient longIngredient = LongIngredient.create((Ingredient)Ingredient.m_43938_((Stream)((Stream)SingleStream.Companion.createSingle((Object)new Ingredient.ItemValue($this$create)))), (long)amount);
        Intrinsics.checkNotNullExpressionValue((Object)longIngredient, (String)"create(...)");
        return longIngredient;
    }

    public static /* synthetic */ LongIngredient create$default(GTRecipeUtils gTRecipeUtils, ItemStack itemStack, long l, int n, Object object) {
        if ((n & 1) != 0) {
            l = itemStack.m_41613_();
        }
        return gTRecipeUtils.create(itemStack, l);
    }

    private static final boolean getOverclockRecipe$lambda$1(Object it) {
        Intrinsics.checkNotNullParameter((Object)it, (String)"it");
        return true;
    }

    private static final List modify$lambda$4(Map $o, RecipeCapability t, List list) {
        return (List)$o.get(t);
    }

    private static final List modify$lambda$5(Function2 $tmp0, Object p0, Object p1) {
        return (List)$tmp0.invoke(p0, p1);
    }

    private static final List modifyNotTick$lambda$6(Map $o, RecipeCapability t, List list) {
        return (List)$o.get(t);
    }

    private static final List modifyNotTick$lambda$7(Function2 $tmp0, Object p0, Object p1) {
        return (List)$tmp0.invoke(p0, p1);
    }

    private static final List copyContentChances$lambda$11(Object it) {
        return (List)new ObjectArrayList();
    }
}
