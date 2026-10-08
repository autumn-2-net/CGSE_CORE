/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
 *  com.gregtechceu.gtceu.api.recipe.GTRecipe
 *  com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient
 *  com.gregtechceu.gtceu.api.recipe.lookup.Branch
 *  com.gtladd.gtladditions.api.recipe.OptimizedRecipeSearch$LeafVisitor
 *  com.gtladd.gtladditions.api.recipe.OptimizedRecipeSearch$SearchScratch
 *  com.gtladd.gtladditions.api.recipe.ledger.ContentEntry
 *  com.gtladd.gtladditions.api.recipe.ledger.PartLedger
 *  com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext
 *  com.gtladd.gtladditions.api.recipe.lookup.IBranchAddition
 *  com.gtladd.gtladditions.mixin.gtlcore.machine.part.InternalSlotAccessor
 *  com.gtladd.gtladditions.mixin.gtlcore.machine.part.MEPatternBufferPartMachineBaseInvoker
 *  com.mojang.datafixers.util.Either
 *  it.unimi.dsi.fastutil.ints.Int2ReferenceMap
 *  it.unimi.dsi.fastutil.objects.Object2ObjectMap
 *  it.unimi.dsi.fastutil.objects.Object2ObjectMap$Entry
 *  it.unimi.dsi.fastutil.objects.Object2ObjectMaps
 *  it.unimi.dsi.fastutil.objects.ObjectArrayList
 *  it.unimi.dsi.fastutil.objects.ObjectIterator
 *  it.unimi.dsi.fastutil.objects.ObjectOpenHashSet
 *  it.unimi.dsi.fastutil.objects.ObjectSet
 *  it.unimi.dsi.fastutil.objects.Reference2ObjectMap$Entry
 *  it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap
 *  org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine
 *  org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart
 *  org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase
 *  org.jetbrains.annotations.Nullable
 */
package com.gtladd.gtladditions.api.recipe;

import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.AbstractMapIngredient;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gtladd.gtladditions.api.machine.IRecipeSearchProvider;
import com.gtladd.gtladditions.api.recipe.OptimizedRecipeSearch;
import com.gtladd.gtladditions.api.recipe.ledger.ContentEntry;
import com.gtladd.gtladditions.api.recipe.ledger.PartLedger;
import com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext;
import com.gtladd.gtladditions.api.recipe.lookup.IBranchAddition;
import com.gtladd.gtladditions.mixin.gtlcore.machine.part.InternalSlotAccessor;
import com.gtladd.gtladditions.mixin.gtlcore.machine.part.MEPatternBufferPartMachineBaseInvoker;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeCapabilityMachine;
import org.gtlcore.gtlcore.api.machine.trait.MEPatternRecipeHandlePart;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachineBase;
import org.jetbrains.annotations.Nullable;

public final class OptimizedRecipeSearch {
    static final ThreadLocal<SearchScratch> searchScratch = ThreadLocal.withInitial(SearchScratch::new);

    @Nullable
    public static GTRecipe find(WorkableElectricMultiblockMachine holder, Branch branch, Predicate<GTRecipe> canHandle) {
        if (!(holder instanceof IRecipeCapabilityMachine)) {
            return null;
        }
        IRecipeCapabilityMachine rcm = (IRecipeCapabilityMachine)holder;
        RecipeSearchContext ctx = OptimizedRecipeSearch.activeContext(holder);
        for (MEPatternRecipeHandlePart part : rcm.getMEPatternRecipeHandleParts()) {
            MEPatternBufferPartMachineBase machine = ctx.getPatternMachine(part);
            if (machine == null) continue;
            Int2ReferenceMap cacheRecipe = machine.getMETrait().getSlot2RecipesCache();
            for (Object slot : ((MEPatternBufferPartMachineBaseInvoker)machine).getActiveSlots()) {
                int index = ((InternalSlotAccessor)slot).getSlotIndex();
                ObjectSet recipes = (ObjectSet)cacheRecipe.get(index);
                if (recipes == null) continue;
                for (GTRecipe recipe : recipes) {
                    if (!canHandle.test(recipe) || ctx.tryPlan(recipe, 1L, part, index) == null) continue;
                    OptimizedRecipeSearch.accept(ctx, recipe);
                    return recipe;
                }
            }
        }
        GTRecipe hit = ctx.pickSearchHit(canHandle);
        if (hit != null) {
            OptimizedRecipeSearch.accept(ctx, hit);
            return hit;
        }
        return OptimizedRecipeSearch.searchNoCache(holder, branch, canHandle);
    }

    @Nullable
    public static GTRecipe searchNoCache(WorkableElectricMultiblockMachine holder, Branch branch, Predicate<GTRecipe> canHandle) {
        if (!(holder instanceof IRecipeCapabilityMachine)) {
            return null;
        }
        RecipeSearchContext ctx = OptimizedRecipeSearch.activeContext(holder);
        ctx.buildSnapshot();
        for (ObjectArrayList domain : ctx.getSearchDomains()) {
            Predicate<GTRecipe> filter;
            GTRecipe r = OptimizedRecipeSearch.dfs((List<PartLedger>)domain, branch, arg_0 -> OptimizedRecipeSearch.lambda$searchNoCache$0(filter = OptimizedRecipeSearch.leafFilter(ctx, canHandle, (List<PartLedger>)domain), ctx, arg_0));
            if (r == null) continue;
            ctx.recordSearchHit(r, (List)domain);
            return r;
        }
        return null;
    }

    public static List<GTRecipe> collectCandidates(WorkableElectricMultiblockMachine holder, Branch branch, Predicate<GTRecipe> canHandle) {
        ObjectArrayList result = new ObjectArrayList();
        if (!(holder instanceof IRecipeCapabilityMachine)) {
            return result;
        }
        IRecipeCapabilityMachine rcm = (IRecipeCapabilityMachine)holder;
        RecipeSearchContext ctx = OptimizedRecipeSearch.activeContext(holder);
        ctx.buildSnapshot();
        ObjectOpenHashSet seen = new ObjectOpenHashSet();
        for (MEPatternRecipeHandlePart part : rcm.getMEPatternRecipeHandleParts()) {
            MEPatternBufferPartMachineBase machine = ctx.getPatternMachine(part);
            if (machine == null) continue;
            Int2ReferenceMap cacheRecipe = machine.getMETrait().getSlot2RecipesCache();
            for (Object slot : ((MEPatternBufferPartMachineBaseInvoker)machine).getActiveSlots()) {
                int index = ((InternalSlotAccessor)slot).getSlotIndex();
                ObjectSet recipes = (ObjectSet)cacheRecipe.get(index);
                if (recipes == null) continue;
                for (GTRecipe recipe2 : recipes) {
                    if (!canHandle.test(recipe2) || ctx.tryPlan(recipe2, 1L, part, index) == null) continue;
                    OptimizedRecipeSearch.accept(ctx, recipe2);
                    result.add((Object)recipe2);
                    seen.add((Object)recipe2);
                }
            }
        }
        for (ObjectArrayList domain : ctx.getSearchDomains()) {
            Predicate<GTRecipe> filter = OptimizedRecipeSearch.leafFilter(ctx, canHandle, (List<PartLedger>)domain);
            OptimizedRecipeSearch.dfs((List<PartLedger>)domain, branch, recipe -> {
                if (seen.add((Object)recipe) && filter.test(recipe)) {
                    OptimizedRecipeSearch.accept(ctx, recipe);
                    result.add((Object)recipe);
                }
                return null;
            });
        }
        return result;
    }

    static RecipeSearchContext activeContext(WorkableElectricMultiblockMachine machine) {
        RecipeSearchContext recipeSearchContext;
        if (machine instanceof IRecipeSearchProvider) {
            IRecipeSearchProvider p = (IRecipeSearchProvider)machine;
            recipeSearchContext = p.getActiveSearchContext();
        } else {
            recipeSearchContext = null;
        }
        RecipeSearchContext ctx = recipeSearchContext;
        return ctx != null ? ctx : new RecipeSearchContext(machine);
    }

    static Predicate<GTRecipe> leafFilter(RecipeSearchContext ctx, Predicate<GTRecipe> canHandle, List<PartLedger> domain) {
        return recipe -> canHandle.test((GTRecipe)recipe) && ctx.tryPlan(recipe, 1L, domain) != null;
    }

    static void accept(RecipeSearchContext ctx, GTRecipe recipe) {
        ctx.setOriginRecipe(recipe);
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    @Nullable
    static GTRecipe dfs(List<PartLedger> ledgers, Branch node, LeafVisitor visitor) {
        SearchScratch scratch = searchScratch.get();
        if (scratch.busy) {
            return OptimizedRecipeSearch.runSearch(new SearchScratch(), ledgers, node, visitor);
        }
        scratch.busy = true;
        try {
            GTRecipe gTRecipe = OptimizedRecipeSearch.runSearch(scratch, ledgers, node, visitor);
            return gTRecipe;
        }
        finally {
            scratch.release();
            scratch.busy = false;
        }
    }

    @Nullable
    static GTRecipe runSearch(SearchScratch scratch, List<PartLedger> ledgers, Branch node, LeafVisitor visitor) {
        int n = ledgers.size();
        return OptimizedRecipeSearch.dfs0(ledgers, node, scratch.usedFor(n), n, 0, scratch, visitor);
    }

    @Nullable
    static GTRecipe dfs0(List<PartLedger> ledgers, Branch node, long[] used, int n, int depth, SearchScratch scratch, LeafVisitor visitor) {
        GTRecipe hit;
        int free = OptimizedRecipeSearch.freeOf(ledgers, used, n);
        if (free == 0) {
            return null;
        }
        if (free < OptimizedRecipeSearch.minDepth(node)) {
            return null;
        }
        Map nodes = node.getNodes();
        Map special = node.getSpecialNodes();
        Reference2ObjectOpenHashMap childMasks = scratch.masks(depth);
        long[] perScratch = scratch.perFor(n);
        if (nodes.size() + special.size() <= free) {
            hit = OptimizedRecipeSearch.probeTreeSide(ledgers, nodes, used, perScratch, (Reference2ObjectOpenHashMap<Branch, long[]>)childMasks, visitor, n);
            if (hit == null) {
                hit = OptimizedRecipeSearch.probeTreeSide(ledgers, special, used, perScratch, (Reference2ObjectOpenHashMap<Branch, long[]>)childMasks, visitor, n);
            }
        } else {
            hit = OptimizedRecipeSearch.probeMachineSide(ledgers, used, perScratch, (Reference2ObjectOpenHashMap<Branch, long[]>)childMasks, visitor, nodes, special, n);
        }
        if (hit != null) {
            return hit;
        }
        ObjectIterator it = childMasks.reference2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Reference2ObjectMap.Entry e = (Reference2ObjectMap.Entry)it.next();
            long[] perLedger = (long[])e.getValue();
            Branch child = (Branch)e.getKey();
            for (int i = 0; i < perLedger.length; ++i) {
                for (long m = perLedger[i]; m != 0L; m &= m - 1L) {
                    int bit = Long.numberOfTrailingZeros(m);
                    int n2 = i;
                    used[n2] = used[n2] | 1L << bit;
                    GTRecipe r = OptimizedRecipeSearch.dfs0(ledgers, child, used, n, depth + 1, scratch, visitor);
                    int n3 = i;
                    used[n3] = used[n3] & (1L << bit ^ 0xFFFFFFFFFFFFFFFFL);
                    if (r == null) continue;
                    return r;
                }
            }
        }
        return null;
    }

    static int freeOf(List<PartLedger> ledgers, long[] used, int n) {
        int free = 0;
        for (int i = 0; i < n; ++i) {
            free += ledgers.get(i).ownerCount() - Long.bitCount(used[i]);
        }
        return free;
    }

    static void mergeChild(Reference2ObjectOpenHashMap<Branch, long[]> childMasks, Branch child, long[] perLedger) {
        long[] cur = (long[])childMasks.get((Object)child);
        if (cur == null) {
            childMasks.put((Object)child, (Object)((long[])perLedger.clone()));
        } else {
            for (int i = 0; i < cur.length; ++i) {
                int n = i;
                cur[n] = cur[n] | perLedger[i];
            }
        }
    }

    static Iterable<Object2ObjectMap.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>>> fastEntries(Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map) {
        return Object2ObjectMaps.fastIterable((Object2ObjectMap)((Object2ObjectMap)map));
    }

    @Nullable
    static GTRecipe probeTreeSide(List<PartLedger> ledgers, Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map, long[] used, long[] perScratch, Reference2ObjectOpenHashMap<Branch, long[]> childMasks, LeafVisitor visitor, int n) {
        if (map.isEmpty()) {
            return null;
        }
        for (Object2ObjectMap.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>> e : OptimizedRecipeSearch.fastEntries(map)) {
            AbstractMapIngredient key = (AbstractMapIngredient)e.getKey();
            Either either = (Either)e.getValue();
            if (either.left().isPresent()) {
                GTRecipe recipe;
                GTRecipe hit;
                if (!OptimizedRecipeSearch.matchesDomain(ledgers, key) || (hit = visitor.test(recipe = (GTRecipe)either.left().get())) == null) continue;
                return hit;
            }
            if (!either.right().isPresent()) continue;
            boolean any = false;
            for (int i = 0; i < n; ++i) {
                long mask;
                perScratch[i] = mask = ledgers.get(i).ownerMaskOf(key) & (used[i] ^ 0xFFFFFFFFFFFFFFFFL);
                if (mask == 0L) continue;
                any = true;
            }
            if (!any) continue;
            OptimizedRecipeSearch.mergeChild(childMasks, (Branch)either.right().get(), perScratch);
        }
        return null;
    }

    static boolean matchesDomain(List<PartLedger> ledgers, AbstractMapIngredient key) {
        int n = ledgers.size();
        for (int i = 0; i < n; ++i) {
            if (ledgers.get(i).ownerMaskOf(key) == 0L) continue;
            return true;
        }
        return false;
    }

    @Nullable
    static GTRecipe probeMachineSide(List<PartLedger> ledgers, long[] used, long[] per, Reference2ObjectOpenHashMap<Branch, long[]> childMasks, LeafVisitor visitor, Map<AbstractMapIngredient, Either<GTRecipe, Branch>> nodes, Map<AbstractMapIngredient, Either<GTRecipe, Branch>> special, int n) {
        Arrays.fill(per, 0, n, 0L);
        for (int i = 0; i < n; ++i) {
            long free;
            PartLedger ledger = ledgers.get(i);
            long l = free = ledger.ownerCount() >= 64 ? used[i] ^ 0xFFFFFFFFFFFFFFFFL : (used[i] ^ 0xFFFFFFFFFFFFFFFFL) & (1L << ledger.ownerCount()) - 1L;
            while (free != 0L) {
                int bit = Long.numberOfTrailingZeros(free);
                free &= free - 1L;
                ContentEntry entry = ledger.entry(bit);
                if (entry == null) continue;
                for (AbstractMapIngredient v : entry.variants) {
                    Either<GTRecipe, Branch> either = (v.isSpecialIngredient() ? special : nodes).get(v);
                    if (either == null) continue;
                    if (either.left().isPresent()) {
                        GTRecipe recipe = (GTRecipe)either.left().get();
                        GTRecipe hit = visitor.test(recipe);
                        if (hit == null) continue;
                        return hit;
                    }
                    if (!either.right().isPresent()) continue;
                    per[i] = 1L << bit;
                    OptimizedRecipeSearch.mergeChild(childMasks, (Branch)either.right().get(), per);
                    per[i] = 0L;
                }
            }
        }
        return null;
    }

    static int minDepth(Branch node) {
        IBranchAddition holder = (IBranchAddition)node;
        int cached = holder.minDepth();
        if (cached != 0) {
            return cached;
        }
        holder.setMinDepth(Integer.MAX_VALUE);
        try {
            int min = OptimizedRecipeSearch.scanMinDepth(node.getNodes(), Integer.MAX_VALUE);
            min = OptimizedRecipeSearch.scanMinDepth(node.getSpecialNodes(), min);
            holder.setMinDepth(min);
            return min;
        }
        catch (Error | RuntimeException e) {
            holder.setMinDepth(0);
            throw e;
        }
    }

    static int scanMinDepth(Map<AbstractMapIngredient, Either<GTRecipe, Branch>> map, int min) {
        for (Object2ObjectMap.Entry<AbstractMapIngredient, Either<GTRecipe, Branch>> e : OptimizedRecipeSearch.fastEntries(map)) {
            int depth;
            Either either = (Either)e.getValue();
            if (either.left().isPresent()) {
                return 1;
            }
            if (!either.right().isPresent() || (depth = OptimizedRecipeSearch.minDepth((Branch)either.right().get())) == Integer.MAX_VALUE) continue;
            min = Math.min(min, depth + 1);
        }
        return min;
    }

    private static /* synthetic */ GTRecipe lambda$searchNoCache$0(Predicate filter, RecipeSearchContext ctx, GTRecipe recipe) {
        if (filter.test(recipe)) {
            OptimizedRecipeSearch.accept(ctx, recipe);
            return recipe;
        }
        return null;
    }
}
