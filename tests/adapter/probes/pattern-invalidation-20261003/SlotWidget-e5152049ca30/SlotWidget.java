/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib.LDLib
 *  com.lowdragmc.lowdraglib.core.mixins.accessor.SlotAccessor
 *  com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable
 *  com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister
 *  com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator
 *  com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup
 *  com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget
 *  com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator
 *  com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot
 *  com.lowdragmc.lowdraglib.gui.modular.ModularUI
 *  com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer
 *  com.lowdragmc.lowdraglib.gui.texture.IGuiTexture
 *  com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture
 *  com.lowdragmc.lowdraglib.gui.util.DrawerHelper
 *  com.lowdragmc.lowdraglib.gui.widget.SlotWidget$1
 *  com.lowdragmc.lowdraglib.gui.widget.SlotWidget$2
 *  com.lowdragmc.lowdraglib.gui.widget.SlotWidget$EMICallWrapper
 *  com.lowdragmc.lowdraglib.gui.widget.SlotWidget$REICallWrapper
 *  com.lowdragmc.lowdraglib.gui.widget.SlotWidget$WidgetSlot
 *  com.lowdragmc.lowdraglib.gui.widget.Widget
 *  com.lowdragmc.lowdraglib.jei.IngredientIO
 *  com.lowdragmc.lowdraglib.jei.JEIPlugin
 *  com.lowdragmc.lowdraglib.side.item.IItemTransfer
 *  com.lowdragmc.lowdraglib.utils.CycleItemStackHandler
 *  com.lowdragmc.lowdraglib.utils.Position
 *  com.lowdragmc.lowdraglib.utils.Size
 *  com.lowdragmc.lowdraglib.utils.TagOrCycleItemStackTransfer
 *  com.mojang.blaze3d.platform.InputConstants$Key
 *  com.mojang.blaze3d.platform.InputConstants$Type
 *  com.mojang.blaze3d.systems.RenderSystem
 *  com.mojang.datafixers.util.Either
 *  javax.annotation.Nonnull
 *  javax.annotation.Nullable
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.core.HolderSet$ListBacked
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.network.chat.Component
 *  net.minecraft.tags.TagKey
 *  net.minecraft.world.Container
 *  net.minecraft.world.SimpleContainer
 *  net.minecraft.world.entity.player.Player
 *  net.minecraft.world.inventory.AbstractContainerMenu
 *  net.minecraft.world.inventory.ClickType
 *  net.minecraft.world.inventory.Slot
 *  net.minecraft.world.inventory.tooltip.TooltipComponent
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.block.Blocks
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 *  org.jetbrains.annotations.NotNull
 */
package com.lowdragmc.lowdraglib.gui.widget;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.core.mixins.accessor.SlotAccessor;
import com.lowdragmc.lowdraglib.gui.editor.annotation.Configurable;
import com.lowdragmc.lowdraglib.gui.editor.annotation.LDLRegister;
import com.lowdragmc.lowdraglib.gui.editor.configurator.Configurator;
import com.lowdragmc.lowdraglib.gui.editor.configurator.ConfiguratorGroup;
import com.lowdragmc.lowdraglib.gui.editor.configurator.IConfigurableWidget;
import com.lowdragmc.lowdraglib.gui.editor.configurator.WrapperConfigurator;
import com.lowdragmc.lowdraglib.gui.ingredient.IRecipeIngredientSlot;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIGuiContainer;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceBorderTexture;
import com.lowdragmc.lowdraglib.gui.util.DrawerHelper;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.jei.IngredientIO;
import com.lowdragmc.lowdraglib.jei.JEIPlugin;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.utils.CycleItemStackHandler;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import com.lowdragmc.lowdraglib.utils.TagOrCycleItemStackTransfer;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Either;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

/*
 * Exception performing whole class analysis ignored.
 */
@LDLRegister(name="item_slot", group="widget.container")
public class SlotWidget
extends Widget
implements IRecipeIngredientSlot,
IConfigurableWidget {
    public static final ResourceBorderTexture ITEM_SLOT_TEXTURE = new ResourceBorderTexture("ldlib:textures/gui/slot.png", 18, 18, 1, 1);
    @Nullable
    protected static Slot HOVER_SLOT = null;
    @Nullable
    protected Slot slotReference;
    @Configurable(name="ldlib.gui.editor.name.canTakeItems")
    protected boolean canTakeItems;
    @Configurable(name="ldlib.gui.editor.name.canPutItems")
    protected boolean canPutItems;
    public boolean isPlayerContainer;
    public boolean isPlayerHotBar;
    @Configurable(name="ldlib.gui.editor.name.drawHoverOverlay")
    public boolean drawHoverOverlay = true;
    @Configurable(name="ldlib.gui.editor.name.drawHoverTips")
    public boolean drawHoverTips = true;
    protected Runnable changeListener;
    protected BiConsumer<SlotWidget, List<Component>> onAddedTooltips;
    protected Function<ItemStack, ItemStack> itemHook;
    protected IngredientIO ingredientIO = IngredientIO.RENDER_ONLY;
    protected float XEIChance = 1.0f;
    @Nullable
    ItemStack currentJEIRenderedIngredient = null;

    public SlotWidget() {
        super(new Position(0, 0), new Size(18, 18));
    }

    public void initTemplate() {
        this.setBackgroundTexture((IGuiTexture)ITEM_SLOT_TEXTURE);
        this.canTakeItems = true;
        this.canPutItems = true;
    }

    public SlotWidget(Container inventory, int slotIndex, int xPosition, int yPosition, boolean canTakeItems, boolean canPutItems) {
        super(new Position(xPosition, yPosition), new Size(18, 18));
        this.setBackgroundTexture((IGuiTexture)ITEM_SLOT_TEXTURE);
        this.canTakeItems = canTakeItems;
        this.canPutItems = canPutItems;
        this.setContainerSlot(inventory, slotIndex);
    }

    public SlotWidget(IItemTransfer itemHandler, int slotIndex, int xPosition, int yPosition, boolean canTakeItems, boolean canPutItems) {
        super(new Position(xPosition, yPosition), new Size(18, 18));
        this.setBackgroundTexture((IGuiTexture)ITEM_SLOT_TEXTURE);
        this.canTakeItems = canTakeItems;
        this.canPutItems = canPutItems;
        this.setHandlerSlot(itemHandler, slotIndex);
    }

    protected Slot createSlot(Container inventory, int index) {
        return new WidgetSlot(this, inventory, index, 0, 0);
    }

    protected Slot createSlot(IItemTransfer itemHandler, int index) {
        return new WidgetSlotItemTransfer(itemHandler, index, 0, 0);
    }

    public SlotWidget setContainerSlot(Container inventory, int slotIndex) {
        this.updateSlot(this.createSlot(inventory, slotIndex));
        return this;
    }

    public SlotWidget setHandlerSlot(IItemTransfer itemHandler, int slotIndex) {
        this.updateSlot(this.createSlot(itemHandler, slotIndex));
        return this;
    }

    protected void updateSlot(Slot slot) {
        if (this.slotReference != null && this.gui != null && !this.isClientSideWidget) {
            this.getGui().removeNativeSlot(this.slotReference);
        }
        this.slotReference = slot;
        if (this.gui != null && !this.isClientSideWidget) {
            this.getGui().addNativeSlot(this.slotReference, this);
        }
    }

    public final void setSize(Size size) {
    }

    public void setGui(ModularUI gui) {
        if (!this.isClientSideWidget && this.gui != gui) {
            if (this.gui != null && this.slotReference != null) {
                this.gui.removeNativeSlot(this.slotReference);
            }
            if (gui != null && this.slotReference != null) {
                gui.addNativeSlot(this.slotReference, this);
            }
        }
        super.setGui(gui);
    }

    @OnlyIn(value=Dist.CLIENT)
    public void drawInForeground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (this.slotReference != null && this.drawHoverTips && this.isMouseOverElement(mouseX, mouseY) && this.getHoverElement(mouseX, mouseY) == this) {
            ItemStack stack = this.slotReference.m_7993_();
            if (this.gui != null) {
                this.gui.getModularUIGui().setHoveredSlot(this.slotReference);
            }
            if (!stack.m_41619_() && this.gui != null) {
                this.gui.getModularUIGui().setHoverTooltip(this.getFullTooltipTexts(), stack, null, (TooltipComponent)stack.m_150921_().orElse(null));
            } else {
                super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
            }
        } else {
            super.drawInForeground(graphics, mouseX, mouseY, partialTicks);
        }
    }

    @OnlyIn(value=Dist.CLIENT)
    public void drawInBackground(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        Position pos = this.getPosition();
        if (this.slotReference != null) {
            ModularUIGuiContainer modularUIGui;
            ItemStack itemStack = this.currentJEIRenderedIngredient == null ? this.getRealStack(this.slotReference.m_7993_()) : this.currentJEIRenderedIngredient;
            ModularUIGuiContainer modularUIGuiContainer = modularUIGui = this.gui == null ? null : this.gui.getModularUIGui();
            if (itemStack.m_41619_() && modularUIGui != null && modularUIGui.getQuickCrafting() && modularUIGui.getQuickCraftSlots().contains(this.slotReference)) {
                int splitSize = modularUIGui.getQuickCraftSlots().size();
                itemStack = this.gui.getModularUIContainer().m_142621_();
                if (!itemStack.m_41619_() && splitSize > 1 && AbstractContainerMenu.m_38899_((Slot)this.slotReference, (ItemStack)itemStack, (boolean)true)) {
                    itemStack = itemStack.m_41777_();
                    itemStack.m_41769_(AbstractContainerMenu.m_278794_((Set)modularUIGui.getQuickCraftSlots(), (int)modularUIGui.dragSplittingLimit, (ItemStack)itemStack));
                    int k = Math.min(itemStack.m_41741_(), this.slotReference.m_5866_(itemStack));
                    if (itemStack.m_41613_() > k) {
                        itemStack.m_41764_(k);
                    }
                }
            }
            if (!itemStack.m_41619_()) {
                DrawerHelper.drawItemStack((GuiGraphics)graphics, (ItemStack)itemStack, (int)(pos.x + 1), (int)(pos.y + 1), (int)-1, null);
            }
        }
        this.drawOverlay(graphics, mouseX, mouseY, partialTicks);
        if (this.drawHoverOverlay && this.isMouseOverElement(mouseX, mouseY) && this.getHoverElement(mouseX, mouseY) == this) {
            RenderSystem.colorMask((boolean)true, (boolean)true, (boolean)true, (boolean)false);
            DrawerHelper.drawSolidRect((GuiGraphics)graphics, (int)(this.getPosition().x + 1), (int)(this.getPosition().y + 1), (int)16, (int)16, (int)-2130706433);
            RenderSystem.colorMask((boolean)true, (boolean)true, (boolean)true, (boolean)true);
        }
    }

    @OnlyIn(value=Dist.CLIENT)
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.slotReference != null && this.isMouseOverElement(mouseX, mouseY) && this.gui != null) {
            ItemStack stack = this.slotReference.m_7993_();
            if (!(this.canPutItems && stack.m_41619_() || this.canTakeItems && !stack.m_41619_())) {
                return false;
            }
            ModularUIGuiContainer modularUIGui = this.gui.getModularUIGui();
            boolean last = modularUIGui.getQuickCrafting();
            InputConstants.Key mouseKey = InputConstants.Type.MOUSE.m_84895_(button);
            HOVER_SLOT = this.slotReference;
            this.gui.getModularUIGui().superMouseClicked(mouseX, mouseY, button);
            HOVER_SLOT = null;
            if (last != modularUIGui.getQuickCrafting()) {
                modularUIGui.dragSplittingButton = button;
                if (button == 0) {
                    modularUIGui.dragSplittingLimit = 0;
                } else if (button == 1) {
                    modularUIGui.dragSplittingLimit = 1;
                } else if (Minecraft.m_91087_().f_91066_.f_92097_.m_90830_(mouseKey.m_84873_())) {
                    modularUIGui.dragSplittingLimit = 2;
                }
            }
            return true;
        }
        return false;
    }

    @OnlyIn(value=Dist.CLIENT)
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.isMouseOverElement(mouseX, mouseY) && this.gui != null) {
            HOVER_SLOT = this.slotReference;
            this.gui.getModularUIGui().superMouseReleased(mouseX, mouseY, button);
            HOVER_SLOT = null;
            return this.getIngredientIO() == IngredientIO.RENDER_ONLY && (this.canPutItems || this.canTakeItems);
        }
        return false;
    }

    @OnlyIn(value=Dist.CLIENT)
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isMouseOverElement(mouseX, mouseY) && this.gui != null) {
            this.gui.getModularUIGui().superMouseDragged(mouseX, mouseY, button, dragX, dragY);
            return true;
        }
        return false;
    }

    protected void onPositionUpdate() {
        if (this.gui != null) {
            Position position = this.getPosition();
            if (this.slotReference != null) {
                ((SlotAccessor)this.slotReference).setX(position.x + 1 - this.gui.getGuiLeft());
                ((SlotAccessor)this.slotReference).setY(position.y + 1 - this.gui.getGuiTop());
            }
        }
    }

    public SlotWidget(IItemTransfer itemHandler, int slotIndex, int xPosition, int yPosition) {
        this(itemHandler, slotIndex, xPosition, yPosition, true, true);
    }

    public SlotWidget(Container inventory, int slotIndex, int xPosition, int yPosition) {
        this(inventory, slotIndex, xPosition, yPosition, true, true);
    }

    public SlotWidget setBackgroundTexture(IGuiTexture backgroundTexture) {
        this.backgroundTexture = backgroundTexture;
        return this;
    }

    public boolean canPutStack(ItemStack stack) {
        return this.isEnabled() && this.canPutItems;
    }

    public boolean canTakeStack(Player player) {
        return this.isEnabled() && this.canTakeItems;
    }

    public boolean isEnabled() {
        return this.isActive() && this.isVisible();
    }

    public boolean canMergeSlot(ItemStack stack) {
        return this.isEnabled();
    }

    public void onSlotChanged() {
        if (this.gui == null) {
            return;
        }
        this.gui.holder.markAsDirty();
    }

    public ItemStack slotClick(int dragType, ClickType clickTypeIn, Player player) {
        return null;
    }

    @Nullable
    public final Slot getHandler() {
        return this.slotReference;
    }

    public SlotWidget setLocationInfo(boolean isPlayerContainer, boolean isPlayerHotBar) {
        this.isPlayerHotBar = isPlayerHotBar;
        this.isPlayerContainer = isPlayerContainer;
        return this;
    }

    public List<Component> getTooltipTexts() {
        List<Component> tooltips = this.getAdditionalToolTips(new ArrayList<Component>());
        tooltips.addAll(this.tooltipTexts);
        return tooltips;
    }

    public List<Component> getAdditionalToolTips(List<Component> list) {
        if (this.onAddedTooltips != null) {
            this.onAddedTooltips.accept(this, list);
        }
        return list;
    }

    public List<Component> getFullTooltipTexts() {
        if (this.slotReference != null) {
            ItemStack stack;
            ItemStack itemStack = stack = this.currentJEIRenderedIngredient == null ? this.slotReference.m_7993_() : this.currentJEIRenderedIngredient;
            if (!stack.m_41619_()) {
                ArrayList<Component> tips = new ArrayList<Component>(DrawerHelper.getItemToolTip((ItemStack)stack));
                tips.addAll(this.getTooltipTexts());
                return tips;
            }
        }
        return Collections.emptyList();
    }

    public void setCurrentJEIRenderedIngredient(Object ingredient) {
        this.currentJEIRenderedIngredient = ingredient instanceof ItemStack ? (ItemStack)ingredient : null;
    }

    @Nullable
    public Object getXEIIngredientOverMouse(double mouseX, double mouseY) {
        if (this.self().isMouseOverElement(mouseX, mouseY)) {
            Slot handler = this.getHandler();
            if (handler == null) {
                return null;
            }
            ItemStack realStack = this.getRealStack(handler.m_7993_());
            if (handler instanceof WidgetSlotItemTransfer) {
                WidgetSlotItemTransfer widgetSlotItemTransfer = (WidgetSlotItemTransfer)handler;
                IItemTransfer iItemTransfer = widgetSlotItemTransfer.itemHandler;
                if (iItemTransfer instanceof CycleItemStackHandler) {
                    CycleItemStackHandler cycleItemStackHandler = (CycleItemStackHandler)iItemTransfer;
                    return this.getXEIIngredientsFromCycleTransferClickable(cycleItemStackHandler, widgetSlotItemTransfer.index);
                }
                iItemTransfer = widgetSlotItemTransfer.itemHandler;
                if (iItemTransfer instanceof TagOrCycleItemStackTransfer) {
                    TagOrCycleItemStackTransfer transfer = (TagOrCycleItemStackTransfer)iItemTransfer;
                    return this.getXEIIngredientsFromTagOrCycleTransferClickable(transfer, widgetSlotItemTransfer.index);
                }
            }
            if (LDLib.isJeiLoaded() && !realStack.m_41619_()) {
                return JEIPlugin.getItemIngredient((ItemStack)realStack, (int)this.getPosition().x, (int)this.getPosition().y, (int)this.getSize().width, (int)this.getSize().height);
            }
            if (LDLib.isReiLoaded()) {
                return REICallWrapper.getReiIngredients((ItemStack)realStack);
            }
            if (LDLib.isEmiLoaded()) {
                return EMICallWrapper.getEmiIngredients((ItemStack)realStack, (float)this.getXEIChance());
            }
            return realStack;
        }
        return null;
    }

    public List<Object> getXEIIngredients() {
        if (this.slotReference == null || this.slotReference.m_7993_().m_41619_()) {
            return Collections.emptyList();
        }
        Slot handler = this.getHandler();
        if (handler == null) {
            return Collections.emptyList();
        }
        ItemStack realStack = this.getRealStack(handler.m_7993_());
        if (handler instanceof WidgetSlotItemTransfer) {
            WidgetSlotItemTransfer widgetSlotItemTransfer = (WidgetSlotItemTransfer)handler;
            IItemTransfer iItemTransfer = widgetSlotItemTransfer.itemHandler;
            if (iItemTransfer instanceof CycleItemStackHandler) {
                CycleItemStackHandler cycleItemStackHandler = (CycleItemStackHandler)iItemTransfer;
                return this.getXEIIngredientsFromCycleTransferClickable(cycleItemStackHandler, widgetSlotItemTransfer.index);
            }
            iItemTransfer = widgetSlotItemTransfer.itemHandler;
            if (iItemTransfer instanceof TagOrCycleItemStackTransfer) {
                TagOrCycleItemStackTransfer transfer = (TagOrCycleItemStackTransfer)iItemTransfer;
                return this.getXEIIngredientsFromTagOrCycleTransferClickable(transfer, widgetSlotItemTransfer.index);
            }
        }
        if (LDLib.isJeiLoaded()) {
            Object ingredient = JEIPlugin.getItemIngredient((ItemStack)realStack, (int)this.getPosition().x, (int)this.getPosition().y, (int)this.getSize().width, (int)this.getSize().height);
            return ingredient == null ? Collections.emptyList() : List.of((Object)ingredient);
        }
        if (LDLib.isReiLoaded()) {
            return REICallWrapper.getReiIngredients((ItemStack)realStack);
        }
        if (LDLib.isEmiLoaded()) {
            return EMICallWrapper.getEmiIngredients((ItemStack)realStack, (float)this.getXEIChance());
        }
        return List.of((Object)realStack);
    }

    public ItemStack getRealStack(ItemStack itemStack) {
        if (this.itemHook != null) {
            return this.itemHook.apply(itemStack);
        }
        return itemStack;
    }

    private List<Object> getXEIIngredientsFromCycleTransfer(CycleItemStackHandler transfer, int index) {
        Stream<ItemStack> stream = transfer.getStackList(index).stream().map(this::getRealStack);
        if (LDLib.isJeiLoaded()) {
            return stream.filter(stack -> !stack.m_41619_()).collect(Collectors.toList());
        }
        if (LDLib.isReiLoaded()) {
            return REICallWrapper.getReiIngredients(stream);
        }
        if (LDLib.isEmiLoaded()) {
            return EMICallWrapper.getEmiIngredients(stream, (float)this.getXEIChance());
        }
        return Collections.emptyList();
    }

    private List<Object> getXEIIngredientsFromCycleTransferClickable(CycleItemStackHandler transfer, int index) {
        Stream<ItemStack> stream = transfer.getStackList(index).stream().map(this::getRealStack);
        if (LDLib.isJeiLoaded()) {
            return stream.filter(stack -> !stack.m_41619_()).map(item -> JEIPlugin.getItemIngredient((ItemStack)item, (int)this.getPosition().x, (int)this.getPosition().y, (int)this.getSize().width, (int)this.getSize().height)).toList();
        }
        if (LDLib.isReiLoaded()) {
            return REICallWrapper.getReiIngredients(stream);
        }
        if (LDLib.isEmiLoaded()) {
            return EMICallWrapper.getEmiIngredients(stream, (float)this.getXEIChance());
        }
        return Collections.emptyList();
    }

    private List<Object> getXEIIngredientsFromTagOrCycleTransfer(TagOrCycleItemStackTransfer transfer, int index) {
        Either either = (Either)transfer.getStacks().get(index);
        1 ref = new /* Unavailable Anonymous Inner Class!! */;
        either.ifLeft(list -> {
            if (LDLib.isJeiLoaded()) {
                ref.returnValue = list.stream().flatMap(pair -> BuiltInRegistries.f_257033_.m_203431_((TagKey)pair.getFirst()).stream().flatMap(HolderSet.ListBacked::m_203614_).map(item -> this.getRealStack(new ItemStack((ItemLike)item.m_203334_(), ((Integer)pair.getSecond()).intValue())))).collect(Collectors.toList());
            } else if (LDLib.isReiLoaded()) {
                ref.returnValue = REICallWrapper.getReiIngredients(this::getRealStack, (List)list);
            } else if (LDLib.isEmiLoaded()) {
                ref.returnValue = EMICallWrapper.getEmiIngredients((List)list, (float)this.getXEIChance());
            }
        }).ifRight(items -> {
            Stream<ItemStack> stream = items.stream().map(this::getRealStack);
            if (LDLib.isJeiLoaded()) {
                ref.returnValue = stream.filter(stack -> !stack.m_41619_()).collect(Collectors.toList());
            } else if (LDLib.isReiLoaded()) {
                ref.returnValue = REICallWrapper.getReiIngredients(stream);
            } else if (LDLib.isEmiLoaded()) {
                ref.returnValue = EMICallWrapper.getEmiIngredients(stream, (float)this.getXEIChance());
            }
        });
        return ref.returnValue;
    }

    private List<Object> getXEIIngredientsFromTagOrCycleTransferClickable(TagOrCycleItemStackTransfer transfer, int index) {
        Either either = (Either)transfer.getStacks().get(index);
        2 ref = new /* Unavailable Anonymous Inner Class!! */;
        either.ifLeft(list -> {
            if (LDLib.isJeiLoaded()) {
                ref.returnValue = list.stream().flatMap(pair -> BuiltInRegistries.f_257033_.m_203431_((TagKey)pair.getFirst()).stream().flatMap(HolderSet.ListBacked::m_203614_).map(item -> JEIPlugin.getItemIngredient((ItemStack)this.getRealStack(new ItemStack((ItemLike)item.m_203334_(), ((Integer)pair.getSecond()).intValue())), (int)this.getPosition().x, (int)this.getPosition().y, (int)this.getSize().width, (int)this.getSize().height))).collect(Collectors.toList());
            } else if (LDLib.isReiLoaded()) {
                ref.returnValue = REICallWrapper.getReiIngredients(this::getRealStack, (List)list);
            } else if (LDLib.isEmiLoaded()) {
                ref.returnValue = EMICallWrapper.getEmiIngredients((List)list, (float)this.getXEIChance());
            }
        }).ifRight(items -> {
            Stream<ItemStack> stream = items.stream().map(this::getRealStack);
            if (LDLib.isJeiLoaded()) {
                ref.returnValue = stream.filter(stack -> !stack.m_41619_()).map(item -> JEIPlugin.getItemIngredient((ItemStack)item, (int)this.getPosition().x, (int)this.getPosition().y, (int)this.getSize().width, (int)this.getSize().height)).toList();
            } else if (LDLib.isReiLoaded()) {
                ref.returnValue = REICallWrapper.getReiIngredients(stream);
            } else if (LDLib.isEmiLoaded()) {
                ref.returnValue = EMICallWrapper.getEmiIngredients(stream, (float)this.getXEIChance());
            }
        });
        return ref.returnValue;
    }

    public void buildConfigurator(ConfiguratorGroup father) {
        ItemStackTransfer handler = new ItemStackTransfer();
        handler.setStackInSlot(0, Blocks.f_50069_.m_5456_().m_7968_());
        father.addConfigurators(new Configurator[]{new WrapperConfigurator("ldlib.gui.editor.group.preview", (Widget)new /* Unavailable Anonymous Inner Class!! */.setCanPutItems(false).setCanTakeItems(false).setHandlerSlot(handler, 0))});
        super.buildConfigurator(father);
    }

    public SlotWidget setCanTakeItems(boolean canTakeItems) {
        this.canTakeItems = canTakeItems;
        return this;
    }

    public SlotWidget setCanPutItems(boolean canPutItems) {
        this.canPutItems = canPutItems;
        return this;
    }

    public SlotWidget setDrawHoverOverlay(boolean drawHoverOverlay) {
        this.drawHoverOverlay = drawHoverOverlay;
        return this;
    }

    public SlotWidget setDrawHoverTips(boolean drawHoverTips) {
        this.drawHoverTips = drawHoverTips;
        return this;
    }

    public SlotWidget setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
        return this;
    }

    public SlotWidget setOnAddedTooltips(BiConsumer<SlotWidget, List<Component>> onAddedTooltips) {
        this.onAddedTooltips = onAddedTooltips;
        return this;
    }

    public SlotWidget setItemHook(Function<ItemStack, ItemStack> itemHook) {
        this.itemHook = itemHook;
        return this;
    }

    public SlotWidget setIngredientIO(IngredientIO ingredientIO) {
        this.ingredientIO = ingredientIO;
        return this;
    }

    public IngredientIO getIngredientIO() {
        return this.ingredientIO;
    }

    public SlotWidget setXEIChance(float XEIChance) {
        this.XEIChance = XEIChance;
        return this;
    }

    public float getXEIChance() {
        return this.XEIChance;
    }

    public class WidgetSlotItemTransfer
    extends Slot {
        private static final Container emptyInventory = new SimpleContainer(0);
        private final IItemTransfer itemHandler;
        private final int index;

        public WidgetSlotItemTransfer(IItemTransfer itemHandler, int index, int xPosition, int yPosition) {
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
}
