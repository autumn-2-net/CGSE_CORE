package local.cgseprobe;
import org.gtlcore.gtlcore.mixin.ae2.stacks.AEKeyMixin;
import appeng.api.stacks.AEKeyType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import java.util.concurrent.*;

public class KeyCacheRegression {
    private static CompoundTag tag() {
        var tag=new CompoundTag();tag.putString("id","minecraft:stick");var inner=new CompoundTag();inner.putString("original","kept");tag.put("tag",inner);return tag;
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var original=new LegacyCachedKey(){public CompoundTag toTag(){return tag();}public AEKeyType getType(){return AEKeyType.items();}};
        var before=original.toTagGeneric().copy();original.toTagGeneric().putLong("real",123);
        if(before.equals(original.toTagGeneric()))throw new AssertionError("Original bug did not reproduce");
        var fixed=new AEKeyMixin(){public CompoundTag toTag(){return tag();}public AEKeyType getType(){return AEKeyType.items();}};
        var expected=fixed.toTagGeneric().copy();var workers=Executors.newFixedThreadPool(4);
        try {
            var tasks=new java.util.ArrayList<Callable<Void>>();
            for(int n=0;n<4;n++)tasks.add(()->{for(int i=0;i<1000;i++){
                var copy=fixed.toTagGeneric();if(!expected.equals(copy))throw new AssertionError("Cache poisoned");
                copy.putLong("real",i);copy.putLong("#",i);copy.getCompound("tag").putInt("modified",i);
            }return null;});
            for(var result:workers.invokeAll(tasks))result.get();
        } finally {workers.shutdownNow();}
        if(!expected.equals(fixed.toTagGeneric()))throw new AssertionError("Nested cached tag changed");
        System.out.println("Original shared-tag contamination reproduced; 4000 concurrent fixed serializations and nested mutations passed.");
    }
}
