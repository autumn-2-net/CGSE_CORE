package org.cgse.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Positive witnesses only; the restricted catalog never exports a proof. */
final class GraphStockCountRepair<K> implements AutoCloseable {
    private final GraphCompiler.Compiled<K> graph;
    private final GraphCompiler<K> original;
    private final GraphSourceRanking<K> ranking;
    private final Set<String> excluded;
    private final K target;
    private final long amount, started, allowance;
    private final Map<K,Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private GraphCompiler<K> local;
    private IntegerCountSearch<K> search;
    private GraphPlan<K> result;
    private long memory, work;
    private boolean done;

    GraphStockCountRepair(GraphCompiler<K> original, GraphSourceRanking<K> ranking, Set<String> excluded, GraphCompiler.Compiled<K> graph, K target, long amount, Map<K,Long> stock,
                         Map<K,Long> seeds, Set<K> external, boolean preserve, boolean force,
                         PlanningBudget budget, long started, long allowance) {
        this.original=original;this.ranking=ranking;this.excluded=excluded;this.graph=graph;this.target=target;this.amount=amount;this.stock=stock;this.seeds=seeds;
        this.external=external;this.preserve=preserve;this.force=force;this.budget=budget;
        this.started=started;this.allowance=Math.max(0, allowance);
    }

    boolean step() {
        if(done)return true;
        budget.checkpoint();
        if(work>=allowance){done=true;return true;}
        long before=budget.nodes();String prior=budget.failureDetail();
        try {
            if(search==null){
                long bytes=2048L+128L*stock.size()+96L*seeds.size()+96L*external.size();
                for(var recipe:graph.recipes().values()){
                    budget.check();
                    if(work+budget.nodes()-before>=allowance){done=true;return true;}
                    bytes+=384L+128L*(recipe.inputs().size()+(long)recipe.outputs().size());
                }
                if(!budget.tryReserve(bytes)){done=true;return true;}
                memory=bytes;
                var selected=new java.util.LinkedHashMap<>(graph.recipes());
                var keys=new java.util.LinkedHashSet<K>();keys.add(target);
                for(var region:graph.regions())if(region.cyclic())for(var recipe:region.recipes())keys.addAll(recipe.executionOutputs().keySet());
                for(K key:keys){
                    budget.check();if(work+budget.nodes()-before>=allowance){done=true;return true;}
                    var candidates=ranking==null?original.producers(key):ranking.sources(key,true,allowance-work-(budget.nodes()-before));
                    int added=0;
                    for(var recipe:candidates){budget.check();if(excluded.contains(recipe.id())||selected.containsKey(recipe.id()))continue;
                        long cost=384L+128L*(recipe.inputs().size()+(long)recipe.outputs().size());
                        if(!budget.tryReserve(cost)){done=true;return true;}memory+=cost;selected.put(recipe.id(),recipe);if(++added>=2)break;}
                }
                local=new GraphCompiler<>(new ArrayList<>(selected.values()));
                search=new IntegerCountSearch<>(local,target,amount,stock,seeds,external,Set.of(),preserve,force,budget,started);
                long remaining=allowance-work-(budget.nodes()-before);
                if(remaining<=0){done=true;return true;}
                search.scout(remaining);
                return false;
            }
            if(search.step()) {
                var candidate=search.result();
                if(candidate!=null&&candidate.feasible())result=candidate;
                done=true;
                budget.note("stock_count", "recipes="+graph.recipes().size()+"; witness="+(result!=null)+"; work="+(work+budget.nodes()-before));
            }
            return done;
        } catch(PlanningBudget.Exhausted failure){
            if(failure.limit()!=PlanningBudget.Limit.MEMORY_LIMIT)throw failure;
            budget.failureDetail(prior);done=true;return true;
        } finally {work+=budget.nodes()-before;}
    }

    GraphPlan<K> result(){return result;}
    @Override public void close(){if(search!=null)search.close();search=null;local=null;budget.release(memory);memory=0;done=true;}
}
