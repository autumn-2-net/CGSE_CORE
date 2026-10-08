/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  appeng.api.storage.IStorageMounts
 *  appeng.api.storage.IStorageProvider
 *  appeng.api.storage.MEStorage
 *  appeng.me.service.CraftingService
 */
package appeng.me.service.helpers;

import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.me.service.CraftingService;

public class CraftingServiceStorage
implements IStorageProvider {
    private final CraftingService craftingService;
    private final MEStorage inventory = new /* Unavailable Anonymous Inner Class!! */;

    public CraftingServiceStorage(CraftingService craftingService) {
        this.craftingService = craftingService;
    }

    public void mountInventories(IStorageMounts mounts) {
        mounts.mount(this.inventory, Integer.MAX_VALUE);
    }
}
