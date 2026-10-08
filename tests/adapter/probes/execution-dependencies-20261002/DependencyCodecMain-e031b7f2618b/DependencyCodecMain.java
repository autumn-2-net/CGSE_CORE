package org.gtlcore.gtlcore.integration.ae2.graph;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.RegistryBuilder;
import appeng.api.stacks.*;
public class DependencyCodecMain { public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The standalone harness installs the same real AE key types in a real
        // Forge registry. This replaces mod startup, not NBT/key implementation.
        var builder = new RegistryBuilder<AEKeyType>().setName(new ResourceLocation("gtlcore", "graph_test_keys"));
        var create = RegistryBuilder.class.getDeclaredMethod("create");
        create.setAccessible(true);
        @SuppressWarnings("unchecked")
        var registry = (net.minecraftforge.registries.IForgeRegistry<AEKeyType>) create.invoke(builder);
        AEKeyTypesInternal.setRegistry(() -> registry);
        AEKeyTypesInternal.register(AEKeyType.items());
        AEKeyTypesInternal.register(AEKeyType.fluids());
GraphDependencyCodecTest.run();
} }
