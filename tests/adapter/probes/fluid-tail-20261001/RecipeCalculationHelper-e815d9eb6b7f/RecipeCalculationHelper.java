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
 *  com.gregtechceu.gtceu.api.recipe.GTRecipe
 *  com.gregtechceu.gtceu.api.recipe.GTRecipeType
 *  com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic
 *  com.gregtechceu.gtceu.api.recipe.content.Content
 *  com.gregtechceu.gtceu.api.recipe.content.ContentModifier
 *  com.gregtechceu.gtceu.common.data.GTRecipeTypes
 *  com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder
 *  com.gtladd.gtladditions.api.recipe.WirelessGTRecipe
 *  com.gtladd.gtladditions.api.recipe.WirelessGTRecipeBuilder
 *  com.gtladd.gtladditions.common.data.ParallelData
 *  com.gtladd.gtladditions.utils.RecipeCalculationHelper$calculateParallelsWithProcessing$1
 *  com.gtladd.gtladditions.utils.RecipeCalculationHelper$calculateParallelsWithProcessing$2
 *  it.unimi.dsi.fastutil.ints.IntArrayList
 *  it.unimi.dsi.fastutil.ints.IntList
 *  it.unimi.dsi.fastutil.longs.LongArrayList
 *  it.unimi.dsi.fastutil.longs.LongBooleanPair
 *  it.unimi.dsi.fastutil.longs.LongList
 *  it.unimi.dsi.fastutil.longs.LongLongPair
 *  it.unimi.dsi.fastutil.objects.ObjectArrayList
 *  it.unimi.dsi.fastutil.objects.ObjectList
 *  it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap
 *  kotlin.Lazy
 *  kotlin.LazyKt
 *  kotlin.Metadata
 *  kotlin.Pair
 *  kotlin.Triple
 *  kotlin.TuplesKt
 *  kotlin.collections.CollectionsKt
 *  kotlin.collections.MapsKt
 *  kotlin.collections.SetsKt
 *  kotlin.jvm.functions.Function1
 *  kotlin.jvm.functions.Function2
 *  kotlin.jvm.internal.Intrinsics
 *  kotlin.jvm.internal.SourceDebugExtension
 *  kotlin.math.MathKt
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.crafting.Ingredient
 *  org.gtlcore.gtlcore.api.recipe.IGTRecipe
 *  org.gtlcore.gtlcore.api.recipe.IParallelLogic
 *  org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier
 *  org.gtlcore.gtlcore.api.recipe.RecipeResult
 *  org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper
 *  org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient
 *  org.gtlcore.gtlcore.utils.Registries
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
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.chance.logic.ChanceLogic;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipe;
import com.gtladd.gtladditions.api.recipe.WirelessGTRecipeBuilder;
import com.gtladd.gtladditions.common.data.ParallelData;
import com.gtladd.gtladditions.utils.RecipeCalculationHelper;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongBooleanPair;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLongPair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceArrayMap;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.Metadata;
import kotlin.Pair;
import kotlin.Triple;
import kotlin.TuplesKt;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.collections.SetsKt;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import kotlin.math.MathKt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.IParallelLogic;
import org.gtlcore.gtlcore.api.recipe.RecipeExtensionCopier;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.gtlcore.gtlcore.api.recipe.ingredient.LongIngredient;
import org.gtlcore.gtlcore.utils.Registries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(mv={2, 0, 0}, k=1, xi=48, d1={"\u0000\u00e8\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0006\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0000\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010 \n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010!\n\u0002\u0010\u0002\n\u0002\b\u0004\n\u0002\u0010\u001e\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\f\n\u0002\u0010\u0016\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010$\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\"\n\u0002\b\r\b\u00c6\u0002\u0018\u00002\u00020\u0001B\t\b\u0002\u00a2\u0006\u0004\b\u0002\u0010\u0003J9\u0010\n\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0007\u001a\u00020\u00062\u0014\b\u0004\u0010\t\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00040\bH\u0086\b\u00f8\u0001\u0000\u00a2\u0006\u0004\b\n\u0010\u000bJ\u001d\u0010\n\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0007\u001a\u00020\u0006\u00a2\u0006\u0004\b\n\u0010\fJ{\u0010\u001a\u001a \u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\u0018\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\u0018\u0012\u0004\u0012\u00020\u00120\u00172\u0006\u0010\u000e\u001a\u00020\r2\u0006\u0010\u0010\u001a\u00020\u000f2\u0006\u0010\u0011\u001a\u00020\u00062\u0006\u0010\u0013\u001a\u00020\u00122\u0012\u0010\u0014\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00120\b2\u0014\b\u0006\u0010\u0016\u001a\u000e\u0012\u0004\u0012\u00020\u0012\u0012\u0004\u0012\u00020\u00150\bH\u0086\b\u00f8\u0001\u0000\u00a2\u0006\u0004\b\u001a\u0010\u001bJo\u0010 \u001a \u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\u0018\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\u0018\u0012\u0004\u0012\u00020\u001c0\u00172\u0006\u0010\u000e\u001a\u00020\r2\u0006\u0010\u0010\u001a\u00020\u000f2\u0006\u0010\u001d\u001a\u00020\u001c2\u0006\u0010\u0013\u001a\u00020\u00122\u0012\u0010\u001e\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00060\b2\b\b\u0002\u0010\u001f\u001a\u00020\u0015H\u0086\b\u00f8\u0001\u0000\u00a2\u0006\u0004\b \u0010!JA\u0010(\u001a\u00020\u00042\f\u0010#\u001a\b\u0012\u0004\u0012\u00020\u00190\"2\f\u0010$\u001a\b\u0012\u0004\u0012\u00020\u00190\"2\u0006\u0010%\u001a\u00020\u00122\u0006\u0010\u0011\u001a\u00020\u00062\u0006\u0010'\u001a\u00020&\u00a2\u0006\u0004\b(\u0010)JC\u0010.\u001a\u00020-2\f\u0010#\u001a\b\u0012\u0004\u0012\u00020\u00190\"2\f\u0010$\u001a\b\u0012\u0004\u0012\u00020\u00190\"2\u0006\u0010*\u001a\u00020&2\u0006\u0010%\u001a\u00020\u001c2\b\b\u0002\u0010,\u001a\u00020+\u00a2\u0006\u0004\b.\u0010/J1\u00102\u001a\u0002012\u0006\u0010\u0005\u001a\u00020\u00042\f\u0010#\u001a\b\u0012\u0004\u0012\u00020\u0019002\f\u0010$\u001a\b\u0012\u0004\u0012\u00020\u001900\u00a2\u0006\u0004\b2\u00103J)\u00104\u001a\u00020\u00152\f\u0010#\u001a\b\u0012\u0004\u0012\u00020\u00190\"2\f\u0010$\u001a\b\u0012\u0004\u0012\u00020\u00190\"\u00a2\u0006\u0004\b4\u00105JA\u0010;\u001a\u0004\u0018\u00010\r2\f\u00107\u001a\b\u0012\u0004\u0012\u00020\u0004062\u0006\u00108\u001a\u00020\u00062\u0014\b\u0004\u0010:\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u0002090\bH\u0086\b\u00f8\u0001\u0000\u00a2\u0006\u0004\b;\u0010<JG\u0010@\u001a\u0004\u0018\u00010\r2\f\u00107\u001a\b\u0012\u0004\u0012\u00020\u0004062\u0006\u00108\u001a\u00020\u00062\u0006\u0010\u0010\u001a\u00020\u000f2\u0018\u0010?\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0006\u0012\u0004\u0012\u00020>0=\u00a2\u0006\u0004\b@\u0010AJ\u00a3\u0001\u0010H\u001a\u0004\u0018\u00010\r2\f\u00107\u001a\b\u0012\u0004\u0012\u00020\u0004062\u0006\u0010\u0010\u001a\u00020\u000f2\u0014\b\u0004\u0010B\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00060\b2\u001a\b\u0004\u0010C\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0006\u0012\u0004\u0012\u00020\u00060=2\u0014\b\u0006\u0010D\u001a\u000e\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u00040\b2\u001a\b\u0006\u0010E\u001a\u0014\u0012\u0004\u0012\u00020\u0004\u0012\u0004\u0012\u00020\u0006\u0012\u0004\u0012\u00020\u00040=2\b\b\u0002\u0010F\u001a\u00020\u00152\b\b\u0002\u0010G\u001a\u00020\u0015H\u0086\b\u00f8\u0001\u0000\u00a2\u0006\u0004\bH\u0010IJ=\u0010S\u001a\u0004\u0018\u00010\r2\u0006\u0010J\u001a\u00020\u00062\u0006\u0010L\u001a\u00020K2\u0006\u0010N\u001a\u00020M2\u0006\u0010P\u001a\u00020O2\f\u0010R\u001a\b\u0012\u0004\u0012\u00020\u00040Q\u00a2\u0006\u0004\bS\u0010TJ=\u0010U\u001a\u00020\r2\u0006\u0010J\u001a\u00020\u00062\u0006\u0010L\u001a\u00020K2\u0006\u0010N\u001a\u00020M2\u0006\u0010P\u001a\u00020O2\f\u0010R\u001a\b\u0012\u0004\u0012\u00020\u00040QH\u0002\u00a2\u0006\u0004\bU\u0010TJ=\u0010V\u001a\u00020\r2\u0006\u0010J\u001a\u00020\u00062\u0006\u0010L\u001a\u00020K2\u0006\u0010N\u001a\u00020M2\u0006\u0010P\u001a\u00020O2\f\u0010R\u001a\b\u0012\u0004\u0012\u00020\u00040QH\u0002\u00a2\u0006\u0004\bV\u0010TJ-\u0010\\\u001a\n [*\u0004\u0018\u00010\u00040\u00042\u0006\u0010W\u001a\u00020\u00042\u0006\u0010Y\u001a\u00020X2\u0006\u0010Z\u001a\u00020&\u00a2\u0006\u0004\b\\\u0010]JQ\u0010a\u001a\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u00030_\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\"0^2\u001c\u0010`\u001a\u0018\u0012\b\u0012\u0006\u0012\u0002\b\u00030_\u0012\n\u0012\b\u0012\u0004\u0012\u00020\u00190\"0^2\u0006\u0010Y\u001a\u00020X2\u0006\u0010Z\u001a\u00020&\u00a2\u0006\u0004\ba\u0010bJ1\u0010e\u001a\u00020\u00192\u0006\u0010c\u001a\u00020\u00192\n\u0010d\u001a\u0006\u0012\u0002\b\u00030_2\u0006\u0010Y\u001a\u00020X2\u0006\u0010Z\u001a\u00020&\u00a2\u0006\u0004\be\u0010fJ\u0015\u0010i\u001a\u00020\u00152\u0006\u0010h\u001a\u00020g\u00a2\u0006\u0004\bi\u0010jJ\u0015\u0010k\u001a\u00020\u00152\u0006\u0010c\u001a\u00020\u0019\u00a2\u0006\u0004\bk\u0010lJ+\u0010p\u001a\u0002012\f\u0010m\u001a\b\u0012\u0004\u0012\u00020\u0019002\u0006\u0010o\u001a\u00020n2\u0006\u0010Y\u001a\u00020X\u00a2\u0006\u0004\bp\u0010qR\u0014\u0010s\u001a\u00020r8\u0006X\u0086T\u00a2\u0006\u0006\n\u0004\bs\u0010tR\u001d\u0010v\u001a\b\u0012\u0004\u0012\u00020n0u8\u0006\u00a2\u0006\f\n\u0004\bv\u0010w\u001a\u0004\bx\u0010yR!\u0010}\u001a\b\u0012\u0004\u0012\u00020g0u8FX\u0086\u0084\u0002\u00a2\u0006\f\n\u0004\bz\u0010{\u001a\u0004\b|\u0010yR)\u0010\u0081\u0001\u001a\u000e\u0012\u0004\u0012\u00020n\u0012\u0004\u0012\u00020\u00190^8FX\u0086\u0084\u0002\u00a2\u0006\r\n\u0004\b~\u0010{\u001a\u0005\b\u007f\u0010\u0080\u0001\u0082\u0002\u0007\n\u0005\b\u009920\u0001\u00a8\u0006\u0082\u0001"}, d2={"Lcom/gtladd/gtladditions/utils/RecipeCalculationHelper;", "", "<init>", "()V", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "recipe", "", "parallel", "Lkotlin/Function1;", "copyRecipe", "multipleRecipe", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;JLkotlin/jvm/functions/Function1;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;J)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "Lcom/gtladd/gtladditions/common/data/ParallelData;", "parallelData", "Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;", "machine", "maxEUt", "", "euMultiplier", "getTotalRecipeEu", "", "shouldBreak", "Lkotlin/Triple;", "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;", "Lcom/gregtechceu/gtceu/api/recipe/content/Content;", "processParallelDataNormal", "(Lcom/gtladd/gtladditions/common/data/ParallelData;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;JDLkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)Lkotlin/Triple;", "Ljava/math/BigInteger;", "maxTotalEu", "getRecipeEut", "isEnergyConsumer", "processParallelDataWireless", "(Lcom/gtladd/gtladditions/common/data/ParallelData;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;Ljava/math/BigInteger;DLkotlin/jvm/functions/Function1;Z)Lkotlin/Triple;", "", "itemOutputs", "fluidOutputs", "totalEu", "", "minDuration", "buildNormalRecipe", "(Ljava/util/List;Ljava/util/List;DJI)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "duration", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;", "recipeType", "Lcom/gtladd/gtladditions/api/recipe/WirelessGTRecipe;", "buildWirelessRecipe", "(Ljava/util/List;Ljava/util/List;ILjava/math/BigInteger;Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;)Lcom/gtladd/gtladditions/api/recipe/WirelessGTRecipe;", "", "", "collectOutputs", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Ljava/util/List;Ljava/util/List;)V", "hasOutputs", "(Ljava/util/List;Ljava/util/List;)Z", "", "recipes", "totalParallel", "Lit/unimi/dsi/fastutil/longs/LongBooleanPair;", "getParallelAndIfConsumption", "calculateParallelsWithFairAllocation", "(Ljava/util/Collection;JLkotlin/jvm/functions/Function1;)Lcom/gtladd/gtladditions/common/data/ParallelData;", "Lkotlin/Function2;", "Lit/unimi/dsi/fastutil/longs/LongLongPair;", "getParallelAndConsumption", "calculateParallelsWithGreedyAllocation", "(Ljava/util/Collection;JLcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;Lkotlin/jvm/functions/Function2;)Lcom/gtladd/gtladditions/common/data/ParallelData;", "getParallelLimitForRecipe", "getMaxParallelForRecipe", "modifyRecipe", "createParalleledRecipe", "useModifiedRecipe", "preProcessRecipes", "calculateParallelsWithProcessing", "(Ljava/util/Collection;Lcom/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function2;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function2;ZZ)Lcom/gtladd/gtladditions/common/data/ParallelData;", "remaining", "", "parallels", "Lit/unimi/dsi/fastutil/longs/LongList;", "remainingWants", "Lit/unimi/dsi/fastutil/ints/IntList;", "remainingIndices", "Lit/unimi/dsi/fastutil/objects/ObjectList;", "recipeList", "getFinalParallelData", "(J[JLit/unimi/dsi/fastutil/longs/LongList;Lit/unimi/dsi/fastutil/ints/IntList;Lit/unimi/dsi/fastutil/objects/ObjectList;)Lcom/gtladd/gtladditions/common/data/ParallelData;", "getParallelDataBitmap", "getParallelDataIndexArray", "origin", "Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;", "modifier", "fixMultiplier", "kotlin.jvm.PlatformType", "copyFixRecipe", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;I)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "", "Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;", "contents", "copyFixContents", "(Ljava/util/Map;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;I)Ljava/util/Map;", "content", "capability", "copyFixBoost", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;Lcom/gregtechceu/gtceu/api/capability/recipe/RecipeCapability;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;I)Lcom/gregtechceu/gtceu/api/recipe/content/Content;", "Lnet/minecraft/world/item/Item;", "item", "isRecipeCycleContainerItem", "(Lnet/minecraft/world/item/Item;)Z", "isRecipeCycleContainerContent", "(Lcom/gregtechceu/gtceu/api/recipe/content/Content;)Z", "copyList", "Lnet/minecraft/resources/ResourceLocation;", "id", "appendSpecificInput", "(Ljava/util/List;Lnet/minecraft/resources/ResourceLocation;Lcom/gregtechceu/gtceu/api/recipe/content/ContentModifier;)V", "", "RECIPE_CYCLE_CONTAINER", "Ljava/lang/String;", "", "RECIPE_CYCLE_CONTAINER_ITEM_IDS", "Ljava/util/Set;", "getRECIPE_CYCLE_CONTAINER_ITEM_IDS", "()Ljava/util/Set;", "RECIPE_CYCLE_CONTAINER_ITEMS$delegate", "Lkotlin/Lazy;", "getRECIPE_CYCLE_CONTAINER_ITEMS", "RECIPE_CYCLE_CONTAINER_ITEMS", "FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES$delegate", "getFORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES", "()Ljava/util/Map;", "FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES", "gtladditions"})
@SourceDebugExtension(value={"SMAP\nRecipeCalculationHelper.kt\nKotlin\n*S Kotlin\n*F\n+ 1 RecipeCalculationHelper.kt\ncom/gtladd/gtladditions/utils/RecipeCalculationHelper\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n+ 3 _Maps.kt\nkotlin/collections/MapsKt___MapsKt\n+ 4 _Collections.kt\nkotlin/collections/CollectionsKt___CollectionsKt\n*L\n1#1,575:1\n54#1,2:577\n57#1,4:580\n1#2:576\n1#2:579\n216#3:584\n217#3:589\n1557#4:585\n1628#4,3:586\n1628#4,3:590\n*S KotlinDebug\n*F\n+ 1 RecipeCalculationHelper.kt\ncom/gtladd/gtladditions/utils/RecipeCalculationHelper\n*L\n66#1:577,2\n66#1:580,4\n66#1:579\n478#1:584\n478#1:589\n483#1:585\n483#1:586,3\n527#1:590,3\n*E\n"})
public final class RecipeCalculationHelper {
    @NotNull
    public static final RecipeCalculationHelper INSTANCE = new RecipeCalculationHelper();
    @NotNull
    public static final String RECIPE_CYCLE_CONTAINER = "recipe_cycle_container";
    @NotNull
    private static final Set<ResourceLocation> RECIPE_CYCLE_CONTAINER_ITEM_IDS;
    @NotNull
    private static final Lazy RECIPE_CYCLE_CONTAINER_ITEMS$delegate;
    @NotNull
    private static final Lazy FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES$delegate;

    private RecipeCalculationHelper() {
    }

    @NotNull
    public final GTRecipe multipleRecipe(@NotNull GTRecipe recipe, long parallel, @NotNull Function1<? super GTRecipe, ? extends GTRecipe> copyRecipe) {
        GTRecipe gTRecipe;
        Intrinsics.checkNotNullParameter((Object)recipe, (String)"recipe");
        Intrinsics.checkNotNullParameter(copyRecipe, (String)"copyRecipe");
        boolean $i$f$multipleRecipe = false;
        if (parallel > 1L) {
            Object object = copyRecipe.invoke((Object)recipe);
            GTRecipe it = (GTRecipe)object;
            boolean bl = false;
            RecipeExtensionCopier.copy((GTRecipe)recipe, (GTRecipe)it);
            gTRecipe = (GTRecipe)object;
        } else {
            gTRecipe = recipe;
        }
        GTRecipe processed = gTRecipe;
        IGTRecipe.of((GTRecipe)processed).setRealParallels(parallel);
        return processed;
    }

    @NotNull
    public final GTRecipe multipleRecipe(@NotNull GTRecipe recipe, long parallel) {
        GTRecipe gTRecipe;
        Intrinsics.checkNotNullParameter((Object)recipe, (String)"recipe");
        RecipeCalculationHelper this_$iv = this;
        boolean $i$f$multipleRecipe = false;
        if (parallel > 1L) {
            GTRecipe gTRecipe2;
            GTRecipe recipe2 = recipe;
            boolean bl = false;
            GTRecipe gTRecipe3 = recipe2.copy(ContentModifier.multiplier((double)parallel), false);
            Intrinsics.checkNotNullExpressionValue((Object)gTRecipe3, (String)"copy(...)");
            GTRecipe it$iv = gTRecipe2 = gTRecipe3;
            boolean bl2 = false;
            RecipeExtensionCopier.copy((GTRecipe)recipe, (GTRecipe)it$iv);
            gTRecipe = gTRecipe2;
        } else {
            gTRecipe = recipe;
        }
        GTRecipe processed$iv = gTRecipe;
        IGTRecipe.of((GTRecipe)processed$iv).setRealParallels(parallel);
        return processed$iv;
    }

    @NotNull
    public final Triple<ObjectArrayList<Content>, ObjectArrayList<Content>, Double> processParallelDataNormal(@NotNull ParallelData parallelData, @NotNull IRecipeLogicMachine machine, long maxEUt, double euMultiplier, @NotNull Function1<? super GTRecipe, Double> getTotalRecipeEu, @NotNull Function1<? super Double, Boolean> shouldBreak) {
        Intrinsics.checkNotNullParameter((Object)parallelData, (String)"parallelData");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter(getTotalRecipeEu, (String)"getTotalRecipeEu");
        Intrinsics.checkNotNullParameter(shouldBreak, (String)"shouldBreak");
        boolean $i$f$processParallelDataNormal = false;
        ObjectArrayList itemOutputs = new ObjectArrayList();
        ObjectArrayList fluidOutputs = new ObjectArrayList();
        double totalEu = 0.0;
        int n = ((Collection)parallelData.getOriginRecipeList()).size();
        for (int i = 0; i < n; ++i) {
            GTRecipe r = (GTRecipe)parallelData.getOriginRecipeList().get(i);
            long p = parallelData.getParallels()[i];
            if (parallelData.getShouldProcess()) {
                GTRecipe processedRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)this.multipleRecipe(r, p));
                if (RecipeRunnerHelper.matchRecipeInput((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)processedRecipe) && RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)processedRecipe)) {
                    totalEu += ((Number)getTotalRecipeEu.invoke((Object)r)).doubleValue() * (double)p * euMultiplier;
                    Intrinsics.checkNotNull((Object)processedRecipe);
                    this.collectOutputs(processedRecipe, (List)itemOutputs, (List)fluidOutputs);
                }
                if (!((Boolean)shouldBreak.invoke((Object)totalEu)).booleanValue()) continue;
                break;
            }
            totalEu += ((Number)getTotalRecipeEu.invoke((Object)r)).doubleValue() * (double)p * euMultiplier;
            List list = parallelData.getProcessedRecipeList();
            Intrinsics.checkNotNull((Object)list);
            this.collectOutputs((GTRecipe)list.get(i), (List)itemOutputs, (List)fluidOutputs);
        }
        return new Triple((Object)itemOutputs, (Object)fluidOutputs, (Object)totalEu);
    }

    public static /* synthetic */ Triple processParallelDataNormal$default(RecipeCalculationHelper $this, ParallelData parallelData, IRecipeLogicMachine machine, long maxEUt, double euMultiplier, Function1 getTotalRecipeEu, Function1 shouldBreak, int n, Object object) {
        if ((n & 0x20) != 0) {
            shouldBreak = (Function1)new /* Unavailable Anonymous Inner Class!! */;
        }
        Intrinsics.checkNotNullParameter((Object)parallelData, (String)"parallelData");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter((Object)getTotalRecipeEu, (String)"getTotalRecipeEu");
        Intrinsics.checkNotNullParameter((Object)shouldBreak, (String)"shouldBreak");
        boolean $i$f$processParallelDataNormal = false;
        ObjectArrayList itemOutputs = new ObjectArrayList();
        ObjectArrayList fluidOutputs = new ObjectArrayList();
        double totalEu = 0.0;
        int n2 = ((Collection)parallelData.getOriginRecipeList()).size();
        for (int i = 0; i < n2; ++i) {
            GTRecipe r = (GTRecipe)parallelData.getOriginRecipeList().get(i);
            long p = parallelData.getParallels()[i];
            if (parallelData.getShouldProcess()) {
                GTRecipe processedRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)$this.multipleRecipe(r, p));
                if (RecipeRunnerHelper.matchRecipeInput((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)processedRecipe) && RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)processedRecipe)) {
                    totalEu += ((Number)getTotalRecipeEu.invoke((Object)r)).doubleValue() * (double)p * euMultiplier;
                    Intrinsics.checkNotNull((Object)processedRecipe);
                    $this.collectOutputs(processedRecipe, (List)itemOutputs, (List)fluidOutputs);
                }
                if (!((Boolean)shouldBreak.invoke((Object)totalEu)).booleanValue()) continue;
                break;
            }
            totalEu += ((Number)getTotalRecipeEu.invoke((Object)r)).doubleValue() * (double)p * euMultiplier;
            List list = parallelData.getProcessedRecipeList();
            Intrinsics.checkNotNull((Object)list);
            $this.collectOutputs((GTRecipe)list.get(i), (List)itemOutputs, (List)fluidOutputs);
        }
        return new Triple((Object)itemOutputs, (Object)fluidOutputs, (Object)totalEu);
    }

    @NotNull
    public final Triple<ObjectArrayList<Content>, ObjectArrayList<Content>, BigInteger> processParallelDataWireless(@NotNull ParallelData parallelData, @NotNull IRecipeLogicMachine machine, @NotNull BigInteger maxTotalEu, double euMultiplier, @NotNull Function1<? super GTRecipe, Long> getRecipeEut, boolean isEnergyConsumer) {
        Intrinsics.checkNotNullParameter((Object)parallelData, (String)"parallelData");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter((Object)maxTotalEu, (String)"maxTotalEu");
        Intrinsics.checkNotNullParameter(getRecipeEut, (String)"getRecipeEut");
        boolean $i$f$processParallelDataWireless = false;
        ObjectArrayList itemOutputs = new ObjectArrayList();
        ObjectArrayList fluidOutputs = new ObjectArrayList();
        BigInteger accumulatedEu = BigInteger.ZERO;
        int n = ((Collection)parallelData.getOriginRecipeList()).size();
        for (int i = 0; i < n; ++i) {
            GTRecipe r = (GTRecipe)parallelData.getOriginRecipeList().get(i);
            long p = parallelData.getParallels()[i];
            BigInteger parallelEUt = BigInteger.valueOf(((Number)getRecipeEut.invoke((Object)r)).longValue());
            if (p > 1L) {
                parallelEUt = parallelEUt.multiply(BigInteger.valueOf(p));
            }
            BigInteger tempAccumulatedEu = accumulatedEu.add(BigDecimal.valueOf((double)r.duration * euMultiplier).multiply(new BigDecimal(parallelEUt)).toBigInteger());
            if (parallelData.getShouldProcess()) {
                if (isEnergyConsumer && tempAccumulatedEu.compareTo(maxTotalEu) > 0) {
                    if (accumulatedEu.signum() != 0) break;
                    RecipeResult.of((IRecipeLogicMachine)machine, (RecipeResult)RecipeResult.FAIL_NO_ENOUGH_EU_IN);
                    break;
                }
                GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)this.multipleRecipe(r, p));
                if (!RecipeRunnerHelper.matchRecipeInput((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)paralleledRecipe) || !RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)paralleledRecipe)) continue;
                accumulatedEu = tempAccumulatedEu;
                Intrinsics.checkNotNull((Object)paralleledRecipe);
                this.collectOutputs(paralleledRecipe, (List)itemOutputs, (List)fluidOutputs);
                continue;
            }
            accumulatedEu = tempAccumulatedEu;
            List list = parallelData.getProcessedRecipeList();
            Intrinsics.checkNotNull((Object)list);
            this.collectOutputs((GTRecipe)list.get(i), (List)itemOutputs, (List)fluidOutputs);
        }
        BigInteger totalEu = isEnergyConsumer ? accumulatedEu : accumulatedEu.negate();
        return new Triple((Object)itemOutputs, (Object)fluidOutputs, (Object)totalEu);
    }

    public static /* synthetic */ Triple processParallelDataWireless$default(RecipeCalculationHelper $this, ParallelData parallelData, IRecipeLogicMachine machine, BigInteger maxTotalEu, double euMultiplier, Function1 getRecipeEut, boolean isEnergyConsumer, int n, Object object) {
        if ((n & 0x20) != 0) {
            isEnergyConsumer = true;
        }
        Intrinsics.checkNotNullParameter((Object)parallelData, (String)"parallelData");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter((Object)maxTotalEu, (String)"maxTotalEu");
        Intrinsics.checkNotNullParameter((Object)getRecipeEut, (String)"getRecipeEut");
        boolean $i$f$processParallelDataWireless = false;
        ObjectArrayList itemOutputs = new ObjectArrayList();
        ObjectArrayList fluidOutputs = new ObjectArrayList();
        BigInteger accumulatedEu = BigInteger.ZERO;
        int n2 = ((Collection)parallelData.getOriginRecipeList()).size();
        for (int i = 0; i < n2; ++i) {
            GTRecipe r = (GTRecipe)parallelData.getOriginRecipeList().get(i);
            long p = parallelData.getParallels()[i];
            BigInteger parallelEUt = BigInteger.valueOf(((Number)getRecipeEut.invoke((Object)r)).longValue());
            if (p > 1L) {
                parallelEUt = parallelEUt.multiply(BigInteger.valueOf(p));
            }
            BigInteger tempAccumulatedEu = accumulatedEu.add(BigDecimal.valueOf((double)r.duration * euMultiplier).multiply(new BigDecimal(parallelEUt)).toBigInteger());
            if (parallelData.getShouldProcess()) {
                if (isEnergyConsumer && tempAccumulatedEu.compareTo(maxTotalEu) > 0) {
                    if (accumulatedEu.signum() != 0) break;
                    RecipeResult.of((IRecipeLogicMachine)machine, (RecipeResult)RecipeResult.FAIL_NO_ENOUGH_EU_IN);
                    break;
                }
                GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)$this.multipleRecipe(r, p));
                if (!RecipeRunnerHelper.matchRecipeInput((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)paralleledRecipe) || !RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)paralleledRecipe)) continue;
                accumulatedEu = tempAccumulatedEu;
                Intrinsics.checkNotNull((Object)paralleledRecipe);
                $this.collectOutputs(paralleledRecipe, (List)itemOutputs, (List)fluidOutputs);
                continue;
            }
            accumulatedEu = tempAccumulatedEu;
            List list = parallelData.getProcessedRecipeList();
            Intrinsics.checkNotNull((Object)list);
            $this.collectOutputs((GTRecipe)list.get(i), (List)itemOutputs, (List)fluidOutputs);
        }
        BigInteger totalEu = isEnergyConsumer ? accumulatedEu : accumulatedEu.negate();
        return new Triple((Object)itemOutputs, (Object)fluidOutputs, (Object)totalEu);
    }

    @NotNull
    public final GTRecipe buildNormalRecipe(@NotNull List<? extends Content> itemOutputs, @NotNull List<? extends Content> fluidOutputs, double totalEu, long maxEUt, int minDuration) {
        Intrinsics.checkNotNullParameter(itemOutputs, (String)"itemOutputs");
        Intrinsics.checkNotNullParameter(fluidOutputs, (String)"fluidOutputs");
        GTRecipe recipe = GTRecipeBuilder.ofRaw().buildRawRecipe();
        Map map = recipe.outputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"outputs");
        Map map2 = map;
        map2.put(ItemRecipeCapability.CAP, itemOutputs);
        Map map3 = recipe.outputs;
        Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"outputs");
        map2 = map3;
        map2.put(FluidRecipeCapability.CAP, fluidOutputs);
        double d = totalEu / (double)maxEUt;
        long eut = d > (double)minDuration ? maxEUt : (long)(totalEu / (double)minDuration);
        Map map4 = recipe.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map4, (String)"tickInputs");
        Map map5 = map4;
        EURecipeCapability eURecipeCapability = EURecipeCapability.CAP;
        List list = CollectionsKt.listOf((Object)new Content((Object)eut, 10000, 10000, 0, null, null));
        map5.put(eURecipeCapability, list);
        recipe.duration = MathKt.roundToInt((double)Math.max(d, (double)minDuration));
        IGTRecipe.of((GTRecipe)recipe).setHasTick(true);
        IGTRecipe.of((GTRecipe)recipe).setBatchProcessed(true);
        Intrinsics.checkNotNull((Object)recipe);
        return recipe;
    }

    @NotNull
    public final WirelessGTRecipe buildWirelessRecipe(@NotNull List<? extends Content> itemOutputs, @NotNull List<? extends Content> fluidOutputs, int duration, @NotNull BigInteger totalEu, @NotNull GTRecipeType recipeType) {
        Intrinsics.checkNotNullParameter(itemOutputs, (String)"itemOutputs");
        Intrinsics.checkNotNullParameter(fluidOutputs, (String)"fluidOutputs");
        Intrinsics.checkNotNullParameter((Object)totalEu, (String)"totalEu");
        Intrinsics.checkNotNullParameter((Object)recipeType, (String)"recipeType");
        BigInteger eut = totalEu.divide(BigInteger.valueOf(duration)).negate();
        WirelessGTRecipeBuilder wirelessGTRecipeBuilder = WirelessGTRecipeBuilder.Companion.ofRaw(recipeType);
        ItemRecipeCapability itemRecipeCapability = ItemRecipeCapability.CAP;
        Intrinsics.checkNotNullExpressionValue((Object)itemRecipeCapability, (String)"CAP");
        WirelessGTRecipeBuilder wirelessGTRecipeBuilder2 = wirelessGTRecipeBuilder.output((RecipeCapability)itemRecipeCapability, itemOutputs);
        FluidRecipeCapability fluidRecipeCapability = FluidRecipeCapability.CAP;
        Intrinsics.checkNotNullExpressionValue((Object)fluidRecipeCapability, (String)"CAP");
        WirelessGTRecipe recipe = wirelessGTRecipeBuilder2.output((RecipeCapability)fluidRecipeCapability, fluidOutputs).duration(duration).setWirelessEut(eut).buildRawRecipe();
        IGTRecipe.of((GTRecipe)((GTRecipe)recipe)).setBatchProcessed(true);
        return recipe;
    }

    public static /* synthetic */ WirelessGTRecipe buildWirelessRecipe$default(RecipeCalculationHelper recipeCalculationHelper, List list, List list2, int n, BigInteger bigInteger, GTRecipeType gTRecipeType, int n2, Object object) {
        if ((n2 & 0x10) != 0) {
            gTRecipeType = GTRecipeTypes.DUMMY_RECIPES;
        }
        return recipeCalculationHelper.buildWirelessRecipe(list, list2, n, bigInteger, gTRecipeType);
    }

    public final void collectOutputs(@NotNull GTRecipe recipe, @NotNull List<Content> itemOutputs, @NotNull List<Content> fluidOutputs) {
        block1: {
            List it;
            Intrinsics.checkNotNullParameter((Object)recipe, (String)"recipe");
            Intrinsics.checkNotNullParameter(itemOutputs, (String)"itemOutputs");
            Intrinsics.checkNotNullParameter(fluidOutputs, (String)"fluidOutputs");
            List list = (List)recipe.outputs.get(ItemRecipeCapability.CAP);
            if (list != null) {
                it = list;
                boolean bl = false;
                itemOutputs.addAll(it);
            }
            List list2 = (List)recipe.outputs.get(FluidRecipeCapability.CAP);
            if (list2 == null) break block1;
            it = list2;
            boolean bl = false;
            fluidOutputs.addAll(it);
        }
    }

    public final boolean hasOutputs(@NotNull List<? extends Content> itemOutputs, @NotNull List<? extends Content> fluidOutputs) {
        Intrinsics.checkNotNullParameter(itemOutputs, (String)"itemOutputs");
        Intrinsics.checkNotNullParameter(fluidOutputs, (String)"fluidOutputs");
        return !((Collection)itemOutputs).isEmpty() || !((Collection)fluidOutputs).isEmpty();
    }

    @Nullable
    public final ParallelData calculateParallelsWithFairAllocation(@NotNull Collection<? extends GTRecipe> recipes, long totalParallel, @NotNull Function1<? super GTRecipe, ? extends LongBooleanPair> getParallelAndIfConsumption) {
        Intrinsics.checkNotNullParameter(recipes, (String)"recipes");
        Intrinsics.checkNotNullParameter(getParallelAndIfConsumption, (String)"getParallelAndIfConsumption");
        boolean $i$f$calculateParallelsWithFairAllocation = false;
        int length = recipes.size();
        if (length == 0) {
            return null;
        }
        long remaining = totalParallel;
        long[] parallels = new long[length];
        int index = 0;
        ObjectArrayList recipeList = new ObjectArrayList(length);
        LongArrayList remainingWants = new LongArrayList(length);
        IntArrayList remainingIndices = new IntArrayList(length);
        for (GTRecipe gTRecipe : recipes) {
            long allocated;
            LongBooleanPair pair = (LongBooleanPair)getParallelAndIfConsumption.invoke((Object)gTRecipe);
            long p = pair.firstLong();
            if (p <= 0L) continue;
            recipeList.add((Object)gTRecipe);
            if (!pair.secondBoolean()) {
                parallels[index] = p;
                ++index;
                continue;
            }
            parallels[index] = allocated = Math.min(p, totalParallel / (long)length);
            long want = p - allocated;
            if (want > 0L) {
                remainingWants.add(want);
                remainingIndices.add(index);
            }
            remaining -= allocated;
            ++index;
        }
        if (recipeList.isEmpty()) {
            return null;
        }
        return this.getFinalParallelData(remaining, parallels, (LongList)remainingWants, (IntList)remainingIndices, (ObjectList<GTRecipe>)((ObjectList)recipeList));
    }

    @Nullable
    public final ParallelData calculateParallelsWithGreedyAllocation(@NotNull Collection<? extends GTRecipe> recipes, long totalParallel, @NotNull IRecipeLogicMachine machine, @NotNull Function2<? super GTRecipe, ? super Long, ? extends LongLongPair> getParallelAndConsumption) {
        ParallelData parallelData;
        Intrinsics.checkNotNullParameter(recipes, (String)"recipes");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter(getParallelAndConsumption, (String)"getParallelAndConsumption");
        long remain = totalParallel;
        ObjectArrayList recipeList = new ObjectArrayList();
        ObjectArrayList processedRecipeList = new ObjectArrayList();
        LongArrayList parallelsList = new LongArrayList();
        for (GTRecipe gTRecipe : recipes) {
            GTRecipe paralleledRecipe;
            if (remain <= 0L) break;
            LongLongPair pair = (LongLongPair)getParallelAndConsumption.invoke((Object)gTRecipe, (Object)remain);
            long p = pair.firstLong();
            if (p <= 0L || !RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)(paralleledRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)this.multipleRecipe(gTRecipe, p))))) continue;
            remain -= pair.secondLong();
            recipeList.add((Object)gTRecipe);
            processedRecipeList.add((Object)paralleledRecipe);
            parallelsList.add(p);
        }
        if (recipeList.isEmpty()) {
            parallelData = null;
        } else {
            List list = (List)recipeList;
            long[] lArray = parallelsList.toLongArray();
            Intrinsics.checkNotNullExpressionValue((Object)lArray, (String)"toLongArray(...)");
            parallelData = new ParallelData(list, lArray, false, (List)processedRecipeList);
        }
        return parallelData;
    }

    @Nullable
    public final ParallelData calculateParallelsWithProcessing(@NotNull Collection<? extends GTRecipe> recipes, @NotNull IRecipeLogicMachine machine, @NotNull Function1<? super GTRecipe, Long> getParallelLimitForRecipe, @NotNull Function2<? super GTRecipe, ? super Long, Long> getMaxParallelForRecipe, @NotNull Function1<? super GTRecipe, ? extends GTRecipe> modifyRecipe, @NotNull Function2<? super GTRecipe, ? super Long, ? extends GTRecipe> createParalleledRecipe, boolean useModifiedRecipe, boolean preProcessRecipes) {
        ParallelData parallelData;
        Intrinsics.checkNotNullParameter(recipes, (String)"recipes");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter(getParallelLimitForRecipe, (String)"getParallelLimitForRecipe");
        Intrinsics.checkNotNullParameter(getMaxParallelForRecipe, (String)"getMaxParallelForRecipe");
        Intrinsics.checkNotNullParameter(modifyRecipe, (String)"modifyRecipe");
        Intrinsics.checkNotNullParameter(createParalleledRecipe, (String)"createParalleledRecipe");
        boolean $i$f$calculateParallelsWithProcessing = false;
        int length = recipes.size();
        if (length == 0) {
            return null;
        }
        ObjectArrayList recipeList = new ObjectArrayList(length);
        ObjectArrayList processedRecipeList = preProcessRecipes ? new ObjectArrayList(length) : null;
        LongArrayList parallelsList = new LongArrayList(length);
        for (GTRecipe gTRecipe : recipes) {
            long limit;
            GTRecipe modified = (GTRecipe)modifyRecipe.invoke((Object)gTRecipe);
            long parallel = ((Number)getMaxParallelForRecipe.invoke((Object)modified, (Object)(limit = ((Number)getParallelLimitForRecipe.invoke((Object)modified)).longValue()))).longValue();
            if (parallel <= 0L) continue;
            if (preProcessRecipes) {
                GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)((GTRecipe)createParalleledRecipe.invoke((Object)modified, (Object)parallel)));
                if (!RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)paralleledRecipe)) continue;
                recipeList.add((Object)(useModifiedRecipe ? modified : gTRecipe));
                ObjectArrayList objectArrayList = processedRecipeList;
                Intrinsics.checkNotNull((Object)objectArrayList);
                objectArrayList.add((Object)paralleledRecipe);
                parallelsList.add(parallel);
                continue;
            }
            recipeList.add((Object)(useModifiedRecipe ? modified : gTRecipe));
            parallelsList.add(parallel);
        }
        if (recipeList.isEmpty()) {
            parallelData = null;
        } else {
            List list = (List)recipeList;
            long[] lArray = parallelsList.toLongArray();
            Intrinsics.checkNotNullExpressionValue((Object)lArray, (String)"toLongArray(...)");
            parallelData = new ParallelData(list, lArray, !preProcessRecipes, (List)processedRecipeList);
        }
        return parallelData;
    }

    public static /* synthetic */ ParallelData calculateParallelsWithProcessing$default(RecipeCalculationHelper $this, Collection recipes, IRecipeLogicMachine machine, Function1 getParallelLimitForRecipe, Function2 getMaxParallelForRecipe, Function1 modifyRecipe, Function2 createParalleledRecipe, boolean useModifiedRecipe, boolean preProcessRecipes, int n, Object object) {
        ParallelData parallelData;
        if ((n & 0x10) != 0) {
            modifyRecipe = (Function1)calculateParallelsWithProcessing.1.INSTANCE;
        }
        if ((n & 0x20) != 0) {
            createParalleledRecipe = (Function2)calculateParallelsWithProcessing.2.INSTANCE;
        }
        if ((n & 0x40) != 0) {
            useModifiedRecipe = false;
        }
        if ((n & 0x80) != 0) {
            preProcessRecipes = true;
        }
        Intrinsics.checkNotNullParameter((Object)recipes, (String)"recipes");
        Intrinsics.checkNotNullParameter((Object)machine, (String)"machine");
        Intrinsics.checkNotNullParameter((Object)getParallelLimitForRecipe, (String)"getParallelLimitForRecipe");
        Intrinsics.checkNotNullParameter((Object)getMaxParallelForRecipe, (String)"getMaxParallelForRecipe");
        Intrinsics.checkNotNullParameter((Object)modifyRecipe, (String)"modifyRecipe");
        Intrinsics.checkNotNullParameter((Object)createParalleledRecipe, (String)"createParalleledRecipe");
        boolean $i$f$calculateParallelsWithProcessing = false;
        int length = recipes.size();
        if (length == 0) {
            return null;
        }
        ObjectArrayList recipeList = new ObjectArrayList(length);
        ObjectArrayList processedRecipeList = preProcessRecipes ? new ObjectArrayList(length) : null;
        LongArrayList parallelsList = new LongArrayList(length);
        for (GTRecipe recipe : recipes) {
            long limit;
            GTRecipe modified = (GTRecipe)modifyRecipe.invoke((Object)recipe);
            long parallel = ((Number)getMaxParallelForRecipe.invoke((Object)modified, (Object)(limit = ((Number)getParallelLimitForRecipe.invoke((Object)modified)).longValue()))).longValue();
            if (parallel <= 0L) continue;
            if (preProcessRecipes) {
                GTRecipe paralleledRecipe = IParallelLogic.getRecipeOutputChance((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)machine), (GTRecipe)((GTRecipe)createParalleledRecipe.invoke((Object)modified, (Object)parallel)));
                if (!RecipeRunnerHelper.handleRecipeInput((IRecipeLogicMachine)machine, (GTRecipe)paralleledRecipe)) continue;
                recipeList.add((Object)(useModifiedRecipe ? modified : recipe));
                ObjectArrayList objectArrayList = processedRecipeList;
                Intrinsics.checkNotNull((Object)objectArrayList);
                objectArrayList.add((Object)paralleledRecipe);
                parallelsList.add(parallel);
                continue;
            }
            recipeList.add((Object)(useModifiedRecipe ? modified : recipe));
            parallelsList.add(parallel);
        }
        if (recipeList.isEmpty()) {
            parallelData = null;
        } else {
            List list = (List)recipeList;
            long[] lArray = parallelsList.toLongArray();
            Intrinsics.checkNotNullExpressionValue((Object)lArray, (String)"toLongArray(...)");
            parallelData = new ParallelData(list, lArray, !preProcessRecipes, (List)processedRecipeList);
        }
        return parallelData;
    }

    @Nullable
    public final ParallelData getFinalParallelData(long remaining, @NotNull long[] parallels, @NotNull LongList remainingWants, @NotNull IntList remainingIndices, @NotNull ObjectList<GTRecipe> recipeList) {
        Intrinsics.checkNotNullParameter((Object)parallels, (String)"parallels");
        Intrinsics.checkNotNullParameter((Object)remainingWants, (String)"remainingWants");
        Intrinsics.checkNotNullParameter((Object)remainingIndices, (String)"remainingIndices");
        Intrinsics.checkNotNullParameter(recipeList, (String)"recipeList");
        if (recipeList.isEmpty()) {
            return null;
        }
        if (remaining <= 0L || remainingWants.isEmpty()) {
            return new ParallelData((List)recipeList, parallels, false, null, 12, null);
        }
        return remainingWants.size() <= 64 ? this.getParallelDataBitmap(remaining, parallels, remainingWants, remainingIndices, recipeList) : this.getParallelDataIndexArray(remaining, parallels, remainingWants, remainingIndices, recipeList);
    }

    private final ParallelData getParallelDataBitmap(long remaining, long[] parallels, LongList remainingWants, IntList remainingIndices, ObjectList<GTRecipe> recipeList) {
        long perRecipe;
        long distributed;
        int count = remainingWants.size();
        long activeBits = (1L << count) - 1L;
        int activeCount = count;
        for (long remaining2 = remaining; remaining2 > 0L && activeCount > 0 && (perRecipe = remaining2 / (long)activeCount) > 0L; remaining2 -= distributed) {
            distributed = 0L;
            long newActiveBits = 0L;
            int newActiveCount = 0;
            long bits = activeBits;
            while (bits != 0L) {
                int i = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1L;
                int idx = remainingIndices.getInt(i);
                long want = remainingWants.getLong(i);
                long give = Math.min(want, perRecipe);
                parallels[idx] = parallels[idx] + give;
                distributed += give;
                remainingWants.set(i, want - give);
                if (want - give <= 0L) continue;
                newActiveBits |= 1L << i;
                ++newActiveCount;
            }
            activeBits = newActiveBits;
            activeCount = newActiveCount;
        }
        return new ParallelData((List)recipeList, parallels, false, null, 12, null);
    }

    private final ParallelData getParallelDataIndexArray(long remaining, long[] parallels, LongList remainingWants, IntList remainingIndices, ObjectList<GTRecipe> recipeList) {
        long perRecipe;
        long distributed;
        int activeCount = remainingWants.size();
        for (long remaining2 = remaining; remaining2 > 0L && activeCount > 0 && (perRecipe = remaining2 / (long)activeCount) > 0L; remaining2 -= distributed) {
            distributed = 0L;
            int writePos = 0;
            int n = activeCount;
            for (int readPos = 0; readPos < n; ++readPos) {
                int idx = remainingIndices.getInt(readPos);
                long want = remainingWants.getLong(readPos);
                long give = Math.min(want, perRecipe);
                parallels[idx] = parallels[idx] + give;
                distributed += give;
                long newWant = want - give;
                if (newWant <= 0L) continue;
                remainingWants.set(writePos, newWant);
                remainingIndices.set(writePos, idx);
                ++writePos;
            }
            activeCount = writePos;
        }
        return new ParallelData((List)recipeList, parallels, false, null, 12, null);
    }

    public final GTRecipe copyFixRecipe(@NotNull GTRecipe origin, @NotNull ContentModifier modifier, int fixMultiplier) {
        Intrinsics.checkNotNullParameter((Object)origin, (String)"origin");
        Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
        GTRecipeType gTRecipeType = origin.recipeType;
        ResourceLocation resourceLocation = origin.id;
        Map map = origin.inputs;
        Intrinsics.checkNotNullExpressionValue((Object)map, (String)"inputs");
        Map<RecipeCapability<?>, List<Content>> map2 = this.copyFixContents(map, modifier, fixMultiplier);
        Map map3 = origin.outputs;
        Intrinsics.checkNotNullExpressionValue((Object)map3, (String)"outputs");
        Map<RecipeCapability<?>, List<Content>> map4 = this.copyFixContents(map3, modifier, fixMultiplier);
        Map map5 = origin.tickInputs;
        Intrinsics.checkNotNullExpressionValue((Object)map5, (String)"tickInputs");
        Map<RecipeCapability<?>, List<Content>> map6 = this.copyFixContents(map5, modifier, fixMultiplier);
        Map map7 = origin.tickOutputs;
        Intrinsics.checkNotNullExpressionValue((Object)map7, (String)"tickOutputs");
        return RecipeExtensionCopier.copy((GTRecipe)origin, (GTRecipe)new GTRecipe(gTRecipeType, resourceLocation, map2, map4, map6, this.copyFixContents(map7, modifier, fixMultiplier), (Map)new Reference2ReferenceArrayMap(origin.inputChanceLogics), (Map)new Reference2ReferenceArrayMap(origin.outputChanceLogics), (Map)new Reference2ReferenceArrayMap(origin.tickInputChanceLogics), (Map)new Reference2ReferenceArrayMap(origin.tickOutputChanceLogics), (List)new ObjectArrayList((Collection)origin.conditions), (List)new ObjectArrayList((Collection)origin.ingredientActions), origin.data, origin.duration, origin.isFuel));
    }

    /*
     * WARNING - void declaration
     */
    @NotNull
    public final Map<RecipeCapability<?>, List<Content>> copyFixContents(@NotNull Map<RecipeCapability<?>, ? extends List<? extends Content>> contents, @NotNull ContentModifier modifier, int fixMultiplier) {
        Reference2ReferenceArrayMap reference2ReferenceArrayMap;
        Intrinsics.checkNotNullParameter(contents, (String)"contents");
        Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
        Reference2ReferenceArrayMap $this$copyFixContents_u24lambda_u246 = reference2ReferenceArrayMap = new Reference2ReferenceArrayMap();
        boolean bl = false;
        Map<RecipeCapability<?>, List<Content>> $this$forEach$iv = contents;
        boolean $i$f$forEach = false;
        Iterator<Map.Entry<RecipeCapability<?>, List<Content>>> iterator = $this$forEach$iv.entrySet().iterator();
        while (iterator.hasNext()) {
            Collection<Content> collection;
            void $this$mapTo$iv$iv;
            void $this$map$iv;
            Map.Entry<RecipeCapability<?>, List<Content>> element$iv;
            Map.Entry<RecipeCapability<?>, List<Content>> entry = element$iv = iterator.next();
            boolean bl2 = false;
            RecipeCapability<?> cap = entry.getKey();
            List<? extends Content> contentList = entry.getValue();
            if (!(!((Collection)contentList).isEmpty())) continue;
            Iterable iterable = contentList;
            RecipeCapability<?> recipeCapability = cap;
            Reference2ReferenceArrayMap reference2ReferenceArrayMap2 = $this$copyFixContents_u24lambda_u246;
            boolean $i$f$map = false;
            void var19_19 = $this$map$iv;
            Collection destination$iv$iv = new ArrayList(CollectionsKt.collectionSizeOrDefault((Iterable)$this$map$iv, (int)10));
            boolean $i$f$mapTo = false;
            for (Object item$iv$iv : $this$mapTo$iv$iv) {
                void content;
                Content content2 = (Content)item$iv$iv;
                collection = destination$iv$iv;
                boolean bl3 = false;
                collection.add(INSTANCE.copyFixBoost((Content)content, cap, modifier, fixMultiplier));
            }
            collection = (List)destination$iv$iv;
            Collection collection2 = collection;
            reference2ReferenceArrayMap2.put(recipeCapability, (Object)new ObjectArrayList(collection2));
        }
        return (Map)reference2ReferenceArrayMap;
    }

    @NotNull
    public final Content copyFixBoost(@NotNull Content content, @NotNull RecipeCapability<?> capability, @NotNull ContentModifier modifier, int fixMultiplier) {
        Intrinsics.checkNotNullParameter((Object)content, (String)"content");
        Intrinsics.checkNotNullParameter(capability, (String)"capability");
        Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
        Object newContent = content.chance != 0 ? capability.copyContent(content.content, modifier) : capability.copyContent(content.content);
        return new Content(newContent, content.chance, content.maxChance, content.tierChanceBoost / fixMultiplier, content.slotName, content.uiName);
    }

    @NotNull
    public final Set<ResourceLocation> getRECIPE_CYCLE_CONTAINER_ITEM_IDS() {
        return RECIPE_CYCLE_CONTAINER_ITEM_IDS;
    }

    @NotNull
    public final Set<Item> getRECIPE_CYCLE_CONTAINER_ITEMS() {
        Lazy lazy = RECIPE_CYCLE_CONTAINER_ITEMS$delegate;
        return (Set)lazy.getValue();
    }

    @NotNull
    public final Map<ResourceLocation, Content> getFORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES() {
        Lazy lazy = FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES$delegate;
        return (Map)lazy.getValue();
    }

    public final boolean isRecipeCycleContainerItem(@NotNull Item item) {
        Intrinsics.checkNotNullParameter((Object)item, (String)"item");
        return this.getRECIPE_CYCLE_CONTAINER_ITEMS().contains(item);
    }

    public final boolean isRecipeCycleContainerContent(@NotNull Content content) {
        Intrinsics.checkNotNullParameter((Object)content, (String)"content");
        return Intrinsics.areEqual((Object)content.slotName, (Object)RECIPE_CYCLE_CONTAINER);
    }

    public final void appendSpecificInput(@NotNull List<Content> copyList, @NotNull ResourceLocation id, @NotNull ContentModifier modifier) {
        block1: {
            Intrinsics.checkNotNullParameter(copyList, (String)"copyList");
            Intrinsics.checkNotNullParameter((Object)id, (String)"id");
            Intrinsics.checkNotNullParameter((Object)modifier, (String)"modifier");
            Content content = this.getFORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES().get(id);
            if (content == null) break block1;
            Content it = content;
            boolean bl = false;
            if (modifier.getMultiplier() >= 2.0) {
                Content content2 = it.copy((RecipeCapability)ItemRecipeCapability.CAP, ContentModifier.multiplier((double)(modifier.getMultiplier() - 1.0)));
                Intrinsics.checkNotNullExpressionValue((Object)content2, (String)"copy(...)");
                copyList.add(content2);
            }
        }
    }

    /*
     * WARNING - void declaration
     */
    private static final Set RECIPE_CYCLE_CONTAINER_ITEMS_delegate$lambda$8() {
        void var1_1;
        void $this$mapTo$iv;
        Iterable iterable = RECIPE_CYCLE_CONTAINER_ITEM_IDS;
        Collection destination$iv = new LinkedHashSet();
        boolean $i$f$mapTo = false;
        for (Object item$iv : $this$mapTo$iv) {
            void id;
            ResourceLocation resourceLocation = (ResourceLocation)item$iv;
            Collection collection = destination$iv;
            boolean bl = false;
            collection.add(Registries.getItem((String)id.toString()));
        }
        return (Set)var1_1;
    }

    private static final Map FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES_delegate$lambda$9() {
        Pair[] pairArray = new Pair[3];
        ItemStack[] itemStackArray = new ItemStack[]{Registries.getItemStack((String)"kubejs:time_dilation_containment_unit")};
        pairArray[0] = TuplesKt.to((Object)new ResourceLocation("kubejs", "stellar_forge/contained_exotic_matter"), (Object)new Content((Object)LongIngredient.create((Ingredient)Ingredient.m_43927_((ItemStack[])itemStackArray)), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null));
        itemStackArray = new ItemStack[]{Registries.getItemStack((String)"kubejs:extremely_durable_plasma_cell")};
        pairArray[1] = TuplesKt.to((Object)new ResourceLocation("kubejs", "stellar_forge/extremely_durable_plasma_cell"), (Object)new Content((Object)LongIngredient.create((Ingredient)Ingredient.m_43927_((ItemStack[])itemStackArray)), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null));
        itemStackArray = new ItemStack[]{Registries.getItemStack((String)"kubejs:time_dilation_containment_unit")};
        pairArray[2] = TuplesKt.to((Object)new ResourceLocation("kubejs", "stellar_forge/contained_kerr_newmann_singularity"), (Object)new Content((Object)LongIngredient.create((Ingredient)Ingredient.m_43927_((ItemStack[])itemStackArray)), ChanceLogic.getMaxChancedValue(), ChanceLogic.getMaxChancedValue(), 0, null, null));
        return MapsKt.mapOf((Pair[])pairArray);
    }

    static {
        Object[] objectArray = new ResourceLocation[]{new ResourceLocation("kubejs", "extremely_durable_plasma_cell"), new ResourceLocation("kubejs", "time_dilation_containment_unit"), new ResourceLocation("kubejs", "plasma_containment_cell")};
        RECIPE_CYCLE_CONTAINER_ITEM_IDS = SetsKt.setOf((Object[])objectArray);
        RECIPE_CYCLE_CONTAINER_ITEMS$delegate = LazyKt.lazy(RecipeCalculationHelper::RECIPE_CYCLE_CONTAINER_ITEMS_delegate$lambda$8);
        FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES$delegate = LazyKt.lazy(RecipeCalculationHelper::FORGE_OF_THE_ANTICHRIST_SPECIAL_INPUT_RULES_delegate$lambda$9);
    }
}
