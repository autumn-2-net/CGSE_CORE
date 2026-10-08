package org.gtlcore.gtlcore.integration.ae2.graph;

import org.cgse.core.*;
import appeng.api.stacks.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.contents.TranslatableContents;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Local-only geometry and wire regression matrix; never distribute this fixture. */
public final class GraphUiRegressionTest {
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
    public static void packets(AEKey input, AEKey output) {
        var recipe = new GraphRecipe<AEKey>("display", "display", List.of(new GraphRecipe.Slot<>(input,1)),Map.of(input,1L,output,1L));
        var children=new ArrayList<PlanStep>();
        for(int i=0;i<6524;i++)children.add(new PlanStep.Batch("display",1));
        var plan=new GraphPlan<>(output,6524,true,new PlanStep.Sequence(children),Map.of("display",recipe),Map.of(input,1L),Map.of(input,1L),Map.of(),GraphPlan.Result.FEASIBLE,0,0);
        var view=new GraphRingView(UUID.randomUUID(),plan,Map.of("display",6524L));
        int offset=0,pages=0,bytes=0;
        do {
            var page=view.page(offset);
            check(page.graphRows()==2,"Graph prefix lost");
            var buffer=new FriendlyByteBuf(Unpooled.buffer());
            try {
                GraphRingView.write(page,buffer);
                bytes+=buffer.readableBytes();
                check(buffer.readableBytes()<=GraphRingView.MAX_PAGE_BYTES,"Packet exceeds byte bound");
                check(GraphRingView.read(buffer).equals(page),"Packet roundtrip changed rows");
            } finally {buffer.release();}
            pages++;offset+=page.rows().size();
        } while(offset<view.page(0).total());
        check(offset==6527 && pages==26,"Expected 26 pages for ordinary 6527 rows, got "+pages);
        System.out.println("UI 6527 rows: old_pages=204 new_pages="+pages+" bytes="+bytes);
        PlanStep shared=new PlanStep.Batch("display",1);
        for(int i=0;i<80;i++)shared=new PlanStep.Sequence(List.of(shared,shared));
        var huge = java.math.BigInteger.ONE.shiftLeft(120).add(java.math.BigInteger.ONE);
        plan=new GraphPlan<>(output,1,true,shared,Map.of("display",recipe),Map.of(input,huge),Map.of(input,1L),Map.of(input,huge.subtract(java.math.BigInteger.ONE)),GraphPlan.Result.MISSING_INPUT,0,0);
        view=new GraphRingView(UUID.randomUUID(),plan,Map.of("display",Long.MAX_VALUE));
        check(view.page(0).total()==163,"Shared program expanded exponentially");
        check(view.page(0).rows().stream().filter(r->r.kind()==GraphRingView.Kind.REFERENCE).count()==80,"Missing program references");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            GraphRingView.write(view.page(0), buffer);
            var decoded = GraphRingView.read(buffer);
            check(decoded.equals(view.page(0)),"Big integer/shared program packet roundtrip");
            check(decoded.rows().get(0).count().equals(huge),"Exact initial amount clipped to long");
            check(decoded.rows().get(0).missing().equals(huge.subtract(java.math.BigInteger.ONE)),"Exact missing amount clipped");
            check(decoded.rows().get(1).count().equals(java.math.BigInteger.ONE.shiftLeft(80)),"Shared recipe count clipped");
        } finally { buffer.release(); }
        var proof=new GraphPlan.SeedOptimality(0,1,false,false,false,true);
        var contents=(TranslatableContents)GraphSeedStatus.tooltip(proof).get(0).getContents();
        check(contents.getKey().equals("gtlcore.ae.graph.seed_types_unproven"),"Displayed vacuous zero lower bound as evidence");
        System.out.println("UI packet: "+checks+" checks passed; 80 shared levels => 163 rows; exact 2^120+1 amounts");
    }
}
