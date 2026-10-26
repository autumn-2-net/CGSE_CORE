package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.KeyCounter;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;

/** Confirmation must not invoke production preflight or drop a sliced scan. */
final class GraphProviderRequirementsTest {
    static void run(IPatternDetails pattern) {
        var ordinary = new OrdinaryProvider();
        check(GraphProviderRequirements.missing(pattern, List.of(ordinary)) == null,
                "ordinary provider has no shared-tool restriction");
        var tool = new ToolProvider();
        check(GraphProviderRequirements.missing(pattern, List.of(tool)) != null, "missing shared tool remains visible");
        check(GraphProviderRequirements.missing(pattern, List.of(tool, ordinary)) == null,
                "tool-free alternative permits confirmation");

        var fast = new GraphProviderRequirements(List.of(pattern).iterator(), () -> 0L);
        Iterable<ICraftingProvider> largeUnneededTail = () -> new Iterator<>() {
            boolean consumed;
            @Override public boolean hasNext() {
                if (consumed) throw new AssertionError("tool-free first provider must not enumerate remaining alternatives");
                return true;
            }
            @Override public ICraftingProvider next() { consumed = true; return ordinary; }
        };
        check(fast.advance(key -> largeUnneededTail) && fast.message().isEmpty(),
                "ordinary provider fast path neither allocates nor traverses its alternatives");

        // 6,000 inexpensive selected patterns formerly required 94 server ticks
        // even if all providers had no shared-tool constraints at all.
        int[] lookups = {0};
        var large = new GraphProviderRequirements(Collections.nCopies(6000, pattern).iterator(), () -> 0L);
        int slices = 0;
        while (!large.advance(key -> { lookups[0]++; return List.of(ordinary); })) {
            check(++slices < 10, "cheap ordinary scan must not impose 64-pattern tick throttling");
        }
        check(lookups[0] == 6000 && large.message().isEmpty(), "every selected pattern checked exactly once");
        check(large.advance(key -> { throw new AssertionError("completed scan queried again"); }), "completion is stable");

        // A single pattern with many alternatives must yield between providers;
        // edits to AE's live list between calls cannot invalidate that cursor.
        var alternatives = new ArrayList<ICraftingProvider>(Collections.nCopies(5000, tool));
        tool.calls = 0;
        var fanout = new GraphProviderRequirements(List.of(pattern).iterator(), () -> 0L);
        check(!fanout.advance(key -> alternatives) && tool.calls > 0 && tool.calls < 5000,
                "provider fanout is bounded, rather than monopolizing a slice");
        alternatives.clear();
        while (!fanout.advance(key -> { throw new AssertionError("captured pattern looked up again"); })) {}
        check(tool.calls == 5000 && !fanout.message().isEmpty(), "sliced immutable provider view retains missing evidence");

        long[] time = {0};
        var timed = new ToolProvider() {
            @Override public Component gtlcore$graphSharedToolProblem(IPatternDetails ignored) {
                time[0] += 600_000;
                return super.gtlcore$graphSharedToolProblem(ignored);
            }
        };
        var clocked = new GraphProviderRequirements(List.of(pattern).iterator(), () -> time[0]);
        check(!clocked.advance(key -> Collections.nCopies(20, timed)) && timed.calls == 2,
                "elapsed main-thread budget yields even below the operation cap");
        while (!clocked.advance(key -> { throw new AssertionError("timed scan restarted"); })) {}
        check(timed.calls == 20, "timed scan eventually completes without rescanning providers");
        System.out.println("GraphProviderRequirementsTest: PASS");
    }

    private static class OrdinaryProvider implements ICraftingProvider, GraphDispatchPreflight {
        @Override public List<IPatternDetails> getAvailablePatterns() { throw new AssertionError("must use registered list"); }
        @Override public boolean isBusy() { throw new AssertionError("dispatch readiness is not a shared-tool constraint"); }
        @Override public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) { throw new AssertionError("read only"); }
        @Override public boolean gtlcore$canProduceGraphPattern(IPatternDetails pattern) {
            throw new AssertionError("confirmation invoked expensive production preflight");
        }
    }

    private static class ToolProvider extends OrdinaryProvider {
        int calls;
        @Override public Component gtlcore$graphSharedToolProblem(IPatternDetails pattern) {
            calls++;
            return Component.literal("missing shared hammer");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
