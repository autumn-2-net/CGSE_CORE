/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.nbt.CompoundTag
 *  org.jetbrains.annotations.Nullable
 */
package appeng.api.stacks;

import appeng.api.stacks.AEItemKey;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

private static final class AEItemKey.InternedTag {
    private static final AEItemKey.InternedTag EMPTY = new AEItemKey.InternedTag(null);
    private static final WeakHashMap<AEItemKey.InternedTag, WeakReference<AEItemKey.InternedTag>> INTERNED = new WeakHashMap();
    private final CompoundTag tag;
    private final int hashCode;

    AEItemKey.InternedTag(CompoundTag tag) {
        this.tag = tag;
        this.hashCode = Objects.hashCode(tag);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }
        AEItemKey.InternedTag internedTag = (AEItemKey.InternedTag)o;
        return Objects.equals(this.tag, internedTag.tag);
    }

    public int hashCode() {
        return this.hashCode;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static AEItemKey.InternedTag of(@Nullable CompoundTag tag, boolean giveOwnership) {
        if (tag == null) {
            return EMPTY;
        }
        Class<AEItemKey> clazz = AEItemKey.class;
        synchronized (AEItemKey.class) {
            AEItemKey.InternedTag searchHolder = new AEItemKey.InternedTag(tag);
            WeakReference<AEItemKey.InternedTag> weakRef = INTERNED.get(searchHolder);
            AEItemKey.InternedTag ret = null;
            if (weakRef != null) {
                ret = (AEItemKey.InternedTag)weakRef.get();
            }
            if (ret == null) {
                ret = giveOwnership ? searchHolder : new AEItemKey.InternedTag(tag.m_6426_());
                INTERNED.put(ret, new WeakReference<AEItemKey.InternedTag>(ret));
            }
            // ** MonitorExit[var2_2] (shouldn't be in output)
            return ret;
        }
    }
}
