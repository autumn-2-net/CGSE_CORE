/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib.side.item.IItemTransfer
 *  com.lowdragmc.lowdraglib.side.item.ItemTransferHelper
 *  com.lowdragmc.lowdraglib.syncdata.IContentChangeAware
 *  com.lowdragmc.lowdraglib.syncdata.ITagSerializable
 *  javax.annotation.Nonnull
 *  net.minecraft.core.NonNullList
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.ListTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.world.item.ItemStack
 *  org.jetbrains.annotations.NotNull
 */
package com.lowdragmc.lowdraglib.misc;

import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.side.item.ItemTransferHelper;
import com.lowdragmc.lowdraglib.syncdata.IContentChangeAware;
import com.lowdragmc.lowdraglib.syncdata.ITagSerializable;
import java.util.function.Function;
import javax.annotation.Nonnull;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public class ItemStackTransfer
implements IItemTransfer,
ITagSerializable<CompoundTag>,
IContentChangeAware {
    protected NonNullList<ItemStack> stacks;
    private Runnable onContentsChanged;
    private Function<ItemStack, Boolean> filter;

    public ItemStackTransfer() {
        this(1);
    }

    public ItemStackTransfer(int size) {
        this.onContentsChanged = () -> {};
        this.stacks = NonNullList.m_122780_((int)size, (Object)ItemStack.f_41583_);
    }

    public ItemStackTransfer(NonNullList<ItemStack> stacks) {
        this.onContentsChanged = () -> {};
        this.stacks = stacks;
    }

    public ItemStackTransfer(ItemStack stack) {
        this((NonNullList<ItemStack>)NonNullList.m_122783_((Object)ItemStack.f_41583_, (Object[])new ItemStack[]{stack}));
    }

    public void setSize(int size) {
        this.stacks = NonNullList.m_122780_((int)size, (Object)ItemStack.f_41583_);
    }

    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        this.validateSlotIndex(slot);
        this.stacks.set(slot, (Object)stack);
    }

    public int getSlots() {
        return this.stacks.size();
    }

    @Nonnull
    public ItemStack getStackInSlot(int slot) {
        this.validateSlotIndex(slot);
        return (ItemStack)this.stacks.get(slot);
    }

    @Nonnull
    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate, boolean notifyChanges) {
        boolean reachedLimit;
        if (stack.m_41619_()) {
            return ItemStack.f_41583_;
        }
        if (!this.isItemValid(slot, stack)) {
            return stack;
        }
        this.validateSlotIndex(slot);
        ItemStack existing = (ItemStack)this.stacks.get(slot);
        int limit = this.getStackLimit(slot, stack);
        if (!existing.m_41619_()) {
            if (!ItemTransferHelper.canItemStacksStack((ItemStack)stack, (ItemStack)existing)) {
                return stack;
            }
            limit -= existing.m_41613_();
        }
        if (limit <= 0) {
            return stack;
        }
        boolean bl = reachedLimit = stack.m_41613_() > limit;
        if (!simulate) {
            if (existing.m_41619_()) {
                this.stacks.set(slot, (Object)(reachedLimit ? ItemTransferHelper.copyStackWithSize((ItemStack)stack, (int)limit) : stack));
            } else {
                existing.m_41769_(reachedLimit ? limit : stack.m_41613_());
            }
            if (notifyChanges) {
                this.onContentsChanged(slot);
            }
        }
        return reachedLimit ? ItemTransferHelper.copyStackWithSize((ItemStack)stack, (int)(stack.m_41613_() - limit)) : ItemStack.f_41583_;
    }

    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate, boolean notifyChanges) {
        if (amount == 0) {
            return ItemStack.f_41583_;
        }
        this.validateSlotIndex(slot);
        ItemStack existing = (ItemStack)this.stacks.get(slot);
        if (existing.m_41619_()) {
            return ItemStack.f_41583_;
        }
        int toExtract = Math.min(amount, existing.m_41741_());
        if (existing.m_41613_() <= toExtract) {
            if (!simulate) {
                this.stacks.set(slot, (Object)ItemStack.f_41583_);
                if (notifyChanges) {
                    this.onContentsChanged(slot);
                }
                return existing;
            }
            return existing.m_41777_();
        }
        if (!simulate) {
            this.stacks.set(slot, (Object)ItemTransferHelper.copyStackWithSize((ItemStack)existing, (int)(existing.m_41613_() - toExtract)));
            if (notifyChanges) {
                this.onContentsChanged(slot);
            }
        }
        return ItemTransferHelper.copyStackWithSize((ItemStack)existing, (int)toExtract);
    }

    public int getSlotLimit(int slot) {
        return 64;
    }

    protected int getStackLimit(int slot, @Nonnull ItemStack stack) {
        return Math.min(this.getSlotLimit(slot), stack.m_41741_());
    }

    public boolean isItemValid(int slot, @Nonnull ItemStack stack) {
        return this.filter == null || this.filter.apply(stack) != false;
    }

    public CompoundTag serializeNBT() {
        ListTag nbtTagList = new ListTag();
        for (int i = 0; i < this.stacks.size(); ++i) {
            if (((ItemStack)this.stacks.get(i)).m_41619_()) continue;
            CompoundTag itemTag = new CompoundTag();
            itemTag.m_128405_("Slot", i);
            ((ItemStack)this.stacks.get(i)).m_41739_(itemTag);
            nbtTagList.add((Object)itemTag);
        }
        CompoundTag nbt = new CompoundTag();
        nbt.m_128365_("Items", (Tag)nbtTagList);
        nbt.m_128405_("Size", this.stacks.size());
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) {
        this.setSize(nbt.m_128425_("Size", 3) ? nbt.m_128451_("Size") : this.stacks.size());
        ListTag tagList = nbt.m_128437_("Items", 10);
        for (int i = 0; i < tagList.size(); ++i) {
            CompoundTag itemTags = tagList.m_128728_(i);
            int slot = itemTags.m_128451_("Slot");
            if (slot < 0 || slot >= this.stacks.size()) continue;
            this.stacks.set(slot, (Object)ItemStack.m_41712_((CompoundTag)itemTags));
        }
        this.onLoad();
    }

    protected void validateSlotIndex(int slot) {
        if (slot < 0 || slot >= this.stacks.size()) {
            throw new RuntimeException("Slot " + slot + " not in valid range - [0," + this.stacks.size() + ")");
        }
    }

    protected void onLoad() {
    }

    public void onContentsChanged() {
        this.onContentsChanged.run();
    }

    public void onContentsChanged(int slot) {
        this.onContentsChanged();
    }

    @NotNull
    public Object createSnapshot() {
        return this.stacks.stream().map(ItemStack::m_41777_).toArray(ItemStack[]::new);
    }

    public void restoreFromSnapshot(Object snapshot) {
        ItemStack[] copied;
        if (snapshot instanceof ItemStack[] && (copied = (ItemStack[])snapshot).length == this.stacks.size()) {
            for (int i = 0; i < this.stacks.size(); ++i) {
                this.stacks.set(i, (Object)copied[i].m_41777_());
            }
        }
    }

    public ItemStackTransfer copy() {
        NonNullList copiedStack = NonNullList.m_122780_((int)this.stacks.size(), (Object)ItemStack.f_41583_);
        for (int i = 0; i < this.stacks.size(); ++i) {
            copiedStack.set(i, (Object)((ItemStack)this.stacks.get(i)).m_41777_());
        }
        ItemStackTransfer copied = new ItemStackTransfer((NonNullList<ItemStack>)copiedStack);
        copied.setFilter(this.filter);
        return copied;
    }

    public Runnable getOnContentsChanged() {
        return this.onContentsChanged;
    }

    public void setOnContentsChanged(Runnable onContentsChanged) {
        this.onContentsChanged = onContentsChanged;
    }

    public void setFilter(Function<ItemStack, Boolean> filter) {
        this.filter = filter;
    }
}
