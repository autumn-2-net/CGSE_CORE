// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Compare retained ranking windows with independent full-catalog selection. */
public final class ResidualFrontierTest {
    private record Model(GraphCompiler<String> compiler, Map<String, Long> stock,
                         List<String> boundary, Set<String> excluded) {}
    private record Result(long work, long scans, long peak, long nanos) {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--measure")) {
            System.out.println("sources,boundaries,seed,witness,work,scans,peak_bytes,nanos");
            for (int sources : new int[]{256,2048,12000}) for (int boundaries : new int[]{1,4}) for (int seed = 0; seed < 8; seed++) {
                var r = compare(model(sources, boundaries, seed), 16, false);
                System.out.println(sources + "," + boundaries + "," + seed + ",true," + r.work + "," + r.scans + "," + r.peak + "," + r.nanos);
            }
            return;
        }
        for (int seed = 0; seed < 32; seed++) for (int boundaries : new int[]{1,4,12})
            compare(model(96, boundaries, seed), 24, true);
        smallGrants();
        boundaryInvalidation();
        retryAndMemory();
        cancellation();
        var r = compare(model(12000, 1, 1), 16, true);
        check(r.scans == 24000, "Repeated pages still rescan the entire catalog: " + r.scans);
        System.out.println("Residual frontier: 96 independent full-sort comparisons, strict grants, changed boundaries, retry, eviction, memory and cancellation passed; 64 choices from 12000 sources used " + r.scans + " scans");
    }

    private static GraphRecipe<String> recipe(String id, Map<String, Long> in, Map<String, Long> out) {
        return new GraphRecipe<>(id, id, in.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(), out);
    }

    private static Model model(int size, int boundaries, int seed) {
        Random random = new Random(78317 + seed);
        var keys = new ArrayList<String>();
        for (int b = 0; b < boundaries; b++) keys.add("T" + b);
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i = 0; i < size; i++) {
            var outputs = new LinkedHashMap<String,Long>();
            outputs.put(keys.get(i % boundaries), 1L);
            if (boundaries > 1 && i % 3 == 0) outputs.put(keys.get(random.nextInt(boundaries)), 1L);
            recipes.add(recipe("r" + i, Map.of("ore" + random.nextInt(3), 1L + random.nextInt(17)), outputs));
        }
        Collections.shuffle(recipes, random);
        return new Model(new GraphCompiler<>(recipes), Map.of("ore0", 19L, "ore1", 8L, "ore2", 0L), keys, Set.of("r1", "r7"));
    }

    private static Result compare(Model model, int turns, boolean sliced) {
        var budget = budget(64L << 20);
        var pool = new LinkedHashMap<String,GraphRecipe<String>>();
        var expected = new LinkedHashSet<String>();
        var order = new ArrayDeque<>(model.boundary);
        var deferred = new ArrayDeque<String>();
        Deque<GraphRecipe<String>> page = null;
        String activeKey = null;
        boolean more = false;
        long start = System.nanoTime(), scans;
        try (var frontier = new GraphResidualSources<>(model.compiler, model.stock, Set.of(), model.excluded,
                pool, r -> pool.putIfAbsent(r.id(),r) == null, budget)) {
            model.boundary.forEach(frontier::offer);
            for (int turn = 0; turn < turns; turn++) {
                // The oracle fully sorts all remaining original producers on
                // every visit. It has no windows, cache epoch or scan cursor.
                order.addAll(deferred);
                deferred.clear();
                int admitted = 0;
                while (admitted < 4 && (page != null || !order.isEmpty())) {
                    if (page == null) {
                        activeKey = order.removeFirst();
                        var all = new ArrayList<>(model.compiler.producers(activeKey));
                        all.removeIf(r -> expected.contains(r.id()) || model.excluded.contains(r.id()));
                        all.sort(Comparator.comparingInt((GraphRecipe<String> r) -> rawMissing(model, r))
                                .thenComparing(Comparator.comparingInt((GraphRecipe<String> r) -> covered(model, r)).reversed())
                                .thenComparingInt(r -> unavailable(model,r)).thenComparingDouble(r -> pressure(model,r)));
                        page = new ArrayDeque<>(all.subList(0,Math.min(4,all.size())));
                        more = all.size() > 4;
                    }
                    while (admitted < 4 && !page.isEmpty()) {
                        expected.add(page.removeFirst().id());
                        admitted++;
                    }
                    if (page.isEmpty()) {
                        if (more) deferred.addLast(activeKey);
                        page = null;
                    }
                }
                frontier.begin(4);
                if (sliced && turn == 0) {
                    for (int i = 0; i < 7; i++) check(!frontier.step(), "No interrupted scan");
                    frontier.begin(4);
                }
                while (!frontier.step()) {}
                check(List.copyOf(pool.keySet()).equals(List.copyOf(expected)), "Ranking/order changed: turn=" + turn + "; boundaries=" + model.boundary.size());
            }
            scans = frontier.scanned();
        }
        check(budget.reservedBytes() == 0, "Window reservation leaked");
        check(budget.peakBytes() <= 1280L + 256L * (model.boundary.size()-1) + 32768, "Unbounded ranking cache");
        return new Result(budget.searchWork(), scans, budget.peakBytes(), System.nanoTime()-start);
    }

    private static int rawMissing(Model model, GraphRecipe<String> r) {
        return (int)r.inputs().entrySet().stream().filter(e -> model.stock.getOrDefault(e.getKey(),0L) < e.getValue()
                && model.compiler.producers(e.getKey()).isEmpty()).count();
    }
    private static int unavailable(Model model, GraphRecipe<String> r) {
        return (int)r.inputs().entrySet().stream().filter(e -> model.stock.getOrDefault(e.getKey(),0L) < e.getValue()).count();
    }
    private static int covered(Model model, GraphRecipe<String> r) {
        return (int)r.executionOutputs().keySet().stream().filter(model.boundary::contains).count();
    }
    private static double pressure(Model model, GraphRecipe<String> r) {
        return r.inputs().entrySet().stream().mapToDouble(e -> (double)e.getValue()/Math.max(1,model.stock.getOrDefault(e.getKey(),0L))).sum();
    }

    private static void smallGrants() {
        for (long memory : new long[]{1280,5376,65536}) {
            var model = model(96,1,0);
            var budget = budget(memory);
            var pool = new LinkedHashMap<String,GraphRecipe<String>>();
            try (var f = new GraphResidualSources<>(model.compiler, model.stock, Set.of(), model.excluded,
                    pool, r -> pool.putIfAbsent(r.id(),r) == null,budget)) {
                f.offer("T0");
                for (int i = 0; i < 20; i++) {
                    f.begin(0);
                    check(f.step() && pool.size()==i, "Zero grant performed admission");
                    f.begin(1);
                    while (!f.step()) {}
                    check(pool.size()==i+1 && f.added()==1, "One-choice grant overshot or lost the rest of its page");
                }
            }
            check(budget.reservedBytes()==0,"Small grant leaked");
        }
    }

    private static void boundaryInvalidation() {
        var recipes = new ArrayList<GraphRecipe<String>>();
        for (int i=0;i<40;i++) recipes.add(recipe("t"+i,Map.of("ore",1L),Map.of("T",1L)));
        recipes.add(recipe("joint",Map.of("ore",1L),Map.of("T",1L,"U",1L)));
        var compiler=new GraphCompiler<>(recipes);
        var budget=budget(65536);
        var pool=new LinkedHashMap<String,GraphRecipe<String>>();
        try (var f=new GraphResidualSources<>(compiler,Map.of("ore",1L),Set.of(),Set.of(),pool,
                r->pool.putIfAbsent(r.id(),r)==null,budget)) {
            f.offer("T"); f.begin(4); while(!f.step()) {}
            check(f.scanned()==41,"Initial scan");
            f.offer("U"); f.begin(8); while(!f.step()) {}
            check(pool.containsKey("joint") && f.scanned()==83,"Changed boundary reused stale coverage scores");
        }
        check(budget.reservedBytes()==0,"Boundary replacement leaked");
    }

    private static void retryAndMemory() {
        var model=model(96,1,3);
        for (boolean cached : new boolean[]{false,true}) {
            var budget=budget(cached?65536:1280);
            var pool=new LinkedHashMap<String,GraphRecipe<String>>();
            boolean[] refuse={true};
            try (var f=new GraphResidualSources<>(model.compiler,model.stock,Set.of(),model.excluded,pool,
                    r->!refuse[0] && pool.putIfAbsent(r.id(),r)==null,budget)) {
                f.offer("T0"); f.begin(4); while(!f.step()) {}
                check(pool.isEmpty() && f.pending(),"Declined candidates lost their retry frontier");
                refuse[0]=false; f.begin(4); while(!f.step()) {}
                check(pool.size()==4,"Released admission limit was cached as failure");
            }
            check(budget.reservedBytes()==0,"Retry leaked");
        }
        var recipeBudget=budget(6000);
        var pool=new LinkedHashMap<String,GraphRecipe<String>>();
        try (var f=new GraphResidualSources<>(model.compiler,model.stock,Set.of(),model.excluded,pool,r->{
            if (!recipeBudget.tryReserve(900)) return false;
            pool.put(r.id(),r); return true;
        },recipeBudget)) {
            f.offer("T0"); f.begin(4); while(!f.step()) {}
            check(pool.size()==4,"Optional window denied otherwise affordable recipes");
        }
        recipeBudget.release(900L*pool.size());
        check(recipeBudget.reservedBytes()==0,"Recipe/window ownership overlapped");
        // Even the last page must be retryable: there may be no omitted fifth
        // candidate to cause the old scanner to defer this boundary.
        var shortModel=model(3,1,2);
        var budget=budget(65536);
        var shortPool=new LinkedHashMap<String,GraphRecipe<String>>();
        boolean[] refuse={true};
        try(var f=new GraphResidualSources<>(shortModel.compiler,shortModel.stock,Set.of(),Set.of(),shortPool,
                r->!refuse[0] && shortPool.putIfAbsent(r.id(),r)==null,budget)) {
            f.offer("T0"); f.begin(4); while(!f.step()) {}
            check(f.pending(),"Last declined page was dropped");
            refuse[0]=false; f.begin(4); while(!f.step()) {}
            check(shortPool.size()==3 && !f.pending(),"Last declined page did not recover");
        }
        check(budget.reservedBytes()==0,"Last-page retry leaked");
    }

    private static void cancellation() {
        for (boolean afterScan:new boolean[]{false,true}) {
            var model=model(96,1,4);
            var budget=budget(65536);
            var pool=new LinkedHashMap<String,GraphRecipe<String>>();
            var f=new GraphResidualSources<>(model.compiler,model.stock,Set.of(),model.excluded,pool,
                    r->pool.putIfAbsent(r.id(),r)==null,budget);
            try {
                f.offer("T0"); f.begin(4);
                if(afterScan) while(!f.step()) {} else for(int i=0;i<17;i++) f.step();
                budget.cancel();
                try { f.step(); throw new AssertionError("Cancellation swallowed"); }
                catch(CancellationException expected) {}
            } finally { f.close(); f.close(); }
            check(budget.reservedBytes()==0,"Cancelled cached scan leaked");
        }
    }

    private static PlanningBudget budget(long bytes) {return new PlanningBudget(0,8_000_000,bytes,()->false,System::nanoTime);}
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
