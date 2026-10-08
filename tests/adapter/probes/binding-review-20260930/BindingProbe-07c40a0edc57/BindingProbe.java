package org.gtlcore.localbinding;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.stacks.*;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.*;
import java.util.*;

@Mod("gtlbindingprobe")
public class BindingProbe {
    public static void run(IGrid grid, Level level, boolean fixed) throws Exception {
        var service=(CraftingService)grid.getCraftingService();
        var f=CraftingService.class.getDeclaredField("craftingProviders");f.setAccessible(true);
        var registry=(NetworkCraftingProviders)f.get(service);
        var tag=new CompoundTag();tag.m_128359_("binding_probe",UUID.randomUUID().toString());
        AEKey raw=AEItemKey.of(Items.f_42597_,tag),product=AEItemKey.of(Items.f_42617_,tag);
        var original=pattern(raw,product,2,1,level);
        String binding=PatternFingerprint.of(original);
        var recipe=new GraphRecipe<AEKey>("probe",binding,List.of(new GraphRecipe.Slot<>(raw,2,0)),Map.of(product,1L));
        final IPatternDetails[] current={original};
        var provider=(ICraftingProvider)Proxy.newProxyInstance(BindingProbe.class.getClassLoader(),new Class[]{ICraftingProvider.class},(p,m,a)->switch(m.getName()){
            case "getAvailablePatterns"->List.of(current[0]);case "getEmitableItems"->Set.of();case "getPatternPriority"->0;case "isBusy","pushPattern"->false;
            case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;
        });
        var node=(IGridNode)Proxy.newProxyInstance(BindingProbe.class.getClassLoader(),new Class[]{IGridNode.class},(p,m,a)->switch(m.getName()){
            case "getService"->a[0]==ICraftingProvider.class?provider:null;case "getGrid"->grid;
            case "hashCode"->System.identityHashCode(p);case "equals"->p==a[0];default->null;
        });
        var host=(GraphCpuHost)Proxy.newProxyInstance(BindingProbe.class.getClassLoader(),new Class[]{GraphCpuHost.class},(p,m,a)->switch(m.getName()){
            case "level"->level;case "grid"->grid;default->null;
        });
        var adapter=new GtlExecutionAdapter(host,null);
        registry.addProvider(node);
        try {
            adapter.services(service,grid.getEnergyService());
            check(adapter.resolve(recipe)!=null,"original pattern");
            var indexField=NetworkCraftingProviders.class.getDeclaredField("craftableItems");indexField.setAccessible(true);
            var index=(Map<AEKey,Object>)indexField.get(registry);var indexed=index.remove(product);
            check(indexed!=null,"fixture has primary index");
            try {
                var direct=new GtlExecutionAdapter(host,null);direct.services(service,grid.getEnergyService());
                boolean live=fixed?direct.resolve(recipe,original)!=null:direct.resolve(recipe)!=null;
                check(live==fixed,"live captured handle works without primary index");
                current[0]=pattern(raw,product,3,1,level);
                var stale=new GtlExecutionAdapter(host,null);stale.services(service,grid.getEnergyService());
                check((fixed?stale.resolve(recipe,original):stale.resolve(recipe))==null,"stale provider map does not authorize retired handle");
                System.out.println("[Binding Probe] PASS captured live handle resolved="+live+", retired handle rejected");
            } finally {current[0]=original;index.put(product,indexed);}
            registry.removeProvider(node);
            var renamed=original.getDefinition().toStack();
            var nbt=renamed.m_41784_();var display=new CompoundTag();display.m_128359_("Name","\"Equivalent pattern\"");nbt.m_128365_("display",display);
            current[0]=PatternDetailsHelper.decodePattern(renamed,level);
            check(!PatternFingerprint.of(current[0]).equals(binding),"re-encoding changes binding fingerprint");
            registry.addProvider(node);adapter.services(service,grid.getEnergyService());
            boolean resolved=adapter.resolve(recipe)!=null;
            check(resolved==fixed,"equivalent encoding expected="+fixed+" got="+resolved+" reason="+adapter.bindingFailure());
            System.out.println("[Binding Probe] "+(fixed?"FIX PASS":"BASELINE REPRO")+" same inputs/outputs, changed definition: resolved="+resolved);
            registry.removeProvider(node);current[0]=pattern(raw,product,3,1,level);registry.addProvider(node);adapter.services(service,grid.getEnergyService());
            check(adapter.resolve(recipe)==null,"increased input rejected");
            registry.removeProvider(node);current[0]=pattern(raw,product,2,2,level);registry.addProvider(node);adapter.services(service,grid.getEnergyService());
            check(adapter.resolve(recipe)==null,"changed output rejected");
            registry.removeProvider(node);current[0]=original;registry.addProvider(node);adapter.services(service,grid.getEnergyService());
            check(adapter.resolve(recipe)!=null,"original restored after other recipes");
            registry.removeProvider(node);adapter.services(service,grid.getEnergyService());
            check(adapter.resolve(recipe)==null,"removed provider rejected");
            System.out.println("[Binding Probe] PASS increased-input, changed-output, restore, removed-provider checks");
        } finally {registry.removeProvider(node);}
    }
    private static IPatternDetails pattern(AEKey raw,AEKey product,long input,long output,Level level){
        return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(raw,input)},new GenericStack[]{new GenericStack(product,output)}),level);
    }
    private static void check(boolean b,String reason){if(!b)throw new AssertionError(reason);}
}
