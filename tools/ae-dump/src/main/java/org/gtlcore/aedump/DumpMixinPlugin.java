// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0
package org.gtlcore.aedump;

import java.util.List;
import java.util.Set;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** The portable engine can load without its optional AE/GTL diagnostic hosts. */
public final class DumpMixinPlugin implements IMixinConfigPlugin {
    private boolean available;
    @Override public void onLoad(String mixinPackage) {
        // Forge has completed discovery here, but host classes must not be loaded yet.
        var mods = FMLLoader.getLoadingModList();
        available = mods != null && mods.getModFileById("ae2") != null && mods.getModFileById("gtlcore") != null;
    }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return available; }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
