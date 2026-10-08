/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
 *  com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext
 *  org.jetbrains.annotations.NotNull
 *  org.jetbrains.annotations.Nullable
 */
package com.gtladd.gtladditions.api.machine;

import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface IRecipeSearchProvider {
    @Nullable
    public RecipeSearchContext getSearchContext();

    public void setSearchContext(@Nullable RecipeSearchContext var1);

    @Nullable
    default public RecipeSearchContext getActiveSearchContext() {
        RecipeSearchContext ctx = this.getSearchContext();
        return ctx != null && ctx.isCycleActive() ? ctx : null;
    }

    @NotNull
    default public RecipeSearchContext beginSearchCycle(@NotNull WorkableElectricMultiblockMachine machine) {
        RecipeSearchContext ctx = this.getSearchContext();
        if (ctx == null) {
            ctx = new RecipeSearchContext(machine);
            this.setSearchContext(ctx);
        }
        if (!ctx.isCycleActive()) {
            ctx.beginCycle();
        }
        return ctx;
    }

    default public void endSearchCycle() {
        RecipeSearchContext ctx = this.getSearchContext();
        if (ctx != null) {
            ctx.endCycle();
        }
    }
}
