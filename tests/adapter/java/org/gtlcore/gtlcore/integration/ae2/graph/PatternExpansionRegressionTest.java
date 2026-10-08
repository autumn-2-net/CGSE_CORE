package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.mixin.ae2.logic.PatternProviderLogicMixin;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.helpers.patternprovider.PatternProviderTarget;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Exercises the production provider's capacity estimator with selected alternatives. */
final class PatternExpansionRegressionTest {

    static void run() throws Exception {
        var provider = new PatternProviderLogicMixin() {

            @Override
            public void saveChanges() {}
        };
        Method counter = PatternProviderLogicMixin.class.getDeclaredMethod("gtlcore$toInputCounter", IPatternDetails.class);
        counter.setAccessible(true);
        AEKey water = AEFluidKey.of(Fluids.WATER), lava = AEFluidKey.of(Fluids.LAVA);
        IPatternDetails pattern = new IPatternDetails() {

            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.PAPER);
            }

            @Override
            public GenericStack[] getOutputs() {
                return new GenericStack[] { new GenericStack(AEItemKey.of(Items.STONE), 1) };
            }

            @Override
            public boolean supportsPushInputsToExternalInventory() {
                return true;
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[] { new IInput() {

                    @Override
                    public GenericStack[] getPossibleInputs() {
                        return new GenericStack[] { new GenericStack(water, 3), new GenericStack(lava, 3) };
                    }

                    @Override
                    public long getMultiplier() {
                        return 2;
                    }

                    @Override
                    public boolean isValid(AEKey key, Level level) {
                        return key.equals(water) || key.equals(lava);
                    }

                    @Override
                    public AEKey getRemainingKey(AEKey key) {
                        return null;
                    }
                } };
            }
        };
        PatternProviderTarget target = new PatternProviderTarget() {

            @Override
            public long insert(AEKey key, long amount, Actionable mode) {
                if (mode != Actionable.SIMULATE) throw new AssertionError("Capacity check mutated storage");
                return key.equals(lava) ? Math.min(amount, 3000) : 0;
            }

            @Override
            public boolean containsPatternInput(Set<AEKey> inputs) {
                return false;
            }
        };
        long batch = GraphDispatchContext.call(Map.of(lava, 6L), 1000,
                () -> provider.gtlcore$findMaxOperationsForTarget(target, null, null, inputs(counter, provider, pattern), 1000));
        check(batch == 500, "Selected alternative should batch 500 operations, got " + batch);
        check(inputs(counter, provider, pattern).get(water) == 6, "Fallback lost possible-input unit amount");
        GraphDispatchContext.call(Map.of(lava, 6L), 1000, () -> {
            check(inputs(counter, provider, pattern).get(lava) == 6, "Outer selection missing");
            GraphDispatchContext.call(Map.of(water, 12L), 250, () -> {
                check(inputs(counter, provider, pattern).get(water) == 12, "Nested selection missing");
                return null;
            });
            check(inputs(counter, provider, pattern).get(lava) == 6, "Nested selection leaked");
            return null;
        });
        System.out.println("Provider expansion: selected alternatives, exact units and nested context passed; batch=500");
    }

    private static KeyCounter inputs(Method counter, Object provider, IPatternDetails pattern) {
        try {
            return (KeyCounter) counter.invoke(provider, pattern);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
