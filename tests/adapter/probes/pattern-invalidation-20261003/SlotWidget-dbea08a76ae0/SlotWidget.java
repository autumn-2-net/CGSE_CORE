/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib.side.item.IItemTransfer
 *  javax.annotation.Nonnull
 *  javax.annotation.Nullable
 *  net.minecraft.world.Container
 *  net.minecraft.world.SimpleContainer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.inventory.Slot
 *  net.minecraft.world.item.ItemStack
 *  org.jetbrains.annotations.NotNull
 */
package com.lowdragmc.lowdraglib.gui.widget;

import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SlotWidget.WidgetSlotItemTransfer
extends Slot {
    private static final Container emptyInventory = new SimpleContainer(0);
    private final IItemTransfer itemHandler;
    private final int index;

    public SlotWidget.WidgetSlotItemTransfer(IItemTransfer itemHandler, int index, int xPosition, int yPosition) {
        super(emptyInventory, index, xPosition, yPosition);
        this.itemHandler = itemHandler;
        this.index = index;
    }

    public boolean m_5857_(@Nonnull ItemStack stack) {
        return SlotWidget.this.canPutStack(stack) && !stack.m_41619_() && this.itemHandler.isItemValid(this.index, stack);
    }

    public boolean m_8010_(@Nullable Player playerIn) {
        return SlotWidget.this.canTakeStack(playerIn) && !this.itemHandler.extractItem(this.index, 1, true).m_41619_();
    }

    @Nonnull
    public ItemStack m_7993_() {
        return this.itemHandler.getStackInSlot(this.index);
    }

    public void m_5852_(@Nonnull ItemStack stack) {
        this.itemHandler.setStackInSlot(this.index, stack);
        this.m_6654_();
    }

    public void m_40234_(@Nonnull ItemStack oldStackIn, @Nonnull ItemStack newStackIn) {
    }

    public int m_6641_() {
        return this.itemHandler.getSlotLimit(this.index);
    }

    public int m_5866_(@Nonnull ItemStack stack) {
        ItemStack maxAdd = stack.m_41777_();
        int maxInput = stack.m_41741_();
        maxAdd.m_41764_(maxInput);
        ItemStack currentStack = this.itemHandler.getStackInSlot(this.index);
        this.itemHandler.setStackInSlot(this.index, ItemStack.f_41583_);
        ItemStack remainder = this.itemHandler.insertItem(this.index, maxAdd, true);
        this.itemHandler.setStackInSlot(this.index, currentStack);
        return maxInput - remainder.m_41613_();
    }

    @NotNull
    public ItemStack m_6201_(int amount) {
        ItemStack result = this.itemHandler.extractItem(this.index, amount, false);
        if (SlotWidget.this.changeListener != null && !this.m_7993_().m_41619_()) {
            SlotWidget.this.changeListener.run();
        }
        return result;
    }

    public void m_6654_() {
        this.itemHandler.onContentsChanged();
        if (SlotWidget.this.changeListener != null) {
            SlotWidget.this.changeListener.run();
        }
        SlotWidget.this.onSlotChanged();
    }

    public boolean m_6659_() {
        return SlotWidget.this.isEnabled() && (HOVER_SLOT == null || HOVER_SLOT == this);
    }

    public IItemTransfer getItemHandler() {
        return this.itemHandler;
    }
}
