package org.cgse.core;

import org.cgse.core.*;
import java.util.*;

/** Local-only geometry and wire regression matrix; never distribute this fixture. */
public final class GraphLayoutRegressionTest {
    private static int checks;
    private static void check(boolean condition, String why) {
        checks++;
        if (!condition) throw new AssertionError(why);
    }
    private static GraphRecipe<String> recipe(String id, Map<String,Long> inputs, String output) {
        return new GraphRecipe<>(id,id,inputs.entrySet().stream().map(e -> new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),Map.of(output,1L));
    }
    public static void layouts() {
        var recipes = List.of(recipe("goal",Map.of("left",1L,"right",1L),"goal"),
                recipe("left",Map.of("shared",1L),"left"),recipe("right",Map.of("shared",1L),"right"),
                recipe("back",Map.of("goal",1L),"shared"));
        var topology = new PlanTopology<>(recipes);
        var layout = new PlanDependencyLayout<>(topology);
        int root = topology.nodes().stream().filter(n -> "goal".equals(n.resource())).findFirst().orElseThrow().id();
        var view = layout.focus(root,0);
        check(view.entries().stream().anyMatch(e -> e.kind() == PlanDependencyLayout.Kind.CYCLE),"Cycle reference absent");
        check(view.entries().stream().anyMatch(e -> e.kind() == PlanDependencyLayout.Kind.SHARED),"Shared reference absent");
        for (var e : view.entries()) if (e.edge() >= 0) {
            var original = topology.edges().get(e.edge());
            check(original.from() == e.node() && original.to() == view.entries().get(e.parent()).node(),"Invented dependency");
        }
        recipes = new ArrayList<>();
        for (int i=0;i<6527;i++) recipes.add(recipe("chain"+i,Map.of("k"+i,1L),"k"+(i+1)));
        topology = new PlanTopology<>(recipes);
        layout = new PlanDependencyLayout<>(topology);
        root = topology.nodes().stream().filter(n -> "k6527".equals(n.resource())).findFirst().orElseThrow().id();
        long start=System.nanoTime();
        view = layout.focus(root,0);
        check(view.entries().size() == 9,"Long chain expanded more than bounded view");
        check(view.entries().get(8).kind() == PlanDependencyLayout.Kind.COLLAPSED,"Deep chain missing continuation");
        System.out.println("UI deep chain 6527 recipes: displayed="+view.entries().size()+" focus_us="+(System.nanoTime()-start)/1000);
        Map<String,Long> inputs = new LinkedHashMap<>();
        for(int i=0;i<1000;i++) inputs.put("wide"+i,1L);
        topology = new PlanTopology<>(List.of(recipe("wide",inputs,"goal")));
        layout = new PlanDependencyLayout<>(topology);
        Set<Integer> shown = new HashSet<>();
        int offset=0;
        do {
            view = layout.focus(0,offset);
            int next=-1;
            for(var e:view.entries()) {
                if(e.kind()==PlanDependencyLayout.Kind.MORE) next=e.offset();
                else if(e.parent()>=0) shown.add(e.node());
            }
            check(view.entries().size()<=PlanDependencyLayout.MAX_NODES,"Wide view not bounded");
            if(next<0)break;
            check(next>offset,"Pagination failed to advance");
            offset=next;
        } while(true);
        check(shown.size()==1000,"Paging hid legal dependencies");
        view = layout.complete(0, null, true);
        check(view.entries().size() == 1001, "Complete view hid wide dependencies");
        check(view.entries().stream().noneMatch(e -> e.kind() == PlanDependencyLayout.Kind.COLLAPSED || e.kind() == PlanDependencyLayout.Kind.MORE), "Complete tree still paged");
        view = layout.complete(0, k -> k.equals("wide990"), true);
        check(view.entries().size() == 2, "Missing filter lost path or retained unrelated branch");
        recipes = new ArrayList<>();
        for (int i=0;i<6527;i++) recipes.add(recipe("deep"+i,Map.of("d"+i,1L),"d"+(i+1)));
        topology = new PlanTopology<>(recipes);
        layout = new PlanDependencyLayout<>(topology);
        root = topology.nodes().stream().filter(n -> "d6527".equals(n.resource())).findFirst().orElseThrow().id();
        start = System.nanoTime();
        view = layout.complete(root, null, true);
        check(view.entries().size() == 13055, "Complete chain missing dependencies");
        check(view.visibleEntries(new PlanGraphLayout.Box(0,0,320,160)).size() < 10, "Viewport did not cull deep chain");
        check(view.visibleConnections(new PlanGraphLayout.Box(0,0,320,160)).size() < 10, "Viewport did not cull deep connections");
        System.out.println("UI complete 6527-recipe tree: nodes="+view.entries().size()+" build_ms="+(System.nanoTime()-start)/1e6);
        var standalone = new PlanTopology<String>(List.of(), List.of("missing"));
        view = new PlanDependencyLayout<>(standalone).complete(0, k -> true, true);
        check(view.entries().size() == 1, "Missing-only target disappeared without producer");
        System.out.println("UI layout: "+checks+" checks passed");
    }
}