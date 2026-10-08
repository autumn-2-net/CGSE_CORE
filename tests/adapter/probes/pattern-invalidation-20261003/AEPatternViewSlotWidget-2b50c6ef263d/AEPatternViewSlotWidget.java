/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup
 *  com.lowdragmc.lowdraglib.gui.texture.IGuiTexture
 *  com.lowdragmc.lowdraglib.side.item.IItemTransfer
 *  com.lowdragmc.lowdraglib.utils.Position
 *  com.lowdragmc.lowdraglib.utils.Size
 *  net.minecraft.client.gui.GuiGraphics
 *  net.minecraft.world.Container
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 *  org.jetbrains.annotations.NotNull
 */
package com.gregtechceu.gtceu.integration.ae2.gui.widget.slot;

import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.side.item.IItemTransfer;
import com.lowdragmc.lowdraglib.utils.Position;
import com.lowdragmc.lowdraglib.utils.Size;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.Container;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

public class AEPatternViewSlotWidget
extends SlotWidget {
    protected IGuiTexture occupiedTexture;

    public AEPatternViewSlotWidget() {
    }

    public AEPatternViewSlotWidget(Container inventory, int slotIndex, int xPosition, int yPosition, boolean canTakeItems, boolean canPutItems) {
        super(inventory, slotIndex, xPosition, yPosition, canTakeItems, canPutItems);
    }

    public AEPatternViewSlotWidget(IItemTransfer itemHandler, int slotIndex, int xPosition, int yPosition, boolean canTakeItems, boolean canPutItems) {
        super(itemHandler, slotIndex, xPosition, yPosition, canTakeItems, canPutItems);
    }

    public AEPatternViewSlotWidget(IItemTransfer itemHandler, int slotIndex, int xPosition, int yPosition) {
        super(itemHandler, slotIndex, xPosition, yPosition);
    }

    public AEPatternViewSlotWidget(Container inventory, int slotIndex, int xPosition, int yPosition) {
        super(inventory, slotIndex, xPosition, yPosition);
    }

    public AEPatternViewSlotWidget setOccupiedTexture(IGuiTexture ... occupiedTexture) {
        this.occupiedTexture = occupiedTexture.length > 1 ? new GuiTextureGroup(occupiedTexture) : occupiedTexture[0];
        return this;
    }

    @OnlyIn(value=Dist.CLIENT)
    public void updateScreen() {
        super.updateScreen();
        if (this.occupiedTexture != null) {
            this.occupiedTexture.updateTick();
        }
    }

    @OnlyIn(value=Dist.CLIENT)
    protected void drawBackgroundTexture(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        Position pos = this.getPosition();
        Size size = this.getSize();
        if (this.getHandler() != null && this.getHandler().m_6657_()) {
            if (this.occupiedTexture != null) {
                this.occupiedTexture.draw(graphics, mouseX, mouseY, (float)pos.x, (float)pos.y, size.width, size.height);
            }
        } else if (this.backgroundTexture != null) {
            this.backgroundTexture.draw(graphics, mouseX, mouseY, (float)pos.x, (float)pos.y, size.width, size.height);
        }
        if (this.hoverTexture != null && this.isMouseOverElement(mouseX, mouseY)) {
            this.hoverTexture.draw(graphics, mouseX, mouseY, (float)pos.x, (float)pos.y, size.width, size.height);
        }
    }
}
