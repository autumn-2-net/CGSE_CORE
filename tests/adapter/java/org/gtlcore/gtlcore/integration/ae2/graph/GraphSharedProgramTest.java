package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Shared-program persistence and actual executor accounting, with real AE keys/NBT. */
final class GraphSharedProgramTest {
    static void run(AEKey input, AEKey output) {
        var a = new GraphRecipe<AEKey>("shared-a", "a", List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
        var b = new GraphRecipe<AEKey>("shared-b", "b", List.of(new GraphRecipe.Slot<>(input, 1)), Map.of(output, 1L));
        PlanStep deep = new PlanStep.Batch(a.id(), 1);
        for (int i = 0; i < 10000; i++) deep = new PlanStep.Sequence(List.of(deep));
        var deepPlan = new GraphPlan<>(output, 1, false, deep, Map.of(a.id(), a), Map.of(input, 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var deepSnapshot = new GraphJobRuntime<>(deepPlan, deepPlan.initial(), Map.of()).snapshot();
        var deepSaved = GraphJobCodec.write(deepSnapshot);
        var loadedDeep = new GraphJobRuntime<>(GraphJobCodec.read(disk(deepSaved)));
        if (!loadedDeep.snapshot().cursor().equals(deepSnapshot.cursor())) throw new AssertionError("Deep saved cursor changed");
        // Schema 8 used nested node definitions. It must still decode into the
        // same shared identities and the original pre-order cursor numbering.
        var oldPlan = new GraphPlan<>(output, 2, false, new PlanStep.Sequence(List.of(new PlanStep.Batch(a.id(),2))),
                Map.of(a.id(),a),Map.of(input,2L),Map.of(),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var oldRuntime = new GraphJobRuntime<>(oldPlan,oldPlan.initial(),Map.of());
        CompoundTag old = GraphJobCodec.write(oldRuntime.snapshot());
        old.putInt("schemaVersion",8);
        CompoundTag oldRoot = new CompoundTag(), oldBatch = new CompoundTag();
        oldRoot.putString("kind","sequence"); oldRoot.putInt("node",0);
        oldBatch.putString("kind","batch"); oldBatch.putInt("node",1); oldBatch.putString("recipe",a.id()); oldBatch.putLong("count",2);
        ListTag oldChildren = new ListTag(); oldChildren.add(oldBatch); oldRoot.put("children",oldChildren); old.put("steps",oldRoot);
        if (!new GraphJobRuntime<>(GraphJobCodec.read(disk(old))).pendingRuns().equals(Map.of(a.id(),2L))) throw new AssertionError("Legacy program failed");
        oldBatch.putInt("node",160);
        CompoundTag nested = oldBatch;
        for (int i=159;i>=0;i--) {
            CompoundTag wrapper = new CompoundTag(); wrapper.putString("kind","sequence"); wrapper.putInt("node",i);
            ListTag children = new ListTag(); children.add(nested); wrapper.put("children",children); nested=wrapper;
        }
        old.put("steps",nested);
        if (!new GraphJobRuntime<>(GraphJobCodec.read(disk(old))).pendingRuns().equals(Map.of(a.id(),2L))) throw new AssertionError("Legacy 160-level saved plan failed");
        for (int depth : new int[] {8, 40, 60}) {
            PlanStep program = new PlanStep.Batch(a.id(), 1);
            for (int i = 0; i < depth; i++) program = new PlanStep.Sequence(List.of(program, new PlanStep.Batch(b.id(), 1), program));
            long count = (1L << (depth + 1)) - 1;
            var plan = new GraphPlan<>(output, count, true, program, Map.of(a.id(), a, b.id(), b),
                    Map.of(input, count), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
            Map<String, BigInteger> wanted = Map.of(a.id(), BigInteger.ONE.shiftLeft(depth), b.id(), BigInteger.ONE.shiftLeft(depth).subtract(BigInteger.ONE));
            if (!plan.patternTimesExact().equals(wanted)) throw new AssertionError("Shared exact counts " + depth);
            var summary = SequenceSummary.of(program, plan.recipes());
            if (!summary.required(input).equals(BigInteger.valueOf(count)) || summary.required(output).signum() != 0)
                throw new AssertionError("Shared summary " + depth);
            var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
            CompoundTag saved = GraphJobCodec.write(runtime.snapshot());
            if (saved.toString().length() > 30_000) throw new AssertionError("Shared NBT expanded " + depth);
            var restored = GraphJobCodec.read(saved);
            PlanStep.Sequence top = (PlanStep.Sequence) restored.plan().steps();
            if (top.children().get(0) != top.children().get(2)) throw new AssertionError("Shared identity lost " + depth);
            if (!restored.plan().patternTimesExact().equals(wanted)) throw new AssertionError("Shared saved counts " + depth);
            runtime = new GraphJobRuntime<>(restored);
            if (!saved.equals(GraphJobCodec.write(runtime.snapshot()))) throw new AssertionError("Shared cursor IDs changed");
            for (int fault = 0; fault < 5; fault++) {
                CompoundTag bad = saved.copy();
                CompoundTag step = bad.getCompound("steps");
                ListTag rows = step.getList("nodes",Tag.TAG_COMPOUND);
                CompoundTag last = rows.getCompound(rows.size()-1);
                if (fault < 3) last.putIntArray("children",new int[]{fault==0 ? rows.size()-1 : fault==1 ? -1 : 99999});
                else if (fault == 3) last.remove("children");
                else { CompoundTag unreachable = new CompoundTag(); unreachable.putString("kind","batch"); unreachable.putString("recipe",a.id()); unreachable.putLong("count",0); rows.add(unreachable); }
                try { GraphJobCodec.read(bad); throw new AssertionError("Invalid shared reference accepted " + fault); }
                catch (IllegalArgumentException expected) {}
            }
            long[] totals = new long[2];
            var adapter = new GraphJobRuntime.Adapter<AEKey>() {
                public long capacity(GraphRecipe<AEKey> r, long requested) { return 1; }
                public GraphJobRuntime.Outcome push(GraphRecipe<AEKey> r, long runs, Map<AEKey, Long> inputs) { totals[0] += runs; return GraphJobRuntime.Outcome.ACCEPTED; }
                public long deliver(AEKey key, long n) { totals[1] += n; return n; }
                public long refund(AEKey key, long n) { return n; }
            };
            int limit = depth == 8 ? 2000 : 5;
            for (int tick = 0; tick < limit && !runtime.finished(); tick++) {
                for (var returned : runtime.expected().entrySet()) runtime.accept(returned.getKey(), returned.getValue(), false);
                runtime.tick(adapter, tick, 8);
                if (tick < 5 || tick % 31 == 0) {
                    CompoundTag partial = GraphJobCodec.write(runtime.snapshot());
                    runtime = new GraphJobRuntime<>(GraphJobCodec.read(partial));
                    if (!partial.equals(GraphJobCodec.write(runtime.snapshot()))) throw new AssertionError("Shared partial cursor mismatch");
                }
            }
            if (depth == 8 && (!runtime.finished() || totals[0] != count || totals[1] != count))
                throw new AssertionError("Shared execution lost/duplicated outputs: " + Arrays.toString(totals) + " " + runtime.state());
            if (depth > 8 && totals[0] == 0) throw new AssertionError("Shared large execution did not start");
        }
        System.out.println("Shared programs: 40/60-level exact summaries, compact NBT, cursor reload, malformed refs and complete 511-run execution passed");
    }

    private static CompoundTag disk(CompoundTag tag) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, buffer);
            return NbtIo.readCompressed(new ByteArrayInputStream(buffer.toByteArray()));
        } catch (IOException e) { throw new AssertionError(e); }
    }
}
