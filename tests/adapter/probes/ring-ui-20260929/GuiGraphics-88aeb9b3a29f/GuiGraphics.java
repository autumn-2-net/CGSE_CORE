/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.Lighting
 *  com.mojang.blaze3d.platform.Window
 *  com.mojang.blaze3d.systems.RenderSystem
 *  com.mojang.blaze3d.vertex.BufferBuilder
 *  com.mojang.blaze3d.vertex.BufferBuilder$RenderedBuffer
 *  com.mojang.blaze3d.vertex.BufferUploader
 *  com.mojang.blaze3d.vertex.DefaultVertexFormat
 *  com.mojang.blaze3d.vertex.PoseStack
 *  com.mojang.blaze3d.vertex.Tesselator
 *  com.mojang.blaze3d.vertex.VertexConsumer
 *  com.mojang.blaze3d.vertex.VertexFormat$Mode
 *  com.mojang.datafixers.util.Either
 *  com.mojang.math.Divisor
 *  it.unimi.dsi.fastutil.ints.IntIterator
 *  net.minecraft.CrashReport
 *  net.minecraft.CrashReportCategory
 *  net.minecraft.ReportedException
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.Font
 *  net.minecraft.client.gui.Font$DisplayMode
 *  net.minecraft.client.gui.GuiGraphics$ScissorStack
 *  net.minecraft.client.gui.navigation.ScreenRectangle
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
 *  net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner
 *  net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner
 *  net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil
 *  net.minecraft.client.player.LocalPlayer
 *  net.minecraft.client.renderer.GameRenderer
 *  net.minecraft.client.renderer.MultiBufferSource
 *  net.minecraft.client.renderer.MultiBufferSource$BufferSource
 *  net.minecraft.client.renderer.RenderType
 *  net.minecraft.client.renderer.texture.OverlayTexture
 *  net.minecraft.client.renderer.texture.TextureAtlasSprite
 *  net.minecraft.client.resources.model.BakedModel
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.FormattedText
 *  net.minecraft.network.chat.HoverEvent
 *  net.minecraft.network.chat.HoverEvent$Action
 *  net.minecraft.network.chat.HoverEvent$EntityTooltipInfo
 *  net.minecraft.network.chat.HoverEvent$ItemStackInfo
 *  net.minecraft.network.chat.Style
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.util.FastColor$ARGB32
 *  net.minecraft.util.FormattedCharSequence
 *  net.minecraft.util.Mth
 *  net.minecraft.world.entity.LivingEntity
 *  net.minecraft.world.inventory.tooltip.TooltipComponent
 *  net.minecraft.world.item.ItemDisplayContext
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.level.Level
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 *  net.minecraftforge.client.ForgeHooksClient
 *  net.minecraftforge.client.ItemDecoratorHandler
 *  net.minecraftforge.client.event.RenderTooltipEvent$Color
 *  net.minecraftforge.client.event.RenderTooltipEvent$Pre
 *  net.minecraftforge.client.extensions.IForgeGuiGraphics
 *  net.minecraftforge.registries.ForgeRegistries
 *  org.jetbrains.annotations.Nullable
 *  org.joml.Matrix4f
 *  org.joml.Vector2ic
 */
package net.minecraft.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.datafixers.util.Either;
import com.mojang.math.Divisor;
import it.unimi.dsi.fastutil.ints.IntIterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.ItemDecoratorHandler;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.client.extensions.IForgeGuiGraphics;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector2ic;

@OnlyIn(value=Dist.CLIENT)
public class GuiGraphics
implements IForgeGuiGraphics {
    public static final float MAX_GUI_Z = 10000.0f;
    public static final float MIN_GUI_Z = -10000.0f;
    private static final int EXTRA_SPACE_AFTER_FIRST_TOOLTIP_LINE = 2;
    private final Minecraft minecraft;
    private final PoseStack pose;
    private final MultiBufferSource.BufferSource bufferSource;
    private final ScissorStack scissorStack = new ScissorStack();
    private boolean managed;
    private ItemStack tooltipStack = ItemStack.EMPTY;

    private GuiGraphics(Minecraft arg, PoseStack arg2, MultiBufferSource.BufferSource arg3) {
        this.minecraft = arg;
        this.pose = arg2;
        this.bufferSource = arg3;
    }

    public GuiGraphics(Minecraft arg, MultiBufferSource.BufferSource arg2) {
        this(arg, new PoseStack(), arg2);
    }

    @Deprecated
    public void drawManaged(Runnable runnable) {
        this.flush();
        this.managed = true;
        runnable.run();
        this.managed = false;
        this.flush();
    }

    @Deprecated
    private void flushIfUnmanaged() {
        if (!this.managed) {
            this.flush();
        }
    }

    @Deprecated
    private void flushIfManaged() {
        if (this.managed) {
            this.flush();
        }
    }

    public int guiWidth() {
        return this.minecraft.getWindow().getGuiScaledWidth();
    }

    public int guiHeight() {
        return this.minecraft.getWindow().getGuiScaledHeight();
    }

    public PoseStack pose() {
        return this.pose;
    }

    public MultiBufferSource.BufferSource bufferSource() {
        return this.bufferSource;
    }

    public void flush() {
        RenderSystem.disableDepthTest();
        this.bufferSource.endBatch();
        RenderSystem.enableDepthTest();
    }

    public void hLine(int i, int j, int k, int l) {
        this.hLine(RenderType.gui(), i, j, k, l);
    }

    public void hLine(RenderType arg, int j, int k, int l, int m) {
        if (k < j) {
            int i = j;
            j = k;
            k = i;
        }
        this.fill(arg, j, l, k + 1, l + 1, m);
    }

    public void vLine(int i, int j, int k, int l) {
        this.vLine(RenderType.gui(), i, j, k, l);
    }

    public void vLine(RenderType arg, int j, int k, int l, int m) {
        if (l < k) {
            int i = k;
            k = l;
            l = i;
        }
        this.fill(arg, j, k + 1, j + 1, l, m);
    }

    public void enableScissor(int i, int j, int k, int l) {
        this.applyScissor(this.scissorStack.push(new ScreenRectangle(i, j, k - i, l - j)));
    }

    public void disableScissor() {
        this.applyScissor(this.scissorStack.pop());
    }

    private void applyScissor(@Nullable ScreenRectangle arg) {
        this.flushIfManaged();
        if (arg != null) {
            Window window = Minecraft.getInstance().getWindow();
            int i = window.getHeight();
            double d0 = window.getGuiScale();
            double d1 = (double)arg.left() * d0;
            double d2 = (double)i - (double)arg.bottom() * d0;
            double d3 = (double)arg.width() * d0;
            double d4 = (double)arg.height() * d0;
            RenderSystem.enableScissor((int)((int)d1), (int)((int)d2), (int)Math.max(0, (int)d3), (int)Math.max(0, (int)d4));
        } else {
            RenderSystem.disableScissor();
        }
    }

    public void setColor(float f, float g, float h, float i) {
        this.flushIfManaged();
        RenderSystem.setShaderColor((float)f, (float)g, (float)h, (float)i);
    }

    public void fill(int i, int j, int k, int l, int m) {
        this.fill(i, j, k, l, 0, m);
    }

    public void fill(int i, int j, int k, int l, int m, int n) {
        this.fill(RenderType.gui(), i, j, k, l, m, n);
    }

    public void fill(RenderType arg, int i, int j, int k, int l, int m) {
        this.fill(arg, i, j, k, l, 0, m);
    }

    public void fill(RenderType arg, int k, int l, int m, int n, int o, int p) {
        Matrix4f matrix4f = this.pose.last().pose();
        if (k < m) {
            int i = k;
            k = m;
            m = i;
        }
        if (l < n) {
            int j = l;
            l = n;
            n = j;
        }
        float f3 = (float)FastColor.ARGB32.alpha((int)p) / 255.0f;
        float f = (float)FastColor.ARGB32.red((int)p) / 255.0f;
        float f1 = (float)FastColor.ARGB32.green((int)p) / 255.0f;
        float f2 = (float)FastColor.ARGB32.blue((int)p) / 255.0f;
        VertexConsumer vertexconsumer = this.bufferSource.getBuffer(arg);
        vertexconsumer.vertex(matrix4f, (float)k, (float)l, (float)o).color(f, f1, f2, f3).endVertex();
        vertexconsumer.vertex(matrix4f, (float)k, (float)n, (float)o).color(f, f1, f2, f3).endVertex();
        vertexconsumer.vertex(matrix4f, (float)m, (float)n, (float)o).color(f, f1, f2, f3).endVertex();
        vertexconsumer.vertex(matrix4f, (float)m, (float)l, (float)o).color(f, f1, f2, f3).endVertex();
        this.flushIfUnmanaged();
    }

    public void fillGradient(int i, int j, int k, int l, int m, int n) {
        this.fillGradient(i, j, k, l, 0, m, n);
    }

    public void fillGradient(int i, int j, int k, int l, int m, int n, int o) {
        this.fillGradient(RenderType.gui(), i, j, k, l, n, o, m);
    }

    public void fillGradient(RenderType arg, int i, int j, int k, int l, int m, int n, int o) {
        VertexConsumer vertexconsumer = this.bufferSource.getBuffer(arg);
        this.fillGradient(vertexconsumer, i, j, k, l, o, m, n);
        this.flushIfUnmanaged();
    }

    private void fillGradient(VertexConsumer arg, int i, int j, int k, int l, int m, int n, int o) {
        float f = (float)FastColor.ARGB32.alpha((int)n) / 255.0f;
        float f1 = (float)FastColor.ARGB32.red((int)n) / 255.0f;
        float f2 = (float)FastColor.ARGB32.green((int)n) / 255.0f;
        float f3 = (float)FastColor.ARGB32.blue((int)n) / 255.0f;
        float f4 = (float)FastColor.ARGB32.alpha((int)o) / 255.0f;
        float f5 = (float)FastColor.ARGB32.red((int)o) / 255.0f;
        float f6 = (float)FastColor.ARGB32.green((int)o) / 255.0f;
        float f7 = (float)FastColor.ARGB32.blue((int)o) / 255.0f;
        Matrix4f matrix4f = this.pose.last().pose();
        arg.vertex(matrix4f, (float)i, (float)j, (float)m).color(f1, f2, f3, f).endVertex();
        arg.vertex(matrix4f, (float)i, (float)l, (float)m).color(f5, f6, f7, f4).endVertex();
        arg.vertex(matrix4f, (float)k, (float)l, (float)m).color(f5, f6, f7, f4).endVertex();
        arg.vertex(matrix4f, (float)k, (float)j, (float)m).color(f1, f2, f3, f).endVertex();
    }

    public void drawCenteredString(Font arg, String string, int i, int j, int k) {
        this.drawString(arg, string, i - arg.width(string) / 2, j, k);
    }

    public void drawCenteredString(Font arg, Component arg2, int i, int j, int k) {
        FormattedCharSequence formattedcharsequence = arg2.getVisualOrderText();
        this.drawString(arg, formattedcharsequence, i - arg.width(formattedcharsequence) / 2, j, k);
    }

    public void drawCenteredString(Font arg, FormattedCharSequence arg2, int i, int j, int k) {
        this.drawString(arg, arg2, i - arg.width(arg2) / 2, j, k);
    }

    public int drawString(Font arg, @Nullable String string, int i, int j, int k) {
        return this.drawString(arg, string, i, j, k, true);
    }

    public int drawString(Font arg, @Nullable String string, int i, int j, int k, boolean bl) {
        return this.drawString(arg, string, (float)i, (float)j, k, bl);
    }

    public int drawString(Font arg, @Nullable String string, float f, float g, int j, boolean bl) {
        if (string == null) {
            return 0;
        }
        int i = arg.drawInBatch(string, f, g, j, bl, this.pose.last().pose(), (MultiBufferSource)this.bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0, arg.isBidirectional());
        this.flushIfUnmanaged();
        return i;
    }

    public int drawString(Font arg, FormattedCharSequence arg2, int i, int j, int k) {
        return this.drawString(arg, arg2, i, j, k, true);
    }

    public int drawString(Font arg, FormattedCharSequence arg2, int i, int j, int k, boolean bl) {
        return this.drawString(arg, arg2, (float)i, (float)j, k, bl);
    }

    public int drawString(Font arg, FormattedCharSequence arg2, float f, float g, int j, boolean bl) {
        int i = arg.drawInBatch(arg2, f, g, j, bl, this.pose.last().pose(), (MultiBufferSource)this.bufferSource, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        this.flushIfUnmanaged();
        return i;
    }

    public int drawString(Font arg, Component arg2, int i, int j, int k) {
        return this.drawString(arg, arg2, i, j, k, true);
    }

    public int drawString(Font arg, Component arg2, int i, int j, int k, boolean bl) {
        return this.drawString(arg, arg2.getVisualOrderText(), i, j, k, bl);
    }

    public void drawWordWrap(Font arg, FormattedText arg2, int i, int j, int k, int l) {
        for (FormattedCharSequence formattedcharsequence : arg.split(arg2, k)) {
            this.drawString(arg, formattedcharsequence, i, j, l, false);
            j += 9;
        }
    }

    public void blit(int i, int j, int k, int l, int m, TextureAtlasSprite arg) {
        this.innerBlit(arg.atlasLocation(), i, i + l, j, j + m, k, arg.getU0(), arg.getU1(), arg.getV0(), arg.getV1());
    }

    public void blit(int i, int j, int k, int l, int m, TextureAtlasSprite arg, float f, float g, float h, float n) {
        this.innerBlit(arg.atlasLocation(), i, i + l, j, j + m, k, arg.getU0(), arg.getU1(), arg.getV0(), arg.getV1(), f, g, h, n);
    }

    public void renderOutline(int i, int j, int k, int l, int m) {
        this.fill(i, j, i + k, j + 1, m);
        this.fill(i, j + l - 1, i + k, j + l, m);
        this.fill(i, j + 1, i + 1, j + l - 1, m);
        this.fill(i + k - 1, j + 1, i + k, j + l - 1, m);
    }

    public void blit(ResourceLocation arg, int i, int j, int k, int l, int m, int n) {
        this.blit(arg, i, j, 0, (float)k, l, m, n, 256, 256);
    }

    public void blit(ResourceLocation arg, int i, int j, int k, float f, float g, int l, int m, int n, int o) {
        this.blit(arg, i, i + l, j, j + m, k, l, m, f, g, n, o);
    }

    public void blit(ResourceLocation arg, int i, int j, int k, int l, float f, float g, int m, int n, int o, int p) {
        this.blit(arg, i, i + k, j, j + l, 0, m, n, f, g, o, p);
    }

    public void blit(ResourceLocation arg, int i, int j, float f, float g, int k, int l, int m, int n) {
        this.blit(arg, i, j, k, l, f, g, k, l, m, n);
    }

    void blit(ResourceLocation arg, int i, int j, int k, int l, int m, int n, int o, float f, float g, int p, int q) {
        this.innerBlit(arg, i, j, k, l, m, (f + 0.0f) / (float)p, (f + (float)n) / (float)p, (g + 0.0f) / (float)q, (g + (float)o) / (float)q);
    }

    void innerBlit(ResourceLocation arg, int i, int j, int k, int l, int m, float f, float g, float h, float n) {
        RenderSystem.setShaderTexture((int)0, (ResourceLocation)arg);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        Matrix4f matrix4f = this.pose.last().pose();
        BufferBuilder bufferbuilder = Tesselator.getInstance().getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bufferbuilder.vertex(matrix4f, (float)i, (float)k, (float)m).uv(f, h).endVertex();
        bufferbuilder.vertex(matrix4f, (float)i, (float)l, (float)m).uv(f, n).endVertex();
        bufferbuilder.vertex(matrix4f, (float)j, (float)l, (float)m).uv(g, n).endVertex();
        bufferbuilder.vertex(matrix4f, (float)j, (float)k, (float)m).uv(g, h).endVertex();
        BufferUploader.drawWithShader((BufferBuilder.RenderedBuffer)bufferbuilder.end());
    }

    void innerBlit(ResourceLocation arg, int i, int j, int k, int l, int m, float f, float g, float h, float n, float o, float p, float q, float r) {
        RenderSystem.setShaderTexture((int)0, (ResourceLocation)arg);
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.enableBlend();
        Matrix4f matrix4f = this.pose.last().pose();
        BufferBuilder bufferbuilder = Tesselator.getInstance().getBuilder();
        bufferbuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        bufferbuilder.vertex(matrix4f, (float)i, (float)k, (float)m).color(o, p, q, r).uv(f, h).endVertex();
        bufferbuilder.vertex(matrix4f, (float)i, (float)l, (float)m).color(o, p, q, r).uv(f, n).endVertex();
        bufferbuilder.vertex(matrix4f, (float)j, (float)l, (float)m).color(o, p, q, r).uv(g, n).endVertex();
        bufferbuilder.vertex(matrix4f, (float)j, (float)k, (float)m).color(o, p, q, r).uv(g, h).endVertex();
        BufferUploader.drawWithShader((BufferBuilder.RenderedBuffer)bufferbuilder.end());
        RenderSystem.disableBlend();
    }

    public void blitNineSliced(ResourceLocation arg, int i, int j, int k, int l, int m, int n, int o, int p, int q) {
        this.blitNineSliced(arg, i, j, k, l, m, m, m, m, n, o, p, q);
    }

    public void blitNineSliced(ResourceLocation arg, int i, int j, int k, int l, int m, int n, int o, int p, int q, int r) {
        this.blitNineSliced(arg, i, j, k, l, m, n, m, n, o, p, q, r);
    }

    public void blitNineSliced(ResourceLocation arg, int i, int j, int k, int l, int m, int n, int o, int p, int q, int r, int s, int t) {
        m = Math.min(m, k / 2);
        o = Math.min(o, k / 2);
        n = Math.min(n, l / 2);
        p = Math.min(p, l / 2);
        if (k == q && l == r) {
            this.blit(arg, i, j, s, t, k, l);
        } else if (l == r) {
            this.blit(arg, i, j, s, t, m, l);
            this.blitRepeating(arg, i + m, j, k - o - m, l, s + m, t, q - o - m, r);
            this.blit(arg, i + k - o, j, s + q - o, t, o, l);
        } else if (k == q) {
            this.blit(arg, i, j, s, t, k, n);
            this.blitRepeating(arg, i, j + n, k, l - p - n, s, t + n, q, r - p - n);
            this.blit(arg, i, j + l - p, s, t + r - p, k, p);
        } else {
            this.blit(arg, i, j, s, t, m, n);
            this.blitRepeating(arg, i + m, j, k - o - m, n, s + m, t, q - o - m, n);
            this.blit(arg, i + k - o, j, s + q - o, t, o, n);
            this.blit(arg, i, j + l - p, s, t + r - p, m, p);
            this.blitRepeating(arg, i + m, j + l - p, k - o - m, p, s + m, t + r - p, q - o - m, p);
            this.blit(arg, i + k - o, j + l - p, s + q - o, t + r - p, o, p);
            this.blitRepeating(arg, i, j + n, m, l - p - n, s, t + n, m, r - p - n);
            this.blitRepeating(arg, i + m, j + n, k - o - m, l - p - n, s + m, t + n, q - o - m, r - p - n);
            this.blitRepeating(arg, i + k - o, j + n, m, l - p - n, s + q - o, t + n, o, r - p - n);
        }
    }

    public void blitRepeating(ResourceLocation arg, int i, int j, int k, int l, int m, int n, int o, int p) {
        this.blitRepeating(arg, i, j, k, l, m, n, o, p, 256, 256);
    }

    public void blitRepeating(ResourceLocation arg, int m, int n, int o, int p, int q, int r, int s, int t, int textureWidth, int textureHeight) {
        int i = m;
        IntIterator intiterator = GuiGraphics.slices(o, s);
        while (intiterator.hasNext()) {
            int j = intiterator.nextInt();
            int k = (s - j) / 2;
            int l = n;
            IntIterator intiterator1 = GuiGraphics.slices(p, t);
            while (intiterator1.hasNext()) {
                int i1 = intiterator1.nextInt();
                int j1 = (t - i1) / 2;
                this.blit(arg, i, l, q + k, r + j1, j, i1, textureWidth, textureHeight);
                l += i1;
            }
            i += j;
        }
    }

    private static IntIterator slices(int j, int k) {
        int i = Mth.positiveCeilDiv((int)j, (int)k);
        return new Divisor(j, i);
    }

    public void renderItem(ItemStack arg, int i, int j) {
        this.renderItem((LivingEntity)this.minecraft.player, (Level)this.minecraft.level, arg, i, j, 0);
    }

    public void renderItem(ItemStack arg, int i, int j, int k) {
        this.renderItem((LivingEntity)this.minecraft.player, (Level)this.minecraft.level, arg, i, j, k);
    }

    public void renderItem(ItemStack arg, int i, int j, int k, int l) {
        this.renderItem((LivingEntity)this.minecraft.player, (Level)this.minecraft.level, arg, i, j, k, l);
    }

    public void renderFakeItem(ItemStack arg, int i, int j) {
        this.renderItem(null, (Level)this.minecraft.level, arg, i, j, 0);
    }

    public void renderItem(LivingEntity arg, ItemStack arg2, int i, int j, int k) {
        this.renderItem(arg, arg.level(), arg2, i, j, k);
    }

    private void renderItem(@Nullable LivingEntity arg, @Nullable Level arg2, ItemStack arg3, int i, int j, int k) {
        this.renderItem(arg, arg2, arg3, i, j, k, 0);
    }

    private void renderItem(@Nullable LivingEntity arg, @Nullable Level arg2, ItemStack arg3, int i, int j, int k, int l) {
        if (!arg3.isEmpty()) {
            BakedModel bakedmodel = this.minecraft.getItemRenderer().getModel(arg3, arg2, arg, k);
            this.pose.pushPose();
            this.pose.translate((float)(i + 8), (float)(j + 8), (float)(150 + (bakedmodel.isGui3d() ? l : 0)));
            try {
                boolean flag;
                this.pose.mulPoseMatrix(new Matrix4f().scaling(1.0f, -1.0f, 1.0f));
                this.pose.scale(16.0f, 16.0f, 16.0f);
                boolean bl = flag = !bakedmodel.usesBlockLight();
                if (flag) {
                    Lighting.setupForFlatItems();
                }
                this.minecraft.getItemRenderer().render(arg3, ItemDisplayContext.GUI, false, this.pose, (MultiBufferSource)this.bufferSource(), 0xF000F0, OverlayTexture.NO_OVERLAY, bakedmodel);
                this.flush();
                if (flag) {
                    Lighting.setupFor3DItems();
                }
            }
            catch (Throwable throwable) {
                CrashReport crashreport = CrashReport.forThrowable((Throwable)throwable, (String)"Rendering item");
                CrashReportCategory crashreportcategory = crashreport.addCategory("Item being rendered");
                crashreportcategory.setDetail("Item Type", () -> String.valueOf(arg3.getItem()));
                crashreportcategory.setDetail("Registry Name", () -> String.valueOf(ForgeRegistries.ITEMS.getKey((Object)arg3.getItem())));
                crashreportcategory.setDetail("Item Damage", () -> String.valueOf(arg3.getDamageValue()));
                crashreportcategory.setDetail("Item NBT", () -> String.valueOf(arg3.getTag()));
                crashreportcategory.setDetail("Item Foil", () -> String.valueOf(arg3.hasFoil()));
                throw new ReportedException(crashreport);
            }
            this.pose.popPose();
        }
    }

    public void renderItemDecorations(Font arg, ItemStack arg2, int i, int j) {
        this.renderItemDecorations(arg, arg2, i, j, null);
    }

    public void renderItemDecorations(Font arg, ItemStack arg2, int m, int n, @Nullable String string) {
        if (!arg2.isEmpty()) {
            LocalPlayer localplayer;
            float f;
            this.pose.pushPose();
            if (arg2.getCount() != 1 || string != null) {
                String s = string == null ? String.valueOf(arg2.getCount()) : string;
                this.pose.translate(0.0f, 0.0f, 200.0f);
                this.drawString(arg, s, m + 19 - 2 - arg.width(s), n + 6 + 3, 0xFFFFFF, true);
            }
            if (arg2.isBarVisible()) {
                int l = arg2.getBarWidth();
                int i = arg2.getBarColor();
                int j = m + 2;
                int k = n + 13;
                this.fill(RenderType.guiOverlay(), j, k, j + 13, k + 2, -16777216);
                this.fill(RenderType.guiOverlay(), j, k, j + l, k + 1, i | 0xFF000000);
            }
            float f2 = f = (localplayer = this.minecraft.player) == null ? 0.0f : localplayer.getCooldowns().getCooldownPercent(arg2.getItem(), this.minecraft.getFrameTime());
            if (f > 0.0f) {
                int i1 = n + Mth.floor((float)(16.0f * (1.0f - f)));
                int j1 = i1 + Mth.ceil((float)(16.0f * f));
                this.fill(RenderType.guiOverlay(), m, i1, m + 16, j1, Integer.MAX_VALUE);
            }
            this.pose.popPose();
            ItemDecoratorHandler.of((ItemStack)arg2).render(this, arg, arg2, m, n);
        }
    }

    public void renderTooltip(Font arg, ItemStack arg2, int i, int j) {
        this.tooltipStack = arg2;
        this.renderTooltip(arg, Screen.getTooltipFromItem((Minecraft)this.minecraft, (ItemStack)arg2), arg2.getTooltipImage(), i, j);
        this.tooltipStack = ItemStack.EMPTY;
    }

    public void renderTooltip(Font font, List<Component> textComponents, Optional<TooltipComponent> tooltipComponent, ItemStack stack, int mouseX, int mouseY) {
        this.tooltipStack = stack;
        this.renderTooltip(font, textComponents, tooltipComponent, mouseX, mouseY);
        this.tooltipStack = ItemStack.EMPTY;
    }

    public void renderTooltip(Font arg, List<Component> list2, Optional<TooltipComponent> optional, int i, int j) {
        List list = ForgeHooksClient.gatherTooltipComponents((ItemStack)this.tooltipStack, list2, optional, (int)i, (int)this.guiWidth(), (int)this.guiHeight(), (Font)arg);
        this.renderTooltipInternal(arg, list, i, j, DefaultTooltipPositioner.INSTANCE);
    }

    public void renderTooltip(Font arg, Component arg2, int i, int j) {
        this.renderTooltip(arg, List.of(arg2.getVisualOrderText()), i, j);
    }

    public void renderComponentTooltip(Font arg, List<Component> list, int i, int j) {
        List components = ForgeHooksClient.gatherTooltipComponents((ItemStack)this.tooltipStack, list, (int)i, (int)this.guiWidth(), (int)this.guiHeight(), (Font)arg);
        this.renderTooltipInternal(arg, components, i, j, DefaultTooltipPositioner.INSTANCE);
    }

    public void renderComponentTooltip(Font font, List<? extends FormattedText> tooltips, int mouseX, int mouseY, ItemStack stack) {
        this.tooltipStack = stack;
        List components = ForgeHooksClient.gatherTooltipComponents((ItemStack)stack, tooltips, (int)mouseX, (int)this.guiWidth(), (int)this.guiHeight(), (Font)font);
        this.renderTooltipInternal(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE);
        this.tooltipStack = ItemStack.EMPTY;
    }

    public void renderComponentTooltipFromElements(Font font, List<Either<FormattedText, TooltipComponent>> elements, int mouseX, int mouseY, ItemStack stack) {
        this.tooltipStack = stack;
        List components = ForgeHooksClient.gatherTooltipComponentsFromElements((ItemStack)stack, elements, (int)mouseX, (int)this.guiWidth(), (int)this.guiHeight(), (Font)font);
        this.renderTooltipInternal(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE);
        this.tooltipStack = ItemStack.EMPTY;
    }

    public void renderTooltip(Font arg, List<? extends FormattedCharSequence> list, int i, int j) {
        this.renderTooltipInternal(arg, list.stream().map(ClientTooltipComponent::create).collect(Collectors.toList()), i, j, DefaultTooltipPositioner.INSTANCE);
    }

    public void renderTooltip(Font arg, List<FormattedCharSequence> list, ClientTooltipPositioner arg2, int i, int j) {
        this.renderTooltipInternal(arg, list.stream().map(ClientTooltipComponent::create).collect(Collectors.toList()), i, j, arg2);
    }

    private void renderTooltipInternal(Font arg, List<ClientTooltipComponent> list, int m, int n, ClientTooltipPositioner arg2) {
        if (!list.isEmpty()) {
            RenderTooltipEvent.Pre preEvent = ForgeHooksClient.onRenderTooltipPre((ItemStack)this.tooltipStack, (GuiGraphics)this, (int)m, (int)n, (int)this.guiWidth(), (int)this.guiHeight(), list, (Font)arg, (ClientTooltipPositioner)arg2);
            if (preEvent.isCanceled()) {
                return;
            }
            int i = 0;
            int j = list.size() == 1 ? -2 : 0;
            for (ClientTooltipComponent clienttooltipcomponent : list) {
                int k = clienttooltipcomponent.getWidth(preEvent.getFont());
                if (k > i) {
                    i = k;
                }
                j += clienttooltipcomponent.getHeight();
            }
            int i2 = i;
            int j2 = j;
            Vector2ic vector2ic = arg2.positionTooltip(this.guiWidth(), this.guiHeight(), preEvent.getX(), preEvent.getY(), i2, j2);
            int l = vector2ic.x();
            int i1 = vector2ic.y();
            this.pose.pushPose();
            int j1 = 400;
            this.drawManaged(() -> {
                RenderTooltipEvent.Color colorEvent = ForgeHooksClient.onRenderTooltipColor((ItemStack)this.tooltipStack, (GuiGraphics)this, (int)l, (int)i1, (Font)preEvent.getFont(), (List)list);
                TooltipRenderUtil.renderTooltipBackground((GuiGraphics)this, (int)l, (int)i1, (int)i2, (int)j2, (int)400, (int)colorEvent.getBackgroundStart(), (int)colorEvent.getBackgroundEnd(), (int)colorEvent.getBorderStart(), (int)colorEvent.getBorderEnd());
            });
            this.pose.translate(0.0f, 0.0f, 400.0f);
            int k1 = i1;
            for (int l1 = 0; l1 < list.size(); ++l1) {
                ClientTooltipComponent clienttooltipcomponent1 = list.get(l1);
                clienttooltipcomponent1.renderText(preEvent.getFont(), l, k1, this.pose.last().pose(), this.bufferSource);
                k1 += clienttooltipcomponent1.getHeight() + (l1 == 0 ? 2 : 0);
            }
            k1 = i1;
            for (int k2 = 0; k2 < list.size(); ++k2) {
                ClientTooltipComponent clienttooltipcomponent2 = list.get(k2);
                clienttooltipcomponent2.renderImage(preEvent.getFont(), l, k1, this);
                k1 += clienttooltipcomponent2.getHeight() + (k2 == 0 ? 2 : 0);
            }
            this.pose.popPose();
        }
    }

    public void renderComponentHoverEffect(Font arg, @Nullable Style arg2, int i, int j) {
        if (arg2 != null && arg2.getHoverEvent() != null) {
            HoverEvent hoverevent = arg2.getHoverEvent();
            HoverEvent.ItemStackInfo hoverevent$itemstackinfo = (HoverEvent.ItemStackInfo)hoverevent.getValue(HoverEvent.Action.SHOW_ITEM);
            if (hoverevent$itemstackinfo != null) {
                this.renderTooltip(arg, hoverevent$itemstackinfo.getItemStack(), i, j);
            } else {
                HoverEvent.EntityTooltipInfo hoverevent$entitytooltipinfo = (HoverEvent.EntityTooltipInfo)hoverevent.getValue(HoverEvent.Action.SHOW_ENTITY);
                if (hoverevent$entitytooltipinfo != null) {
                    if (this.minecraft.options.advancedItemTooltips) {
                        this.renderComponentTooltip(arg, hoverevent$entitytooltipinfo.getTooltipLines(), i, j);
                    }
                } else {
                    Component component = (Component)hoverevent.getValue(HoverEvent.Action.SHOW_TEXT);
                    if (component != null) {
                        this.renderTooltip(arg, arg.split((FormattedText)component, Math.max(this.guiWidth() / 2, 200)), i, j);
                    }
                }
            }
        }
    }
}
