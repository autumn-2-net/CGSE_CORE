package org.cgse.core;
import java.util.*;
import java.math.BigInteger;
import java.nio.file.*;
import com.google.gson.*;

public final class StartupFamilyProbe {
    static PlanningBudget budget(){return new PlanningBudget(0,100_000_000,128L<<20,()->false,System::nanoTime);}
    static GraphRecipe<String> r(String id,Map<String,Long> in,Map<String,Long> out){return new GraphRecipe<>(id,id,in.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),out);}
    static BigInteger[] counts(RecipeCountModel<String> m,Set<String> support){return m.recipes.stream().map(r->support.contains(r.id())?BigInteger.ONE:BigInteger.ZERO).toArray(BigInteger[]::new);}
    static int id(RecipeCountModel<String> m,String id){for(int i=0;i<m.recipes.size();i++)if(m.recipes.get(i).id().equals(id))return i;throw new AssertionError(id);}
    static void fixed(Path output)throws Exception {
        var rs=List.of(r("a_to_b",Map.of("A",2L),Map.of("B",1L)),r("b_to_a",Map.of("B",1L),Map.of("A",3L)),r("finish",Map.of("A",3L),Map.of("T",1L)),r("repair_x",Map.of("X",1L),Map.of("A",1L)),r("repair_y",Map.of("Y",1L),Map.of("B",1L)));
        var c=new GraphCompiler<>(rs);var b=budget();var journal=new CountProof.Journal(1L<<20);b.proofJournal(journal);
        try(var model=RecipeCountModel.create(c,"T",1,Map.of("A",1L,"X",1L,"Y",1L),Map.of(),Set.of(),Set.of(),true,b)){
            var proof=CountStartup.separate(model,counts(model,Set.of("a_to_b","b_to_a","finish")),b);
            if(proof==null||!proof.repairs().equals(Set.of(id(model,"repair_x"),id(model,"repair_y"))))throw new AssertionError("missing repair alternatives");
            if(!CountStartup.verify(model,proof,b))throw new AssertionError("failed own proof");
            if(CountStartup.verify(model,new CountStartup.Proof<>(proof.recipe(),proof.unbounded(),Set.of()),b))throw new AssertionError("accepted omitted repair");
            if(CountStartup.separate(model,counts(model,Set.of("a_to_b","b_to_a","finish","repair_x")),b)!=null)throw new AssertionError("rejected available repair");
            var lo=new BigInteger[model.recipes.size()];Arrays.fill(lo,BigInteger.ZERO);var hi=new BigInteger[lo.length];
            for(int repair:proof.repairs())hi[repair]=BigInteger.ZERO;
            var propagated=proof.guard().conflict().propagate(lo,hi,b);
            if(propagated==null||!propagated.row().terms().equals(Map.of(proof.recipe(),BigInteger.ONE))||propagated.row().upper().signum()!=0)throw new AssertionError("guard did not prune the next domain");
            c.countSessions.remember(model,List.of(proof.guard().conflict()),b);
            if(!c.countSessions.reuse(model,b).isEmpty())throw new AssertionError("execution-only cut escaped to persistent arithmetic cache");
            try(var shared=new OrderProofs<>(model,b)) {
                shared.publish(model,List.of(proof.guard().conflict()));
                var reversed=new ArrayList<>(rs);Collections.reverse(reversed);
                try(var reordered=RecipeCountModel.create(new GraphCompiler<>(reversed),"T",1,model.stock,Map.of(),Set.of(),Set.of(),true,b)){
                    var imported=shared.forModel(reordered);if(imported.size()!=1)throw new AssertionError("not shared across recipe coordinates");
                    var l=new BigInteger[reordered.recipes.size()];Arrays.fill(l,BigInteger.ZERO);var h=new BigInteger[l.length];h[id(reordered,"repair_x")]=h[id(reordered,"repair_y")]=BigInteger.ZERO;
                    var p=imported.get(0).propagate(l,h,b);int mapped=id(reordered,model.recipes.get(proof.recipe()).id());
                    if(p==null||!p.row().terms().equals(Map.of(mapped,BigInteger.ONE)))throw new AssertionError("wrong recipe-coordinate translation");
                }
                try(var changed=RecipeCountModel.create(c,"T",1,Map.of("A",2L,"X",1L,"Y",1L),Map.of(),Set.of(),Set.of(),true,b)){
                    if(!shared.forModel(changed).isEmpty())throw new AssertionError("inventory changed but conflict shared");
                }
            }
            if(journal.executions().size()!=1)throw new AssertionError("certificate missing");
            var family=CountStartup.separateAll(model,counts(model,Set.of("a_to_b","b_to_a","finish")),b);
            if(family.size()!=3)throw new AssertionError("expected all three startup guards: "+family);
            if(journal.executions().size()!=4)throw new AssertionError("family certificates missing");
            for(var member:family) if(!CountStartup.verify(model,member,b))throw new AssertionError("invalid family member");
            journal.write(output);var replay=CountProof.read(output);
            if(replay.executions().stream().anyMatch(p->ExecutionProof.verify(p,1_000_000)!=CountProof.Verdict.VERIFIED))throw new AssertionError("certificate replay");
            var low=new PlanningBudget(0,1000,128L<<20,()->false,System::nanoTime);
            if(CountStartup.separate(model,counts(model,Set.of("a_to_b","b_to_a","finish")),low)!=null||low.reservedBytes()!=0)throw new AssertionError("partial cut");
        }
        if(b.reservedBytes()!=0)throw new AssertionError("leak");
        for(boolean external:new boolean[]{false,true}){
            var changed=budget();try(var model=RecipeCountModel.create(c,"T",1,Map.of("A",external?1L:2L),Map.of(),external?Set.of("A"):Set.of(),Set.of(),true,changed)){
                if(CountStartup.separate(model,counts(model,Set.of("a_to_b","b_to_a","finish")),changed)!=null)throw new AssertionError("stale inventory/external");
            }if(changed.reservedBytes()!=0)throw new AssertionError("changed leak");
        }
        System.out.println("quantity threshold, repair alternatives, conflict propagation/coordinate sharing, cache scope, limits, tampering and proof archive passed");
    }
    static long bfs(RecipeCountModel<String> m,CountStartup.Proof<String> p){
        // Independent finite-state proof of the conditional: without a repair,
        // no reachable state can enable the blocked recipe.
        var seen=new HashSet<List<Long>>();var q=new ArrayDeque<List<Long>>();var initial=m.keys.stream().map(k->m.stock.getOrDefault(k,0L)).toList();seen.add(initial);q.add(initial);
        while(!q.isEmpty()){
            var state=q.removeFirst();
            for(int i=0;i<m.recipes.size();i++){
                var r=m.recipes.get(i);boolean enabled=true;
                for(var e:r.inputs().entrySet())if(state.get(m.ids.get(e.getKey()))<e.getValue()){enabled=false;break;}
                if(!enabled)continue;if(i==p.recipe())throw new AssertionError("invalid learned guard: blocked transition is executable");
                if(p.repairs().contains(i))continue;var next=new ArrayList<>(state);
                for(var e:r.inputs().entrySet()){int k=m.ids.get(e.getKey());next.set(k,next.get(k)-e.getValue());}
                for(var e:r.outputs().entrySet()){Integer k=m.ids.get(e.getKey());if(k!=null)next.set(k,next.get(k)+e.getValue());}
                if(seen.add(next))q.add(next);
            }
            if(seen.size()>100000)throw new AssertionError("not a finite fixture");
        }return seen.size();
    }
    public static void main(String[] args)throws Exception {
        fixed(Path.of(args[1]));var cases=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();var rng=new Random(7331);int total=0,cuts=0;long states=0;
        for(var e:cases){var c=e.getAsJsonObject();var recipes=new ArrayList<GraphRecipe<String>>();var stock=new LinkedHashMap<String,Long>();
            c.getAsJsonObject("stock").entrySet().forEach(v->stock.put(v.getKey(),v.getValue().getAsLong()));
            for(var x:c.getAsJsonArray("recipes")){var recipe=x.getAsJsonObject();var inputs=new LinkedHashMap<String,Long>();var outputs=new LinkedHashMap<String,Long>();
                for(var v:recipe.getAsJsonArray("slots")){var s=v.getAsJsonObject();inputs.merge(s.get("key").getAsString(),s.get("amount").getAsLong(),Long::sum);}
                recipe.getAsJsonObject("outputs").entrySet().forEach(v->outputs.put(v.getKey(),v.getValue().getAsLong()));recipes.add(r(recipe.get("id").getAsString(),inputs,outputs));}
            var b=budget();try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),c.get("target").getAsString(),c.get("amount").getAsLong(),stock,Map.of(),Set.of(),Set.of(),true,b)){
                if(model==null)throw new AssertionError("missing model");
                for(int trial=0;trial<8;trial++){var vector=new BigInteger[model.recipes.size()];for(int i=0;i<vector.length;i++)vector[i]=rng.nextBoolean()?BigInteger.ONE:BigInteger.ZERO;
                    var family=CountStartup.separateAll(model,vector,b);total++;
                    for(var proof:family){cuts++;states+=bfs(model,proof);if(!CountStartup.verify(model,proof,b))throw new AssertionError("certificate");}}
            }if(b.reservedBytes()!=0)throw new AssertionError("memory leaked "+b.reservedBytes());
        }
        System.out.println("PASS supports="+total+" learned_cuts="+cuts+" independently_explored_states="+states);
    }
}
