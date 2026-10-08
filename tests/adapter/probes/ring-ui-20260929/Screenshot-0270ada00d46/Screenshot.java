/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.pipeline.RenderTarget
 *  com.mojang.blaze3d.platform.NativeImage
 *  com.mojang.blaze3d.systems.RenderSystem
 *  com.mojang.logging.LogUtils
 *  javax.annotation.Nullable
 *  net.minecraft.ChatFormatting
 *  net.minecraft.Util
 *  net.minecraft.network.chat.ClickEvent
 *  net.minecraft.network.chat.ClickEvent$Action
 *  net.minecraft.network.chat.Component
 *  net.minecraft.network.chat.MutableComponent
 *  net.minecraftforge.api.distmarker.Dist
 *  net.minecraftforge.api.distmarker.OnlyIn
 *  net.minecraftforge.client.ForgeHooksClient
 *  net.minecraftforge.client.event.ScreenshotEvent
 *  org.slf4j.Logger
 */
package net.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.ScreenshotEvent;
import org.slf4j.Logger;

@OnlyIn(value=Dist.CLIENT)
public class Screenshot {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SCREENSHOT_DIR = "screenshots";
    private int rowHeight;
    private final DataOutputStream outputStream;
    private final byte[] bytes;
    private final int width;
    private final int height;
    private File file;

    public static void grab(File file, RenderTarget arg, Consumer<Component> consumer) {
        Screenshot.grab(file, null, arg, consumer);
    }

    public static void grab(File file, @Nullable String string, RenderTarget arg, Consumer<Component> consumer) {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.recordRenderCall(() -> Screenshot._grab(file, string, arg, consumer));
        } else {
            Screenshot._grab(file, string, arg, consumer);
        }
    }

    private static void _grab(File file, @Nullable String string, RenderTarget arg, Consumer<Component> consumer) {
        NativeImage nativeimage = Screenshot.takeScreenshot(arg);
        File file1 = new File(file, SCREENSHOT_DIR);
        file1.mkdir();
        File file2 = string == null ? Screenshot.getFile(file1) : new File(file1, string);
        ScreenshotEvent event = ForgeHooksClient.onScreenshot((NativeImage)nativeimage, (File)file2);
        if (event.isCanceled()) {
            consumer.accept(event.getCancelMessage());
            return;
        }
        File target = event.getScreenshotFile();
        Util.ioPool().execute(() -> {
            try {
                nativeimage.writeToFile(target);
                MutableComponent component = Component.literal((String)file2.getName()).withStyle(ChatFormatting.UNDERLINE).withStyle(arg -> arg.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, target.getAbsolutePath())));
                if (event.getResultMessage() != null) {
                    consumer.accept(event.getResultMessage());
                } else {
                    consumer.accept((Component)Component.translatable((String)"screenshot.success", (Object[])new Object[]{component}));
                }
            }
            catch (Exception exception) {
                LOGGER.warn("Couldn't save screenshot", (Throwable)exception);
                consumer.accept((Component)Component.translatable((String)"screenshot.failure", (Object[])new Object[]{exception.getMessage()}));
            }
            finally {
                nativeimage.close();
            }
        });
    }

    public static NativeImage takeScreenshot(RenderTarget arg) {
        int i = arg.width;
        int j = arg.height;
        NativeImage nativeimage = new NativeImage(i, j, false);
        RenderSystem.bindTexture((int)arg.getColorTextureId());
        nativeimage.downloadTexture(0, true);
        nativeimage.flipY();
        return nativeimage;
    }

    private static File getFile(File file) {
        String s = Util.getFilenameFormattedDateTime();
        int i = 1;
        File file1;
        while ((file1 = new File(file, s + (String)(i == 1 ? "" : "_" + i) + ".png")).exists()) {
            ++i;
        }
        return file1;
    }

    public Screenshot(File file, int j, int k, int l) throws IOException {
        this.width = j;
        this.height = k;
        this.rowHeight = l;
        File file1 = new File(file, SCREENSHOT_DIR);
        file1.mkdir();
        String s = "huge_" + Util.getFilenameFormattedDateTime();
        int i = 1;
        while ((this.file = new File(file1, s + (String)(i == 1 ? "" : "_" + i) + ".tga")).exists()) {
            ++i;
        }
        byte[] abyte = new byte[18];
        abyte[2] = 2;
        abyte[12] = (byte)(j % 256);
        abyte[13] = (byte)(j / 256);
        abyte[14] = (byte)(k % 256);
        abyte[15] = (byte)(k / 256);
        abyte[16] = 24;
        this.bytes = new byte[j * l * 3];
        this.outputStream = new DataOutputStream(new FileOutputStream(this.file));
        this.outputStream.write(abyte);
    }

    public void addRegion(ByteBuffer byteBuffer, int m, int n, int o, int p) {
        int i = o;
        int j = p;
        if (o > this.width - m) {
            i = this.width - m;
        }
        if (p > this.height - n) {
            j = this.height - n;
        }
        this.rowHeight = j;
        for (int k = 0; k < j; ++k) {
            byteBuffer.position((p - j) * o * 3 + k * o * 3);
            int l = (m + k * this.width) * 3;
            byteBuffer.get(this.bytes, l, i * 3);
        }
    }

    public void saveRow() throws IOException {
        this.outputStream.write(this.bytes, 0, this.width * 3 * this.rowHeight);
    }

    public File close() throws IOException {
        this.outputStream.close();
        return this.file;
    }
}
