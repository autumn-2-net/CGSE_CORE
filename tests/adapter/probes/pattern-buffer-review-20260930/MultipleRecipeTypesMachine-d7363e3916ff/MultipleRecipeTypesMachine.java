/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.capability.IEnergyContainer
 *  com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider
 *  com.gregtechceu.gtceu.api.gui.fancy.TabsWidget
 *  com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
 *  com.gregtechceu.gtceu.api.machine.MetaMachine
 *  com.gregtechceu.gtceu.api.machine.fancyconfigurator.CombinedDirectionalFancyConfigurator
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
 *  com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
 *  com.gregtechceu.gtceu.api.recipe.GTRecipe
 *  com.gregtechceu.gtceu.api.recipe.GTRecipeType
 *  com.gtladd.gtladditions.api.machine.IMultipleRecipeTypeMachine$DefaultImpls
 *  com.gtladd.gtladditions.api.machine.gui.GTLAddMachineModeFancyConfigurator
 *  com.gtladd.gtladditions.api.machine.gui.MultiblockDisplayText
 *  com.gtladd.gtladditions.api.machine.gui.MultiblockDisplayText$Builder
 *  kotlin.Metadata
 *  kotlin.jvm.internal.Intrinsics
 *  kotlin.jvm.internal.SourceDebugExtension
 *  net.minecraft.network.chat.Component
 *  org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus
 *  org.jetbrains.annotations.NotNull
 *  org.jetbrains.annotations.Nullable
 */
package com.gtladd.gtladditions.api.machine;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.fancyconfigurator.CombinedDirectionalFancyConfigurator;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gtladd.gtladditions.api.machine.IMultipleRecipeTypeMachine;
import com.gtladd.gtladditions.api.machine.gui.GTLAddMachineModeFancyConfigurator;
import com.gtladd.gtladditions.api.machine.gui.MultiblockDisplayText;
import com.gtladd.gtladditions.api.machine.logic.MultiRecipeTypesLogic;
import java.util.List;
import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import net.minecraft.network.chat.Component;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(mv={2, 0, 0}, k=1, xi=48, d1={"\u0000Z\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0011\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0010!\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0005\b\u0016\u0018\u00002\u00020\u00012\u00020\u0002B\u000f\u0012\u0006\u0010\u0004\u001a\u00020\u0003\u00a2\u0006\u0004\b\u0005\u0010\u0006J\u000f\u0010\b\u001a\u00020\u0007H\u0016\u00a2\u0006\u0004\b\b\u0010\tJ#\u0010\r\u001a\u00020\u00072\u0012\u0010\f\u001a\n\u0012\u0006\b\u0001\u0012\u00020\u000b0\n\"\u00020\u000bH\u0014\u00a2\u0006\u0004\b\r\u0010\u000eJ\u0019\u0010\u0011\u001a\u0004\u0018\u00010\u000f2\u0006\u0010\u0010\u001a\u00020\u000fH\u0016\u00a2\u0006\u0004\b\u0011\u0010\u0012J\u0017\u0010\u0016\u001a\u00020\u00152\u0006\u0010\u0014\u001a\u00020\u0013H\u0016\u00a2\u0006\u0004\b\u0016\u0010\u0017J\u000f\u0010\u0019\u001a\u00020\u0018H\u0016\u00a2\u0006\u0004\b\u0019\u0010\u001aJ\u001d\u0010\u001e\u001a\u00020\u00152\f\u0010\u001d\u001a\b\u0012\u0004\u0012\u00020\u001c0\u001bH\u0016\u00a2\u0006\u0004\b\u001e\u0010\u001fR \u0010!\u001a\b\u0012\u0004\u0012\u00020 0\n8\u0016X\u0096\u0004\u00a2\u0006\f\n\u0004\b!\u0010\"\u001a\u0004\b#\u0010$\u00a8\u0006%"}, d2={"Lcom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine;", "Lcom/gregtechceu/gtceu/api/machine/multiblock/WorkableElectricMultiblockMachine;", "Lcom/gtladd/gtladditions/api/machine/IMultipleRecipeTypeMachine;", "Lcom/gregtechceu/gtceu/api/machine/IMachineBlockEntity;", "holder", "<init>", "(Lcom/gregtechceu/gtceu/api/machine/IMachineBlockEntity;)V", "Lcom/gtladd/gtladditions/api/machine/logic/MultiRecipeTypesLogic;", "getRecipeLogic", "()Lcom/gtladd/gtladditions/api/machine/logic/MultiRecipeTypesLogic;", "", "", "args", "createRecipeLogic", "([Ljava/lang/Object;)Lcom/gtladd/gtladditions/api/machine/logic/MultiRecipeTypesLogic;", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "recipe", "modifyRecipe", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "Lcom/gregtechceu/gtceu/api/gui/fancy/TabsWidget;", "sideTabs", "", "attachSideTabs", "(Lcom/gregtechceu/gtceu/api/gui/fancy/TabsWidget;)V", "", "parallel", "()I", "", "Lnet/minecraft/network/chat/Component;", "textList", "addDisplayText", "(Ljava/util/List;)V", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;", "multiRecipeTypes", "[Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;", "getMultiRecipeTypes", "()[Lcom/gregtechceu/gtceu/api/recipe/GTRecipeType;", "gtladditions"})
@SourceDebugExtension(value={"SMAP\nMultipleRecipeTypesMachine.kt\nKotlin\n*S Kotlin\n*F\n+ 1 MultipleRecipeTypesMachine.kt\ncom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n*L\n1#1,49:1\n1#2:50\n*E\n"})
public class MultipleRecipeTypesMachine
extends WorkableElectricMultiblockMachine
implements IMultipleRecipeTypeMachine {
    @NotNull
    private final GTRecipeType[] multiRecipeTypes;

    public MultipleRecipeTypesMachine(@NotNull IMachineBlockEntity holder) {
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        super(holder, new Object[0]);
        this.multiRecipeTypes = new GTRecipeType[0];
    }

    @NotNull
    public MultiRecipeTypesLogic getRecipeLogic() {
        RecipeLogic recipeLogic = super.getRecipeLogic();
        Intrinsics.checkNotNull((Object)recipeLogic, (String)"null cannot be cast to non-null type com.gtladd.gtladditions.api.machine.logic.MultiRecipeTypesLogic");
        return (MultiRecipeTypesLogic)recipeLogic;
    }

    @NotNull
    protected MultiRecipeTypesLogic createRecipeLogic(Object ... args) {
        Intrinsics.checkNotNullParameter((Object)args, (String)"args");
        return new MultiRecipeTypesLogic(this);
    }

    @Nullable
    public GTRecipe modifyRecipe(@NotNull GTRecipe recipe) {
        Intrinsics.checkNotNullParameter((Object)recipe, (String)"recipe");
        return recipe;
    }

    public void attachSideTabs(@NotNull TabsWidget sideTabs) {
        block1: {
            Intrinsics.checkNotNullParameter((Object)sideTabs, (String)"sideTabs");
            sideTabs.setMainTab((IFancyUIProvider)this);
            if (this.getMultiRecipeTypes().length > 1) {
                sideTabs.attachSubTab((IFancyUIProvider)new GTLAddMachineModeFancyConfigurator((IMultipleRecipeTypeMachine)this));
            }
            CombinedDirectionalFancyConfigurator combinedDirectionalFancyConfigurator = CombinedDirectionalFancyConfigurator.of((MetaMachine)this.self(), (MetaMachine)this.self());
            if (combinedDirectionalFancyConfigurator == null) break block1;
            CombinedDirectionalFancyConfigurator it = combinedDirectionalFancyConfigurator;
            boolean bl = false;
            sideTabs.attachSubTab((IFancyUIProvider)it);
        }
    }

    @Override
    @NotNull
    public GTRecipeType[] getMultiRecipeTypes() {
        return this.multiRecipeTypes;
    }

    public int parallel() {
        return 1;
    }

    public void addDisplayText(@NotNull List<Component> textList) {
        Intrinsics.checkNotNullParameter(textList, (String)"textList");
        MultiblockDisplayText.Builder builder = MultiblockDisplayText.builder(textList, (boolean)this.isFormed()).setWorkingStatus(this.recipeLogic.isWorkingEnabled(), this.recipeLogic.isActive()).addEnergyUsageLine((IEnergyContainer)this.energyContainer).addEnergyTierLine(this.tier).addMachineModeLine(this.getMultiRecipeType()).addParallelsLine((Number)this.parallel()).addWorkingStatusLine().addProgressLine(this.recipeLogic.getProgressPercent());
        RecipeLogic recipeLogic = this.recipeLogic;
        Intrinsics.checkNotNull((Object)recipeLogic, (String)"null cannot be cast to non-null type org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus");
        builder.addRecipeStatus((IRecipeStatus)recipeLogic);
    }

    @Override
    @NotNull
    public GTRecipeType getMultiRecipeType() {
        return IMultipleRecipeTypeMachine.DefaultImpls.getMultiRecipeType((IMultipleRecipeTypeMachine)this);
    }
}
