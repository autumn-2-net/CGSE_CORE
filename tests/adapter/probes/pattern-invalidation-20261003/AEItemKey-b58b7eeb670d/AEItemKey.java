/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  appeng.api.stacks.AEKey
 *  appeng.api.stacks.AEKeyType
 *  appeng.api.storage.AEKeyFilter
 *  appeng.core.AELog
 *  net.minecraft.core.BlockPos
 *  net.minecraft.core.registries.BuiltInRegistries
 *  net.minecraft.nbt.CompoundTag
 *  net.minecraft.nbt.NumericTag
 *  net.minecraft.nbt.Tag
 *  net.minecraft.network.FriendlyByteBuf
 *  net.minecraft.network.chat.Component
 *  net.minecraft.resources.ResourceLocation
 *  net.minecraft.tags.TagKey
 *  net.minecraft.world.item.Item
 *  net.minecraft.world.item.ItemStack
 *  net.minecraft.world.item.crafting.Ingredient
 *  net.minecraft.world.level.ItemLike
 *  net.minecraft.world.level.Level
 *  net.minecraftforge.common.capabilities.CapabilityProvider
 *  org.jetbrains.annotations.Nullable
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 */
package appeng.api.stacks;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.AEKeyFilter;
import appeng.core.AELog;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AEItemKey
extends AEKey {
    private static final Logger LOG = LoggerFactory.getLogger(AEItemKey.class);
    private static final MethodHandle SERIALIZE_CAPS_HANDLE;
    private final Item item;
    private final InternedTag internedTag;
    private final InternedTag internedCaps;
    private final int hashCode;
    private final int cachedDamage;
    @Nullable
    private ItemStack readOnlyStack;
    private int maxStackSize = -1;

    @Nullable
    private static CompoundTag serializeStackCaps(ItemStack stack) {
        try {
            CompoundTag caps = SERIALIZE_CAPS_HANDLE.invokeExact((CapabilityProvider)stack);
            return caps == null || caps.m_128456_() ? null : caps;
        }
        catch (Throwable ex) {
            throw new RuntimeException("Failed to call serializeCaps", ex);
        }
    }

    private AEItemKey(Item item, InternedTag internedTag, InternedTag internedCaps) {
        Tag tag;
        this.item = item;
        this.internedTag = internedTag;
        this.internedCaps = internedCaps;
        this.hashCode = Objects.hash(item, internedTag, internedCaps);
        if (internedTag.tag != null && (tag = internedTag.tag.m_128423_("Damage")) instanceof NumericTag) {
            NumericTag numericTag = (NumericTag)tag;
            this.cachedDamage = numericTag.m_7047_();
        } else {
            this.cachedDamage = 0;
        }
    }

    @Nullable
    public static AEItemKey of(ItemStack stack) {
        if (stack.m_41619_()) {
            return null;
        }
        AEItemKey ret = AEItemKey.of((ItemLike)stack.m_41720_(), stack.m_41783_(), AEItemKey.serializeStackCaps(stack));
        ret.maxStackSize = stack.m_41741_();
        return ret;
    }

    public static boolean matches(AEKey what, ItemStack itemStack) {
        AEItemKey itemKey;
        return what instanceof AEItemKey && (itemKey = (AEItemKey)what).matches(itemStack);
    }

    public static boolean is(AEKey what) {
        return what instanceof AEItemKey;
    }

    public static AEKeyFilter filter() {
        return AEItemKey::is;
    }

    public AEKeyType getType() {
        return AEKeyType.items();
    }

    public AEItemKey dropSecondary() {
        return AEItemKey.of((ItemLike)this.item, null);
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || ((Object)((Object)this)).getClass() != o.getClass()) {
            return false;
        }
        AEItemKey aeItemKey = (AEItemKey)((Object)o);
        return this.item == aeItemKey.item && this.internedTag == aeItemKey.internedTag && this.internedCaps == aeItemKey.internedCaps;
    }

    public int hashCode() {
        return this.hashCode;
    }

    public static AEItemKey of(ItemLike item) {
        return AEItemKey.of(item, null);
    }

    public static AEItemKey of(ItemLike item, @Nullable CompoundTag tag) {
        return AEItemKey.of(item, tag, null);
    }

    private static AEItemKey of(ItemLike item, @Nullable CompoundTag tag, @Nullable CompoundTag caps) {
        return new AEItemKey(item.m_5456_(), InternedTag.of(tag, false), InternedTag.of(caps, false));
    }

    public boolean matches(ItemStack stack) {
        return !stack.m_41619_() && stack.m_150930_(this.item) && Objects.equals(stack.m_41783_(), this.internedTag.tag) && Objects.equals(AEItemKey.serializeStackCaps(stack), this.internedCaps.tag);
    }

    public boolean matches(Ingredient ingredient) {
        return ingredient.test(this.getReadOnlyStack());
    }

    public ItemStack getReadOnlyStack() {
        if (this.readOnlyStack == null) {
            this.readOnlyStack = new ItemStack((ItemLike)this.item, 1, this.internedCaps.tag);
            this.readOnlyStack.m_41751_(this.internedTag.tag);
        } else if (this.readOnlyStack.m_41619_()) {
            LOG.error("Something destroyed the read-only itemstack of {}", (Object)this);
            this.readOnlyStack = null;
            return this.getReadOnlyStack();
        }
        return this.readOnlyStack;
    }

    public ItemStack toStack() {
        return this.toStack(1);
    }

    public ItemStack toStack(int count) {
        if (count <= 0) {
            return ItemStack.f_41583_;
        }
        ItemStack result = new ItemStack((ItemLike)this.item, count, this.internedCaps.tag);
        result.m_41751_(this.copyTag());
        return result;
    }

    public Item getItem() {
        return this.item;
    }

    @Nullable
    public static AEItemKey fromTag(CompoundTag tag) {
        try {
            Item item = (Item)BuiltInRegistries.f_257033_.m_6612_(new ResourceLocation(tag.m_128461_("id"))).orElseThrow(() -> new IllegalArgumentException("Unknown item id."));
            CompoundTag extraTag = tag.m_128441_("tag") ? tag.m_128469_("tag") : null;
            CompoundTag extraCaps = tag.m_128441_("caps") ? tag.m_128469_("caps") : null;
            return AEItemKey.of((ItemLike)item, extraTag, extraCaps);
        }
        catch (Exception e) {
            AELog.debug((String)"Tried to load an invalid item key from NBT: %s", (Object[])new Object[]{tag, e});
            return null;
        }
    }

    public CompoundTag toTag() {
        CompoundTag result = new CompoundTag();
        result.m_128359_("id", BuiltInRegistries.f_257033_.m_7981_((Object)this.item).toString());
        if (this.internedTag.tag != null) {
            result.m_128365_("tag", (Tag)this.internedTag.tag.m_6426_());
        }
        if (this.internedCaps.tag != null) {
            result.m_128365_("caps", (Tag)this.internedCaps.tag.m_6426_());
        }
        return result;
    }

    public Object getPrimaryKey() {
        return this.item;
    }

    public int getFuzzySearchValue() {
        return this.cachedDamage;
    }

    public int getFuzzySearchMaxValue() {
        return this.getReadOnlyStack().m_41776_();
    }

    public ResourceLocation getId() {
        return BuiltInRegistries.f_257033_.m_7981_((Object)this.item);
    }

    @Nullable
    public CompoundTag getTag() {
        return this.internedTag.tag;
    }

    @Nullable
    public CompoundTag copyTag() {
        return this.internedTag.tag != null ? this.internedTag.tag.m_6426_() : null;
    }

    public boolean hasTag() {
        return this.internedTag.tag != null;
    }

    public ItemStack wrapForDisplayOrFilter() {
        return this.toStack();
    }

    public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        while (amount > 0L) {
            if (drops.size() > 1000) {
                AELog.warn((String)"Tried dropping an excessive amount of items, ignoring %s %ss", (Object[])new Object[]{amount, this.item});
                break;
            }
            long taken = Math.min(amount, (long)this.getMaxStackSize());
            amount -= taken;
            drops.add(this.toStack((int)taken));
        }
    }

    protected Component computeDisplayName() {
        return this.getReadOnlyStack().m_41786_();
    }

    public boolean isTagged(TagKey<?> tag) {
        return this.item.m_204114_().m_203656_(tag);
    }

    public boolean isDamaged() {
        return this.cachedDamage > 0;
    }

    public int getMaxStackSize() {
        int ret = this.maxStackSize;
        if (ret == -1) {
            this.maxStackSize = ret = this.getReadOnlyStack().m_41741_();
        }
        return ret;
    }

    public void writeToPacket(FriendlyByteBuf data) {
        data.m_130130_(Item.m_41393_((Item)this.item));
        CompoundTag compoundTag = null;
        if (this.item.m_41465_() || this.item.m_41468_()) {
            compoundTag = this.item.getShareTag(this.toStack());
        }
        data.m_130079_(compoundTag);
    }

    public static AEItemKey fromPacket(FriendlyByteBuf data) {
        int i = data.m_130242_();
        Item item = Item.m_41445_((int)i);
        CompoundTag shareTag = data.m_130260_();
        ItemStack stack = new ItemStack((ItemLike)item);
        stack.readShareTag(shareTag);
        return new AEItemKey(item, InternedTag.of(stack.m_41783_(), true), InternedTag.of(AEItemKey.serializeStackCaps(stack), true));
    }

    public String toString() {
        ResourceLocation id = BuiltInRegistries.f_257033_.m_7981_((Object)this.item);
        String idString = id != BuiltInRegistries.f_257033_.m_122315_() ? id.toString() : this.item.getClass().getName() + "(unregistered)";
        return this.internedTag.tag == null ? idString : idString + " (+tag)";
    }

    static {
        try {
            Method method = CapabilityProvider.class.getDeclaredMethod("serializeCaps", new Class[0]);
            method.setAccessible(true);
            SERIALIZE_CAPS_HANDLE = MethodHandles.lookup().unreflect(method);
        }
        catch (Exception exception) {
            throw new RuntimeException("Failed to create serializeCaps method handle", exception);
        }
    }

    private static final class InternedTag {
        private static final InternedTag EMPTY = new InternedTag(null);
        private static final WeakHashMap<InternedTag, WeakReference<InternedTag>> INTERNED = new WeakHashMap();
        private final CompoundTag tag;
        private final int hashCode;

        InternedTag(CompoundTag tag) {
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
            InternedTag internedTag = (InternedTag)o;
            return Objects.equals(this.tag, internedTag.tag);
        }

        public int hashCode() {
            return this.hashCode;
        }

        /*
         * WARNING - Removed try catching itself - possible behaviour change.
         */
        public static InternedTag of(@Nullable CompoundTag tag, boolean giveOwnership) {
            if (tag == null) {
                return EMPTY;
            }
            Class<AEItemKey> clazz = AEItemKey.class;
            synchronized (AEItemKey.class) {
                InternedTag searchHolder = new InternedTag(tag);
                WeakReference<InternedTag> weakRef = INTERNED.get(searchHolder);
                InternedTag ret = null;
                if (weakRef != null) {
                    ret = (InternedTag)weakRef.get();
                }
                if (ret == null) {
                    ret = giveOwnership ? searchHolder : new InternedTag(tag.m_6426_());
                    INTERNED.put(ret, new WeakReference<InternedTag>(ret));
                }
                // ** MonitorExit[var2_2] (shouldn't be in output)
                return ret;
            }
        }
    }
}
