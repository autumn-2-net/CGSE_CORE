/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
 *  com.gregtechceu.gtceu.api.machine.MetaMachine
 *  com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine
 *  kotlin.Metadata
 *  kotlin.jvm.internal.Intrinsics
 *  org.gtlcore.gtlcore.common.data.GTLRecipeModifiers
 *  org.jetbrains.annotations.NotNull
 */
package com.gtladd.gtladditions.api.machine.wireless;

import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gtladd.gtladditions.api.machine.wireless.GTLAddWirelessWorkableElectricMultipleRecipesMachine;
import java.util.Arrays;
import kotlin.Metadata;
import kotlin.jvm.internal.Intrinsics;
import org.gtlcore.gtlcore.common.data.GTLRecipeModifiers;
import org.jetbrains.annotations.NotNull;

@Metadata(mv={2, 0, 0}, k=1, xi=48, d1={"\u0000\"\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0011\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0003\u0018\u00002\u00020\u0001B'\u0012\u0006\u0010\u0003\u001a\u00020\u0002\u0012\u0016\u0010\u0006\u001a\f\u0012\b\b\u0001\u0012\u0004\u0018\u00010\u00050\u0004\"\u0004\u0018\u00010\u0005\u00a2\u0006\u0004\b\u0007\u0010\bJ\u000f\u0010\n\u001a\u00020\tH\u0016\u00a2\u0006\u0004\b\n\u0010\u000b\u00a8\u0006\f"}, d2={"Lcom/gtladd/gtladditions/api/machine/wireless/GTLAddWirelessWorkableElectricParallelHatchMultipleRecipesMachine;", "Lcom/gtladd/gtladditions/api/machine/wireless/GTLAddWirelessWorkableElectricMultipleRecipesMachine;", "Lcom/gregtechceu/gtceu/api/machine/IMachineBlockEntity;", "holder", "", "", "args", "<init>", "(Lcom/gregtechceu/gtceu/api/machine/IMachineBlockEntity;[Ljava/lang/Object;)V", "", "getMaxParallel", "()I", "gtladditions"})
public final class GTLAddWirelessWorkableElectricParallelHatchMultipleRecipesMachine
extends GTLAddWirelessWorkableElectricMultipleRecipesMachine {
    public GTLAddWirelessWorkableElectricParallelHatchMultipleRecipesMachine(@NotNull IMachineBlockEntity holder, Object ... args) {
        Intrinsics.checkNotNullParameter((Object)holder, (String)"holder");
        Intrinsics.checkNotNullParameter((Object)args, (String)"args");
        super(holder, Arrays.copyOf(args, args.length));
    }

    public int getMaxParallel() {
        return GTLRecipeModifiers.getHatchParallel((MetaMachine)((MetaMachine)this));
    }
}
