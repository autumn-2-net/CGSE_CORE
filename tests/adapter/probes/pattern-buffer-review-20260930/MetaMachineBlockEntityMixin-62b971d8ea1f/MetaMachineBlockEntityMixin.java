/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  appeng.hooks.ticking.TickHandler
 *  com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity
 *  com.gregtechceu.gtceu.api.machine.IMachineBlockEntity
 *  com.gregtechceu.gtceu.api.machine.MetaMachine
 *  com.hepdd.gtmthings.api.capability.IBindable
 *  net.minecraft.core.BlockPos
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.world.level.block.entity.BlockEntity
 *  net.minecraft.world.level.block.entity.BlockEntityType
 *  net.minecraft.world.level.block.state.BlockState
 *  org.jetbrains.annotations.NotNull
 *  org.spongepowered.asm.mixin.Final
 *  org.spongepowered.asm.mixin.Mixin
 *  org.spongepowered.asm.mixin.Shadow
 */
package com.gtladd.gtladditions.mixin.gtceu.api.machine;

import appeng.hooks.ticking.TickHandler;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.hepdd.gtmthings.api.capability.IBindable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value={MetaMachineBlockEntity.class})
public abstract class MetaMachineBlockEntityMixin
extends BlockEntity
implements IMachineBlockEntity {
    @Shadow(remap=false)
    @Final
    public MetaMachine metaMachine;

    public MetaMachineBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    public void m_142466_(@NotNull CompoundTag tag) {
        super.m_142466_(tag);
        MetaMachine metaMachine = this.metaMachine;
        if (metaMachine instanceof IBindable) {
            IBindable b = (IBindable)metaMachine;
            if (tag.m_128441_("uuid")) {
                b.setUUID(tag.m_128342_("uuid"));
                this.metaMachine.onLoad();
            }
        }
    }

    public long getOffsetTimer() {
        return this.level() == null ? this.getOffset() : TickHandler.instance().getCurrentTick() + this.getOffset();
    }
}
