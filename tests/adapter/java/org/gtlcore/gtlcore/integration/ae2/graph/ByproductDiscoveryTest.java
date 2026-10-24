package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AEProcessingPattern;
import io.netty.buffer.Unpooled;

import java.math.BigInteger;
import java.util.*;

final class ByproductDiscoveryTest {

    static void run() throws Exception {
        AEKey raw = AEItemKey.of(Items.IRON_INGOT), main = AEItemKey.of(Items.GOLD_INGOT),
                side = AEItemKey.of(Items.DIAMOND), target = AEItemKey.of(Items.EMERALD);
        IPatternDetails joint = pattern(raw, new GenericStack(main, 1), new GenericStack(side, 1));
        IPatternDetails direct = pattern(raw, new GenericStack(side, 1));
        var budget = budget();
        var build = new ByproductPatternIndex.Build(List.of(joint, joint, direct));
        while (!build.step(budget)) {}
        var index = build.result();
        check(index.sources(side, List.of()).equals(List.of(direct, joint)), "snapshot retains primary and secondary membership");
        check(index.sources(side, List.of(direct)).equals(List.of(direct, joint)), "primary source order retained");
        check(index.sources(side, List.of(joint)).equals(List.of(joint, direct)), "already registered secondary not duplicated");
        check(index.sources(main, List.of()).equals(List.of(joint)), "primary snapshot survives a live registry removal");
        check(index.sources(raw, List.of()).isEmpty(), "inputs are not fabricated output sources");
        build.close();
        check(budget.reservedBytes() == 0, "finished index ownership released");
        for (int i = 0; i < 64; i++) try (var abandoned = new ByproductPatternIndex.Build(List.of(joint, direct))) {
            abandoned.step(budget);
        }
        check(budget.reservedBytes() == 0, "abandoned revision indexes accumulate reservations");

        var registered = new RegisteredPatternIndex();
        registered.changed(joint, true);
        var first = registered.view();
        var replacement = pattern(raw, new GenericStack(main, 1), new GenericStack(side, 1));
        check(joint != replacement && joint.equals(replacement), "equal replacement control");
        for (int i = 0; i < 128; i++) {
            registered.changed(joint, false); registered.changed(replacement, true);
            check(registered.view() == first, "immutable processing refresh rebuilt output index");
        }
        check(first.sources(side, List.of(replacement)).size() == 1, "equal refreshed source duplicated");
        registered.changed(direct, true);
        check(first.sources(side, List.of(direct)).equals(List.of(joint)), "retained view changed after a registry mutation");
        check(registered.view().sources(side, List.of()).equals(List.of(direct,joint)), "next view misses newly registered primary");
        registered.changed(joint, false);
        check(registered.view().sources(side, List.of(direct)).equals(List.of(direct)), "removed byproduct source retained");

        var dynamicOutputs = new GenericStack[][] { joint.getOutputs() };
        var dynamic = (IPatternDetails) java.lang.reflect.Proxy.newProxyInstance(IPatternDetails.class.getClassLoader(),
                new Class<?>[] { IPatternDetails.class }, (self, method, arguments) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == arguments[0];
                    case "getOutputs" -> dynamicOutputs[0];
                    default -> method.invoke(joint, arguments);
                });
        registered.changed(dynamic, true);
        check(registered.view().sources(side, List.of()).equals(List.of(direct,dynamic)), "custom source missing");
        registered.changed(dynamic, false);
        dynamicOutputs[0] = new GenericStack[] { new GenericStack(main, 1), new GenericStack(raw, 2) };
        registered.changed(dynamic, true);
        check(registered.view().sources(side, List.of()).equals(List.of(direct)) && registered.view().sources(raw, List.of()).equals(List.of(dynamic)),
                "equal custom definition retained stale outputs");

        var normalize = GtlPatternCatalog.class.getDeclaredMethod("normalize", IPatternDetails.class, String.class, KeyCounter.class, Level.class);
        normalize.setAccessible(true);
        @SuppressWarnings("unchecked")
        var captured = (List<GraphRecipe<AEKey>>) normalize.invoke(null, joint, PatternFingerprint.of(joint), new KeyCounter(), null);
        check(captured.size() == 1, "secondary discovery keeps one complete pattern");
        var recipe = captured.get(0);
        for (long amount : new long[] { 1, Integer.MAX_VALUE, Long.MAX_VALUE }) {
            var planner = new GraphPlanner<>(new GraphCompiler<>(captured));
            var plan = planner.plan(side, amount, Map.of(raw, amount), true, true, budget());
            check(plan.feasible() && plan.patternTimesExact().get(recipe.id()).equals(BigInteger.valueOf(amount)), "long secondary plan");
            check(plan.initialExact().get(raw).equals(BigInteger.valueOf(amount)), "whole recipe inputs accounted");
            var verification = new PlanVerification<>(plan, budget());
            while (!verification.step()) {}
        }
        var finish = new GraphRecipe<>("combine", "combine", List.of(new GraphRecipe.Slot<>(main, 1), new GraphRecipe.Slot<>(side, 1)), Map.of(target, 1L));
        var combined = new ArrayList<>(captured);
        combined.add(finish);
        var plan = new GraphPlanner<>(new GraphCompiler<>(combined)).plan(target, 100, Map.of(raw, 100L), true, true, budget());
        check(plan.feasible() && plan.patternTimesExact().get(recipe.id()).equals(BigInteger.valueOf(100)), "joint requirements do not double recipe runs");

        var tag = new CompoundTag();
        tag.putString("variant", "one");
        AEKey tagged = AEFluidKey.of(Fluids.WATER, tag), plain = AEFluidKey.of(Fluids.WATER);
        var fluidBuild = new ByproductPatternIndex.Build(List.of(pattern(raw, new GenericStack(main, 1), new GenericStack(tagged, 1000))));
        var fluidBudget = budget();
        while (!fluidBuild.step(fluidBudget)) {}
        check(fluidBuild.result().sources(tagged, List.of()).size() == 1 && fluidBuild.result().sources(plain, List.of()).isEmpty(), "fluid NBT identity retained");
        fluidBuild.close();

        for (var proof : Arrays.asList(null, new GraphPlan.SeedOptimality(1, 3, false, false, true),
                new GraphPlan.SeedOptimality(2, 2, true, true, false),
                new GraphPlan.SeedOptimality(0, 0, true, true, true, true),
                new GraphPlan.SeedOptimality(1, 2, false, false, false, true))) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                GraphSeedStatus.write(buffer, proof);
                check(Objects.equals(GraphSeedStatus.read(buffer), proof) && !buffer.isReadable(), "seed proof packet round trip");
            } finally {
                buffer.release();
            }
        }
        var seedRecipe = new GraphRecipe<AEKey>("seed", "seed", List.of(new GraphRecipe.Slot<>(main, 1)), Map.of(main, 1L, target, 1L));
        var proof = new GraphPlan.SeedOptimality(1, 1, true, true, true, true);
        var seedPlan = new GraphPlan<>(target, 1, true, new PlanStep.Batch("seed", 1), Map.of("seed", seedRecipe),
                Map.of(main, 1L), Map.of(main, 1L), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0).withSeedOptimality(proof);
        var runtime = new GraphJobRuntime<>(seedPlan, seedPlan.initial(), Map.of());
        var saved = GraphJobCodec.write(runtime.snapshot());
        check(GraphJobCodec.read(saved).plan().seedOptimality().equals(proof), "seed proof survives task save/reload");
        saved.getCompound("seedOptimality").remove("baseMaterialTradeoff");
        check(!GraphJobCodec.read(saved).plan().seedOptimality().baseMaterialTradeoff(), "old scope metadata defaults to fixed materials");
        saved.remove("seedOptimality");
        check(GraphJobCodec.read(saved).plan().seedOptimality() == null, "old task remains readable without proof metadata");
        var failure = new java.util.concurrent.CompletionException(new GraphPlanningFailure(GraphPlan.Result.INFEASIBLE, "checked"));
        check(GraphPlanningFailure.messageKey(failure).endsWith(".infeasible"), "proven infeasibility has its own message");
        check(GraphPlanningFailure.messageKey(new IllegalStateException("unexpected")) == null, "unexpected crashes retain original error path");
        check(GraphPlanningFailure.messageKey(new java.util.concurrent.CompletionException(new GraphSnapshotChanged("changed")))
                .endsWith(".snapshot_changed"), "real capture edits have a retry outcome, not a shortage or crash");
        var definitions=GtlPatternCatalog.class.getDeclaredMethod("sameDefinitions",List.class,List.class);
        definitions.setAccessible(true);
        var a=new GtlPatternCatalog.Signature(PatternFingerprint.capture(joint),0);
        var b=new GtlPatternCatalog.Signature(PatternFingerprint.capture(direct),0);
        check((boolean)definitions.invoke(null,List.of(a,b),List.of(b,a)),"equal recipe permutations invalidate capture");
        check(!(boolean)definitions.invoke(null,List.of(a,a),List.of(a,b)),"changed recipe multiplicity accepted");
        check(!(boolean)definitions.invoke(null,List.of(a),List.of(new GtlPatternCatalog.Signature(a.values(),1))),"priority change accepted");
        check(GraphPlanningFailure.messageKey(new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT)).endsWith(".search_limit"), "budget stop is not mislabeled infeasible");
        System.out.println("Byproduct sources: joint accounting, long quantities, duplicate sources, NBT and proof display passed");
    }

    private static PlanningBudget budget() {
        return new PlanningBudget(5000, 1_000_000, () -> false);
    }

    private static IPatternDetails pattern(AEKey raw, GenericStack... output) {
        CompoundTag data = new CompoundTag();
        ListTag in = new ListTag(), out = new ListTag();
        in.add(GenericStack.writeTag(new GenericStack(raw, 1)));
        for (var stack : output) out.add(GenericStack.writeTag(stack));
        data.put("in", in);
        data.put("out", out);
        ItemStack encoded = new ItemStack(Items.PAPER);
        encoded.setTag(data);
        return new AEProcessingPattern(AEItemKey.of(encoded));
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
