/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.capability.recipe.IO
 *  com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder
 *  com.gregtechceu.gtceu.api.machine.MetaMachine
 *  com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine
 *  com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine
 *  com.gregtechceu.gtceu.api.machine.trait.RecipeLogic
 *  com.gregtechceu.gtceu.api.machine.trait.RecipeLogic$Status
 *  com.gregtechceu.gtceu.api.recipe.GTRecipe
 *  com.gregtechceu.gtceu.api.recipe.lookup.Branch
 *  com.gtladd.gtladditions.api.machine.ICoilMachine
 *  com.gtladd.gtladditions.api.machine.IEnergyMachine
 *  com.gtladd.gtladditions.api.recipe.OptimizedRecipeSearch
 *  com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext
 *  com.gtladd.gtladditions.utils.GTRecipeUtils
 *  com.gtladd.gtladditions.utils.MathUtil
 *  kotlin.Metadata
 *  kotlin.Unit
 *  kotlin.jvm.internal.Intrinsics
 *  kotlin.jvm.internal.SourceDebugExtension
 *  net.minecraft.nbt.CompoundTag
 *  org.gtlcore.gtlcore.api.machine.ISuspendableMachine
 *  org.gtlcore.gtlcore.api.machine.trait.ILockRecipe
 *  org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus
 *  org.gtlcore.gtlcore.api.recipe.IGTRecipe
 *  org.gtlcore.gtlcore.api.recipe.RecipeResult
 *  org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper
 *  org.jetbrains.annotations.NotNull
 */
package com.gtladd.gtladditions.api.machine.logic;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.capability.recipe.IRecipeCapabilityHolder;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.lookup.Branch;
import com.gtladd.gtladditions.api.machine.ICoilMachine;
import com.gtladd.gtladditions.api.machine.IEnergyMachine;
import com.gtladd.gtladditions.api.machine.MultipleRecipeTypesMachine;
import com.gtladd.gtladditions.api.recipe.OptimizedRecipeSearch;
import com.gtladd.gtladditions.api.recipe.ledger.RecipeSearchContext;
import com.gtladd.gtladditions.utils.GTRecipeUtils;
import com.gtladd.gtladditions.utils.MathUtil;
import kotlin.Metadata;
import kotlin.Unit;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.SourceDebugExtension;
import net.minecraft.nbt.CompoundTag;
import org.gtlcore.gtlcore.api.machine.ISuspendableMachine;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;
import org.gtlcore.gtlcore.api.machine.trait.IRecipeStatus;
import org.gtlcore.gtlcore.api.recipe.IGTRecipe;
import org.gtlcore.gtlcore.api.recipe.RecipeResult;
import org.gtlcore.gtlcore.api.recipe.RecipeRunnerHelper;
import org.jetbrains.annotations.NotNull;

@Metadata(mv={2, 0, 0}, k=1, xi=48, d1={"\u0000H\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0010\u000b\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0007\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010\t\n\u0002\b\u0003\u0018\u00002\u00020\u00012\u00020\u00022\u00020\u0003B\u000f\u0012\u0006\u0010\u0005\u001a\u00020\u0004\u00a2\u0006\u0004\b\u0006\u0010\u0007J\u000f\u0010\b\u001a\u00020\u0004H\u0016\u00a2\u0006\u0004\b\b\u0010\tJ\u000f\u0010\u000b\u001a\u00020\nH\u0016\u00a2\u0006\u0004\b\u000b\u0010\fJ\u0017\u0010\u000f\u001a\u00020\n2\u0006\u0010\u000e\u001a\u00020\rH\u0016\u00a2\u0006\u0004\b\u000f\u0010\u0010J\u000f\u0010\u0011\u001a\u00020\nH\u0016\u00a2\u0006\u0004\b\u0011\u0010\fJ\u0019\u0010\u0013\u001a\u00020\u00122\b\u0010\u000e\u001a\u0004\u0018\u00010\rH\u0002\u00a2\u0006\u0004\b\u0013\u0010\u0014J\u000f\u0010\u0015\u001a\u00020\nH\u0016\u00a2\u0006\u0004\b\u0015\u0010\fJ\u001f\u0010\u0019\u001a\u00020\n2\u0006\u0010\u0017\u001a\u00020\u00162\u0006\u0010\u0018\u001a\u00020\u0012H\u0016\u00a2\u0006\u0004\b\u0019\u0010\u001aJ\u0017\u0010\u001b\u001a\u00020\n2\u0006\u0010\u0017\u001a\u00020\u0016H\u0016\u00a2\u0006\u0004\b\u001b\u0010\u001cJ\u0017\u0010\u001d\u001a\u00020\u00122\u0006\u0010\u000e\u001a\u00020\rH\u0002\u00a2\u0006\u0004\b\u001d\u0010\u0014J\u001f\u0010 \u001a\u00020\u00122\u0006\u0010\u001f\u001a\u00020\u001e2\u0006\u0010\u000e\u001a\u00020\rH\u0002\u00a2\u0006\u0004\b \u0010!J\u0017\u0010\"\u001a\u00020\u00122\u0006\u0010\u000e\u001a\u00020\rH\u0002\u00a2\u0006\u0004\b\"\u0010\u0014R\u0014\u0010\u0005\u001a\u00020\u00048\u0002X\u0082\u0004\u00a2\u0006\u0006\n\u0004\b\u0005\u0010#R\u0016\u0010%\u001a\u00020$8\u0002@\u0002X\u0082\u000e\u00a2\u0006\u0006\n\u0004\b%\u0010&\u00a8\u0006'"}, d2={"Lcom/gtladd/gtladditions/api/machine/logic/MultiRecipeTypesLogic;", "Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;", "Lorg/gtlcore/gtlcore/api/machine/trait/ILockRecipe;", "Lorg/gtlcore/gtlcore/api/machine/trait/IRecipeStatus;", "Lcom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine;", "multiTypeMachine", "<init>", "(Lcom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine;)V", "getMachine", "()Lcom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine;", "", "findAndHandleRecipe", "()V", "Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;", "recipe", "setupRecipe", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)V", "handleRecipeWorking", "", "handleSearchingRecipes", "(Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)Z", "onRecipeFinish", "Lnet/minecraft/nbt/CompoundTag;", "tag", "forDrop", "saveCustomPersistedData", "(Lnet/minecraft/nbt/CompoundTag;Z)V", "loadCustomPersistedData", "(Lnet/minecraft/nbt/CompoundTag;)V", "checkConditionsOnly", "Lcom/gtladd/gtladditions/api/machine/ICoilMachine;", "coil", "coilOk", "(Lcom/gtladd/gtladditions/api/machine/ICoilMachine;Lcom/gregtechceu/gtceu/api/recipe/GTRecipe;)Z", "checkRecipe", "Lcom/gtladd/gtladditions/api/machine/MultipleRecipeTypesMachine;", "", "eut", "J", "gtladditions"})
@SourceDebugExtension(value={"SMAP\nMultiRecipeTypesLogic.kt\nKotlin\n*S Kotlin\n*F\n+ 1 MultiRecipeTypesLogic.kt\ncom/gtladd/gtladditions/api/machine/logic/MultiRecipeTypesLogic\n+ 2 fake.kt\nkotlin/jvm/internal/FakeKt\n*L\n1#1,145:1\n1#2:146\n*E\n"})
public final class MultiRecipeTypesLogic
extends RecipeLogic
implements ILockRecipe,
IRecipeStatus {
    @NotNull
    private final MultipleRecipeTypesMachine multiTypeMachine;
    private long eut;

    public MultiRecipeTypesLogic(@NotNull MultipleRecipeTypesMachine multiTypeMachine) {
        Intrinsics.checkNotNullParameter((Object)multiTypeMachine, (String)"multiTypeMachine");
        super((IRecipeLogicMachine)multiTypeMachine);
        this.multiTypeMachine = multiTypeMachine;
    }

    @NotNull
    public MultipleRecipeTypesMachine getMachine() {
        MetaMachine metaMachine = super.getMachine();
        Intrinsics.checkNotNull((Object)metaMachine, (String)"null cannot be cast to non-null type com.gtladd.gtladditions.api.machine.MultipleRecipeTypesMachine");
        return (MultipleRecipeTypesMachine)metaMachine;
    }

    public void findAndHandleRecipe() {
        this.lastRecipe = null;
        this.lastOriginRecipe = null;
        this.setRecipeStatus(null);
        if (this.isLock() && this.getLockRecipe() != null) {
            this.lastOriginRecipe = this.getLockRecipe();
            GTRecipe gTRecipe = this.getLockRecipe();
            Intrinsics.checkNotNullExpressionValue((Object)gTRecipe, (String)"getLockRecipe(...)");
            GTRecipe gTRecipe2 = this.multiTypeMachine.modifyRecipe(gTRecipe);
            if (gTRecipe2 != null) {
                GTRecipe it = gTRecipe2;
                boolean bl = false;
                if (this.checkRecipe(it)) {
                    this.setupRecipe(it);
                }
            }
        } else {
            GTRecipeUtils.INSTANCE.withSearchContext((WorkableElectricMultiblockMachine)this.multiTypeMachine, arg_0 -> MultiRecipeTypesLogic.findAndHandleRecipe$lambda$1(this, arg_0));
        }
    }

    public void setupRecipe(@NotNull GTRecipe recipe) {
        Intrinsics.checkNotNullParameter((Object)recipe, (String)"recipe");
        if (!this.machine.beforeWorking(recipe)) {
            this.setStatus(RecipeLogic.Status.IDLE);
            this.progress = 0;
            this.duration = 0;
            return;
        }
        if (this.handleRecipeIO(recipe, IO.IN)) {
            if (this.lastRecipe != null && !Intrinsics.areEqual((Object)recipe, (Object)this.lastRecipe)) {
                this.chanceCaches.clear();
            }
            this.eut = GTRecipeUtils.INSTANCE.getGetEU(recipe);
            this.lastRecipe = recipe;
            this.setStatus(RecipeLogic.Status.WORKING);
            this.progress = 0;
            this.duration = recipe.duration;
        }
    }

    public void handleRecipeWorking() {
        if (this.lastRecipe == null) {
            String string = "Required value was null.";
            throw new IllegalStateException(string.toString());
        }
        MultipleRecipeTypesMachine multipleRecipeTypesMachine = this.multiTypeMachine;
        Intrinsics.checkNotNull((Object)multipleRecipeTypesMachine, (String)"null cannot be cast to non-null type com.gtladd.gtladditions.api.machine.IEnergyMachine");
        if (GTRecipeUtils.INSTANCE.matchEUt(this.eut, (IEnergyMachine)multipleRecipeTypesMachine)) {
            this.setStatus(RecipeLogic.Status.WORKING);
            GTRecipeUtils.INSTANCE.handleEUt(this.eut, (IEnergyMachine)this.multiTypeMachine);
            if (!this.machine.onWorking()) {
                this.interruptRecipe();
                return;
            }
            ++this.progress;
            ++this.totalContinuousRunningTime;
        } else {
            this.setWaiting(null);
        }
        if (this.getStatus() == RecipeLogic.Status.WAITING) {
            this.doDamping();
        }
    }

    private final boolean handleSearchingRecipes(GTRecipe recipe) {
        GTRecipe gTRecipe = recipe;
        if (gTRecipe != null) {
            GTRecipe it = gTRecipe;
            boolean bl = false;
            GTRecipe gTRecipe2 = this.multiTypeMachine.modifyRecipe(it);
            if (gTRecipe2 != null) {
                GTRecipe modify = gTRecipe2;
                boolean bl2 = false;
                if (this.checkRecipe(modify)) {
                    if (this.isLock()) {
                        this.setLockRecipe(it);
                    }
                    this.lastOriginRecipe = it;
                    this.setupRecipe(modify);
                    return true;
                }
            }
        }
        return false;
    }

    public void onRecipeFinish() {
        this.machine.afterWorking();
        GTRecipe gTRecipe = this.lastRecipe;
        if (gTRecipe != null) {
            GTRecipe it = gTRecipe;
            boolean bl = false;
            RecipeRunnerHelper.handleRecipeOutput((IRecipeLogicMachine)this.machine, (GTRecipe)it);
        }
        if (this.machine instanceof ISuspendableMachine) {
            IRecipeLogicMachine iRecipeLogicMachine = this.machine;
            Intrinsics.checkNotNull((Object)iRecipeLogicMachine, (String)"null cannot be cast to non-null type org.gtlcore.gtlcore.api.machine.ISuspendableMachine");
            ISuspendableMachine ism = (ISuspendableMachine)iRecipeLogicMachine;
            if (ism.gtlcore$isSuspendAfterFinish()) {
                this.setStatus(RecipeLogic.Status.SUSPEND);
                ism.gtlcore$setSuspendAfterFinish(false);
            } else {
                GTRecipe gTRecipe2 = this.lastOriginRecipe;
                if (gTRecipe2 != null) {
                    GTRecipe it = gTRecipe2;
                    boolean bl = false;
                    if (this.handleSearchingRecipes(it)) {
                        return;
                    }
                }
                this.setStatus(RecipeLogic.Status.IDLE);
            }
        }
        this.progress = 0;
        this.duration = 0;
    }

    public void saveCustomPersistedData(@NotNull CompoundTag tag, boolean forDrop) {
        Intrinsics.checkNotNullParameter((Object)tag, (String)"tag");
        super.saveCustomPersistedData(tag, forDrop);
        tag.m_128356_("eut", this.eut);
    }

    public void loadCustomPersistedData(@NotNull CompoundTag tag) {
        Intrinsics.checkNotNullParameter((Object)tag, (String)"tag");
        super.loadCustomPersistedData(tag);
        if (tag.m_128441_("eut")) {
            this.eut = tag.m_128454_("eut");
        }
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    private final boolean checkConditionsOnly(GTRecipe recipe) {
        if (IGTRecipe.of((GTRecipe)recipe).getEuTier() > this.multiTypeMachine.getTier()) return false;
        if (!recipe.checkConditions((RecipeLogic)this).isSuccess()) return false;
        IRecipeLogicMachine iRecipeLogicMachine = this.machine;
        if (!(iRecipeLogicMachine instanceof ICoilMachine)) return true;
        ICoilMachine iCoilMachine = (ICoilMachine)iRecipeLogicMachine;
        if (iCoilMachine == null) return true;
        ICoilMachine it = iCoilMachine;
        boolean bl = false;
        boolean bl2 = this.coilOk(it, recipe);
        if (!bl2) return false;
        return true;
    }

    private final boolean coilOk(ICoilMachine coil, GTRecipe recipe) {
        long temp = (long)coil.getCoilType().getCoilTemperature() + 100L * (long)MathUtil.INSTANCE.maxToInt((Number)0, (Number)(this.getMachine().getTier() - 2));
        if (temp < (long)recipe.data.m_128451_("ebf_temp")) {
            RecipeResult.of((IRecipeLogicMachine)this.machine, (RecipeResult)RecipeResult.FAIL_NO_ENOUGH_TEMPERATURE);
            return false;
        }
        return true;
    }

    /*
     * Enabled force condition propagation
     * Lifted jumps to return sites
     */
    private final boolean checkRecipe(GTRecipe recipe) {
        if (!RecipeRunnerHelper.matchRecipe((IRecipeCapabilityHolder)((IRecipeCapabilityHolder)this.machine), (GTRecipe)recipe)) return false;
        if (!recipe.matchTickRecipe((IRecipeCapabilityHolder)this.machine).isSuccess()) return false;
        if (!recipe.checkConditions((RecipeLogic)this).isSuccess()) return false;
        IRecipeLogicMachine iRecipeLogicMachine = this.machine;
        if (!(iRecipeLogicMachine instanceof ICoilMachine)) return true;
        ICoilMachine iCoilMachine = (ICoilMachine)iRecipeLogicMachine;
        if (iCoilMachine == null) return true;
        ICoilMachine it = iCoilMachine;
        boolean bl = false;
        boolean bl2 = this.coilOk(it, recipe);
        if (!bl2) return false;
        return true;
    }

    private static final Unit findAndHandleRecipe$lambda$1(MultiRecipeTypesLogic this$0, RecipeSearchContext ctx) {
        if (ctx != null) {
            this$0.handleSearchingRecipes(OptimizedRecipeSearch.find((WorkableElectricMultiblockMachine)this$0.multiTypeMachine, (Branch)this$0.multiTypeMachine.getMultiRecipeType().getLookup().getLookup(), this$0::checkConditionsOnly));
        }
        return Unit.INSTANCE;
    }
}
