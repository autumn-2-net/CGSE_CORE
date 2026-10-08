package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class FuelAllocationReview {
    record Route(String cat,int catUnits,String fuel,int cost,int count){}
    static GraphRecipe<String> recipe(String id,Map<String,Long> input,Map<String,Long> output) {
        return new GraphRecipe<>(id,id,input.entrySet().stream().map(e->new GraphRecipe.Slot<>(e.getKey(),e.getValue())).toList(),output);
    }
    static boolean oracle(List<Route> routes,Map<String,Long> stock,Map<String,Long> seeds,int at,Map<String,Long> spent) {
        if(at==routes.size())return true;
        Route r=routes.get(at);
        for(int burn=0;burn<=r.count;burn++) {
            if(burn<r.count && stock.get(r.cat)<r.catUnits)continue;
            long cat=spent.getOrDefault(r.cat,0L)+(long)burn*r.catUnits;
            long fuel=spent.getOrDefault(r.fuel,0L)+(long)(r.count-burn)*r.cost;
            if(cat+seeds.getOrDefault(r.cat,0L)>stock.get(r.cat)||fuel+seeds.getOrDefault(r.fuel,0L)>stock.get(r.fuel))continue;
            Map<String,Long> next=new HashMap<>(spent);next.put(r.cat,cat);next.put(r.fuel,fuel);
            if(oracle(routes,stock,seeds,at+1,next))return true;
        }
        return false;
    }
    public static void main(String[] args) {
        Random rng=new Random(919752);
        int found=0,feasible=0,missed=0;
        for(int test=0;test<4000;test++) {
            int count=2+rng.nextInt(5),cats=1+rng.nextInt(2),fuels=1+rng.nextInt(2);
            List<Route> routes=new ArrayList<>();Map<String,Long> stock=new LinkedHashMap<>(),seeds=new HashMap<>(),finish=new LinkedHashMap<>();
            List<GraphRecipe<String>> recipes=new ArrayList<>();
            for(int i=0;i<cats;i++){stock.put("C"+i,(long)rng.nextInt(12));if(test%7==0)seeds.put("C"+i,1L);}
            for(int i=0;i<fuels;i++){stock.put("F"+i,(long)rng.nextInt(25));if(test%9==0)seeds.put("F"+i,1L);}
            for(int i=0;i<count;i++) {
                Route r=new Route("C"+rng.nextInt(cats),1+rng.nextInt(3),"F"+rng.nextInt(fuels),1+rng.nextInt(4),1+rng.nextInt(3));routes.add(r);
                stock.put("U"+i,(long)r.count);finish.put("P"+i,(long)r.count);
                recipes.add(recipe("return"+i,Map.of("U"+i,1L,r.cat,(long)r.catUnits,r.fuel,(long)r.cost),Map.of("P"+i,1L,r.cat,(long)r.catUnits)));
                recipes.add(recipe("burn"+i,Map.of("U"+i,1L,r.cat,(long)r.catUnits),Map.of("P"+i,1L)));
            }
            seeds.entrySet().removeIf(e->stock.getOrDefault(e.getKey(),0L)<e.getValue());
            recipes.add(recipe("finish",finish,Map.of("G",1L)));
            Map<String,GraphRecipe<String>> primitive=new LinkedHashMap<>();recipes.forEach(r->primitive.put(r.id(),r));
            var budget=new PlanningBudget(0,1_000_000,64L<<20,()->false,System::nanoTime);
            try(var model=RecipeCountModel.create(new GraphCompiler<>(recipes),"G",1,stock,seeds,Set.of(),Set.of(),true,budget)) {
                var work=CountRecoveryFuel.compile(model,recipes,budget);if(work==null)throw new AssertionError("Expected independent accounts");
                List<PlanStep> steps=new ArrayList<>();Map<String,GraphRecipe<String>> virtual=new LinkedHashMap<>();
                for(var r:work.recipes()) {
                    virtual.put(r.id(),r);
                    if(r.id().startsWith("@recovery_fuel/")) {
                        var raw=r.inputs().keySet().stream().filter(k->k.startsWith("U")).findFirst().orElseThrow();
                        steps.add(new PlanStep.Batch(r.id(),stock.get(raw)));
                    }
                }
                steps.add(new PlanStep.Batch("finish",1));
                var plan=new GraphPlan<>("G",1,true,new PlanStep.Sequence(steps),virtual,stock,Map.of(),seeds,GraphPlan.Result.FEASIBLE,0,0);
                long[] retained={0};PlanStep lifted=work.lift(plan,b->retained[0]+=b);
                boolean sat=oracle(routes,stock,seeds,0,Map.of());if(sat)feasible++;
                if(sat) {
                    Map<String,java.math.BigInteger> pricedNeed=new HashMap<>();
                    for(var r:routes)pricedNeed.merge(r.fuel,BigInteger.valueOf((long)r.cost*r.count),BigInteger::add);
                    Map<String,Long> credited=work.pricedStock();
                    for(var e:pricedNeed.entrySet())if(e.getValue().add(BigInteger.valueOf(seeds.getOrDefault(e.getKey(),0L))).compareTo(BigInteger.valueOf(credited.get(e.getKey())))>0)throw new AssertionError("Optimistic cost bound excluded a valid account allocation");
                }
                if(lifted!=null) {
                    if(!sat)throw new AssertionError("False executable witness "+test);
                    var summary=GraphFixtureRegression.summary(lifted,primitive,new IdentityHashMap<>());
                    for(var e:summary.need().entrySet())if(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)).compareTo(e.getValue())<0)throw new AssertionError("Bad prefix "+test+" "+e);
                    for(var e:seeds.entrySet())if(BigInteger.valueOf(stock.getOrDefault(e.getKey(),0L)).add(summary.delta().getOrDefault(e.getKey(),BigInteger.ZERO)).compareTo(BigInteger.valueOf(e.getValue()))<0)throw new AssertionError("Seed spent "+test+" "+e+" stock="+stock+" routes="+routes);
                    if(!summary.delta().get("G").equals(BigInteger.ONE))throw new AssertionError("Goal");
                    found++;
                }else if(sat)missed++;
                budget.release(retained[0]);
                // Producing an account from outside the factored routes invalidates the view.
                List<GraphRecipe<String>> altered=new ArrayList<>(recipes);altered.add(recipe("extraFuel",Map.of("A",1L),Map.of(routes.get(0).fuel,1L)));
                var guarded=CountRecoveryFuel.compile(model,altered,budget);
                if(guarded!=null && guarded.recipes().stream().noneMatch(r->r.id().equals("return0")))throw new AssertionError("Future fuel treated as initial stock");
            }
            if(budget.reservedBytes()!=0)throw new AssertionError("Workspace leak "+budget.reservedBytes());
        }
        System.out.println("PASS 4000 independent exact burn-allocation oracles; SAT="+feasible+" witnessed="+found+" optional_unresolved="+missed+"; prefix, retained seeds and account isolation verified");
    }
}
