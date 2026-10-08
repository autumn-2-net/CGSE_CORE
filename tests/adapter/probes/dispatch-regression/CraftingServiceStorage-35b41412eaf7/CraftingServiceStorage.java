/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  appeng.api.config.Actionable
 *  appeng.api.networking.security.IActionSource
 *  appeng.api.stacks.AEKey
 *  appeng.api.storage.MEStorage
 *  appeng.core.localization.GuiText
 *  net.minecraft.network.chat.Component
 */
package appeng.me.service.helpers;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.core.localization.GuiText;
import net.minecraft.network.chat.Component;

class CraftingServiceStorage.1
implements MEStorage {
    CraftingServiceStorage.1() {
    }

    public boolean isPreferredStorageFor(AEKey key, IActionSource source) {
        return true;
    }

    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return CraftingServiceStorage.this.craftingService.insertIntoCpus(what, amount, mode);
    }

    public Component getDescription() {
        return GuiText.AutoCrafting.text();
    }
}
